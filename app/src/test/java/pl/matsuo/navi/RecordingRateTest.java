package pl.matsuo.navi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Recording at a fix a second rather than a fix every ten.
 *
 * <p>Two things change at that rate and both are checked here: a standing car gets ten times as
 * many chances to wander on a noisy receiver, and the route is followed far more closely, which
 * costs storage that has to stay in proportion.
 */
public class RecordingRateTest {

    private FakeStore store;
    private long t0;

    @Before
    public void setUp() {
        store = new FakeStore();
        t0 = 1_700_000_000_000L;
        Trip.forget();
    }

    private boolean fix(long at, double lat, double lon, float speed) {
        return Trip.record(store, at, lat, lon, Trip.NO_ACCURACY, speed);
    }

    // ───────────────────────────── a standing car ─────────────────────────────

    @Test
    public void aFixThatReportsNoMovementIsNotStored() {
        fix(t0, 52.0, 21.0, 12f); // moving, opens the drive
        assertFalse("stopped at a light", fix(t0 + 1000, 52.0005, 21.0, 0f));
        assertEquals(1, Trip.points);
    }

    @Test
    public void aStandingCarCannotWanderDownTheRoad() {
        // The worst case at a fix a second: a receiver that jumps far enough each
        // time to pass the distance rule, while the car has not moved at all.
        fix(t0, 52.0, 21.0, 10f);
        double drift = 0;
        for (int i = 1; i <= 600; i++) {
            drift += 0.0001; // about 11 m a second of pure noise
            fix(t0 + i * 1000L, 52.0 + drift, 21.0, 0.0f);
        }
        assertEquals("nothing should have been stored", 1, Trip.points);
        assertEquals(0, Trip.meters, 0.001);
    }

    @Test
    public void theSpeedGateIsJustBelowWalkingPace() {
        fix(t0, 52.0, 21.0, 10f);
        assertFalse(fix(t0 + 1000, 52.001, 21.0, Trip.MIN_SPEED_MS - 0.01f));
        assertTrue(fix(t0 + 2000, 52.002, 21.0, Trip.MIN_SPEED_MS));
    }

    @Test
    public void standingStillStillKeepsTheDriveAlive() {
        fix(t0, 52.0, 21.0, 10f);
        long id = Trip.currentId();
        // Ten minutes at a red light would be a long light, but the clock has to
        // keep the drive open across it.
        for (int i = 1; i <= 240; i++) {
            fix(t0 + i * 1000L, 52.0, 21.0, 0f);
        }
        fix(t0 + 241_000, 52.001, 21.0, 10f);
        assertEquals("the drive was split by a stop", id, Trip.currentId());
        assertEquals(1, store.tripCount());
    }

    @Test
    public void aFixWithoutASpeedIsJudgedOnDistanceAlone() {
        // Not every receiver reports speed; the old rule has to still apply.
        fix(t0, 52.0, 21.0, Trip.NO_SPEED);
        assertFalse("two metres is not movement", fix(t0 + 1000, 52.00002, 21.0, Trip.NO_SPEED));
        assertTrue(fix(t0 + 2000, 52.001, 21.0, Trip.NO_SPEED));
    }

    @Test
    public void aStationaryFirstFixStillOpensTheDrive() {
        // Switching on while parked must not leave the launcher with no trip at
        // all; the gate only applies once something is open.
        assertTrue(fix(t0, 52.0, 21.0, 0f));
        assertEquals(1, Trip.points);
    }

    // ───────────────────────────── the rate itself ─────────────────────────────

    @Test
    public void theDefaultIsOneSecondAndOnlyTheOfferedRatesAreAccepted() {
        assertEquals(1, TripService.INTERVALS_S[0]);
        assertEquals(3, TripService.INTERVALS_S[1]);
        assertEquals(10, TripService.INTERVALS_S[2]);
    }

    @Test
    public void closelyFollowedCornersCostWhatTheyShould() {
        // A minute of motorway at a fix a second: every fix is far enough apart
        // to be kept, so this is the worst case for storage.
        long[] t = new long[60];
        int[] lat = new int[60];
        int[] lon = new int[60];
        for (int i = 0; i < 60; i++) {
            t[i] = t0 / 1000 + i;
            lat[i] = Track.micro(52.0 + i * 0.00025); // about 28 m a second
            lon[i] = Track.micro(21.0);
        }
        byte[] packed = Track.encode(t, lat, lon, 60);
        // Five bytes a fix: an hour of this is about 18 KB, a far cry from the
        // 111 KB the one-line-per-fix CSV would have cost at this rate.
        assertEquals(Track.HEADER + 5 * 59, packed.length);
        assertEquals(60, Track.decode(packed).n);
    }

    @Test
    public void aFixASecondFollowsTheRouteMoreCloselyThanOneEveryTen() {
        // The same drive sampled at both rates: the dense one has to be the
        // longer polyline, because it is the one that does not cut the corners.
        List<double[]> drive = TripFixtures.expand(TripFixtures.SHORT_ERRAND, t0);
        double dense = 0;
        double sparse = 0;
        for (int i = 1; i < drive.size(); i++) {
            dense +=
                    Geo.distance(
                            drive.get(i - 1)[1], drive.get(i - 1)[2],
                            drive.get(i)[1], drive.get(i)[2]);
        }
        for (int i = 10; i < drive.size(); i += 10) {
            sparse +=
                    Geo.distance(
                            drive.get(i - 10)[1], drive.get(i - 10)[2],
                            drive.get(i)[1], drive.get(i)[2]);
        }
        assertTrue("dense " + dense + " vs sparse " + sparse, dense >= sparse);
    }
}
