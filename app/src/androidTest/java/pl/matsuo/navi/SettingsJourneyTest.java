package pl.matsuo.navi;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import java.io.File;
import java.io.FileOutputStream;
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
    public void followingTheSunIsOfferedAndIsOnToBeginWith() throws Exception {
        // On by default, so a unit nobody configures still dims itself at dusk.
        // Started from a cleared setting rather than from whatever the previous
        // test left, because the default is the thing being asserted.
        context.getSharedPreferences(MainActivity.PREFS, 0)
                .edit()
                .remove(MainActivity.PREF_AUTO_NIGHT)
                .commit();
        open(null);
        open("look");

        String on = str(R.string.look_autonight, str(R.string.look_on));
        String off = str(R.string.look_autonight, str(R.string.look_off));
        assertNotNull("following the sun is not on by default", scrollToText(on));
        tapUntilLabelChanges(on, off);
        tapUntilLabelChanges(off, on);
    }

    @Test
    public void aReadingWithoutSunTimesLeavesTheScreenAlone() throws Exception {
        // The canned readings carry no sunrise or sunset - they were written to
        // pin a weather screen, not a sky - so this is also every screen test's
        // own situation. If the automatic switch guessed instead of standing
        // still, it would dim the screen out from under all of them, and the
        // failures would land anywhere but here.
        SharedPreferences prefs = context.getSharedPreferences(MainActivity.PREFS, 0);
        prefs.edit()
                .putBoolean(MainActivity.PREF_AUTO_NIGHT, true)
                .putBoolean("dim", false)
                .remove(MainActivity.PREF_DIM_BY_HAND)
                .putLong(MainActivity.PREF_WEATHER_AT, System.currentTimeMillis())
                .commit();

        open("home", MainActivity.EXTRA_WEATHER, "all");
        open("look");
        assertVisible(R.string.look_title);

        assertFalse(
                "a reading with no sun times dimmed the screen anyway",
                prefs.getBoolean("dim", false));
        assertNotNull(
                "the night dimming no longer says it is off",
                scrollToText(str(R.string.look_night, str(R.string.look_off))));
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
        if (settledOnAPicture()) {
            assertTrue("a picture is named but nothing is drawn", somethingIsDrawn());
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
        //
        // The cache is filled here rather than fetched, because what is being
        // tested is how views are put on screen and taken off again, which has
        // nothing to do with where the pictures came from. Asking the network
        // four times over would only add a way for this to fail that has
        // nothing to do with what it is checking.
        seedPictures(Pics.LEGO);

        open("look");
        for (int i = 0; i < 3; i++) {
            tapForAPicture(i);
            assertTrue("the seeded cache did not draw", settledOnAPicture());
            device.click(device.getDisplayWidth() / 2, device.getDisplayHeight() / 2);
            assertVisible(R.string.look_title);
        }
        tapForAPicture(3);
        assertTrue("the seeded cache did not draw", settledOnAPicture());

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

    /**
     * Puts a full set of pictures in the cache, so that showing one asks nothing of the network.
     *
     * <p>{@link Pics#WANTED} of them exactly: a cache holding fewer is topped up behind whatever is
     * on screen, and a test that did not want the network should not quietly start one.
     */
    private void seedPictures(String set) throws Exception {
        File dir = new File(context.getCacheDir(), Pics.cacheDir(set));
        // Emptied first. The cache outlives a test - nothing in the reset
        // between them touches it - so a test before this one may have left ten
        // fetched pictures here, and seeding on top of those would leave twenty,
        // only half of them this test's own.
        deleteTree(dir);
        assertTrue("could not make " + dir, dir.mkdirs() || dir.isDirectory());
        StringBuilder credits = new StringBuilder();
        for (int i = 0; i < Pics.WANTED; i++) {
            String name = Pics.fileName(i);
            Bitmap shot = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
            // Not one flat colour: somethingIsDrawn() counts colours, and a
            // picture this test seeded must look like a picture to it.
            shot.eraseColor(0xFF102030 + i * 0x0A0A0A);
            for (int x = 0; x < 320; x++) {
                shot.setPixel(x, 90, 0xFFFFFFFF);
            }
            try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
                shot.compress(Bitmap.CompressFormat.JPEG, 90, out);
            }
            shot.recycle();
            credits.append(Pics.creditLine(name, new Pics.Shot("", "Photographer " + i, "a test")))
                    .append('\n');
        }
        try (FileOutputStream out = new FileOutputStream(new File(dir, Pics.CREDITS))) {
            out.write(credits.toString().getBytes("UTF-8"));
        }
    }

    /**
     * How long the picture screen may stay on "loading" before this counts as broken.
     *
     * <p>Deliberately far longer than {@link #TIMEOUT_MS}, which is the wait for a view to appear
     * and the wrong unit for this. A first tap on an empty cache asks an image service a question
     * and then downloads ten photographs before any of them is shown: about six seconds on a fast
     * desk connection, and an unknown multiple of that on a build machine running an emulator in
     * software. Ten seconds was the wait before, which is to say the test was timing the network
     * and calling the result a bug.
     */
    private static final long PICTURE_MS = 90_000L;

    /**
     * Waits for the picture screen to settle, and says whether a photograph is what it settled on.
     *
     * <p>Both endings are legitimate and which one happens is not the test's business: a machine
     * with no way out to the network says so in words, and a machine with one draws a photograph
     * and names the photographer. Only a screen still saying "loading" when the time is up is a
     * failure, and it fails here rather than further down, where it would be reported as whatever
     * assertion happened to be next.
     */
    private boolean settledOnAPicture() throws Exception {
        String none = str(R.string.pics_none);
        long deadline = System.currentTimeMillis() + PICTURE_MS;
        while (System.currentTimeMillis() < deadline) {
            if (device.hasObject(By.textContains(" / "))) {
                return true;
            }
            if (device.hasObject(By.text(none))) {
                return false;
            }
            Thread.sleep(250);
        }
        fail(
                "the picture screen was still loading after "
                        + PICTURE_MS / 1000
                        + "s; what was on screen: "
                        + whatIsOnScreen());
        return false;
    }

    /**
     * Asks the settings for a picture, and does not go on until they have given way to one.
     *
     * <p>The panel is built again every time a picture is closed, and scrolling to a button does
     * not wait for that: a tap can land where the button was a moment ago and be swallowed. Left
     * alone, that shows up much later as a wait for a photograph timing out - which blames the
     * pictures for a tap that was never taken. Checked here instead, straight after the tap, and
     * tried again rather than failing on the first swallowed one.
     */
    private void tapForAPicture(int round) {
        for (int attempt = 0; attempt < 3; attempt++) {
            scrollTo(R.string.pics_lego).click();
            if (device.wait(Until.gone(By.text(str(R.string.look_title))), TIMEOUT_MS)) {
                return;
            }
        }
        fail("round " + round + ": the settings would not give way to a picture");
    }

    /**
     * Every piece of text on screen, for a failure message that explains itself.
     *
     * <p>A test that times out waiting for something says only that it waited. Which of the ways it
     * could have got there actually happened - the picture never arrived, or the tap that was meant
     * to ask for one never landed and the settings are still up - is the whole question, and a
     * screen read at the moment of failure answers it without a second run.
     */

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
