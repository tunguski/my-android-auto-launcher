package pl.matsuo.navi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Reading the CSV trips of earlier versions.
 *
 * <p>The loop that inserts and verifies them needs a database, so it is covered on a device in
 * {@code TripJourneyTest}. Everything decided here - what a line means, what counts as damaged,
 * what makes two trips the same drive - is plain arithmetic and belongs on the JVM.
 */
public class MigrationTest {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private static Track.Fixes parse(String csv) throws IOException {
        return Migration.parse(new StringReader(csv));
    }

    // ───────────────────────────── reading a file ─────────────────────────────

    @Test
    public void theOldFormatIsReadExactly() throws Exception {
        Track.Fixes f =
                parse("1766740800,52.229700,21.012200\n" + "1766740810,52.230238,21.013300\n");
        assertEquals(2, f.n);
        assertEquals(1766740800L, f.t[0]);
        assertEquals(52_229_700, f.lat[0]);
        assertEquals(21_012_200, f.lon[0]);
        assertEquals(1766740810L, f.t[1]);
        assertEquals(52_230_238, f.lat[1]);
    }

    @Test
    public void anEmptyFileHasNoFixes() throws Exception {
        assertEquals(0, parse("").n);
        assertEquals(0, parse("\n\n").n);
    }

    @Test
    public void damagedLinesAreSkippedAndTheRestOfTheDriveSurvives() throws Exception {
        Track.Fixes f =
                parse(
                        "1766740800,52.229700,21.012200\n"
                                + "garbage\n"
                                + "1766740810,oops,21.012200\n"
                                + ",,\n"
                                + "1766740820,52.239700,21.012200\n");
        assertEquals(2, f.n);
        assertEquals(1766740800L, f.t[0]);
        assertEquals(1766740820L, f.t[1]);
    }

    @Test
    public void aLineCutInHalfByAPowerCutCostsOneFix() throws Exception {
        Track.Fixes f = parse("1766740800,52.229700,21.012200\n1766740810,52.23");
        assertEquals(1, f.n);
    }

    @Test
    public void whitespaceAroundTheFieldsIsTolerated() throws Exception {
        Track.Fixes f = parse(" 1766740800 , 52.229700 , 21.012200 \n");
        assertEquals(1, f.n);
        assertEquals(52_229_700, f.lat[0]);
    }

    @Test
    public void aLongFileGrowsTheBufferWithoutLosingAnything() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            sb.append(1766740800L + i * 10)
                    .append(",52.")
                    .append(100000 + i)
                    .append(",21.012200\n");
        }
        Track.Fixes f = parse(sb.toString());
        assertEquals(500, f.n);
        assertEquals(1766740800L + 499 * 10, f.t[499]);
    }

    @Test
    public void aTripFileSurvivesTheWholeRoundTripIntoTheTrackFormat() throws Exception {
        File dir = folder.newFolder("trips");
        List<double[]> drive = TripFixtures.expand(TripFixtures.SHORT_COMMUTE, 1_700_000_000_000L);
        File csv = TripFixtures.write(dir, drive);

        Track.Fixes parsed;
        try (java.io.FileReader r = new java.io.FileReader(csv)) {
            parsed = Migration.parse(r);
        }
        Track.Fixes packed = Track.decode(Track.encode(parsed.t, parsed.lat, parsed.lon, parsed.n));

        assertEquals(drive.size(), packed.n);
        assertTrue(Migration.matches(parsed, packed));
        // Six decimals in, six decimals out: the import loses nothing.
        for (int i = 0; i < packed.n; i++) {
            assertEquals(Track.micro(drive.get(i)[1]), packed.lat[i]);
            assertEquals(Track.micro(drive.get(i)[2]), packed.lon[i]);
        }
    }

    // ───────────────────────────── verification ─────────────────────────────

    @Test
    public void whatWasStoredIsComparedWithWhatWasRead() throws Exception {
        Track.Fixes a = parse("100,52.000000,21.000000\n110,52.001000,21.001000\n");
        assertTrue(Migration.matches(a, Track.decode(Track.encode(a.t, a.lat, a.lon, a.n))));
    }

    @Test
    public void aDifferentNumberOfFixesIsNotAMatch() throws Exception {
        Track.Fixes a = parse("100,52.000000,21.000000\n110,52.001000,21.001000\n");
        Track.Fixes b = parse("100,52.000000,21.000000\n");
        assertFalse(Migration.matches(a, b));
    }

    @Test
    public void adifferentPositionIsNotAMatch() throws Exception {
        Track.Fixes a = parse("100,52.000000,21.000000\n110,52.001000,21.001000\n");
        Track.Fixes b = parse("100,52.000000,21.000000\n110,52.002000,21.001000\n");
        assertFalse(Migration.matches(a, b));
    }

    @Test
    public void aDifferentTimeIsNotAMatch() throws Exception {
        Track.Fixes a = parse("100,52.000000,21.000000\n110,52.001000,21.001000\n");
        Track.Fixes b = parse("100,52.000000,21.000000\n120,52.001000,21.001000\n");
        assertFalse(Migration.matches(a, b));
    }

    @Test
    public void twoEmptyTracksMatch() throws Exception {
        assertTrue(Migration.matches(parse(""), parse("")));
    }

    // ───────────────────────────── the file list ─────────────────────────────

    @Test
    public void onlyCsvFilesAreOffered() throws Exception {
        File dir = folder.newFolder("trips");
        assertTrue(new File(dir, "trip-20260101-100000.csv").createNewFile());
        assertTrue(new File(dir, "notes.txt").createNewFile());
        assertTrue(new File(dir, Migration.IMPORTED).mkdir());

        File[] pending = Migration.pending(dir);
        assertEquals(1, pending.length);
        assertEquals("trip-20260101-100000.csv", pending[0].getName());
    }

    @Test
    public void filesComeOutOldestFirst() throws Exception {
        File dir = folder.newFolder("trips");
        assertTrue(new File(dir, "trip-20260301-100000.csv").createNewFile());
        assertTrue(new File(dir, "trip-20260101-100000.csv").createNewFile());
        assertTrue(new File(dir, "trip-20260201-100000.csv").createNewFile());

        File[] pending = Migration.pending(dir);
        assertEquals("trip-20260101-100000.csv", pending[0].getName());
        assertEquals("trip-20260301-100000.csv", pending[2].getName());
    }

    @Test
    public void aMissingFolderIsNotAnError() {
        assertEquals(0, Migration.pending(new File("no-such-folder")).length);
        assertEquals(0, Migration.importedBytes(new File("no-such-folder")));
        assertEquals(0, Migration.deleteImported(new File("no-such-folder")));
    }

    // ───────────────────────────── the leftovers ─────────────────────────────

    @Test
    public void theKeptFilesCanBeMeasuredAndDeleted() throws Exception {
        File dir = folder.newFolder("trips");
        File imported = new File(dir, Migration.IMPORTED);
        assertTrue(imported.mkdirs());
        Files.write(
                new File(imported, "a.csv").toPath(),
                "100,52.0,21.0\n".getBytes(StandardCharsets.UTF_8));
        Files.write(
                new File(imported, "b.csv").toPath(),
                "100,52.0,21.0\n".getBytes(StandardCharsets.UTF_8));

        assertEquals(28, Migration.importedBytes(dir));
        assertEquals(2, Migration.deleteImported(dir));
        assertEquals(0, Migration.importedBytes(dir));
    }
}
