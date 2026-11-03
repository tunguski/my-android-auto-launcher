package pl.matsuo.navi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import java.io.File;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Journey: the car is driving, Google Maps is in front and the launcher keeps recording the route.
 *
 * <p>Nothing here fakes the recording logic — that is unit tested. What is proved on the device is
 * the part a JVM cannot show: that the foreground service starts and stays up, that its
 * notification is there, and that a drive is picked up again after the process is killed.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class RecordingJourneyTest extends LauncherTest {

    @Before
    public void startFresh() throws Exception {
        resetState();
    }

    /**
     * Stands in for the process being killed and started again.
     *
     * <p>{@code am force-stop} is not an option: the instrumentation lives in the process of the
     * app under test, so stopping it would end the test run. This drops the live trip exactly as a
     * restart would and then starts the service again, which is the code path that has to pick the
     * drive up from disk.
     */
    private void restartRecording() throws Exception {
        shell("am stopservice " + PKG + "/.TripService");
        Trip.forget();
        Trip.resume(TripDb.of(context), System.currentTimeMillis()); // what onCreate does first
        TripService.start(context);
    }

    @Test
    public void theRecordingServiceIsRunningAfterTheLauncherStarts() throws Exception {
        open(null);
        String services = shell("dumpsys activity services " + PKG);
        assertTrue(
                "the trip service is not running:\n" + services, services.contains("TripService"));
    }

    @Test
    public void theServiceRunsInTheForegroundSoAndroidDoesNotThrottleIt() throws Exception {
        // A background app gets a handful of fixes an hour, which would leave the
        // route in holes whenever the navigation app is in front.
        open(null);
        String services = shell("dumpsys activity services " + PKG);
        assertTrue(
                "the trip service is not a foreground service:\n" + services,
                services.contains("isForeground=true"));
    }

    @Test
    public void theRecordingNotificationIsVisible() throws Exception {
        open(null);
        device.openNotification();
        try {
            assertTrue(
                    "no recording notification",
                    device.wait(
                            androidx.test.uiautomator.Until.hasObject(
                                    androidx.test.uiautomator.By.textContains(
                                            str(R.string.notif_title))),
                            TIMEOUT_MS));
        } finally {
            shell("cmd statusbar collapse");
        }
    }

    @Test
    public void theNotificationTextIsAFormattedDistanceAndDuration() throws Exception {
        // 1.6 crashed exactly here: the format string had lost its placeholders,
        // so String.format threw before the service ever reached the foreground.
        // A notification that reads "<distance> · <duration>" proves it formatted.
        open(null);
        device.openNotification();
        try {
            assertTrue(
                    "the notification text is not a distance and a duration",
                    device.wait(
                            androidx.test.uiautomator.Until.hasObject(
                                    androidx.test.uiautomator.By.text(
                                            java.util.regex.Pattern.compile(
                                                    "\\d+([.,]\\d+)? (m|km) · .+"))),
                            TIMEOUT_MS));
        } finally {
            shell("cmd statusbar collapse");
        }
    }

    @Test
    public void aKilledProcessPicksTheDriveUpAgainInsteadOfSplittingIt() throws Exception {
        installTripHistory();
        File dir = new File(context.getExternalFilesDir(null), "trips");
        int before = dir.listFiles((d, n) -> n.endsWith(".csv")).length;

        restartRecording();
        open("trip");

        int after = dir.listFiles((d, n) -> n.endsWith(".csv")).length;
        assertEquals("a restart must not open a new trip file", before, after);
        assertTrue("the drive in progress was not picked up", hasTextContaining("km"));
    }

    @Test
    public void theLauncherKeepsRunningWhenAnotherAppComesToTheFront() throws Exception {
        open(null);
        shell("am start -a android.settings.SETTINGS");
        device.waitForIdle();
        device.pressHome();
        // The other app takes a moment to go; waiting for the launcher to be
        // back is the assertion, not a race against the window animation.
        assertTrue(
                "HOME did not come back to the launcher",
                device.wait(
                        androidx.test.uiautomator.Until.hasObject(
                                androidx.test.uiautomator.By.pkg(PKG)),
                        TIMEOUT_MS));
        assertVisible(R.string.tile_nav);
        String services = shell("dumpsys activity services " + PKG);
        assertTrue(
                "recording stopped while another app was in front",
                services.contains("TripService"));
    }

    @Test
    public void theLauncherIsTheDefaultHomeScreen() throws Exception {
        // The real complaint from the car: after the ignition went off and on, a
        // stock Android screen came up instead of the launcher.
        String roles = shell("cmd role get-role-holders android.app.role.HOME");
        assertTrue("the launcher does not hold the HOME role: " + roles, roles.contains(PKG));
        device.pressHome();
        // Waiting for it, rather than asking once: whatever was in front takes a
        // moment to go, and the window animation is not what is being tested.
        assertTrue(
                "HOME did not come back to the launcher",
                device.wait(
                        androidx.test.uiautomator.Until.hasObject(
                                androidx.test.uiautomator.By.pkg(PKG)),
                        TIMEOUT_MS));
        assertVisible(R.string.tile_nav);
    }
}
