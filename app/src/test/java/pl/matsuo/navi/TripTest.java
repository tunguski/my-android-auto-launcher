package pl.matsuo.navi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * The recording rulebook: which fixes count, what the figures add up to, when one drive ends and
 * the next begins, and how a restarted process picks a drive up again.
 */
public class TripTest {

    private FakeStore store;
    private long t0;

    @Before
    public void setUp() {
        store = new FakeStore();
        t0 = 1_700_000_000_000L; // a fixed instant
        Trip.forget();
    }

    private boolean fix(long at, double lat, double lon) {
        return Trip.record(store, at, lat, lon, Trip.NO_ACCURACY);
    }

    // ───────────────────────────── starting a trip ─────────────────────────────

    @Test
    public void theFirstFixOpensATrip() {
        assertTrue(fix(t0, 52.2297, 21.0122));
        assertEquals(1, store.tripCount());
        assertEquals(1, Trip.points);
        assertEquals(t0, Trip.startedAt);
        assertEquals(0, Trip.meters, 0.001);
        assertEquals(52.2297, Trip.lastLat, 1e-6);
        assertEquals(21.0122, Trip.lastLon, 1e-6);
        assertNotEquals(0, Trip.currentId());
    }

    @Test
    public void theFirstFixIsStored() {
        fix(t0, 52.2297, 21.0122);
        FakeStore.Stored s = store.newest();
        assertEquals(1, s.points());
        assertEquals(t0 / 1000, s.startedAt());
    }

    @Test
    public void nothingIsOpenBeforeTheFirstFix() {
        assertEquals(0, Trip.currentId());
        assertEquals(0, store.tripCount());
    }

    @Test
    public void aPauseLongerThanFiveMinutesEndsTheDriveAndStartsAnother() {
        fix(t0, 52.2297, 21.0122);
        long first = Trip.currentId();

        fix(t0 + Trip.GAP_MS + 1000, 52.4000, 21.0122);

        assertEquals(2, store.tripCount());
        assertTrue("the first drive was not closed", store.get(first).closed);
        assertNotEquals(first, Trip.currentId());
        assertEquals(1, Trip.points); // the figures restart with the new drive
        assertEquals(0, Trip.meters, 0.001);
    }

    @Test
    public void aShorterPauseKeepsTheSameDrive() {
        fix(t0, 52.2297, 21.0122);
        long id = Trip.currentId();
        fix(t0 + Trip.GAP_MS - 1000, 52.2397, 21.0122);

        assertEquals(1, store.tripCount());
        assertEquals(id, Trip.currentId());
        assertEquals(2, Trip.points);
        assertEquals(0, store.closedCount());
    }

    @Test
    public void aPauseOfExactlyTheGapStillKeepsTheDrive() {
        fix(t0, 52.2297, 21.0122);
        fix(t0 + Trip.GAP_MS, 52.2397, 21.0122);
        assertEquals(1, store.tripCount());
    }

    // ──────────────────────────── filtering fixes ────────────────────────────

    @Test
    public void anImpreciseFixIsDropped() {
        assertFalse(Trip.record(store, t0, 52.2297, 21.0122, Trip.MAX_ACCURACY_M + 1));
        assertEquals(0, store.tripCount());
        assertEquals(0, Trip.points);
    }

    @Test
    public void aFixAtTheAccuracyLimitIsKept() {
        assertTrue(Trip.record(store, t0, 52.2297, 21.0122, Trip.MAX_ACCURACY_M));
        assertEquals(1, Trip.points);
    }

    @Test
    public void anUnknownAccuracyIsNotTreatedAsBad() {
        assertTrue(Trip.record(store, t0, 52.2297, 21.0122, Trip.NO_ACCURACY));
        assertEquals(1, Trip.points);
    }

    @Test
    public void standingStillStoresNothingButKeepsTheDriveAlive() {
        fix(t0, 52.2297, 21.0122);
        assertFalse("two metres is not movement", fix(t0 + 10_000, 52.22972, 21.01220));
        assertEquals(1, Trip.points);
        assertEquals(0, Trip.meters, 0.001);

        // The drive is still open ten minutes later because the clock kept moving.
        fix(t0 + 10_000 + Trip.GAP_MS - 1000, 52.2397, 21.0122);
        assertEquals(1, store.tripCount());
        assertEquals(2, Trip.points);
    }

    @Test
    public void aGpsJumpIsRecordedButNotCounted() {
        fix(t0, 52.2297, 21.0122);
        fix(t0 + 10_000, 54.3520, 18.6466); // 283 km in ten seconds
        assertEquals(2, Trip.points);
        assertEquals(0, Trip.meters, 0.001);
        assertEquals(2, store.newest().points());
    }

    @Test
    public void distanceAddsUpAlongTheDrive() {
        fix(t0, 52.2297, 21.0122);
        fix(t0 + 10_000, 52.2397, 21.0122);
        fix(t0 + 20_000, 52.2497, 21.0122);
        double expected =
                Geo.distance(52.2297, 21.0122, 52.2397, 21.0122)
                        + Geo.distance(52.2397, 21.0122, 52.2497, 21.0122);
        assertEquals(expected, Trip.meters, 1.0);
        assertEquals(3, Trip.points);
    }

    @Test
    public void aStepJustOverTheMovementThresholdCounts() {
        fix(t0, 52.0, 21.0);
        assertTrue(fix(t0 + 10_000, 52.000060, 21.0)); // about 6.7 m north
        assertEquals(2, Trip.points);
    }

    // ───────────────────── resuming after a process restart ─────────────────────

    @Test
    public void aRecentDriveIsPickedUpAgain() {
        fix(t0, 52.2297, 21.0122);
        fix(t0 + 10_000, 52.2397, 21.0122);
        long id = Trip.currentId();
        double metersBefore = Trip.meters;

        store.restart();
        Trip.resume(store, t0 + 60_000);

        assertEquals(id, Trip.currentId());
        assertEquals(2, Trip.points);
        assertEquals(metersBefore, Trip.meters, 1.0);
        assertEquals(t0, Trip.startedAt);
        assertEquals(52.2397, Trip.lastLat, 1e-6);
    }

    @Test
    public void fixesAfterAResumeJoinTheSameDrive() {
        fix(t0, 52.2297, 21.0122);
        fix(t0 + 10_000, 52.2397, 21.0122);
        long id = Trip.currentId();

        store.restart();
        Trip.resume(store, t0 + 60_000);
        fix(t0 + 70_000, 52.2497, 21.0122);

        assertEquals(1, store.tripCount());
        assertEquals(id, Trip.currentId());
        assertEquals(3, Trip.points);
        assertEquals(3, store.get(id).points());
    }

    @Test
    public void anOldDriveIsClosedRatherThanResumed() {
        fix(t0, 52.2297, 21.0122);
        long id = Trip.currentId();

        store.restart();
        Trip.resume(store, t0 + Trip.GAP_MS + 1000);

        assertTrue("the stale drive should have been closed", store.get(id).closed);
        assertEquals(0, Trip.currentId());

        fix(t0 + Trip.GAP_MS + 2000, 52.2397, 21.0122);
        assertNotEquals(id, Trip.currentId());
        assertEquals(2, store.tripCount());
    }

    @Test
    public void resumingWithNothingStoredIsHarmless() {
        Trip.resume(store, t0);
        assertEquals(0, Trip.currentId());
        assertEquals(0, Trip.points);
    }

    @Test
    public void resumingWhenEverythingIsClosedIsHarmless() {
        fix(t0, 52.0, 21.0);
        Trip.closeCurrent(store);

        Trip.resume(store, t0 + 1000);
        assertEquals(0, Trip.currentId());
    }

    @Test
    public void aTripOpenedButNeverWrittenToIsAdopted() {
        long id = store.open(t0 / 1000, Track.micro(52.0), Track.micro(21.0));
        store.get(id).fixes.clear(); // opened, then the process died before the first write

        Trip.resume(store, t0 + 1000);
        assertEquals(id, Trip.currentId());

        fix(t0 + 2000, 52.0, 21.0);
        assertEquals("no second trip should have been opened", 1, store.tripCount());
    }

    // ───────────────────────────── closing by hand ─────────────────────────────

    @Test
    public void closingEndsTheDriveAndClearsTheFigures() {
        fix(t0, 52.0, 21.0);
        long id = Trip.currentId();

        Trip.closeCurrent(store);

        assertTrue(store.get(id).closed);
        assertEquals(0, Trip.currentId());
        assertEquals(0, Trip.points);
        assertEquals(0, Trip.meters, 0.0);
    }

    @Test
    public void closingWhenNothingIsOpenDoesNothing() {
        Trip.closeCurrent(store);
        assertEquals(0, store.closeCalls);
        assertEquals(0, Trip.currentId());
    }

    @Test
    public void forgettingLeavesTheStoredDriveAlone() {
        fix(t0, 52.0, 21.0);
        long id = Trip.currentId();

        Trip.forget();

        assertEquals(0, Trip.currentId());
        assertFalse("forgetting must not close anything", store.get(id).closed);
        assertEquals(1, store.get(id).points());
    }

    // ───────────────────────────── the demo drives ─────────────────────────────

    @Test
    public void theWarsawGdanskFixtureRecordsAsOneDrive() {
        List<double[]> drive = TripFixtures.expand(TripFixtures.WARSAW_GDANSK, t0);
        for (double[] f : drive) {
            Trip.record(store, (long) f[0], f[1], f[2], Trip.NO_ACCURACY);
        }
        assertEquals("the drive was split", 1, store.tripCount());
        assertEquals(drive.size(), Trip.points);
        assertEquals(320_000, Trip.meters, 10_000);
    }

    @Test
    public void theShortFixturesRecordAsTownDrives() {
        for (TripFixtures.Leg[] legs :
                new TripFixtures.Leg[][] {TripFixtures.SHORT_ERRAND, TripFixtures.SHORT_COMMUTE}) {
            Trip.forget();
            FakeStore fresh = new FakeStore();
            for (double[] f : TripFixtures.expand(legs, t0)) {
                Trip.record(fresh, (long) f[0], f[1], f[2], Trip.NO_ACCURACY);
            }
            assertEquals(1, fresh.tripCount());
            assertTrue("length " + Trip.meters, Trip.meters > 1_000 && Trip.meters < 20_000);
        }
    }

    @Test
    public void theFixtureDriveSurvivesARestartHalfway() {
        List<double[]> drive = TripFixtures.expand(TripFixtures.WARSAW_GDANSK, t0);
        int half = drive.size() / 2;
        for (int i = 0; i < half; i++) {
            double[] f = drive.get(i);
            Trip.record(store, (long) f[0], f[1], f[2], Trip.NO_ACCURACY);
        }
        long id = Trip.currentId();
        double halfway = Trip.meters;

        store.restart();
        Trip.resume(store, (long) drive.get(half - 1)[0] + 60_000);
        for (int i = half; i < drive.size(); i++) {
            double[] f = drive.get(i);
            Trip.record(store, (long) f[0], f[1], f[2], Trip.NO_ACCURACY);
        }

        assertEquals("the drive was split by the restart", 1, store.tripCount());
        assertEquals(id, Trip.currentId());
        assertTrue(Trip.meters > halfway);
        assertEquals(320_000, Trip.meters, 10_000);
        assertEquals(drive.size(), store.get(id).points());
    }

    @Test
    public void aDriveIsNotResumedAcrossTheNight() {
        List<double[]> drive = TripFixtures.expand(TripFixtures.SHORT_COMMUTE, t0);
        for (double[] f : drive) {
            Trip.record(store, (long) f[0], f[1], f[2], Trip.NO_ACCURACY);
        }
        long id = Trip.currentId();

        store.restart();
        Trip.resume(store, (long) drive.get(drive.size() - 1)[0] + 12 * 3600_000L);

        assertTrue(store.get(id).closed);
        assertEquals(0, Trip.currentId());
    }

    @Test
    public void theTestDoubleRefusesAFixForATripThatWasNeverOpened() {
        // Guards the double itself: a rule change that appended without opening
        // would otherwise pass silently.
        try {
            store.append(99, t0 / 1000, 0, 0);
            org.junit.Assert.fail("expected the store to refuse");
        } catch (IllegalStateException expected) {
            assertNull(store.get(99));
        }
    }

    // ───────────────────── starting where the car parked ─────────────────────

    /** Where the previous drive ended; the spot offered as the next drive's start. */
    private static final double PARK_LAT = 52.2297;

    private static final double PARK_LON = 21.0122;

    /** Metres per degree of latitude on the sphere {@link Geo} uses. */
    private static final double M_PER_DEGREE = 111_195.0;

    /** A fix the given distance due north of the parking spot. */
    private boolean fixNorthOfPark(long at, double metres) {
        return fix(at, PARK_LAT + metres / M_PER_DEGREE, PARK_LON);
    }

    @Test
    public void theDriveBeginsWhereItParkedWhenTheFirstFixArrivesSoon() {
        Trip.armSeed(t0, PARK_LAT, PARK_LON);
        assertTrue(fixNorthOfPark(t0 + 40_000, 1500));

        // The drive starts at the parking spot and at the moment recording began,
        // not a kilometre and a half later where the receiver found itself.
        assertEquals(t0, Trip.startedAt);
        assertEquals(2, Trip.points);
        assertEquals(1500, Trip.meters, 5);
        FakeStore.Stored s = store.newest();
        assertEquals(t0 / 1000, s.startedAt());
        assertEquals(Track.micro(PARK_LAT), s.fixes.get(0)[1]);
        assertEquals(Track.micro(PARK_LON), s.fixes.get(0)[2]);
    }

    @Test
    public void theFirstFixStartsTheDriveWhenTheWaitWasTooLong() {
        Trip.armSeed(t0, PARK_LAT, PARK_LON);
        // A minute past the window: by now the car could have come from anywhere.
        assertTrue(fixNorthOfPark(t0 + Trip.SEED_WINDOW_MS + 60_000, 1000));

        assertEquals(t0 + Trip.SEED_WINDOW_MS + 60_000, Trip.startedAt);
        assertEquals(1, Trip.points);
        assertEquals(0, Trip.meters, 0.001);
    }

    @Test
    public void theFirstFixStartsTheDriveWhenTheCarIsTooFarFromWhereItParked() {
        Trip.armSeed(t0, PARK_LAT, PARK_LON);
        // Half a minute and two and a half kilometres: not a car pulling out of a
        // parking space, so the parking spot is not where this drive began.
        assertTrue(fixNorthOfPark(t0 + 30_000, 2500));

        assertEquals(t0 + 30_000, Trip.startedAt);
        assertEquals(1, Trip.points);
    }

    @Test
    public void everyMinuteOfWaitingAllowsAnotherTwoKilometres() {
        Trip.armSeed(t0, PARK_LAT, PARK_LON);
        // Just over a minute, so the allowance is four kilometres, not two.
        assertTrue(fixNorthOfPark(t0 + 61_000, 3900));
        assertEquals(t0, Trip.startedAt);

        Trip.forget();
        store = new FakeStore();
        Trip.armSeed(t0, PARK_LAT, PARK_LON);
        assertTrue(fixNorthOfPark(t0 + 61_000, 4100));
        assertEquals("beyond the allowance, this is a new start", t0 + 61_000, Trip.startedAt);
    }

    @Test
    public void theLastMomentOfTheWindowStillCounts() {
        Trip.armSeed(t0, PARK_LAT, PARK_LON);
        assertTrue(fixNorthOfPark(t0 + Trip.SEED_WINDOW_MS, 5900)); // three minutes, six allowed
        assertEquals(t0, Trip.startedAt);
    }

    @Test
    public void theSeededLegIsCountedEvenThoughItIsLongerThanAGpsJump() {
        Trip.armSeed(t0, PARK_LAT, PARK_LON);
        // Longer than MAX_STEP_M, which would normally be dismissed as a jump. Both
        // ends are known positions here, so the leg was driven and it counts.
        assertTrue(fixNorthOfPark(t0 + 120_000, 2500));
        assertTrue("the leg is longer than a step may be", 2500 > Trip.MAX_STEP_M);
        assertEquals(2500, Trip.meters, 5);
    }

    @Test
    public void theParkingSpotIsOfferedToOneDriveOnly() {
        Trip.armSeed(t0, PARK_LAT, PARK_LON);
        fixNorthOfPark(t0 + 30_000, 1000);
        long first = Trip.currentId();

        // A long pause ends that drive; the next one begins on its own evidence,
        // even though it starts near the same spot and soon after recording began.
        long later = t0 + 30_000 + Trip.GAP_MS + 60_000;
        assertTrue(fixNorthOfPark(later, 1200));

        assertNotEquals(first, Trip.currentId());
        assertEquals(later, Trip.startedAt);
        assertEquals(1, Trip.points);
    }

    @Test
    public void theParkingSpotIsNotUsedWhileADriveIsAlreadyOpen() {
        fix(t0, PARK_LAT, PARK_LON); // a drive in progress
        long id = Trip.currentId();
        Trip.armSeed(t0 + 1000, 50.0, 20.0); // as a restarted service would offer

        assertTrue(fixNorthOfPark(t0 + 2000, 100));
        assertEquals("the open drive was replaced", id, Trip.currentId());
        assertEquals(t0, Trip.startedAt);
    }

    @Test
    public void forgettingTheDriveForgetsTheParkingSpot() {
        Trip.armSeed(t0, PARK_LAT, PARK_LON);
        Trip.forget();
        assertTrue(fixNorthOfPark(t0 + 30_000, 1000));
        assertEquals(t0 + 30_000, Trip.startedAt);
        assertEquals(1, Trip.points);
    }

    @Test
    public void aFixFromBeforeRecordingStartedIsNotSeeded() {
        // A receiver handing over a stale fix it had cached: older than the moment
        // recording began, so it says nothing about where this drive starts.
        assertFalse(Trip.seedApplies(t0, t0 - 1000, PARK_LAT, PARK_LON, PARK_LAT, PARK_LON));
    }
}
