package pl.matsuo.navi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiObject2;
import java.util.regex.Pattern;
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
    public void theBrightnessSliderCanBeMoved() throws Exception {
        open("look");
        Rect bar = waitFor(By.clazz("android.widget.SeekBar")).getVisibleBounds();
        String before = percentBeside(bar);
        // Dragged by hand from the thumb, which sits at the right-hand end while
        // the brightness is full. UiAutomator's own scroll gesture keeps to the
        // middle of the view and leaves a slider at either extreme untouched, so
        // the old form of this test moved nothing and could not have noticed.
        int y = bar.centerY();
        device.swipe(bar.right - bar.width() / 20, y, bar.centerX(), y, 40);
        device.waitForIdle();
        // Dimming must never black the screen out completely: the labels stay.
        assertVisible(R.string.look_brightness);
        // And the slider still works. Dragging it is a sideways drag, which is
        // also how a panel is left, so the swipe back has to leave this one
        // child alone - and the two ways of getting that wrong look opposite:
        // the panel closes under the finger, or the thumb no longer moves.
        assertNotEquals("the brightness slider did not move", before, percentBeside(bar));
    }

    /**
     * The percentage shown next to the slider.
     *
     * <p>Picked by where it is, because it is not the only percentage on the screen: the four
     * layout sizes are offered as 100%, 80%, 67% and 50%, and they come first. Taking the first one
     * instead - which this test did - meant reading a button that never changes, so the assertion
     * held whatever the slider did.
     */
    private String percentBeside(Rect bar) {
        for (UiObject2 o : device.findObjects(By.text(Pattern.compile("\\d+%")))) {
            if (Rect.intersects(o.getVisibleBounds(), bar)
                    || (o.getVisibleBounds().centerY() > bar.top
                            && o.getVisibleBounds().centerY() < bar.bottom)) {
                return o.getText();
            }
        }
        throw new AssertionError("no percentage beside the slider at " + bar);
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
}
