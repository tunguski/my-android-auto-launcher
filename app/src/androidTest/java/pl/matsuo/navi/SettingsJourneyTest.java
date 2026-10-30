package pl.matsuo.navi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiObject2;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Journey: the driver adjusts the screen — size, brightness, night dimming, language, the home
 * point — and makes the launcher the default home screen.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class SettingsJourneyTest extends LauncherTest {

    @Before
    public void startFresh() throws Exception {
        resetState();
    }

    @Test
    public void settingsAreReachedThroughTheAppList() throws Exception {
        openHome(0);
        tapText(R.string.tile_apps);
        tapText(R.string.look_title);
        assertVisible(R.string.look_clock_size);
        assertVisible(R.string.look_brightness);
        scrollTo(R.string.look_language);
    }

    @Test
    public void everySettingFitsOnTheScreenOrScrollsIntoView() throws Exception {
        // The panel was taller than 720 px once and the bottom half was simply
        // unreachable; it is a ScrollView now.
        open("look");
        assertVisible(R.string.look_clock_size);
        scrollTo(R.string.look_language);
        scrollTo(R.string.home_default);
        scrollTo(R.string.home_picker);
        scrollTo(R.string.look_hint);
    }

    @Test
    public void allFourSizeVariantsAreOffered() throws Exception {
        open("look");
        for (String name : MainActivity.SIZE_NAMES) {
            assertNotNull("no " + name + " button", waitFor(By.text(name)));
        }
    }

    @Test
    public void choosingASmallerSizeShrinksTheClock() throws Exception {
        openHome(0);
        int fullHeight = clock().getVisibleBounds().height();

        openHome(3);
        int halfHeight = clock().getVisibleBounds().height();

        assertTrue(
                "100% clock " + fullHeight + " px, 50% clock " + halfHeight + " px",
                halfHeight < fullHeight * 0.75);
    }

    @Test
    public void theSizeChoiceIsStoredAndSurvivesLeavingTheScreen() throws Exception {
        open("look");
        tapText(R.string.look_clock_size); // the label, to make sure the panel is up
        waitFor(By.text(MainActivity.SIZE_NAMES[2])).click();
        device.waitForIdle();

        assertEquals(
                "the choice was not stored",
                2,
                context.getSharedPreferences(MainActivity.PREFS, 0).getInt("size", -1));

        device.pressBack();
        int chosen = clock().getVisibleBounds().height();
        open(null); // come back to the home screen again
        int again = clock().getVisibleBounds().height();
        assertEquals(chosen, again, 4);
    }

    @Test
    public void nightDimmingTogglesAndSaysWhichWayItIs() throws Exception {
        open("look");
        String off = str(R.string.look_night, str(R.string.look_off));
        String on = str(R.string.look_night, str(R.string.look_on));
        UiObject2 toggle = device.findObject(By.text(off));
        if (toggle == null) {
            toggle = waitFor(By.text(on));
            toggle.click();
            device.waitForIdle();
            assertNotNull(waitFor(By.text(off)));
        } else {
            toggle.click();
            device.waitForIdle();
            assertNotNull(waitFor(By.text(on)));
        }
    }

    @Test
    public void theBrightnessSliderCanBeMoved() throws Exception {
        open("look");
        UiObject2 slider = waitFor(By.clazz("android.widget.SeekBar"));
        slider.setGestureMargin(slider.getVisibleBounds().width() / 4);
        slider.scroll(androidx.test.uiautomator.Direction.LEFT, 0.5f);
        device.waitForIdle();
        // Dimming must never black the screen out completely: the labels stay.
        assertVisible(R.string.look_brightness);
    }

    @Test
    public void theLanguageCanBeSwitchedToPolishAndBack() throws Exception {
        open("look");
        tapAfterScroll(R.string.lang_pl);
        device.waitForIdle();
        assertNotNull(
                "the settings panel is not in Polish", scrollToText(strPl(R.string.look_language)));

        device.pressBack();
        assertNotNull(
                "the home screen is not in Polish", waitFor(By.text(strPl(R.string.tile_nav))));

        open("look");
        tapAfterScroll(R.string.lang_en); // the button says "English" either way
        device.waitForIdle();
        scrollTo(R.string.look_language);
        device.pressBack();
        assertVisible(R.string.tile_nav);
    }

    @Test
    public void theLanguageButtonsCarryTheirOwnNames() throws Exception {
        // They were rendering as empty boxes once, which is why this is asserted.
        open("look");
        scrollTo(R.string.lang_system);
        scrollTo(R.string.lang_en);
        scrollTo(R.string.lang_pl);
        for (int res : new int[] {R.string.lang_system, R.string.lang_en, R.string.lang_pl}) {
            UiObject2 b = scrollTo(res);
            assertTrue(
                    str(res) + " renders as an empty box",
                    b.getVisibleBounds().width() > 20 && b.getVisibleBounds().height() > 10);
        }
    }

    @Test
    public void theDefaultHomeScreenButtonIsThere() throws Exception {
        // Missing in 1.4, which left the launcher non-default after every ignition.
        open("look");
        tapAfterScroll(R.string.home_default);
        device.waitForIdle();
        // Either the role dialog comes up or a toast says it is already default;
        // both leave the launcher running rather than crashing.
        assertTrue(device.getCurrentPackageName() != null);
    }

    @Test
    public void theHomePointCanBeEnteredAsAnAddress() throws Exception {
        open("look");
        tapAfterScroll(R.string.home_set_address);
        UiObject2 input = waitFor(By.clazz("android.widget.EditText"));
        input.setText("Warszawa, Marszalkowska 1");
        device.findObject(By.text("OK")).click();
        device.waitForIdle();
        // Without a network the geocoder says nothing was found; either way the
        // dialog closes and the panel is still there.
        assertVisible(R.string.look_title);
    }

    @Test
    public void theHomeSectionShowsWhetherAPointIsSet() throws Exception {
        open("look");
        assertTrue(hasTextContaining(str(R.string.home_section)));

        open("look", MainActivity.EXTRA_HOME_POINT, "52.2297,21.0122,Warszawa");
        assertTrue("the stored home point is not shown", hasTextContaining("Warszawa"));
    }

    @Test
    public void backFromTheSettingsReturnsToTheBlackScreen() throws Exception {
        open("look");
        device.pressBack();
        assertVisible(R.string.tile_nav);
    }

    private String str(int res, String arg) {
        return context.getString(res, arg);
    }
}
