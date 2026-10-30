package pl.matsuo.navi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiObject2;
import java.io.File;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Journey: the driver checks how long the drive has taken, how far it has gone, how far home is,
 * and looks at the shape of the route — then at an earlier trip.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class TripJourneyTest extends LauncherTest {

    /** Warsaw, the start of the demo drives. */
    private static final String HOME_WARSAW = "52.2297,21.0122,Warszawa";

    @Before
    public void startFresh() throws Exception {
        resetState();
    }

    @Test
    public void withNothingRecordedTheScreenSaysSo() throws Exception {
        open("trip");
        assertVisible(R.string.stats_title);
        assertVisible(R.string.stats_no_route);
        assertVisible(R.string.stats_no_home);
    }

    @Test
    public void theFiguresAreLabelledTimeDistanceAndToHome() throws Exception {
        open("trip");
        assertVisible(R.string.stats_time);
        assertVisible(R.string.stats_distance);
        assertVisible(R.string.stats_to_home);
    }

    @Test
    public void theDriveInProgressIsPickedUpFromDisk() throws Exception {
        installTripHistory();
        // The long demo drive was fed through the recorder and left open, as it
        // would be on a unit switched off mid-drive. Forget it and resume, which
        // is exactly what the service does when it starts again.
        long before = Trip.currentId();
        Trip.forget();
        Trip.resume(TripDb.of(context), System.currentTimeMillis());
        open("trip");

        assertEquals("the stored drive was not resumed", before, Trip.currentId());
        assertTrue(
                "the resumed distance is not on the screen: " + Format.distance(Trip.meters),
                hasTextContaining(Format.distance(Trip.meters)));
        assertTrue(
                "the resumed drive is not about 320 km: " + Trip.meters,
                Trip.meters > 310_000 && Trip.meters < 330_000);
    }

    @Test
    public void theOldCsvFilesAreImportedAndKept() throws Exception {
        installTripHistory();
        // installTripHistory writes the two town drives as the CSV files earlier
        // versions wrote; the migration should have taken them into the database
        // and moved the files aside rather than deleting them.
        File csvDir = Migration.csvDir(context);
        assertEquals("a CSV was left unimported", 0, Migration.pending(csvDir).length);
        assertTrue("the imported files were not kept", Migration.importedBytes(csvDir) > 0);

        open("trip");
        assertVisible(R.string.stats_history);
        assertTrue("an imported drive is missing from the history", hasTextContaining("9.0 km"));
        assertTrue("an imported drive is missing from the history", hasTextContaining("2.8 km"));
    }

    @Test
    public void importingTwiceChangesNothing() throws Exception {
        installTripHistory();
        TripDb db = TripDb.of(context);
        int before = db.count();

        // Put the files back where they were and run it again.
        File csvDir = Migration.csvDir(context);
        File imported = new File(csvDir, Migration.IMPORTED);
        for (File f : imported.listFiles()) {
            assertTrue(f.renameTo(new File(csvDir, f.getName())));
        }
        Migration.Result again = Migration.run(csvDir, db);

        assertEquals("a trip was imported twice", before, db.count());
        assertEquals(0, again.imported);
        assertEquals(2, again.alreadyThere);
    }

    @Test
    public void theHistoryStripListsTheEarlierTrips() throws Exception {
        installTripHistory();
        open("trip");
        assertVisible(R.string.stats_history);
        assertVisible(R.string.stats_current);
        List<UiObject2> chips =
                device.findObjects(By.clazz("android.widget.TextView").textContains("km"));
        assertTrue(
                "expected the current trip plus two earlier ones, found " + chips.size(),
                chips.size() >= 3);
    }

    @Test
    public void anEarlierTripCanBeSelected() throws Exception {
        installTripHistory();
        open("trip");
        assertVisible(R.string.stats_history);
        // The oldest chip is the short errand; selecting it must not disturb the
        // figures of the drive in progress.
        List<UiObject2> chips =
                device.findObjects(By.clazz("android.widget.TextView").textContains("km"));
        chips.get(chips.size() - 1).click();
        device.waitForIdle();
        assertVisible(R.string.stats_title);
        assertFalse(
                "the route disappeared after selecting a trip",
                device.findObject(By.text(str(R.string.stats_no_route))) != null);
    }

    @Test
    public void theDistanceHomeIsShownOnceAHomePointIsSet() throws Exception {
        installTripHistory();
        open("trip", MainActivity.EXTRA_HOME_POINT, HOME_WARSAW);
        // The demo drive ends in Gdansk, 283 km from Warsaw as the crow flies.
        assertTrue("no distance to the home point", hasTextContaining("km"));
        assertFalse(
                "the home point was not stored",
                device.findObject(By.text(str(R.string.stats_no_home))) != null);
    }

    @Test
    public void theRecordedRouteIsDrawnAsAShape() throws Exception {
        installTripHistory();
        open("trip");
        // The route view carries no text, so it is identified by its accessibility
        // description and by covering a good part of the screen.
        UiObject2 route = waitFor(By.desc(str(R.string.stats_route_desc)));
        assertTrue(
                "the route area is too small: " + route.getVisibleBounds(),
                route.getVisibleBounds().height() > device.getDisplayHeight() / 4);
    }

    @Test
    public void everyDriveIsOneRowInTheDatabase() throws Exception {
        installTripHistory();
        TripDb db = TripDb.of(context);
        // Two imported town drives plus the one in progress.
        assertEquals(3, db.count());
        assertEquals(2, db.recent(50).size());
        assertEquals("the drive in progress is not history", 2, db.recent(50).size());
    }

    /** The same three drives as one CSV file each: 47,744 bytes of data in 4 KiB blocks. */
    private static final long AS_CSV_ON_DISK = 53_248;

    @Test
    public void theDatabaseIsSmallerThanTheFilesItReplaced() throws Exception {
        installTripHistory();
        Trip.closeCurrent(TripDb.of(context));

        // The write-ahead log counts: space hidden there is space used.
        File file = context.getDatabasePath(TripDb.NAME);
        File wal = new File(file.getParentFile(), TripDb.NAME + "-wal");
        long used = file.length() + (wal.exists() ? wal.length() : 0);

        assertTrue("the database is empty", file.length() > 0);
        assertTrue(
                "storage grew: " + used + " bytes against " + AS_CSV_ON_DISK + " as CSV files",
                used < AS_CSV_ON_DISK);
    }

    @Test
    public void finishingADriveGivesBackTheSpaceItsWorkingRowsUsed() throws Exception {
        installTripHistory(); // leaves the long drive open, thousands of working rows
        TripDb db = TripDb.of(context);
        File file = context.getDatabasePath(TripDb.NAME);
        File wal = new File(file.getParentFile(), TripDb.NAME + "-wal");
        long whileDriving = file.length() + (wal.exists() ? wal.length() : 0);

        Trip.closeCurrent(db);

        long afterwards = file.length() + (wal.exists() ? wal.length() : 0);
        assertTrue(
                "closing a drive should release its working rows: "
                        + whileDriving
                        + " -> "
                        + afterwards,
                afterwards < whileDriving);
    }

    @Test
    public void backFromTheTripScreenReturnsToTheBlackScreen() throws Exception {
        open("trip");
        device.pressBack();
        assertVisible(R.string.tile_nav);
    }

    @Test
    public void theTripScreenSurvivesBeingOpenedTwice() throws Exception {
        installTripHistory();
        open("trip");
        open("trip");
        assertVisible(R.string.stats_distance);
    }
}
