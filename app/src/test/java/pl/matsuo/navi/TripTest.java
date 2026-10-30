package pl.matsuo.navi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The recording rulebook: when a new trip starts, which fixes count, what the figures add up to and
 * how a restarted process picks the drive up again.
 */
public class TripTest {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private File dir;
    private long t0;

    @Before
    public void setUp() throws IOException {
        dir = folder.newFolder("trips");
        t0 = 1_700_000_000_000L; // a fixed instant, so names are stable
        Trip.forget();
    }

    private boolean fix(long at, double lat, double lon) {
        return Trip.record(dir, at, lat, lon, Trip.NO_ACCURACY);
    }

    // ───────────────────────────── starting a trip ─────────────────────────────

    @Test
    public void theFirstFixOpensATripFile() {
        assertTrue(fix(t0, 52.2297, 21.0122));
        assertEquals(1, Trip.list(dir).length);
        assertEquals(1, Trip.points);
        assertEquals(t0, Trip.startedAt);
        assertEquals(0, Trip.meters, 0.001);
        assertEquals(52.2297, Trip.lastLat, 1e-6);
        assertEquals(21.0122, Trip.lastLon, 1e-6);
    }

    @Test
    public void theFileIsNamedAfterTheStartOfTheTrip() {
        fix(t0, 52.2297, 21.0122);
        String name = Trip.list(dir)[0].getName();
        assertTrue(name, name.startsWith("trip-") && name.endsWith(".csv"));
    }

    @Test
    public void aPauseLongerThanFiveMinutesStartsANewTrip() {
        fix(t0, 52.2297, 21.0122);
        fix(t0 + Trip.GAP_MS + 1000, 52.4000, 21.0122);
        assertEquals(2, Trip.list(dir).length);
        assertEquals(1, Trip.points); // the figures restart with the new trip
        assertEquals(0, Trip.meters, 0.001);
    }

    @Test
    public void aShorterPauseKeepsTheSameTrip() {
        fix(t0, 52.2297, 21.0122);
        fix(t0 + Trip.GAP_MS - 1000, 52.2397, 21.0122);
        assertEquals(1, Trip.list(dir).length);
        assertEquals(2, Trip.points);
    }

    // ──────────────────────────── filtering fixes ────────────────────────────

    @Test
    public void anImpreciseFixIsDropped() {
        assertFalse(Trip.record(dir, t0, 52.2297, 21.0122, Trip.MAX_ACCURACY_M + 1));
        assertEquals(0, Trip.list(dir).length);
        assertEquals(0, Trip.points);
    }

    @Test
    public void aFixAtTheAccuracyLimitIsKept() {
        assertTrue(Trip.record(dir, t0, 52.2297, 21.0122, Trip.MAX_ACCURACY_M));
        assertEquals(1, Trip.points);
    }

    @Test
    public void standingStillStoresNothingButKeepsTheTripAlive() {
        fix(t0, 52.2297, 21.0122);
        // Two metres away: below the minimum step.
        assertFalse(fix(t0 + 10_000, 52.22972, 21.01220));
        assertEquals(1, Trip.points);
        assertEquals(0, Trip.meters, 0.001);

        // The trip is still open ten minutes later because the clock kept moving.
        fix(t0 + 10_000 + Trip.GAP_MS - 1000, 52.2397, 21.0122);
        assertEquals(1, Trip.list(dir).length);
        assertEquals(2, Trip.points);
    }

    @Test
    public void aGpsJumpIsRecordedButNotCounted() {
        fix(t0, 52.2297, 21.0122);
        fix(t0 + 10_000, 54.3520, 18.6466); // 296 km in ten seconds
        assertEquals(2, Trip.points);
        assertEquals(0, Trip.meters, 0.001);
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

    // ────────────────────────────── reading back ──────────────────────────────

    @Test
    public void whatIsRecordedIsWhatIsReadBack() {
        fix(t0, 52.2297, 21.0122);
        fix(t0 + 10_000, 52.2397, 21.0222);
        List<double[]> pts = Trip.read(Trip.list(dir)[0]);
        assertEquals(2, pts.size());
        assertEquals(52.2297, pts.get(0)[0], 1e-6);
        assertEquals(21.0222, pts.get(1)[1], 1e-6);
    }

    @Test
    public void readingAMissingFileGivesAnEmptyRoute() {
        assertTrue(Trip.read(new File(dir, "nope.csv")).isEmpty());
        assertTrue(Trip.read(null).isEmpty());
    }

    @Test
    public void damagedLinesAreSkippedAndTheRestOfTheTripSurvives() throws IOException {
        File f = new File(dir, "trip-20231114-220000.csv");
        try (FileWriter w = new FileWriter(f)) {
            w.write("1700000000,52.229700,21.012200\n");
            w.write("garbage\n");
            w.write("1700000010,oops,21.012200\n");
            w.write("1700000020,52.239700,21.012200\n");
        }
        List<double[]> pts = Trip.read(f);
        assertEquals(2, pts.size());

        Trip.Summary s = Trip.summarize(f);
        assertEquals(2, s.points);
        assertEquals(1_700_000_000_000L, s.startedAt);
        assertEquals(1_700_000_020_000L, s.endedAt);
        assertEquals(Geo.distance(52.2297, 21.0122, 52.2397, 21.0122), s.meters, 1.0);
    }

    @Test
    public void summaryOfAnEmptyFileHasNoPoints() throws IOException {
        File f = folder.newFile("empty.csv");
        assertEquals(0, Trip.summarize(f).points);
    }

    @Test
    public void tripsAreListedNewestFirst() {
        fix(t0, 52.2297, 21.0122);
        fix(t0 + 2 * Trip.GAP_MS, 52.3297, 21.0122);
        File[] files = Trip.list(dir);
        assertEquals(2, files.length);
        assertTrue(files[0].getName().compareTo(files[1].getName()) > 0);
    }

    @Test
    public void onlyCsvFilesCountAsTrips() throws IOException {
        fix(t0, 52.2297, 21.0122);
        assertTrue(new File(dir, "notes.txt").createNewFile());
        assertEquals(1, Trip.list(dir).length);
    }

    @Test
    public void anEmptyFolderHasNoTrips() throws IOException {
        assertEquals(0, Trip.list(folder.newFolder("fresh")).length);
    }

    // ───────────────────── resuming after a process restart ─────────────────────

    @Test
    public void aRecentTripIsPickedUpAgainAfterARestart() {
        fix(t0, 52.2297, 21.0122);
        fix(t0 + 10_000, 52.2397, 21.0122);
        File before = Trip.currentFile();
        double metresBefore = Trip.meters;

        Trip.forget(); // as if the service had been killed
        Trip.resume(dir, t0 + 60_000);

        assertEquals(before, Trip.currentFile());
        assertEquals(2, Trip.points);
        assertEquals(metresBefore, Trip.meters, 1.0);
        assertEquals(t0, Trip.startedAt);
    }

    @Test
    public void fixesAfterAResumeAppendToTheSameFile() {
        fix(t0, 52.2297, 21.0122);
        fix(t0 + 10_000, 52.2397, 21.0122);
        File before = Trip.currentFile();

        Trip.forget();
        Trip.resume(dir, t0 + 60_000);
        fix(t0 + 70_000, 52.2497, 21.0122);

        assertEquals(1, Trip.list(dir).length);
        assertEquals(before, Trip.currentFile());
        assertEquals(3, Trip.points);
        assertEquals(3, Trip.read(before).size());
    }

    @Test
    public void anOldTripIsNotResumed() {
        fix(t0, 52.2297, 21.0122);
        File before = Trip.currentFile();

        Trip.forget();
        Trip.resume(dir, t0 + Trip.GAP_MS + 1000);
        assertEquals(null, Trip.currentFile());

        fix(t0 + Trip.GAP_MS + 2000, 52.2397, 21.0122);
        assertNotEquals(before, Trip.currentFile());
        assertEquals(2, Trip.list(dir).length);
    }

    @Test
    public void resumingWithNothingStoredIsHarmless() {
        Trip.resume(dir, t0);
        assertEquals(null, Trip.currentFile());
        assertEquals(0, Trip.points);
    }

    @Test
    public void resumingPastAnEmptyNewestFileIsHarmless() throws IOException {
        assertTrue(new File(dir, "trip-29991231-235959.csv").createNewFile());
        Trip.resume(dir, t0);
        assertEquals(null, Trip.currentFile());
    }

    // ───────────────────────────── the demo history ─────────────────────────────

    @Test
    public void theFixturesWriteThreeTripsWithTheLongOneNewest() throws IOException {
        TripFixtures.writeAll(dir, t0);
        File[] files = Trip.list(dir);
        assertEquals(3, files.length);

        Trip.Summary newest = Trip.summarize(files[0]);
        Trip.Summary middle = Trip.summarize(files[1]);
        Trip.Summary oldest = Trip.summarize(files[2]);
        assertTrue(newest.meters > middle.meters);
        assertTrue(newest.meters > oldest.meters);
        assertTrue(newest.startedAt > middle.startedAt);
        assertTrue(middle.startedAt > oldest.startedAt);
    }

    @Test
    public void theWarsawGdanskFixtureIsARealisticDrive() throws IOException {
        TripFixtures.writeAll(dir, t0);
        Trip.Summary s = Trip.summarize(Trip.list(dir)[0]);

        // 320 km and 3 h 50, an average of 83 km/h.
        assertEquals(320_000, s.meters, 10_000);
        long hours = (s.endedAt - s.startedAt) / 3_600_000L;
        assertEquals(3, hours, 1);
        double kmh = s.meters / 1000.0 / ((s.endedAt - s.startedAt) / 3_600_000.0);
        assertTrue("average " + kmh + " km/h", kmh > 75 && kmh < 95);
        assertTrue("fixes " + s.points, s.points > 1000);
    }

    @Test
    public void theWarsawGdanskFixtureStartsInWarsawAndEndsInGdansk() throws IOException {
        TripFixtures.writeAll(dir, t0);
        List<double[]> pts = Trip.read(Trip.list(dir)[0]);
        assertEquals(52.2297, pts.get(0)[0], 0.001);
        assertEquals(21.0122, pts.get(0)[1], 0.001);
        assertEquals(54.3520, pts.get(pts.size() - 1)[0], 0.001);
        assertEquals(18.6466, pts.get(pts.size() - 1)[1], 0.001);
    }

    @Test
    public void theFixtureKeepsTenSecondsBetweenFixes() {
        List<double[]> fixes = TripFixtures.expand(TripFixtures.WARSAW_GDANSK, t0);
        for (int i = 1; i < fixes.size(); i++) {
            assertEquals(TripFixtures.STEP_MS, (long) (fixes.get(i)[0] - fixes.get(i - 1)[0]));
        }
    }

    @Test
    public void everyFixtureStepIsBelowTheGpsJumpLimit() {
        // Otherwise the app would record the shape but refuse to count the
        // distance, and the demo history would read as zero kilometres.
        List<double[]> fixes = TripFixtures.expand(TripFixtures.WARSAW_GDANSK, t0);
        for (int i = 1; i < fixes.size(); i++) {
            float step =
                    Geo.distance(
                            fixes.get(i - 1)[1],
                            fixes.get(i - 1)[2],
                            fixes.get(i)[1],
                            fixes.get(i)[2]);
            assertTrue("step " + step + " m", step > Trip.MIN_STEP_M && step < Trip.MAX_STEP_M);
        }
    }

    @Test
    public void theShortFixturesAreTownDrives() throws IOException {
        TripFixtures.writeAll(dir, t0);
        File[] files = Trip.list(dir);
        for (int i = 1; i < 3; i++) {
            Trip.Summary s = Trip.summarize(files[i]);
            assertTrue("length " + s.meters, s.meters > 1_000 && s.meters < 20_000);
            long minutes = (s.endedAt - s.startedAt) / 60_000L;
            assertTrue("duration " + minutes + " min", minutes >= 2 && minutes <= 45);
        }
    }

    @Test
    public void theNewestFixtureTripIsRecentEnoughToBeResumedAsTheCurrentDrive()
            throws IOException {
        TripFixtures.writeAll(dir, t0);
        Trip.resume(dir, t0);
        assertEquals(Trip.list(dir)[0], Trip.currentFile());
        assertTrue(Trip.meters > 300_000);
    }
}
