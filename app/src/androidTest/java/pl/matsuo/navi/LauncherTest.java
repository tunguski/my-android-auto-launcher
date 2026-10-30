package pl.matsuo.navi;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.Direction;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import org.junit.Before;

/**
 * Base class for the on-device tests.
 *
 * <p>The launcher is driven through UiAutomator rather than Espresso on purpose: it is a home
 * screen, so half of what has to be proved — that the HOME key comes back to the black screen, that
 * the unit boots into it — happens outside the activity and is invisible to an in-process test
 * runner.
 *
 * <p>Each test states the screen it wants with the debug-only intent extras of {@link
 * MainActivity}, so no test depends on the weather outside, on a GPS fix or on tapping its way
 * through three screens first.
 */
public abstract class LauncherTest {

    static final String PKG = "pl.matsuo.navi";
    static final String ACTIVITY = PKG + "/.MainActivity";
    static final long TIMEOUT_MS = 10_000L;

    UiDevice device;
    Context context;

    @Before
    public void prepare() throws Exception {
        device = UiDevice.getInstance(getInstrumentation());
        context = getInstrumentation().getTargetContext();
        // An idle emulator locks its screen, and then the launcher is behind the
        // keyguard and every selector finds com.android.systemui instead.
        device.wakeUp();
        shell("wm dismiss-keyguard");
        // A notification shade left open by an earlier test covers everything,
        // and then every selector finds com.android.systemui instead.
        shell("cmd statusbar collapse");
        // Android shows a one-time "Viewing full screen" card the first time an
        // app hides the system bars. It would land in the middle of a screenshot.
        shell("settings put secure immersive_mode_confirmations confirmed");
        shell("cmd package set-home-activity " + ACTIVITY);
        copyWeatherFixtures();
    }

    // ───────────────────────────── device plumbing ─────────────────────────────

    String shell(String command) throws IOException {
        ParcelFileDescriptor fd =
                getInstrumentation().getUiAutomation().executeShellCommand(command);
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n));
            }
            return sb.toString();
        }
    }

    /**
     * Clears every stored setting and trip, so each test starts from scratch.
     *
     * <p>Not with {@code pm clear}: the instrumentation is loaded into the process of the app under
     * test, so clearing that package kills the test run itself. The settings and the trip folder
     * are reachable from here directly.
     */
    void resetState() throws Exception {
        context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
        Trip.forget();
        TripDb.resetForTests();
        context.deleteDatabase(TripDb.NAME);
        // The trips folder holds the imported-CSV folder inside it now, so the
        // whole tree goes rather than just its top-level entries.
        deleteTree(new File(context.getExternalFilesDir(null), "trips"));
        deleteTree(new File(context.getExternalFilesDir(null), FolderBackup.EXPORT));
        shell("pm grant " + PKG + " android.permission.ACCESS_FINE_LOCATION");
        shell("pm grant " + PKG + " android.permission.ACCESS_COARSE_LOCATION");
        // Without this the foreground service still runs, but its notification
        // never appears in the shade, so the tests that look for it find nothing.
        shell("pm grant " + PKG + " android.permission.POST_NOTIFICATIONS");
        shell("cmd package set-home-activity " + ACTIVITY);
        copyWeatherFixtures();
    }

    static void deleteContents(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            assertTrue("cannot delete " + f, f.delete());
        }
    }

    /**
     * Puts the canned Open-Meteo readings where the debug hook looks for them. They travel with the
     * test APK as assets; see the androidTest assets source folder in the build file, which points
     * at {@code tools/wx}.
     */
    void copyWeatherFixtures() throws IOException {
        File dir = new File(context.getExternalFilesDir(null), "wx");
        if (!dir.exists() && !dir.mkdirs()) {
            return;
        }
        for (String name : getInstrumentation().getContext().getAssets().list("")) {
            if (!name.endsWith(".json")) {
                continue;
            }
            try (InputStream in = getInstrumentation().getContext().getAssets().open(name);
                    OutputStream out = new FileOutputStream(new File(dir, name))) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
        }
    }

    /**
     * Puts the demo history where the app will find it, the way a real unit would have it.
     *
     * <p>The two town drives arrive as the CSV files earlier versions wrote and are imported, so
     * every journey test also exercises the migration. The long drive is fed through the recorder
     * fix by fix and deliberately left open, which is what a unit switched off mid-drive looks
     * like.
     */
    void installTripHistory() throws Exception {
        File dir = new File(context.getExternalFilesDir(null), "trips");
        deleteContents(dir);
        Trip.forget();
        TripDb db = TripDb.of(context);
        long now = System.currentTimeMillis();

        TripFixtures.write(
                dir, TripFixtures.expand(TripFixtures.SHORT_ERRAND, now - 3 * 86400_000L));
        TripFixtures.write(dir, TripFixtures.expand(TripFixtures.SHORT_COMMUTE, now - 86400_000L));
        Migration.run(dir, db);

        List<double[]> drive = TripFixtures.expand(TripFixtures.WARSAW_GDANSK, 0);
        long start = now - 2 * 60_000L - (long) drive.get(drive.size() - 1)[0];
        for (double[] fix : drive) {
            Trip.record(db, start + (long) fix[0], fix[1], fix[2], Trip.NO_ACCURACY);
        }
    }

    static void deleteTree(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                deleteTree(f);
            }
            f.delete();
        }
        dir.delete();
    }

    // ───────────────────────────── starting a screen ─────────────────────────────

    /**
     * Starts the launcher on a given screen.
     *
     * @param extras alternating key and value, e.g. {@code "navi.weather", "ice"}
     */
    void open(String screen, String... extras) throws Exception {
        StringBuilder cmd =
                new StringBuilder("am start -W -n " + ACTIVITY + " --activity-clear-top");
        if (screen != null) {
            cmd.append(" --es ").append(MainActivity.EXTRA_SCREEN).append(' ').append(screen);
        }
        for (int i = 0; i + 1 < extras.length; i += 2) {
            // No quoting: executeShellCommand does not run a shell, so quotes
            // would end up inside the value. No fixture value contains a space.
            cmd.append(" --es ").append(extras[i]).append(' ').append(extras[i + 1]);
        }
        String out = shell(cmd.toString());
        assertTrue(
                "the launcher did not come up; in front instead: "
                        + device.getCurrentPackageName()
                        + "\n"
                        + cmd
                        + "\n"
                        + out,
                device.wait(Until.hasObject(By.pkg(PKG)), TIMEOUT_MS));
        device.waitForIdle();
    }

    /** Starts the launcher on the home screen with the given size variant. */
    void openHome(int sizeVariant, String... extras) throws Exception {
        shell(
                "am start -W -n "
                        + ACTIVITY
                        + " --ei "
                        + MainActivity.EXTRA_SIZE
                        + " "
                        + sizeVariant
                        + " --es "
                        + MainActivity.EXTRA_SCREEN
                        + " home");
        device.wait(Until.hasObject(By.pkg(PKG).depth(0)), TIMEOUT_MS);
        if (extras.length > 0) {
            open("home", extras);
        }
        device.waitForIdle();
    }

    // ───────────────────────────── assertions ─────────────────────────────

    String str(int res) {
        return context.getString(res);
    }

    /** The same string in Polish, whatever the device language is. */
    String strPl(int res) {
        Configuration cfg = new Configuration(context.getResources().getConfiguration());
        cfg.setLocale(Locale.forLanguageTag("pl"));
        return context.createConfigurationContext(cfg).getString(res);
    }

    UiObject2 waitFor(BySelector selector) {
        UiObject2 o = device.wait(Until.findObject(selector), TIMEOUT_MS);
        assertNotNull("not on screen: " + selector, o);
        return o;
    }

    UiObject2 waitForText(int res) {
        return waitFor(By.text(str(res)));
    }

    /**
     * The big clock on the home screen.
     *
     * <p>Found by what it says, not by its class: a TextClock does not override
     * getAccessibilityClassName, so UiAutomator sees a plain TextView.
     */
    UiObject2 clock() {
        return waitFor(By.text(java.util.regex.Pattern.compile("\\d{1,2}:\\d{2}")));
    }

    void assertVisible(int res) {
        waitForText(res);
    }

    /**
     * Asserts that a string is part of some line on screen.
     *
     * <p>Needed wherever several items share one view: the home screen joins all active warnings
     * into a single line, so an exact text match would only ever find the one-warning case.
     */
    void assertVisiblePart(int res) {
        assertNotNull(
                "no line contains " + str(res),
                device.wait(Until.findObject(By.textContains(str(res))), TIMEOUT_MS));
    }

    /**
     * Scrolls the settings panel until the given text is on screen.
     *
     * <p>UiAutomator only sees what is rendered, so anything below the fold of a ScrollView has to
     * be brought into view before it can be found at all.
     */
    UiObject2 scrollTo(int res) {
        return scrollToText(str(res));
    }

    UiObject2 scrollToText(String text) {
        UiObject2 found = device.findObject(By.text(text));
        if (found != null) {
            return found;
        }
        UiObject2 panel = device.findObject(By.scrollable(true));
        assertNotNull("nothing to scroll while looking for " + text, panel);
        for (int i = 0; i < 6 && found == null; i++) {
            panel.scroll(Direction.DOWN, 0.4f);
            device.waitForIdle();
            found = device.findObject(By.text(text));
        }
        assertNotNull("not reachable by scrolling: " + text, found);
        return found;
    }

    void tapAfterScroll(int res) {
        scrollTo(res).click();
        device.waitForIdle();
    }

    void assertGone(int res) {
        assertTrue(
                str(res) + " should not be on screen",
                device.wait(Until.gone(By.text(str(res))), TIMEOUT_MS));
    }

    void tapText(int res) {
        waitForText(res).click();
        device.waitForIdle();
    }

    /** Every piece of text currently on screen, for the looser assertions. */
    List<UiObject2> allText() {
        return device.findObjects(By.clazz("android.widget.TextView"));
    }

    boolean hasTextContaining(String fragment) {
        return device.findObject(By.textContains(fragment)) != null;
    }

    // ───────────────────────────── screenshots ─────────────────────────────

    /**
     * Saves a screenshot into the private app folder, from where the screenshot script pulls it
     * into {@code docs/previews}.
     *
     * <p>Internal storage, not external: from Android 11 on, adb cannot read {@code
     * /sdcard/Android/data/<package>}, while {@code run-as} can always read the private folder of a
     * debuggable app.
     */
    void screenshot(String name) throws Exception {
        File dir = new File(context.getFilesDir(), "shots");
        if (!dir.exists() && !dir.mkdirs()) {
            return;
        }
        device.waitForIdle();
        Bitmap bmp = getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull("screenshot failed", bmp);
        try (OutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        bmp.recycle();
    }

    /** Copies a file, used when a test wants to keep a trip CSV for inspection. */
    static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from);
                OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }
}
