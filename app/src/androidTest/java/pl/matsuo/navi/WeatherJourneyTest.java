package pl.matsuo.navi;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Journey: the driver glances at the temperature, sees a warning and opens the forecast.
 *
 * <p>Every reading comes from the canned files in {@code tools/wx}, the same ones the warning unit
 * tests use, so these tests never depend on the real weather.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class WeatherJourneyTest extends LauncherTest {

    @Before
    public void startFresh() throws Exception {
        resetState();
    }

    private void home(String reading) throws Exception {
        open("home", MainActivity.EXTRA_WEATHER, reading);
    }

    @Test
    public void theTemperatureIsOnTheHomeScreen() throws Exception {
        home("calm");
        assertTrue("no temperature on the home screen", hasTextContaining("8°"));
    }

    @Test
    public void calmWeatherShowsNoWarningOnTheHomeScreen() throws Exception {
        home("calm");
        assertFalse(hasTextContaining(str(R.string.warn_ice)));
        assertFalse(hasTextContaining(str(R.string.warn_storm)));
    }

    @Test
    public void blackIceIsCalledOutOnTheHomeScreen() throws Exception {
        home("ice");
        assertVisiblePart(R.string.warn_ice);
    }

    @Test
    public void freezingRainWithinThreeHoursIsCalledOutEvenAtSixDegrees() throws Exception {
        home("icerain");
        assertTrue(hasTextContaining("6°"));
        assertVisiblePart(R.string.warn_ice);
    }

    @Test
    public void aStormIsCalledOut() throws Exception {
        home("storm");
        assertVisiblePart(R.string.warn_storm);
    }

    @Test
    public void strongWindIsCalledOut() throws Exception {
        home("wind");
        assertVisiblePart(R.string.warn_wind);
    }

    @Test
    public void denseFogIsCalledOut() throws Exception {
        home("fog");
        assertVisiblePart(R.string.warn_fog);
    }

    @Test
    public void heavySnowIsCalledOutTogetherWithBlackIce() throws Exception {
        home("snow");
        assertVisiblePart(R.string.warn_snow);
        assertVisiblePart(R.string.warn_ice);
    }

    @Test
    public void allWarningsAtOnceStillFitOnTheHomeScreen() throws Exception {
        home("all");
        for (int res :
                new int[] {
                    R.string.warn_ice,
                    R.string.warn_storm,
                    R.string.warn_wind,
                    R.string.warn_fog,
                    R.string.warn_snow
                }) {
            assertVisiblePart(res);
        }
    }

    @Test
    public void theWeatherScreenShowsTheReadingTheForecastAndTheWarning() throws Exception {
        open("weather", MainActivity.EXTRA_WEATHER, "snow");
        assertVisible(R.string.weather_title);
        assertVisible(R.string.weather_today);
        assertTrue("no description", hasTextContaining(str(R.string.wmo_snow)));
        assertTrue("no place name", hasTextContaining("Gdansk"));
        assertTrue(
                "no warning line",
                hasTextContaining(str(R.string.warn_prefix).replace("%1$s", "").trim()));
    }

    @Test
    public void theWeatherScreenShowsTheHourlyStrip() throws Exception {
        open("weather", MainActivity.EXTRA_WEATHER, "calm");
        // The canned reading starts at 07:00, so the strip begins at 08.
        assertTrue("no hourly strip", hasTextContaining("08"));
    }

    @Test
    public void tappingTheTemperatureOpensTheForecast() throws Exception {
        home("calm");
        waitFor(androidx.test.uiautomator.By.textContains("8°")).click();
        device.waitForIdle();
        assertVisible(R.string.weather_title);
    }

    @Test
    public void backFromTheForecastReturnsToTheBlackScreen() throws Exception {
        open("weather", MainActivity.EXTRA_WEATHER, "calm");
        device.pressBack();
        assertVisible(R.string.tile_nav);
    }

    // ───────────────────── a reading that has gone out of date ─────────────────────

    /** The gusty reading, dated far enough back to have gone stale. */
    private void homeStale(String reading) throws Exception {
        open(
                "home",
                MainActivity.EXTRA_WEATHER,
                reading,
                MainActivity.EXTRA_WEATHER_AGE_MIN,
                "240");
    }

    @Test
    public void aStaleReadingShowsNoTemperatureOnTheHomeScreen() throws Exception {
        homeStale("wind");
        assertFalse(
                "a four-hour-old temperature is still on the home screen",
                hasTextContaining("12°"));
    }

    @Test
    public void aStaleReadingShowsNoWarningOnTheHomeScreen() throws Exception {
        // The one that matters most. The gusty reading warns about strong wind,
        // gusting to 85 km/h, and a driver who reads that warning decides something about
        // a road - on evidence four hours out of date, which is worse than an
        // empty space where it was.
        homeStale("wind");
        assertFalse(
                "a four-hour-old gust warning is still on the home screen",
                hasTextContaining(str(R.string.warn_wind)));
    }

    @Test
    public void aStaleReadingStillLetsTheForecastScreenBeReached() throws Exception {
        // Hiding the figures must not take the way through to the screen that
        // explains why they are gone: the panel keeps its dash and its tap.
        homeStale("wind");
        waitFor(androidx.test.uiautomator.By.textContains("--°")).click();
        device.waitForIdle();
        assertVisible(R.string.weather_title);
    }

    @Test
    public void aStaleReadingSaysOnTheForecastScreenThatThereIsNoConnection() throws Exception {
        open(
                "weather",
                MainActivity.EXTRA_WEATHER,
                "wind",
                MainActivity.EXTRA_WEATHER_AGE_MIN,
                "240");
        assertVisible(R.string.weather_offline);
        assertFalse(
                "the stale forecast is shown underneath the notice",
                hasTextContaining(str(R.string.warn_wind)));
    }

    @Test
    public void justUnderThreeHoursIsStillShown() throws Exception {
        // The boundary, and the test that keeps the three above honest: without
        // it they would also pass if pinning a reading were simply broken.
        open("home", MainActivity.EXTRA_WEATHER, "wind", MainActivity.EXTRA_WEATHER_AGE_MIN, "170");
        assertTrue("a reading not yet three hours old was hidden anyway", hasTextContaining("12°"));
    }
}
