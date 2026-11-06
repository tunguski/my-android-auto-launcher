package pl.matsuo.navi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiObject2;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Journey: the driver turns the ignition on and uses the black screen. */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class HomeJourneyTest extends LauncherTest {

    @Before
    public void startFresh() throws Exception {
        resetState();
        openHome(0);
    }

    @Test
    public void theHomeScreenShowsTheClockAndFiveTiles() {
        assertNotNull("no clock on the home screen", clock());
        assertVisible(R.string.tile_nav);
        assertVisible(R.string.tile_music);
        assertVisible(R.string.tile_trip);
        assertVisible(R.string.tile_weather);
        assertVisible(R.string.tile_apps);
    }

    @Test
    public void thereIsNoSettingsTileAnyMore() {
        // Settings moved to the app list; the home row is for driving.
        assertFalse(
                "a Settings tile is back on the home screen",
                hasTextContaining("Settings") || hasTextContaining("Ustawienia"));
    }

    @Test
    public void theClockAndTheDateAreCentred() {
        List<UiObject2> texts = allText();
        assertFalse(texts.isEmpty());
        int screenWidth = device.getDisplayWidth();
        UiObject2 clock = clock();
        // The clock column sits in the left half, centred within its own column.
        assertTrue(
                "the clock is not centred in its column",
                Math.abs(clock.getVisibleBounds().centerX() - screenWidth / 4) < screenWidth / 8);
    }

    @Test
    public void theTripTileOpensTheTripScreen() throws Exception {
        tapText(R.string.tile_trip);
        assertVisible(R.string.stats_title);
        assertVisible(R.string.stats_distance);
    }

    @Test
    public void theWeatherTileOpensTheWeatherScreen() throws Exception {
        tapText(R.string.tile_weather);
        assertVisible(R.string.weather_title);
    }

    @Test
    public void theAllAppsTileOpensTheAppList() throws Exception {
        tapText(R.string.tile_apps);
        assertVisible(R.string.apps_title);
        assertVisible(R.string.look_title);
    }

    @Test
    public void backAlwaysReturnsToTheBlackScreen() throws Exception {
        tapText(R.string.tile_weather);
        assertVisible(R.string.weather_title);
        device.pressBack();
        assertVisible(R.string.tile_nav);

        tapText(R.string.tile_apps);
        assertVisible(R.string.apps_title);
        device.pressBack();
        assertVisible(R.string.tile_nav);

        tapText(R.string.tile_trip);
        device.pressBack();
        assertVisible(R.string.tile_nav);
    }

    @Test
    public void backOnTheHomeScreenNeverLeavesTheLauncher() throws Exception {
        device.pressBack();
        device.waitForIdle();
        assertEquals(PKG, device.getCurrentPackageName());
        assertVisible(R.string.tile_nav);
    }

    @Test
    public void theHomeKeyComesBackToTheBlackScreen() throws Exception {
        tapText(R.string.tile_apps);
        assertVisible(R.string.apps_title);
        device.pressHome();
        device.waitForIdle();
        assertEquals(PKG, device.getCurrentPackageName());
        assertVisible(R.string.tile_nav);
    }

    @Test
    public void holdingTheClockOpensTheLayoutSettings() throws Exception {
        clock().longClick();
        device.waitForIdle();
        assertVisible(R.string.look_title);
    }

    @Test
    public void theAppListPutsTheOriginalHomeScreensFirst() throws Exception {
        tapText(R.string.tile_apps);
        assertVisible(R.string.apps_title);
        // The grid loads an icon for every app on the unit, so this waits for the
        // entry rather than looking once and giving up.
        waitFor(By.textContains(str(R.string.apps_original_home).replace("%1$s", "").trim()));
    }

    // ───────────────────────── swiping back to the black screen ─────────────────────────

    @Test
    public void swipingRightOnTheAppListReturnsToTheBlackScreen() throws Exception {
        tapText(R.string.tile_apps);
        assertVisible(R.string.apps_title);

        swipeRight();
        assertVisible(R.string.tile_nav);
    }

    @Test
    public void swipingRightOnTheWeatherScreenReturnsToTheBlackScreen() throws Exception {
        tapText(R.string.tile_weather);
        assertVisible(R.string.weather_title);

        swipeRight();
        assertVisible(R.string.tile_nav);
    }

    @Test
    public void swipingLeftDoesNotLeaveTheScreen() throws Exception {
        // Only one direction goes back, or every sideways scroll inside a panel
        // would throw the driver out of it.
        tapText(R.string.tile_apps);
        assertVisible(R.string.apps_title);

        int y = device.getDisplayHeight() / 2;
        device.swipe(device.getDisplayWidth() * 9 / 10, y, device.getDisplayWidth() / 4, y, 10);
        device.waitForIdle();

        assertVisible(R.string.apps_title);
    }
}
