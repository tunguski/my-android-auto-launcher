package pl.matsuo.navi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import java.io.File;
import java.io.IOException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Journey: the owner decides where trips are copied to, and the launcher keeps recording whatever
 * that decision turns out to be.
 *
 * <p>The negative paths matter more than the happy one here. A backup that is off, unconfigured,
 * failing or refusing to authenticate must cost nothing but an unsynced trip.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class BackupJourneyTest extends LauncherTest {

    private SharedPreferences prefs;
    private TripDb db;

    @Before
    public void startFresh() throws Exception {
        resetState();
        prefs = context.getSharedPreferences(MainActivity.PREFS, 0);
        db = TripDb.of(context);
    }

    /** A backend that always fails, to prove what happens when Drive misbehaves. */
    private static final class BrokenBackup implements Backup {
        int attempts;

        @Override
        public boolean configured() {
            return true;
        }

        @Override
        public String upload(String folder, String name, byte[] data) throws IOException {
            attempts++;
            throw new IOException("no network");
        }
    }

    private File exportDir() {
        return new File(context.getExternalFilesDir(null), FolderBackup.EXPORT);
    }

    // ───────────────────────────── off by default ─────────────────────────────

    @Test
    public void backupIsOffUntilItIsTurnedOn() {
        assertEquals(BackupRunner.MODE_OFF, BackupRunner.mode(prefs));
        assertFalse(BackupRunner.enabled(prefs));
        assertEquals(null, BackupRunner.backend(context, prefs));
    }

    @Test
    public void withBackupOffNothingLeavesTheUnit() throws Exception {
        installTripHistory();
        Trip.closeCurrent(db);
        int waiting = BackupRunner.waiting(db);
        assertTrue("there should be finished trips to copy", waiting > 0);

        BackupRunner.trigger(context, prefs);
        Thread.sleep(1500);

        assertFalse("nothing should have been exported", exportDir().exists());
        assertEquals("no trip may be marked as copied", waiting, BackupRunner.waiting(db));
    }

    // ───────────────────────────── the happy path ─────────────────────────────

    @Test
    public void turningOnTheFolderBackupCopiesEveryFinishedTrip() throws Exception {
        installTripHistory();
        Trip.closeCurrent(db); // the drive in progress finishes
        int waiting = BackupRunner.waiting(db);
        assertTrue(waiting > 0);

        prefs.edit().putString(BackupRunner.PREF_MODE, BackupRunner.MODE_FOLDER).apply();
        BackupRunner.runNow(context, prefs);

        assertEquals("everything should have been copied", 0, BackupRunner.waiting(db));
        assertEquals(0, prefs.getInt(BackupRunner.PREF_FAILURES, -1));
        assertTrue(prefs.getLong(BackupRunner.PREF_LAST_OK, 0) > 0);

        // Every trip the database calls copied has to actually be on disk. Counting
        // files instead would be fragile: the service is recording the emulator's
        // own position while this runs, so the total is not a fixed number.
        int copied = 0;
        for (TripDb.Row r : db.recent(100)) {
            if (r.uploadedAt == 0) {
                continue;
            }
            File written =
                    new File(
                            new File(exportDir(), Artifact.year(r.startedAt)),
                            Artifact.name(r.startedAt));
            assertTrue("marked as copied but not written: trip " + r.id, written.exists());
            copied++;
        }
        assertEquals("every finished trip should have been copied", waiting, copied);
    }

    @Test
    public void anExportedTripIsTheDriveThatWasRecorded() throws Exception {
        installTripHistory();
        Trip.closeCurrent(db);
        prefs.edit().putString(BackupRunner.PREF_MODE, BackupRunner.MODE_FOLDER).apply();
        BackupRunner.runNow(context, prefs);

        TripDb.Row row = db.recent(50).get(0);
        File written =
                new File(
                        new File(exportDir(), Artifact.year(row.startedAt)),
                        Artifact.name(row.startedAt));
        assertTrue("no file for the newest trip", written.exists());

        Track.Fixes exported = Artifact.parse(java.nio.file.Files.readAllBytes(written.toPath()));
        assertEquals(row.points, exported.n);
        assertEquals(row.startedAt, exported.t[0]);
    }

    @Test
    public void aTripIsNotCopiedTwice() throws Exception {
        installTripHistory();
        Trip.closeCurrent(db);
        prefs.edit().putString(BackupRunner.PREF_MODE, BackupRunner.MODE_FOLDER).apply();

        BackupRunner.runNow(context, prefs);
        long firstPass = prefs.getLong(BackupRunner.PREF_LAST_OK, 0);
        BackupRunner.runNow(context, prefs);

        assertEquals(0, BackupRunner.waiting(db));
        assertTrue(prefs.getLong(BackupRunner.PREF_LAST_OK, 0) >= firstPass);
    }

    @Test
    public void theDriveInProgressIsNeverCopied() throws Exception {
        installTripHistory(); // leaves the long drive open
        assertTrue("the fixture should leave a drive open", Trip.currentId() != 0);

        prefs.edit().putString(BackupRunner.PREF_MODE, BackupRunner.MODE_FOLDER).apply();
        BackupRunner.runNow(context, prefs);

        TripDb.Row open = db.trip(Trip.currentId());
        assertEquals("an unfinished drive must not be uploaded", 0, open.uploadedAt);
    }

    // ───────────────────────────── when it goes wrong ─────────────────────────────

    @Test
    public void aFailingBackendCostsNothingButAnUnsyncedTrip() throws Exception {
        installTripHistory();
        Trip.closeCurrent(db);
        int waiting = BackupRunner.waiting(db);

        prefs.edit().putString(BackupRunner.PREF_MODE, BackupRunner.MODE_FOLDER).apply();
        BrokenBackup broken = new BrokenBackup();
        BackupRunner.runNow(context, prefs, broken);

        assertEquals("it should have tried", 1, broken.attempts);
        assertEquals("and given up for now", waiting, BackupRunner.waiting(db));
        assertEquals(1, prefs.getInt(BackupRunner.PREF_FAILURES, 0));
        assertEquals("no network", prefs.getString(BackupRunner.PREF_LAST_ERROR, ""));

        // The trips themselves are untouched, and so is the drive in progress.
        assertEquals(waiting, db.pendingBackup(100).size());
        assertTrue(db.recent(50).size() > 0);
    }

    @Test
    public void repeatedFailuresBackOffInsteadOfHammering() throws Exception {
        installTripHistory();
        Trip.closeCurrent(db);
        prefs.edit().putString(BackupRunner.PREF_MODE, BackupRunner.MODE_FOLDER).apply();

        BrokenBackup broken = new BrokenBackup();
        BackupRunner.runNow(context, prefs, broken);
        assertEquals(1, broken.attempts);

        // The next trigger is inside the backoff window, so it must not run.
        BackupRunner.trigger(context, prefs);
        Thread.sleep(1000);
        assertEquals("it should have waited", 1, broken.attempts);
    }

    @Test
    public void anUnconfiguredBackendSaysSoRatherThanFailingSilently() throws Exception {
        installTripHistory();
        Trip.closeCurrent(db);
        prefs.edit().putString(BackupRunner.PREF_MODE, BackupRunner.MODE_DRIVE).apply();

        // No OAuth client in the public build, so Drive cannot be configured.
        assertEquals(null, BackupRunner.backend(context, prefs));
        BackupRunner.runNow(context, prefs);

        assertFalse(prefs.getString(BackupRunner.PREF_LAST_ERROR, "").isEmpty());
        assertEquals(1, prefs.getInt(BackupRunner.PREF_FAILURES, 0));
    }

    @Test
    public void recordingCarriesOnWhileBackupIsBroken() throws Exception {
        prefs.edit().putString(BackupRunner.PREF_MODE, BackupRunner.MODE_DRIVE).apply();
        BackupRunner.runNow(context, prefs); // fails: no client

        // Counted as a difference, because the real service is recording the
        // emulator's own position at the same time.
        int before = Trip.points;
        long now = System.currentTimeMillis();
        for (int i = 0; i < 5; i++) {
            Trip.record(db, now + i * 10_000L, 52.0 + i * 0.001, 21.0, Trip.NO_ACCURACY);
        }

        assertEquals("the drive was recorded regardless", before + 5, Trip.points);
        assertTrue(db.openFixes(Trip.currentId()).n >= 5);
    }

    @Test
    public void switchingBackupOffAgainLeavesTheTripsAlone() throws Exception {
        installTripHistory();
        Trip.closeCurrent(db);
        int trips = db.recent(100).size();

        prefs.edit().putString(BackupRunner.PREF_MODE, BackupRunner.MODE_FOLDER).apply();
        BackupRunner.runNow(context, prefs);
        prefs.edit().putString(BackupRunner.PREF_MODE, BackupRunner.MODE_OFF).apply();

        assertEquals(trips, db.recent(100).size());
        assertFalse(BackupRunner.enabled(prefs));
    }

    // ───────────────────────────── through the settings ─────────────────────────────

    @Test
    public void theBackupSectionIsInTheSettings() throws Exception {
        open("look");
        scrollTo(R.string.backup_section);
        scrollTo(R.string.backup_off);
        scrollTo(R.string.backup_drive);
    }

    @Test
    public void choosingGoogleDriveWithoutAClientExplainsItself() throws Exception {
        open("look");
        tapAfterScroll(R.string.backup_drive);
        assertEquals(BackupRunner.MODE_DRIVE, BackupRunner.mode(prefs));
        // The build carries no OAuth client, so it must say so rather than
        // pretending to work. Choosing a mode rebuilds the panel from the top,
        // so the message has to be scrolled back into view.
        scrollTo(R.string.backup_no_client);
    }

    @Test
    public void choosingTheFolderShowsWhereTripsWillGo() throws Exception {
        open("look");
        tapAfterScroll(R.string.backup_folder);
        assertEquals(BackupRunner.MODE_FOLDER, BackupRunner.mode(prefs));
        scrollTo(R.string.storage_section); // brings the end of the panel into view
        assertTrue(
                "the settings should name the folder trips go to",
                hasTextContaining(FolderBackup.EXPORT));
    }

    @Test
    public void theStorageSectionSaysWhatIsStored() throws Exception {
        installTripHistory();
        open("look");
        scrollTo(R.string.backup_export_now); // sits just below the count
        assertTrue(
                "the number of stored trips should be on screen",
                hasTextContaining("" + db.count()));
    }
}
