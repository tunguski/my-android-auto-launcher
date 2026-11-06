package pl.matsuo.navi;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.Direction;
import androidx.test.uiautomator.UiObject2;
import java.util.List;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

/**
 * Produces every preview in {@code docs/previews}.
 *
 * <p>These are not mock-ups: each one is a real screen of the real APK on a 1280x720 device, in the
 * state the test puts it into. The screenshot script runs this class and copies the results over
 * the stored previews, so the pictures in the user guide can never drift away from the build.
 */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@LargeTest
public class ScreenshotTest extends LauncherTest {

    @Before
    public void startFresh() throws Exception {
        resetState();
        installTripHistory();
    }

    // ───────────────────────────── the home screen ─────────────────────────────

    @Test
    public void shot01HomeFullSize() throws Exception {
        open("home", MainActivity.EXTRA_WEATHER, "calm");
        screenshot("home");
    }

    @Test
    public void shot02HomeSizeVariants() throws Exception {
        for (int i = 0; i < MainActivity.SIZE_NAMES.length; i++) {
            openHome(i, MainActivity.EXTRA_WEATHER, "calm");
            screenshot("home_size_" + MainActivity.SIZE_NAMES[i].replace("%", ""));
        }
    }

    @Test
    public void shot03HomeWarnings() throws Exception {
        for (String reading :
                new String[] {"ice", "icerain", "storm", "wind", "fog", "snow", "all"}) {
            open("home", MainActivity.EXTRA_WEATHER, reading);
            screenshot("home_warn_" + reading);
        }
    }

    @Test
    public void shot04HomeNightDimmed() throws Exception {
        open("home", MainActivity.EXTRA_WEATHER, "calm");
        UiObject2 clock = clock();
        clock.click(); // a tap on the clock dims text and icons
        device.waitForIdle();
        screenshot("home_night");
        clock.click();
    }

    @Test
    public void shot04bHomeAfterDark() throws Exception {
        // The only reading in tools/wx taken after sunset, and so the only one
        // that draws the moon. Every other fixture is daytime, which is how a
        // broken crescent shipped through 2.4 without once being looked at.
        open("home", MainActivity.EXTRA_WEATHER, "night");
        screenshot("home_night_sky");
    }

    @Test
    public void shot05HomeInPolish() throws Exception {
        open("look");
        chooseLanguage(R.string.lang_system, R.string.lang_pl);
        open("home", MainActivity.EXTRA_WEATHER, "ice");
        screenshot("home_polish");
        open("look");
        chooseLanguage(R.string.lang_pl, R.string.lang_en);
    }

    /**
     * Switches the language through the list behind the language button.
     *
     * <p>The button carries the language in use, so opening the list means tapping whatever it says
     * now - which is why the current one has to be named as well as the wanted one.
     */
    private void chooseLanguage(int currentLabel, int wanted) throws Exception {
        tapAfterScroll(currentLabel);
        waitFor(By.text(str(wanted))).click();
        device.waitForIdle();
    }

    // ───────────────────────────── the other panels ─────────────────────────────

    @Test
    public void shot06Trip() throws Exception {
        // As on the head unit when the ignition goes off and on again mid-drive:
        // the service picks the trip up from disk and keeps counting.
        open("trip", MainActivity.EXTRA_HOME_POINT, "52.2297,21.0122,Warszawa");
        screenshot("trip");
    }

    @Test
    public void shot06bTripEarlierOne() throws Exception {
        open("trip", MainActivity.EXTRA_HOME_POINT, "52.2297,21.0122,Warszawa");
        // The oldest chip: the short errand, to show what a town drive looks like.
        List<UiObject2> chips =
                device.findObjects(By.clazz("android.widget.TextView").textContains("km"));
        chips.get(chips.size() - 1).click();
        device.waitForIdle();
        screenshot("trip_earlier");
    }

    @Test
    public void shot06cTripHistory() throws Exception {
        open("trip", MainActivity.EXTRA_HOME_POINT, "52.2297,21.0122,Warszawa");
        tapText(R.string.hist_title);
        screenshot("trip_history");
    }

    @Test
    public void shot07TripWithoutHistory() throws Exception {
        resetState();
        open("trip");
        screenshot("trip_empty");
    }

    @Test
    public void shot08Weather() throws Exception {
        open("weather", MainActivity.EXTRA_WEATHER, "calm");
        screenshot("weather");
    }

    @Test
    public void shot09WeatherWithWarnings() throws Exception {
        open("weather", MainActivity.EXTRA_WEATHER, "snow");
        screenshot("weather_warning");
    }

    @Test
    public void shot10Apps() throws Exception {
        open("apps");
        screenshot("apps");
    }

    @Test
    public void shot11Settings() throws Exception {
        open("look", MainActivity.EXTRA_HOME_POINT, "52.2297,21.0122,Warszawa");
        screenshot("settings_top");
        UiObject2 panel = device.findObject(By.scrollable(true));
        if (panel == null) {
            return;
        }
        // Three shots, because the panel has outgrown two: every section the guide
        // describes has to appear in one of them, and the last one has to be the
        // actual end of the panel rather than wherever one scroll happens to land.
        panel.scroll(Direction.DOWN, 1.0f);
        device.waitForIdle();
        screenshot("settings_middle");

        while (panel.scroll(Direction.DOWN, 1.0f)) {
            device.waitForIdle();
        }
        device.waitForIdle();
        screenshot("settings_bottom");
    }
}
