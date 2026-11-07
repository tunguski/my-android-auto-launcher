package pl.matsuo.navi;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Rect;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import java.util.List;
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
        // The build, in the corner, with no label on it.
        assertTrue(
                "the panel does not say which version this is",
                hasTextContaining("v " + Updater.installedVersion(context)));
        scrollTo(R.string.look_language);
        scrollTo(R.string.home_section);
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

    /** The stored size variant, once it has been written, or whatever is there after the wait. */
    private int storedSize() throws Exception {
        for (long waited = 0; waited < TIMEOUT_MS; waited += 100) {
            int stored = context.getSharedPreferences(MainActivity.PREFS, 0).getInt("size", -1);
            if (stored != -1) {
                return stored;
            }
            Thread.sleep(100);
        }
        return context.getSharedPreferences(MainActivity.PREFS, 0).getInt("size", -1);
    }

    @Test
    public void theSizeChoiceIsStoredAndSurvivesLeavingTheScreen() throws Exception {
        open("look");
        tapText(R.string.look_clock_size); // the label, to make sure the panel is up
        waitFor(By.text(MainActivity.SIZE_NAMES[2])).click();
        device.waitForIdle();

        // Waited for, not asked once: the tap is handled on the main thread and
        // the preference is written there, so a single look can land first.
        assertEquals("the choice was not stored", 2, storedSize());

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
    public void everyBrightnessStepIsOffered() throws Exception {
        open("look");
        for (int level : MainActivity.BRIGHTNESS) {
            assertNotNull("no " + level + "% brightness button", scrollToText(level + "%"));
        }
    }

    @Test
    public void choosingABrightnessStoresItAndLeavesTheScreenReadable() throws Exception {
        open("look");
        // 40%, because it is one of the three percentages that belong to the
        // brightness row alone: the four layout sizes above it are 100%, 80%,
        // 67% and 50%, so asking for either of those by text is ambiguous.
        scrollToText("40%").click();
        device.waitForIdle();
        assertEquals("the brightness was not stored", 40, storedBrightness());
        // Dimming must never black the screen out completely: the labels stay.
        assertVisible(R.string.look_brightness);
    }

    /** The stored brightness, once the tap has been handled on the main thread. */
    private int storedBrightness() throws Exception {
        for (long waited = 0; waited < TIMEOUT_MS; waited += 100) {
            int stored = context.getSharedPreferences(MainActivity.PREFS, 0).getInt("bright", -1);
            if (stored == 40) {
                return stored;
            }
            Thread.sleep(100);
        }
        return context.getSharedPreferences(MainActivity.PREFS, 0).getInt("bright", -1);
    }

    @Test
    public void theLanguageCanBeSwitchedToPolishAndBack() throws Exception {
        open("look");
        // The button carries the current language, so it says "System" here.
        tapAfterScroll(R.string.lang_system);
        waitFor(By.text(str(R.string.lang_pl))).click();
        device.waitForIdle();
        assertNotNull(
                "the settings panel is not in Polish", scrollToText(strPl(R.string.look_language)));

        device.pressBack();
        assertNotNull(
                "the home screen is not in Polish", waitFor(By.text(strPl(R.string.tile_nav))));

        open("look");
        tapAfterScroll(R.string.lang_pl); // and now the button says "Polski"
        waitFor(By.text(str(R.string.lang_en))).click(); // "English" in either language
        device.waitForIdle();
        scrollTo(R.string.look_language);
        device.pressBack();
        assertVisible(R.string.tile_nav);
    }

    @Test
    public void theLanguageListCarriesEachNameInItsOwnLanguage() throws Exception {
        // They were rendering as empty boxes once, which is why this is asserted.
        // The list behind the button is also what replaced three buttons in a
        // row: the third of them was squeezed until "Polski" ran down the screen
        // one letter per line, which is a bug the bounds below would now catch.
        open("look");
        tapAfterScroll(R.string.lang_system);
        for (int res : new int[] {R.string.lang_system, R.string.lang_en, R.string.lang_pl}) {
            // Every match, not the first: the button that opened the list
            // carries a language name too, and asking for one would let the
            // button stand in for the row that is meant to be checked.
            assertNotNull(waitFor(By.text(str(res))));
            List<UiObject2> all = device.findObjects(By.text(str(res)));
            for (UiObject2 b : all) {
                Rect at = b.getVisibleBounds();
                assertTrue(
                        str(res) + " renders as " + at + ", taller than it is wide",
                        at.width() > at.height() && at.height() > 10);
            }
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
        // That the section is on the panel at all is asserted where every other
        // setting is; this is only about the point it names. Two columns of
        // groups now, so it sits below the fold and has to be scrolled to.
        open("look", MainActivity.EXTRA_HOME_POINT, "52.2297,21.0122,Warszawa");
        scrollToText("Warszawa");
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

    @Test
    public void swipingRightOnTheSettingsPanelReturnsToTheBlackScreen() throws Exception {
        open("look");
        assertVisible(R.string.look_title);

        // Slowly on purpose, and the harder of the two cases: the panel scrolls
        // vertically and is full of children that claim the touch stream, and a
        // swipe made at a driver's pace must still reach the launcher. The brisk
        // version of the gesture is covered on the app list.
        swipeRightSlowly();
        assertVisible(R.string.tile_nav);
    }

    @Test
    public void bothPictureButtonsAreOffered() throws Exception {
        open("look");
        // Scrolled to the buttons, not to the heading above them: the heading
        // can sit on the last line of the panel with its card still below the
        // fold, and a settings panel is taller than the screen by design.
        assertNotNull(scrollTo(R.string.pics_lego));
        assertNotNull(scrollTo(R.string.pics_kitty));
    }

    @Test
    public void aPictureTakesTheWholeScreenAndATouchBringsTheSettingsBack() throws Exception {
        open("look");
        scrollTo(R.string.pics_lego).click();
        device.waitForIdle();

        // Nothing of the settings is left on screen: the picture is the screen.
        assertTrue(
                "the settings panel is still showing behind the picture",
                device.wait(Until.gone(By.text(str(R.string.look_title))), TIMEOUT_MS));

        // One of the two honest outcomes, and the test says which. Nothing
        // travels inside the APK any more: the first tap fills a cache from the
        // network, so a device with no way out says so, and a device with one
        // draws a photograph and names whoever took it. What must never happen
        // is a screen that is neither.
        boolean saidItHasNone =
                device.wait(Until.hasObject(By.text(str(R.string.pics_none))), TIMEOUT_MS);
        if (!saidItHasNone) {
            assertTrue("neither a picture nor a word about why not", somethingIsDrawn());
            assertTrue(
                    "a picture arrived with nobody named beside it",
                    device.wait(Until.hasObject(By.textContains(" / ")), TIMEOUT_MS));
        }

        device.click(device.getDisplayWidth() / 2, device.getDisplayHeight() / 2);
        assertVisible(R.string.look_title);
    }

    @Test
    public void lookingAtPicturesDoesNotLeaveThemStackedUpBehind() throws Exception {
        // Touching a picture goes back to the settings, and the settings used to
        // be laid on top of the picture rather than in place of it: three
        // pictures looked at in a row left three full-screen views in the tree,
        // three bitmaps held, and three photographers' names in the corner, of
        // which the one being read was the oldest and so the wrong one.
        open("look");
        for (int i = 0; i < 3; i++) {
            scrollTo(R.string.pics_lego).click();
            device.waitForIdle();
            device.click(device.getDisplayWidth() / 2, device.getDisplayHeight() / 2);
            assertVisible(R.string.look_title);
        }
        scrollTo(R.string.pics_lego).click();
        device.waitForIdle();

        // Whatever this device managed to fetch, there is at most one of it.
        assertTrue(
                "a picture screen was left behind under the settings",
                device.findObjects(By.textContains(" / ")).size() <= 1);
    }

    @Test
    public void theUnsplashKeyCanBeTypedInAndTakenBackOut() throws Exception {
        // There is nowhere else a key could come from: it is typed into this
        // unit or there is none. The button says whether there is one, and
        // never what it is.
        //
        // Started from a known state rather than from whatever the last run
        // left: this test is the only thing that writes that setting, and a
        // failure half way through it would otherwise make the next run fail
        // for a different reason and hide the first one.
        context.getSharedPreferences(MainActivity.PREFS, 0)
                .edit()
                .remove(MainActivity.PREF_UNSPLASH)
                .commit();

        // Brought up first and asked for the panel second. A cold start reads
        // the screen extra before the launcher has a screen to put it on and
        // settles on the black one; every other test here is saved from that
        // only by whatever ran before it.
        open(null);
        open("look");
        assertVisible(R.string.look_title);
        scrollTo(R.string.pics_key_unset).click();
        device.waitForIdle();

        UiObject2 field = waitFor(By.clazz("android.widget.EditText"));
        assertNotNull("no field to type a key into", field);
        field.setText("not-a-real-key");
        device.findObject(By.text("OK")).click();
        device.waitForIdle();
        assertVisible(R.string.look_title);

        assertNotNull("the settings still say there is no key", scrollTo(R.string.pics_key_set));
        assertEquals(
                "what was typed is not what was kept",
                "not-a-real-key",
                context.getSharedPreferences(MainActivity.PREFS, 0)
                        .getString(MainActivity.PREF_UNSPLASH, ""));

        // And out again: an empty field means Openverse, not a broken key.
        scrollTo(R.string.pics_key_set).click();
        device.waitForIdle();
        field = waitFor(By.clazz("android.widget.EditText"));
        assertNotNull(field);
        field.setText("");
        device.findObject(By.text("OK")).click();
        device.waitForIdle();
        assertVisible(R.string.look_title);

        assertNotNull(
                "clearing the field did not clear the key", scrollTo(R.string.pics_key_unset));
        assertEquals(
                "",
                context.getSharedPreferences(MainActivity.PREFS, 0)
                        .getString(MainActivity.PREF_UNSPLASH, ""));
    }

    /** Whether the screen holds more than one colour, which a drawn photograph always does. */
    private boolean somethingIsDrawn() {
        Bitmap shot = getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull("screenshot failed", shot);
        int first = shot.getPixel(0, 0);
        for (int x = 0; x < shot.getWidth(); x += 16) {
            for (int y = 0; y < shot.getHeight(); y += 16) {
                if (shot.getPixel(x, y) != first) {
                    return true;
                }
            }
        }
        return false;
    }
}
