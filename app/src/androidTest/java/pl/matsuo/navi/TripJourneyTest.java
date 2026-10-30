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
        // The newest demo trip ended two minutes ago, so the service picks it up
        // as the drive in progress instead of starting a fresh one.
        Trip.resume(context);
        open("trip");

        assertEquals("the stored drive was not resumed", Trip.list(context)[0], Trip.currentFile());
        assertTrue(
                "the resumed distance is not on the screen: " + Format.distance(Trip.meters),
                hasTextContaining(Format.distance(Trip.meters)));
        assertTrue(
                "the resumed drive is not about 320 km: " + Trip.meters,
                Trip.meters > 310_000 && Trip.meters < 330_000);
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
    public void theTripFilesAreOneCsvPerDrive() throws Exception {
        installTripHistory();
        File dir = new File(context.getExternalFilesDir(null), "trips");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".csv"));
        assertEquals(3, files == null ? 0 : files.length);
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
