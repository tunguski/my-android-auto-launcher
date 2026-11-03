package pl.matsuo.navi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Journey: the owner looks for a newer release and installs it.
 *
 * <p>The check itself talks to GitHub, so what is proved here is everything around it: the settings
 * say what is installed, the daily check does not run more than daily, a downloaded file can be
 * handed to the package installer, and a unit that refuses to install anything is told about rather
 * than left silent.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class UpdateJourneyTest extends LauncherTest {

    private SharedPreferences prefs;

    @Before
    public void startFresh() throws Exception {
        resetState();
        prefs = context.getSharedPreferences(MainActivity.PREFS, 0);
        deleteTree(new File(context.getExternalFilesDir(null), ApkProvider.DIR));
    }

    // ───────────────────────────── in the settings ─────────────────────────────

    @Test
    public void theSettingsSayWhichVersionIsInstalled() throws Exception {
        open("look");
        scrollTo(R.string.update_section);
        scrollToText(str(R.string.update_installed, Updater.installedVersion(context)));
    }

    @Test
    public void theInstalledVersionIsTheOneThisBuildCarries() {
        // The comparison hangs off this, so an empty or odd value would silently
        // mean "never offer an update".
        String version = Updater.installedVersion(context);
        assertFalse("no version name", version.isEmpty());
        assertFalse("the version has no numbers", Update.normalise(version).isEmpty());
        assertFalse(
                "a release of the same version must not count as newer",
                Update.isNewer(version, "v" + Update.normalise(version)));
    }

    @Test
    public void thereIsAButtonToCheckAndASwitchForTheDailyCheck() throws Exception {
        open("look");
        scrollTo(R.string.update_check_now);
        scrollToText(str(R.string.update_auto, str(R.string.look_on)));
    }

    @Test
    public void theDailyCheckCanBeTurnedOff() throws Exception {
        assertTrue(Updater.autoCheckEnabled(prefs));
        open("look");
        scrollToText(str(R.string.update_auto, str(R.string.look_on))).click();
        device.waitForIdle();
        assertFalse(Updater.autoCheckEnabled(prefs));
    }

    @Test
    public void aFoundUpdateIsNamedOnTheSettingsScreen() throws Exception {
        prefs.edit().putString(Updater.PREF_FOUND, "9.9").apply();
        open("look");
        scrollToText(str(R.string.update_available, "9.9"));
    }

    @Test
    public void aFailedCheckIsReportedRatherThanHidden() throws Exception {
        prefs.edit().putString(Updater.PREF_LAST_ERROR, "no network").apply();
        open("look");
        scrollToText(str(R.string.update_check_failed, "no network"));
    }

    // ───────────────────────────── how often it asks ─────────────────────────────

    @Test
    public void theFirstCheckIsDueAndTheNextIsNotUntilTomorrow() {
        long now = 1_700_000_000_000L;
        assertTrue("never checked", Updater.dueForCheck(prefs, now));

        prefs.edit().putLong(Updater.PREF_LAST_CHECK, now).apply();
        assertFalse(Updater.dueForCheck(prefs, now + 1000));
        assertFalse(Updater.dueForCheck(prefs, now + Updater.CHECK_EVERY_MS - 1000));
        assertTrue(Updater.dueForCheck(prefs, now + Updater.CHECK_EVERY_MS));
    }

    @Test
    public void aClockThatJumpsBackwardsDoesNotStopTheCheckForEver() {
        // The unit sets its clock from GPS after boot, which can move it back.
        prefs.edit().putLong(Updater.PREF_LAST_CHECK, 2_000_000_000_000L).apply();
        assertTrue(Updater.dueForCheck(prefs, 1_000_000_000_000L));
    }

    @Test
    public void aVersionIsOnlyOfferedOnce() {
        assertFalse(Updater.alreadyOffered(prefs, "2.2"));
        Updater.markOffered(prefs, "2.2");
        assertTrue(Updater.alreadyOffered(prefs, "2.2"));
        // But a later one still gets its turn.
        assertFalse(Updater.alreadyOffered(prefs, "2.3"));
    }

    // ───────────────────────────── handing over the file ─────────────────────────────

    /** Writes something that stands in for a downloaded APK. */
    private File fakeDownload(String name) throws Exception {
        File apk = ApkProvider.file(context, name);
        try (FileOutputStream out = new FileOutputStream(apk)) {
            out.write("not really an apk".getBytes(StandardCharsets.UTF_8));
        }
        return apk;
    }

    @Test
    public void theInstallerIsGivenAContentUriNotAFilePath() throws Exception {
        File apk = fakeDownload("navi-9.9-release.apk");
        Intent install = Updater.installIntent(context, apk);
        assertNotNull("nothing on this unit can open an APK", install);

        Uri uri = install.getData();
        assertEquals("content", uri.getScheme());
        assertEquals(ApkProvider.AUTHORITY, uri.getAuthority());
        // A file:// URI is what throws FileUriExposedException from Android 7 on.
        assertFalse(String.valueOf(uri), uri.toString().startsWith("file:"));
        assertTrue(
                "the installer needs read permission on the URI",
                (install.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
    }

    @Test
    public void theProviderServesTheDownloadedFile() throws Exception {
        File apk = fakeDownload("navi-9.9-release.apk");
        Uri uri = ApkProvider.uriFor(apk.getName());

        try (Cursor c = context.getContentResolver().query(uri, null, null, null, null)) {
            assertNotNull(c);
            assertTrue(c.moveToFirst());
            assertEquals(
                    apk.getName(),
                    c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)));
            assertEquals(apk.length(), c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE)));
        }
        assertEquals(ApkProvider.MIME, context.getContentResolver().getType(uri));
        assertNotNull(context.getContentResolver().openInputStream(uri));
    }

    @Test
    public void theProviderRefusesAnythingOutsideTheUpdateFolder() throws Exception {
        // A path that climbs out would otherwise hand any file to any caller.
        assertNull(
                context.getContentResolver()
                        .query(
                                ApkProvider.uriFor("../../databases/navi.db"),
                                null,
                                null,
                                null,
                                null));
        assertNull(
                context.getContentResolver()
                        .query(
                                ApkProvider.uriFor("nothing-downloaded.apk"),
                                null,
                                null,
                                null,
                                null));
    }

    @Test
    public void aUnitThatWillNotInstallPackagesIsAskedForPermissionNotIgnored() throws Exception {
        // Whatever this unit answers, there has to be somewhere to send the owner.
        Intent settings = Updater.unknownSourcesSettings(context);
        assertEquals(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, settings.getAction());
        assertEquals("package:" + context.getPackageName(), settings.getData().toString());
    }
}
