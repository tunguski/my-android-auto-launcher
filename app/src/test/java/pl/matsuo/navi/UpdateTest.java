package pl.matsuo.navi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Reading a GitHub release: which version is newer, and which file to offer.
 *
 * <p>Getting the comparison wrong in one direction means never offering an update, and in the other
 * means offering one for ever, so every shape these strings come in is pinned down here.
 */
public class UpdateTest {

    private static String release(String tag, String... assetNames) {
        StringBuilder assets = new StringBuilder();
        for (String name : assetNames) {
            if (assets.length() > 0) {
                assets.append(',');
            }
            assets.append("{\"name\":\"")
                    .append(name)
                    .append("\",\"size\":1234,\"browser_download_url\":\"https://example/")
                    .append(name)
                    .append("\"}");
        }
        return "{\"tag_name\":\""
                + tag
                + "\",\"body\":\"notes\",\"draft\":false,\"prerelease\":false,"
                + "\"assets\":["
                + assets
                + "]}";
    }

    // ───────────────────────────── version numbers ─────────────────────────────

    @Test
    public void theNumbersAreTakenOutOfWhateverShapeTheyArriveIn() {
        assertEquals("2.1", Update.normalise("v2.1"));
        assertEquals("2.1", Update.normalise("V2.1"));
        assertEquals("2.1", Update.normalise("2.1"));
        assertEquals("2.1", Update.normalise("2.1-debug"));
        assertEquals("2.1", Update.normalise("  v2.1  "));
        assertEquals("2.1", Update.normalise("2.1."));
        assertEquals("2", Update.normalise("v2"));
        assertEquals("", Update.normalise("nightly"));
        assertEquals("", Update.normalise(null));
    }

    @Test
    public void theDebugBuildIsTheSameReleaseAsItsTag() {
        // What the unit actually runs today: 2.1-debug against the tag v2.1.
        assertEquals(0, Update.compare("2.1-debug", "v2.1"));
        assertFalse(Update.isNewer("2.1-debug", "v2.1"));
    }

    @Test
    public void laterVersionsCompareAsLater() {
        assertTrue(Update.isNewer("2.1", "v2.2"));
        assertTrue(Update.isNewer("2.1", "v3.0"));
        assertTrue(Update.isNewer("2.1", "v2.1.1"));
        assertFalse(Update.isNewer("2.2", "v2.1"));
        assertFalse(Update.isNewer("2.1", "v2.1"));
    }

    @Test
    public void tenIsNewerThanNineRatherThanOlder() {
        // The trap a string comparison falls into.
        assertTrue(Update.isNewer("2.9", "v2.10"));
        assertTrue(Update.isNewer("9.0", "v10.0"));
        assertFalse(Update.isNewer("2.10", "v2.9"));
    }

    @Test
    public void aMissingComponentIsAZero() {
        assertEquals(0, Update.compare("2.1", "2.1.0"));
        assertEquals(0, Update.compare("2", "2.0.0"));
        assertTrue(Update.isNewer("2.1", "2.1.1"));
    }

    @Test
    public void aReleaseWithNoVersionNumberIsNeverAnUpgrade() {
        assertFalse(Update.isNewer("2.1", "nightly"));
        assertFalse(Update.isNewer("2.1", ""));
        assertFalse(Update.isNewer("2.1", null));
    }

    @Test
    public void rubbishInAVersionIsTreatedAsZeroRatherThanThrowing() {
        assertEquals(0, Update.compare("2.x", "2"));
        assertFalse(Update.isNewer("", "v0"));
    }

    // ───────────────────────────── reading a release ─────────────────────────────

    @Test
    public void aReleaseIsReadWithItsFile() {
        Update.Release r = Update.parse(release("v2.2", "navi-2.2-release.apk"));
        assertEquals("v2.2", r.tag);
        assertEquals("2.2", r.version);
        assertEquals("navi-2.2-release.apk", r.apkName);
        assertEquals("https://example/navi-2.2-release.apk", r.apkUrl);
        assertEquals(1234, r.apkBytes);
        assertTrue(r.installable());
    }

    @Test
    public void theSignedApkIsPreferredOverAnythingElseInTheRelease() {
        Update.Release r =
                Update.parse(
                        release(
                                "v2.2",
                                "navi-user-guide.pdf",
                                "navi-2.2-debug.apk",
                                "navi-previews.zip",
                                "navi-2.2-release.apk",
                                "SHA256SUMS.txt"));
        assertEquals("navi-2.2-release.apk", r.apkName);
    }

    @Test
    public void aReleaseWithOnlyOneApkOffersThatOne() {
        Update.Release r = Update.parse(release("v2.2", "navi-user-guide.pdf", "navi-2.2.apk"));
        assertEquals("navi-2.2.apk", r.apkName);
    }

    @Test
    public void aReleaseWithNothingInstallableSaysSo() {
        Update.Release r = Update.parse(release("v2.2", "navi-user-guide.pdf"));
        assertEquals("2.2", r.version);
        assertFalse(r.installable());
    }

    @Test
    public void draftsAndPrereleasesAreNotOffered() {
        assertNull(
                Update.parse(
                        "{\"tag_name\":\"v2.2\",\"draft\":true,\"prerelease\":false,"
                                + "\"assets\":[]}"));
        assertNull(
                Update.parse(
                        "{\"tag_name\":\"v2.2\",\"draft\":false,\"prerelease\":true,"
                                + "\"assets\":[]}"));
    }

    @Test
    public void aRepositoryWithNoReleasesIsNotAFailure() {
        // What the launcher turns a 404 into.
        assertNull(Update.parse("{}"));
    }

    @Test
    public void anythingUnreadableIsNoRelease() {
        assertNull(Update.parse("not json"));
        assertNull(Update.parse(""));
        assertNull(Update.parse(null));
        assertNull(Update.parse("{\"tag_name\":\"nightly\",\"assets\":[]}"));
    }

    @Test
    public void aReleaseWithoutAnAssetListIsStillRead() {
        Update.Release r = Update.parse("{\"tag_name\":\"v2.2\"}");
        assertEquals("2.2", r.version);
        assertFalse(r.installable());
    }

    @Test
    public void theUrlPointsAtTheRepositoryReleases() {
        assertEquals(
                "https://api.github.com/repos/owner/name/releases/latest",
                Update.latestUrl("owner/name"));
    }
}
