package pl.matsuo.navi;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import java.io.File;
import java.io.InputStream;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The SQLite side of storage, on a real device because that is the only place SQLite exists.
 *
 * <p>The recording rules are unit tested against an in-memory store; what is proved here is that
 * this implementation keeps the same contract, and that what goes into the database comes back out
 * of it.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class TripDbTest {

    /** A database of its own, so the recording service cannot race this test. */
    private static final String TEST_DB = "navi-test.db";

    private Context context;
    private TripDb db;
    private long t0;

    @Before
    public void freshDatabase() throws Exception {
        context = getInstrumentation().getTargetContext();
        // A database of its own is not enough. The recorder keeps the live drive in
        // static fields, and these tests drive those fields; a service recording the
        // emulator's own position at the same time would put its fixes into whichever
        // drive these tests have made current - and into the other database. Stop it.
        // The next test that opens the launcher starts it again.
        stopRecorder();
        context.deleteDatabase(TEST_DB);
        db = new TripDb(context, TEST_DB);
        t0 = 1_700_000_000L; // seconds
        Trip.forget();
    }

    @After
    public void tidy() {
        db.close();
        context.deleteDatabase(TEST_DB);
        // Nothing in the real database answers to the ids used here.
        Trip.forget();
    }

    private void stopRecorder() throws Exception {
        ParcelFileDescriptor fd =
                getInstrumentation()
                        .getUiAutomation()
                        .executeShellCommand("am stopservice pl.matsuo.navi/.TripService");
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            byte[] buf = new byte[256];
            while (in.read(buf) > 0) {
                // Drained, so the command has finished before the test starts.
            }
        }
    }

    private long threeFixDrive() {
        long id = db.open(t0, Track.micro(52.000000), Track.micro(21.000000));
        db.append(id, t0 + 10, Track.micro(52.001000), Track.micro(21.000000));
        db.append(id, t0 + 20, Track.micro(52.002000), Track.micro(21.000000));
        return id;
    }

    // ───────────────────────────── recording ─────────────────────────────

    @Test
    public void anOpenedTripIsFoundAgainWithItsFixes() {
        long id = threeFixDrive();

        TripStore.Open open = db.findOpen();
        assertEquals(id, open.id);
        assertEquals(t0, open.startedAtSeconds);
        assertEquals(t0 + 20, open.lastFixSeconds);
        assertEquals(3, open.points);
        assertEquals(Track.micro(52.002000), open.lastLatMicro);
        assertTrue("the drive should have a length", open.meters > 100);
    }

    @Test
    public void aTripOpenedButNeverWrittenToStillKnowsWhenItStarted() {
        // The contract the in-memory double copies: TripService can die between
        // opening a trip and the first fix landing.
        long id = db.open(t0, Track.micro(52.0), Track.micro(21.0));
        db.getWritableDatabase().delete("open_fix", "trip_id=?", new String[] {Long.toString(id)});

        TripStore.Open open = db.findOpen();
        assertEquals(id, open.id);
        assertEquals(0, open.points);
        assertEquals(t0, open.startedAtSeconds);
    }

    @Test
    public void closingPacksTheDriveAndClearsTheWorkingRows() {
        long id = threeFixDrive();
        db.close(id);

        assertNull("the drive is no longer open", db.findOpen());
        assertEquals(0, db.openFixes(id).n);

        TripDb.Row r = db.trip(id);
        assertTrue(r.closed);
        assertEquals(3, r.points);
        assertEquals(t0, r.startedAt);
        assertEquals(t0 + 20, r.endedAt);
        assertEquals(20_000L, r.durationMillis());
        assertEquals(222, r.meters, 10); // two steps of a thousandth of a degree

        Track.Fixes back = Track.decode(db.track(id));
        assertEquals(3, back.n);
        assertEquals(Track.micro(52.002000), back.lat[2]);
        assertEquals(t0 + 10, back.t[1]);
    }

    @Test
    public void thePackedTrackIsTheSmallFormat() {
        long id = db.open(t0, Track.micro(52.0), Track.micro(21.0));
        for (int i = 1; i < 100; i++) {
            db.append(id, t0 + i * 10, Track.micro(52.0 + i * 0.0005), Track.micro(21.0));
        }
        db.close(id);
        // Five bytes a fix plus the header, not thirty-one a fix.
        assertEquals(Track.HEADER + 5 * 99, db.track(id).length);
    }

    @Test
    public void finishingADriveGivesBackTheSpaceItsWorkingRowsUsed() {
        // A four-hour drive leaves thousands of working rows behind. Measured here
        // rather than in a journey test because the recording service writes to the
        // shared database every second, which both regrows the log and can block
        // the checkpoint that shortens the file.
        long id = db.open(t0, Track.micro(52.0), Track.micro(21.0));
        for (int i = 1; i < 4000; i++) {
            db.append(id, t0 + i, Track.micro(52.0 + i * 0.0002), Track.micro(21.0));
        }
        long whileDriving = sizeOnDisk();

        db.close(id);

        long afterwards = sizeOnDisk();
        assertTrue(
                "closing a drive should release its working rows: "
                        + whileDriving
                        + " -> "
                        + afterwards,
                afterwards < whileDriving);
        // And what is left is the packed track, not the rows it came from.
        assertEquals(4000, Track.decode(db.track(id)).n);
    }

    /** The database and its write-ahead log; space hidden in the log is space used. */
    private long sizeOnDisk() {
        File file = context.getDatabasePath(TEST_DB);
        File wal = new File(file.getParentFile(), TEST_DB + "-wal");
        return file.length() + (wal.exists() ? wal.length() : 0);
    }

    @Test
    public void closingTwiceIsHarmless() {
        long id = threeFixDrive();
        db.close(id);
        byte[] track = db.track(id);
        db.close(id);

        assertEquals(3, db.trip(id).points);
        assertEquals(track.length, db.track(id).length);
    }

    @Test
    public void aDriveThatRecordedNothingLeavesNoTrace() {
        long id = db.open(t0, Track.micro(52.0), Track.micro(21.0));
        db.getWritableDatabase().delete("open_fix", "trip_id=?", new String[] {Long.toString(id)});
        db.close(id);

        assertNull(db.trip(id));
        assertEquals(0, db.count());
    }

    @Test
    public void twoDrivesStartingInTheSameSecondBothSurvive() {
        // Only a confused clock does this, and refusing to record would be worse.
        long a = db.open(t0, Track.micro(52.0), Track.micro(21.0));
        long b = db.open(t0, Track.micro(53.0), Track.micro(22.0));
        assertNotEquals(a, b);
        assertEquals(2, db.count());
    }

    @Test
    public void twoFixesInTheSameSecondKeepTheLaterOne() {
        long id = db.open(t0, Track.micro(52.0), Track.micro(21.0));
        db.append(id, t0, Track.micro(52.5), Track.micro(21.0));
        assertEquals(1, db.openFixes(id).n);
        assertEquals(Track.micro(52.5), db.openFixes(id).lat[0]);
    }

    @Test
    public void theNewestOpenDriveIsTheOneResumed() {
        long old = threeFixDrive();
        db.close(old);
        long current = db.open(t0 + 1000, Track.micro(53.0), Track.micro(22.0));

        assertEquals(current, db.findOpen().id);
    }

    @Test
    public void nothingOpenMeansNothingToResume() {
        long id = threeFixDrive();
        db.close(id);
        assertNull(db.findOpen());
    }

    // ───────────────────────────── reading back ─────────────────────────────

    @Test
    public void theHistoryIsNewestFirstAndSkipsWhatHasNoShape() {
        long first = threeFixDrive();
        db.close(first);

        long later = db.open(t0 + 1000, Track.micro(53.0), Track.micro(22.0));
        db.append(later, t0 + 1010, Track.micro(53.001), Track.micro(22.0));
        db.close(later);

        long single = db.open(t0 + 2000, Track.micro(54.0), Track.micro(23.0));
        db.close(single); // one fix: a dot, not a route

        long open = db.open(t0 + 3000, Track.micro(55.0), Track.micro(24.0));
        db.append(open, t0 + 3010, Track.micro(55.001), Track.micro(24.0));

        List<TripDb.Row> rows = db.recent(50);
        assertEquals(2, rows.size());
        assertEquals(later, rows.get(0).id);
        assertEquals(first, rows.get(1).id);
        assertNotEquals("the drive in progress is not history", open, rows.get(0).id);
    }

    @Test
    public void theHistoryHonoursItsLimit() {
        for (int i = 0; i < 5; i++) {
            long id = db.open(t0 + i * 100, Track.micro(52.0 + i), Track.micro(21.0));
            db.append(id, t0 + i * 100 + 10, Track.micro(52.0 + i + 0.001), Track.micro(21.0));
            db.close(id);
        }
        assertEquals(3, db.recent(3).size());
        assertEquals(5, db.recent(50).size());
    }

    @Test
    public void aRouteComesBackForAFinishedDriveAndForOneInProgress() {
        long open = threeFixDrive();
        assertEquals(3, db.route(open).size());
        assertEquals(52.002, db.route(open).get(2)[0], 1e-6);

        db.close(open);
        assertEquals(3, db.route(open).size());
        assertEquals(21.0, db.route(open).get(0)[1], 1e-6);
    }

    @Test
    public void askingForATripThatIsNotThereIsNotAnError() {
        assertNull(db.trip(999));
        assertTrue(db.route(999).isEmpty());
        assertEquals(0, db.track(999).length);
    }

    // ───────────────────────────── importing ─────────────────────────────

    @Test
    public void animportedTripIsStoredOnceHoweverOftenItIsOffered() {
        Track.Fixes fixes =
                new Track.Fixes(
                        new long[] {t0, t0 + 10},
                        new int[] {Track.micro(52.0), Track.micro(52.001)},
                        new int[] {Track.micro(21.0), Track.micro(21.0)},
                        2);

        long id = db.importTrip(fixes, TripDb.SOURCE_CSV);
        assertTrue(id > 0);
        assertEquals(-1, db.importTrip(fixes, TripDb.SOURCE_CSV));
        assertEquals(1, db.count());

        TripDb.Row r = db.trip(id);
        assertEquals(TripDb.SOURCE_CSV, r.source);
        assertEquals(2, r.points);
        assertTrue(r.closed);
        assertEquals(2, Track.decode(db.track(id)).n);
    }

    @Test
    public void animportWithNoFixesIsRefused() {
        assertEquals(-1, db.importTrip(Track.decode(new byte[0]), TripDb.SOURCE_CSV));
        assertEquals(0, db.count());
    }

    // ───────────────────────────── backup bookkeeping ─────────────────────────────

    @Test
    public void onlyFinishedDrivesAreOfferedForBackup() {
        long closed = threeFixDrive();
        db.close(closed);
        long open = db.open(t0 + 1000, Track.micro(53.0), Track.micro(22.0));
        db.append(open, t0 + 1010, Track.micro(53.001), Track.micro(22.0));

        List<TripDb.Row> pending = db.pendingBackup(10);
        assertEquals(1, pending.size());
        assertEquals(closed, pending.get(0).id);
    }

    @Test
    public void aBackedUpDriveIsNotOfferedAgain() {
        long id = threeFixDrive();
        db.close(id);

        db.markBackedUp(id, "remote-1", "hash-1", t0 + 100);
        assertTrue(db.pendingBackup(10).isEmpty());

        TripDb.Row r = db.trip(id);
        assertEquals("remote-1", r.remoteId);
        assertEquals(t0 + 100, r.uploadedAt);
    }

    @Test
    public void forgettingTheBackupStateOffersEverythingAgain() {
        long id = threeFixDrive();
        db.close(id);
        db.markBackedUp(id, "remote-1", "hash-1", t0 + 100);

        db.clearBackupState();

        assertEquals(1, db.pendingBackup(10).size());
        assertNull(db.trip(id).remoteId);
    }

    @Test
    public void pendingDrivesComeOutOldestFirstSoTheyUploadInOrder() {
        long older = threeFixDrive();
        db.close(older);
        long newer = db.open(t0 + 5000, Track.micro(53.0), Track.micro(22.0));
        db.append(newer, t0 + 5010, Track.micro(53.001), Track.micro(22.0));
        db.close(newer);

        List<TripDb.Row> pending = db.pendingBackup(10);
        assertEquals(older, pending.get(0).id);
        assertEquals(newer, pending.get(1).id);
    }

    // ───────────────────────────── notes ─────────────────────────────

    @Test
    public void notesSurviveAndCanBeOverwritten() {
        assertEquals("fallback", db.meta("nothing", "fallback"));
        db.setMeta("k", "one");
        assertEquals("one", db.meta("k", "fallback"));
        db.setMeta("k", "two");
        assertEquals("two", db.meta("k", "fallback"));
    }

    // ───────────────────────────── the whole way through ─────────────────────────────

    @Test
    public void aFixtureDriveRecordsPacksAndReadsBackIntact() {
        List<double[]> drive = TripFixtures.expand(TripFixtures.SHORT_COMMUTE, t0 * 1000L);
        for (double[] f : drive) {
            Trip.record(db, (long) f[0], f[1], f[2], Trip.NO_ACCURACY);
        }
        long id = Trip.currentId();
        double live = Trip.meters;
        Trip.closeCurrent(db);

        TripDb.Row r = db.trip(id);
        assertEquals(drive.size(), r.points);
        assertEquals("the packed length must match what was counted live", live, r.meters, 1.0);
        assertEquals(drive.size(), db.route(id).size());
        assertFalse(db.recent(10).isEmpty());
    }

    // ──────────────────── where the last drive left the car ────────────────────

    @Test
    public void nothingStoredMeansNoLastPoint() {
        assertNull(db.lastPoint());
    }

    @Test
    public void theLastPointOfAFinishedDriveComesBackFromThePackedTrack() {
        long id = threeFixDrive();
        db.close(id);

        double[] p = db.lastPoint();
        assertEquals(52.002000, p[0], 1e-6);
        assertEquals(21.000000, p[1], 1e-6);
    }

    @Test
    public void theLastPointOfADriveInProgressComesBackFromItsFixes() {
        threeFixDrive(); // left open
        double[] p = db.lastPoint();
        assertEquals(52.002000, p[0], 1e-6);
    }

    @Test
    public void theNewestDriveIsTheOneThatCounts() {
        long older = threeFixDrive();
        db.close(older);
        long newer = db.open(t0 + 86400, Track.micro(50.000000), Track.micro(19.000000));
        db.close(newer);

        double[] p = db.lastPoint();
        assertEquals("an older drive answered instead of the newest", 50.0, p[0], 1e-6);
    }

    @Test
    public void aNewDriveIsStitchedToWhereTheLastOneParked() {
        // The whole point, through the real database: a drive is finished and the
        // car parked; later the receiver takes a minute to find itself, and the
        // new drive must still begin at the parking spot rather than down the road.
        long parked = threeFixDrive();
        db.close(parked);
        double[] spot = db.lastPoint();

        long startedListening = (t0 + 86400) * 1000L;
        Trip.armSeed(startedListening, spot[0], spot[1]);
        // 50 seconds later, 1.2 km north: within the window and the allowance.
        assertTrue(
                Trip.record(
                        db,
                        startedListening + 50_000,
                        spot[0] + 1200 / 111_195.0,
                        spot[1],
                        Trip.NO_ACCURACY));
        long id = Trip.currentId();
        Trip.closeCurrent(db);

        assertNotEquals(parked, id);
        List<double[]> route = db.route(id);
        assertEquals("the parking spot is not the first point", 2, route.size());
        assertEquals(spot[0], route.get(0)[0], 1e-5);
        assertEquals(spot[1], route.get(0)[1], 1e-5);
        assertEquals("the missing leg was not counted", 1200, db.trip(id).meters, 10);
        assertEquals(
                "the drive does not start when recording did", t0 + 86400, db.trip(id).startedAt);
    }
}
