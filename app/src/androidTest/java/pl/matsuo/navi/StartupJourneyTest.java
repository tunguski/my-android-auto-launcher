package pl.matsuo.navi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.Until;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Journey: the car has been parked for a week, the unit cold-boots, and the driver pulls away
 * immediately.
 *
 * <p>Two complaints from the car come together here. The ROM shows its own screen first, and the
 * GPS engine does not start until the launcher does - so the opening minutes of the drive were
 * being lost. Coming up at boot is what addresses both.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class StartupJourneyTest extends LauncherTest {

    private SharedPreferences prefs;

    @Before
    public void startFresh() throws Exception {
        resetState();
        prefs = context.getSharedPreferences(MainActivity.PREFS, 0);
    }

    /**
     * Hands the receiver the broadcast a boot sends.
     *
     * <p>Called rather than broadcast: BOOT_COMPLETED is protected, so adb cannot send it, and what
     * is worth testing is this app's reaction to it rather than Android's delivery.
     */
    private void pretendTheUnitJustBooted() {
        new BootReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));
    }

    // ───────────────────────────── coming up at boot ─────────────────────────────

    @Test
    public void afterABootTheBlackScreenIsWhatTheDriverSees() throws Exception {
        shell("am start -a android.settings.SETTINGS"); // as the ROM would
        device.waitForIdle();

        pretendTheUnitJustBooted();

        assertTrue(
                "the launcher did not claim the screen after the boot",
                device.wait(Until.hasObject(By.pkg(PKG)), TIMEOUT_MS));
        assertVisible(R.string.tile_nav);
    }

    @Test
    public void comingUpAtBootAlsoStartsTheRecording() throws Exception {
        pretendTheUnitJustBooted();
        device.wait(Until.hasObject(By.pkg(PKG)), TIMEOUT_MS);

        // The whole point: the receiver started the launcher, and the launcher
        // started the service, so the receiver is already looking for satellites
        // before the car moves.
        String services = shell("dumpsys activity services " + PKG);
        assertTrue("recording did not start with the launcher", services.contains("TripService"));
    }

    @Test
    public void aDriverWhoTurnsItOffIsLeftAlone() throws Exception {
        prefs.edit().putBoolean(BootReceiver.PREF_BOOT_HOME, false).apply();
        shell("am start -a android.settings.SETTINGS");
        assertTrue(
                "another app was supposed to be in front for this test",
                device.wait(Until.hasObject(By.pkg("com.android.settings")), TIMEOUT_MS));

        pretendTheUnitJustBooted();
        device.waitForIdle();

        assertTrue(
                "the launcher should have stayed out of the way, but took the screen",
                device.wait(Until.gone(By.pkg(PKG)), 3000)
                        || !PKG.equals(device.getCurrentPackageName()));
    }

    @Test
    public void aBroadcastWithNoActionIsIgnored() {
        // Some ROMs send odd things; none of them may crash the receiver.
        new BootReceiver().onReceive(context, new Intent());
        new BootReceiver().onReceive(context, null);
    }

    // ───────────────────────────── the recording rate ─────────────────────────────

    @Test
    public void theRecordingRateIsOneSecondUnlessChanged() throws Exception {
        assertEquals(1000, TripService.intervalMillis(prefs));
    }

    @Test
    public void theRateCanBeChangedFromTheSettings() throws Exception {
        open("look");
        scrollTo(R.string.record_section);
        tapAfterScroll(R.string.record_seconds, 3);

        assertEquals(3000, TripService.intervalMillis(prefs));

        // And it is still in force after coming back to the screen.
        open("look");
        assertEquals(3000, TripService.intervalMillis(prefs));
    }

    @Test
    public void allThreeRatesAreOffered() throws Exception {
        open("look");
        for (int seconds : TripService.INTERVALS_S) {
            scrollToText(str(R.string.record_seconds, seconds));
        }
    }

    @Test
    public void aNonsenseStoredRateFallsBackToTheDefault() {
        prefs.edit().putInt(TripService.PREF_INTERVAL, 7).apply();
        assertEquals(1000, TripService.intervalMillis(prefs));
        prefs.edit().putInt(TripService.PREF_INTERVAL, -5).apply();
        assertEquals(1000, TripService.intervalMillis(prefs));
    }

    @Test
    public void theBootSwitchCanBeTurnedOffAndOnAgain() throws Exception {
        open("look");
        String on = str(R.string.record_boot_home, str(R.string.look_on));
        String off = str(R.string.record_boot_home, str(R.string.look_off));

        // The label is waited for, not the stored setting. The switch writes the
        // setting and then rebuilds the panel, so "off" on screen means the
        // handler has run - and the setting is then there to be read rather
        // than hoped for.
        tapUntilLabelChanges(on, off);
        assertEquals(false, prefs.getBoolean(BootReceiver.PREF_BOOT_HOME, true));

        open("look");
        tapUntilLabelChanges(off, on);
        assertEquals(true, prefs.getBoolean(BootReceiver.PREF_BOOT_HOME, false));
    }

    // ───────────────────────────── waiting for the sky ─────────────────────────────

    @Test
    public void beforeTheFirstFixTheScreenSaysItIsWaitingForOne() throws Exception {
        // With recording off for this run: the notice is what the screen says while
        // no drive is open, and a live receiver on the emulator would end that state
        // mid-assertion - which is a race in the test, not a fault in the screen.
        open("trip", MainActivity.EXTRA_NO_RECORDING, "1");
        // Not "no route recorded yet": nothing is wrong, the receiver is still
        // looking, and a driver staring at an empty screen deserves to know which.
        assertVisible(R.string.stats_waiting_gps);
    }

    @Test
    public void onceSomethingIsRecordedTheWaitingNoticeGoesAway() throws Exception {
        installTripHistory();
        open("trip");
        assertGone(R.string.stats_waiting_gps);
    }
}
