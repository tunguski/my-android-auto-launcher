package pl.matsuo.navi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Backing up: when to try again, what a folder backend writes, and how the Google protocol is read.
 *
 * <p>No network is touched here. The HTTP in {@link DriveBackup} is a few dozen lines around these
 * decisions, and the decisions are what can be wrong.
 */
public class BackupTest {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    // ───────────────────────────── when to retry ─────────────────────────────

    @Test
    public void theFirstAttemptIsImmediate() {
        assertEquals(0, BackupPolicy.backoff(0));
        assertTrue(BackupPolicy.due(0, 0, 1000));
    }

    @Test
    public void failuresBackOffAndThenStop() {
        assertEquals(60_000L, BackupPolicy.backoff(1));
        assertEquals(5 * 60_000L, BackupPolicy.backoff(2));
        assertTrue(BackupPolicy.backoff(4) < BackupPolicy.backoff(5));
        // A head unit can be out of range for weeks; the wait must stop growing.
        assertEquals(BackupPolicy.backoff(5), BackupPolicy.backoff(50));
        assertEquals(BackupPolicy.backoff(5), BackupPolicy.backoff(Integer.MAX_VALUE));
    }

    @Test
    public void aNegativeFailureCountIsTreatedAsNone() {
        assertEquals(0, BackupPolicy.backoff(-1));
    }

    @Test
    public void anAttemptIsDueOnlyOnceTheWaitHasPassed() {
        long last = 1_000_000L;
        assertFalse(BackupPolicy.due(1, last, last + 59_000));
        assertTrue(BackupPolicy.due(1, last, last + 60_000));
        assertTrue(BackupPolicy.due(1, last, last + 60_001));
    }

    @Test
    public void aClockThatJumpsBackwardsDoesNotPostponeForEver() {
        // The unit sets its clock from GPS after boot, which can move it backwards.
        assertTrue(BackupPolicy.due(5, 2_000_000L, 1_000_000L));
    }

    @Test
    public void nothingAttemptedYetIsAlwaysDue() {
        assertTrue(BackupPolicy.due(5, 0, 0));
    }

    // ───────────────────────────── the folder backend ─────────────────────────────

    @Test
    public void aFolderBackupWritesWhereItSaysItDoes() throws Exception {
        File root = folder.newFolder("files");
        FolderBackup backup = new FolderBackup(root);
        assertTrue(backup.configured());

        String where =
                backup.upload("2026", "trip.csv.gz", "hello".getBytes(StandardCharsets.UTF_8));

        File written =
                new File(new File(new File(root, FolderBackup.EXPORT), "2026"), "trip.csv.gz");
        assertTrue(written.exists());
        assertEquals(written.getAbsolutePath(), where);
        assertEquals(
                "hello", new String(Files.readAllBytes(written.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void nothingHalfWrittenIsLeftBehind() throws Exception {
        File root = folder.newFolder("files");
        new FolderBackup(root).upload("2026", "trip.csv.gz", new byte[] {1, 2, 3});
        File dir = new File(new File(root, FolderBackup.EXPORT), "2026");
        for (File f : dir.listFiles()) {
            assertFalse("a .part file was left behind: " + f, f.getName().endsWith(".part"));
        }
    }

    @Test
    public void twoRunsWritingTheSameTripLeaveOneWholeFile() throws Exception {
        // The defect this pins down lost data rather than corrupting it: two runs
        // overlapped, one renamed its scratch file over the other's, and the
        // trip whose export had just been consumed was already marked as copied.
        // Whatever the interleaving, the file has to exist and be one of the two
        // payloads whole - never half of one, never missing.
        File root = folder.newFolder("files");
        FolderBackup backup = new FolderBackup(root);
        byte[] red = new byte[4096];
        byte[] blue = new byte[4096];
        Arrays.fill(red, (byte) 'r');
        Arrays.fill(blue, (byte) 'b');

        for (int round = 0; round < 20; round++) {
            Thread one = uploader(backup, red);
            Thread two = uploader(backup, blue);
            one.start();
            two.start();
            one.join();
            two.join();
            // A run may be refused: replacing a file that another run is
            // replacing at the same instant is denied outright on Windows, which
            // is where this runs. That costs one trip's export until the next
            // run. What must never happen is the file below being destroyed or
            // left half written, and that is what is asserted.

            File dir = new File(new File(root, FolderBackup.EXPORT), "2026");
            File written = new File(dir, "trip.csv.gz");
            assertTrue("the export vanished in round " + round, written.exists());
            assertTrue("the export vanished in round " + round + ": " + failure, written.exists());
            byte[] got = Files.readAllBytes(written.toPath());
            assertTrue(
                    "a half written export in round "
                            + round
                            + ": "
                            + got.length
                            + " bytes, "
                            + failure,
                    Arrays.equals(got, red) || Arrays.equals(got, blue));
            for (File f : dir.listFiles()) {
                assertFalse("scratch file left behind: " + f, f.getName().endsWith(".part"));
            }
        }
    }

    /** One export, with whatever the thread fails with carried out to the test. */
    private Thread uploader(FolderBackup backup, byte[] body) {
        Thread t =
                new Thread(
                        () -> {
                            try {
                                backup.upload("2026", "trip.csv.gz", body);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
        t.setUncaughtExceptionHandler((thread, e) -> failure = e);
        return t;
    }

    private volatile Throwable failure;

    @Test
    public void uploadingTheSameTripAgainReplacesIt() throws Exception {
        File root = folder.newFolder("files");
        FolderBackup backup = new FolderBackup(root);
        backup.upload("2026", "trip.csv.gz", "first".getBytes(StandardCharsets.UTF_8));
        backup.upload("2026", "trip.csv.gz", "second".getBytes(StandardCharsets.UTF_8));

        File written =
                new File(new File(new File(root, FolderBackup.EXPORT), "2026"), "trip.csv.gz");
        assertEquals(
                "second", new String(Files.readAllBytes(written.toPath()), StandardCharsets.UTF_8));
    }

    @Test(expected = IOException.class)
    public void aFolderThatCannotBeCreatedIsReportedRatherThanIgnored() throws Exception {
        // A file where the folder should be: creating the directory must fail.
        File root = folder.newFolder("files");
        File inTheWay = new File(root, FolderBackup.EXPORT);
        assertTrue(inTheWay.createNewFile());
        new FolderBackup(root).upload("2026", "trip.csv.gz", new byte[] {1});
    }

    // ───────────────────────────── the Google protocol ─────────────────────────────

    @Test
    public void aFormBodyIsPercentEncoded() {
        assertEquals("a=1&b=two", DriveApi.form("a", "1", "b", "two"));
        assertEquals(
                "scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fdrive.file",
                DriveApi.form("scope", DriveApi.SCOPE));
        assertEquals("", DriveApi.form());
        assertEquals("a=1", DriveApi.form("a", "1", "dangling"));
    }

    @Test
    public void theSignInRequestAsksForTheNarrowestScope() {
        String body = DriveApi.deviceCodeBody("client-123");
        assertTrue(body.contains("client_id=client-123"));
        assertTrue(body.contains("drive.file"));
        // The broad scope would give the app the whole drive; it must not appear.
        assertFalse(body.contains("auth%2Fdrive&"));
    }

    @Test
    public void theDeviceCodeResponseIsRead() throws Exception {
        DriveApi.DeviceCode code =
                DriveApi.parseDeviceCode(
                        "{\"device_code\":\"D1\",\"user_code\":\"ABC-DEF\","
                                + "\"verification_url\":\"https://google.com/device\","
                                + "\"expires_in\":1800,\"interval\":5}");
        assertEquals("D1", code.deviceCode);
        assertEquals("ABC-DEF", code.userCode);
        assertEquals("https://google.com/device", code.verificationUrl);
        assertEquals(5, code.intervalSeconds);
        assertEquals(1800, code.expiresInSeconds);
    }

    @Test
    public void bothSpellingsOfTheVerificationUrlAreAccepted() throws Exception {
        DriveApi.DeviceCode code =
                DriveApi.parseDeviceCode(
                        "{\"device_code\":\"D1\",\"user_code\":\"A\","
                                + "\"verification_uri\":\"https://www.google.com/device\"}");
        assertEquals("https://www.google.com/device", code.verificationUrl);
        assertEquals(5, code.intervalSeconds); // the documented default
    }

    @Test(expected = IOException.class)
    public void somethingThatIsNotADeviceCodeIsRejected() throws Exception {
        DriveApi.parseDeviceCode("{\"error\":\"invalid_scope\"}");
    }

    @Test
    public void theTokenResponseIsRead() throws Exception {
        DriveApi.Token token =
                DriveApi.parseToken(
                        "{\"access_token\":\"A\",\"refresh_token\":\"R\",\"expires_in\":3599}");
        assertEquals("A", token.accessToken);
        assertEquals("R", token.refreshToken);
        assertEquals(3599, token.expiresInSeconds);
    }

    @Test
    public void aRefreshResponseWithoutANewRefreshTokenIsFine() throws Exception {
        DriveApi.Token token = DriveApi.parseToken("{\"access_token\":\"A\",\"expires_in\":3599}");
        assertEquals("A", token.accessToken);
        assertNull(token.refreshToken);
    }

    @Test(expected = IOException.class)
    public void aTokenResponseWithoutATokenIsRejected() throws Exception {
        DriveApi.parseToken("{\"error\":\"invalid_grant\"}");
    }

    @Test
    public void waitingIsToldApartFromFailing() {
        assertEquals(
                DriveApi.Poll.PENDING, DriveApi.classify("{\"error\":\"authorization_pending\"}"));
        assertEquals(DriveApi.Poll.SLOW_DOWN, DriveApi.classify("{\"error\":\"slow_down\"}"));
        assertEquals(DriveApi.Poll.DENIED, DriveApi.classify("{\"error\":\"access_denied\"}"));
        assertEquals(DriveApi.Poll.EXPIRED, DriveApi.classify("{\"error\":\"expired_token\"}"));
    }

    @Test
    public void anythingUnexpectedCountsAsAFailureRatherThanAWait() {
        // Including the one that matters most: a scope the device flow refuses.
        assertEquals(DriveApi.Poll.FAILED, DriveApi.classify("{\"error\":\"invalid_scope\"}"));
        assertEquals(DriveApi.Poll.FAILED, DriveApi.classify("not json at all"));
        assertEquals(DriveApi.Poll.FAILED, DriveApi.classify("{}"));
        assertEquals(DriveApi.Poll.FAILED, DriveApi.classify(null));
    }

    @Test
    public void anErrorIsTurnedIntoSomethingWorthShowing() {
        assertEquals(
                "Insufficient permission",
                DriveApi.errorMessage("{\"error\":{\"message\":\"Insufficient permission\"}}"));
        assertEquals(
                "Device code expired",
                DriveApi.errorMessage(
                        "{\"error\":\"expired_token\",\"error_description\":\"Device code"
                                + " expired\"}"));
        assertEquals("invalid_scope", DriveApi.errorMessage("{\"error\":\"invalid_scope\"}"));
        assertNull(DriveApi.errorMessage(null));
        assertNull(DriveApi.errorMessage("plain text"));
    }

    @Test
    public void theFileIdIsFoundInBothShapesOfResponse() {
        assertEquals("file-1", DriveApi.parseId("{\"id\":\"file-1\",\"name\":\"x\"}"));
        assertNull(DriveApi.parseId("{\"name\":\"x\"}"));
        assertNull(DriveApi.parseId("rubbish"));

        assertEquals(
                "f-1", DriveApi.parseFirstId("{\"files\":[{\"id\":\"f-1\"},{\"id\":\"f-2\"}]}"));
        assertNull(DriveApi.parseFirstId("{\"files\":[]}"));
        assertNull(DriveApi.parseFirstId("{}"));
        assertNull(DriveApi.parseFirstId("rubbish"));
    }

    @Test
    public void aQueryCannotBeBrokenByAnOddFileName() {
        String query = DriveApi.fileQuery("folder-1", "2026-01-15T07-15-30.csv.gz");
        assertTrue(query.startsWith(DriveApi.FILES_URL));
        assertFalse("the query must be encoded, not raw", query.contains(" "));
        assertTrue(query.contains("folder-1"));
    }

    @Test
    public void metadataNamesTheParentAndTheType() {
        String file = DriveApi.fileMetadata("trip.csv.gz", "folder-1");
        assertTrue(file.contains("\"name\":\"trip.csv.gz\""));
        assertTrue(file.contains(DriveApi.ARTIFACT_MIME));
        assertTrue(file.contains("\"parents\":[\"folder-1\"]"));

        String top = DriveApi.folderMetadata(DriveApi.FOLDER, null);
        assertTrue(top.contains(DriveApi.FOLDER_MIME));
        assertFalse("the app folder sits at the top level", top.contains("parents"));
    }

    @Test
    public void aNameWithAQuoteInItDoesNotBreakTheJson() {
        String json = DriveApi.fileMetadata("odd\"name.csv.gz", null);
        assertTrue(json.contains("odd\\\"name.csv.gz"));
    }

    @Test
    public void theUploadBodyCarriesTheMetadataAndThenTheBytes() {
        byte[] content = {1, 2, 3, 4};
        byte[] body = DriveApi.multipart("BOUND", "{\"name\":\"x\"}", content, "application/gzip");
        String text = new String(body, StandardCharsets.ISO_8859_1);

        assertTrue(text.startsWith("--BOUND\r\nContent-Type: application/json"));
        assertTrue(text.contains("{\"name\":\"x\"}"));
        assertTrue(text.contains("Content-Type: application/gzip"));
        assertTrue(text.endsWith("--BOUND--\r\n"));
        // The payload must survive byte for byte, whatever it contains.
        int at = text.indexOf("application/gzip\r\n\r\n") + "application/gzip\r\n\r\n".length();
        for (int i = 0; i < content.length; i++) {
            assertEquals(content[i], body[at + i]);
        }
    }

    @Test
    public void anEmptyUploadIsStillAValidBody() {
        String text =
                new String(
                        DriveApi.multipart("B", "{}", new byte[0], "application/gzip"),
                        StandardCharsets.ISO_8859_1);
        assertTrue(text.endsWith("--B--\r\n"));
    }
}
