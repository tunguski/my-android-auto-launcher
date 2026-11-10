package pl.matsuo.navi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import java.io.File;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Journey: the driver checks how long the drive has taken, how far it has gone, how far home is,
 * and looks at the shape of the route — then at an earlier trip.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class TripJourneyTest extends LauncherTest {

    /** Warsaw, the start of the demo drives. */
    private static final String HOME_WARSAW = "52.2297,21.0122,Warszawa";

    @Before
    public void startFresh() throws Exception {
        resetState();
    }

    @Test
    public void withNothingRecordedTheScreenSaysSo() throws Exception {
        open("trip");
        assertVisible(R.string.stats_title);
        assertVisible(R.string.stats_no_route);
        assertVisible(R.string.stats_no_home);
    }

    @Test
    public void theFiguresAreLabelledTimeDistanceAndToHome() throws Exception {
        open("trip");
        assertVisible(R.string.stats_time);
        assertVisible(R.string.stats_distance);
        assertVisible(R.string.stats_to_home);
    }

    @Test
    public void theDriveInProgressIsPickedUpFromDisk() throws Exception {
        installTripHistory();
        // The long demo drive was fed through the recorder and left open, as it
        // would be on a unit switched off mid-drive. Forget it and resume, which
        // is exactly what the service does when it starts again.
        long before = Trip.currentId();
        Trip.forget();
        Trip.resume(TripDb.of(context), System.currentTimeMillis());

        // What resume left behind, read before anything else can touch it: the
        // recorder keeps its drive in static fields, and the service that comes
        // up with the screen below listens to a real location provider. A fix
        // arriving mid-assertion would otherwise be read as resume's own work.
        long resumedId = Trip.currentId();
        double resumedMeters = Trip.meters;
        open("trip");

        assertEquals("the stored drive was not resumed", before, resumedId);
        assertTrue(
                "the resumed distance is not on the screen: " + Format.distance(resumedMeters),
                hasTextContaining(Format.distance(resumedMeters)));
        assertTrue(
                "the resumed drive is not about 320 km: " + resumedMeters,
                resumedMeters > 310_000 && resumedMeters < 330_000);
    }

    @Test
    public void theOldCsvFilesAreImportedAndKept() throws Exception {
        installTripHistory();
        // installTripHistory writes the two town drives as the CSV files earlier
        // versions wrote; the migration should have taken them into the database
        // and moved the files aside rather than deleting them.
        File csvDir = Migration.csvDir(context);
        assertEquals("a CSV was left unimported", 0, Migration.pending(csvDir).length);
        assertTrue("the imported files were not kept", Migration.importedBytes(csvDir) > 0);

        open("trip");
        assertVisible(R.string.stats_history);
        assertTrue("an imported drive is missing from the history", hasTextContaining("9.0 km"));
        assertTrue("an imported drive is missing from the history", hasTextContaining("2.8 km"));
    }

    @Test
    public void importingTwiceChangesNothing() throws Exception {
        installTripHistory();
        TripDb db = TripDb.of(context);
        int before = db.count();

        // Put the files back where they were and run it again.
        File csvDir = Migration.csvDir(context);
        File imported = new File(csvDir, Migration.IMPORTED);
        for (File f : imported.listFiles()) {
            assertTrue(f.renameTo(new File(csvDir, f.getName())));
        }
        Migration.Result again = Migration.run(csvDir, db);

        assertEquals("a trip was imported twice", before, db.count());
        assertEquals(0, again.imported);
        assertEquals(2, again.alreadyThere);
    }

    @Test
    public void theHistoryStripListsTheEarlierTrips() throws Exception {
        installTripHistory();
        open("trip");
        assertVisible(R.string.stats_history);
        assertVisible(R.string.stats_current);
        List<UiObject2> chips =
                device.findObjects(By.clazz("android.widget.TextView").textContains("km"));
        assertTrue(
                "expected the current trip plus two earlier ones, found " + chips.size(),
                chips.size() >= 3);
    }

    @Test
    public void anEarlierTripCanBeSelected() throws Exception {
        installTripHistory();
        open("trip");
        assertVisible(R.string.stats_history);
        // The oldest chip is the short errand; selecting it must not disturb the
        // figures of the drive in progress.
        List<UiObject2> chips =
                device.findObjects(By.clazz("android.widget.TextView").textContains("km"));
        chips.get(chips.size() - 1).click();
        device.waitForIdle();
        assertVisible(R.string.stats_title);
        assertFalse(
                "the route disappeared after selecting a trip",
                device.findObject(By.text(str(R.string.stats_no_route))) != null);
        // Redrawing the strip must not take the History button next to it with
        // it: they share a row at the foot of the screen.
        assertVisible(R.string.hist_title);
    }

    @Test
    public void theDistanceHomeIsShownOnceAHomePointIsSet() throws Exception {
        installTripHistory();
        open("trip", MainActivity.EXTRA_HOME_POINT, HOME_WARSAW);
        // The demo drive ends in Gdansk, 283 km from Warsaw as the crow flies.
        assertTrue("no distance to the home point", hasTextContaining("km"));
        assertFalse(
                "the home point was not stored",
                device.findObject(By.text(str(R.string.stats_no_home))) != null);
    }

    @Test
    public void theRecordedRouteIsDrawnAsAShape() throws Exception {
        installTripHistory();
        open("trip");
        // The route view carries no text, so it is identified by its accessibility
        // description and by covering a good part of the screen.
        UiObject2 route = waitFor(By.desc(str(R.string.stats_route_desc)));
        assertTrue(
                "the route area is too small: " + route.getVisibleBounds(),
                route.getVisibleBounds().height() > device.getDisplayHeight() / 4);
    }

    @Test
    public void everyDriveIsOneRowInTheDatabase() throws Exception {
        installTripHistory();
        TripDb db = TripDb.of(context);
        // Two imported town drives plus the one in progress.
        assertEquals(3, db.count());
        assertEquals(2, db.recent(50).size());
        assertEquals("the drive in progress is not history", 2, db.recent(50).size());
    }

    @Test
    public void theTimeOfDayIsInTheTopRightCorner() throws Exception {
        // Asserted by where it is, not merely that it exists, because the figure
        // for how long the drive has taken is formatted "4:35" once it passes an
        // hour - so a search for a clock face finds two things on this screen
        // and only its position tells them apart. Which is also the request:
        // the corner is the point, a driver glancing down knows where to look.
        installTripHistory();
        open("trip");
        assertVisible(R.string.stats_title);

        UiObject2 corner = null;
        for (UiObject2 found : device.findObjects(By.text(Pattern.compile("\\d{1,2}:\\d{2}")))) {
            Rect at = found.getVisibleBounds();
            if (at.centerX() > device.getDisplayWidth() / 2
                    && at.top < device.getDisplayHeight() / 5) {
                corner = found;
            }
        }
        assertTrue(
                "nothing that looks like a clock is in the top right of the trip screen",
                corner != null);
        assertTrue(
                "the clock is not against the right edge: " + corner.getVisibleBounds(),
                corner.getVisibleBounds().right > device.getDisplayWidth() * 0.85);
    }

    @Test
    public void theTripScreenKeepsMovingAfterSomethingElseHasHadTheScreen() throws Exception {
        // Reported from the car: the route drew for five minutes, then stopped
        // while the drive went on being recorded, and going out to the home
        // screen and back in brought it alive again with the whole route on it.
        //
        // Nothing had crashed - an exception on the main thread would have taken
        // the launcher with it. The figures on this screen are redrawn by a
        // ticker that onPause stops and only showStats starts, so one visit from
        // anything that takes the screen left the panel standing still until it
        // was rebuilt by hand.
        installTripHistory();
        open("trip");
        assertVisible(R.string.stats_title);

        shell("am start -a android.settings.SETTINGS");
        assertTrue(
                "another app was supposed to take the screen for this test",
                device.wait(Until.hasObject(By.pkg("com.android.settings")), TIMEOUT_MS));
        device.pressBack();
        assertTrue(
                "the launcher did not come back",
                device.wait(Until.hasObject(By.pkg(PKG)), TIMEOUT_MS));
        assertVisible(R.string.stats_title);

        // The drive goes on. Nothing is tapped and nothing is reopened: the only
        // thing that can put this on screen is the ticker, which is the point.
        Trip.meters = 999_000;
        String expected = Format.distance(Trip.meters);
        assertTrue(
                "the trip screen stopped redrawing after the launcher came back;"
                        + " it still says "
                        + whatIsOnScreen(),
                device.wait(Until.hasObject(By.textContains(expected)), 30_000));
    }

    @Test
    public void backFromTheTripScreenReturnsToTheBlackScreen() throws Exception {
        open("trip");
        device.pressBack();
        assertVisible(R.string.tile_nav);
    }

    @Test
    public void theTripScreenSurvivesBeingOpenedTwice() throws Exception {
        installTripHistory();
        open("trip");
        open("trip");
        assertVisible(R.string.stats_distance);
    }

    // ───────────────────────── three on the strip, the rest in the table ─────────────────────────

    /** Five finished drives, so the strip has more than it is allowed to show. */
    private void installFiveFinishedDrives() {
        TripDb db = TripDb.of(context);
        for (int i = 0; i < 5; i++) {
            long id =
                    db.open(1_700_000_000L + i * 86_400L, Track.micro(52.0 + i), Track.micro(21.0));
            db.append(id, 1_700_000_060L + i * 86_400L, Track.micro(52.1 + i), Track.micro(21.1));
            db.close(id);
        }
    }

    @Test
    public void theStripOffersThreeEarlierTripsAtMost() throws Exception {
        installFiveFinishedDrives();
        open("trip");
        assertVisible(R.string.stats_history);

        // Three stored drives plus the one being recorded, and no more: a strip
        // long enough to scroll is a strip nobody reads at the wheel.
        List<UiObject2> chips =
                device.findObjects(By.clazz("android.widget.TextView").textContains("km"));
        assertTrue(
                "the strip shows more than three earlier trips: " + chips.size(),
                chips.size() <= 4);
    }

    @Test
    public void theHistoryButtonOpensATableOfEveryTrip() throws Exception {
        installFiveFinishedDrives();
        open("trip");
        tapText(R.string.hist_title);

        assertVisible(R.string.hist_date);
        assertVisible(R.string.hist_start);
        assertTrue(
                "the table does not say how many trips there are",
                hasTextContaining(str(R.string.hist_count, 5)));

        // It is a table, so the figures have to stand under their headings. The
        // rows were laid out to the width of their own text once, which left
        // every column somewhere else and the date cut in half.
        UiObject2 startHeading = waitFor(By.text(str(R.string.hist_start)));
        UiObject2 firstStart = waitFor(By.text(Pattern.compile("\\d{2}:\\d{2}")));
        assertTrue(
                "the start column does not sit under its heading: "
                        + startHeading.getVisibleBounds()
                        + " against "
                        + firstStart.getVisibleBounds(),
                Math.abs(startHeading.getVisibleBounds().left - firstStart.getVisibleBounds().left)
                        < 12);
    }

    @Test
    public void theHistoryTableSaysSoWhenNothingWasRecorded() throws Exception {
        open("trip");
        tapText(R.string.hist_title);

        assertVisible(R.string.hist_none);
    }

    @Test
    public void swipingRightOnTheHistoryTableGoesBackToTheTripScreen() throws Exception {
        installFiveFinishedDrives();
        open("trip");
        tapText(R.string.hist_title);
        assertVisible(R.string.hist_date);

        // One step back, not all the way out: the table is only reachable from
        // the trip screen, so that is where back from it means.
        swipeRight();
        assertVisible(R.string.stats_title);
        assertVisible(R.string.stats_distance);

        // The Back arrow on that screen has to land in the same place, or the
        // two ways back from it would mean different things.
        tapText(R.string.hist_title);
        assertVisible(R.string.hist_date);
        tapText(R.string.nav_back);
        assertVisible(R.string.stats_title);
    }

    @Test
    public void swipingRightOnTheTripScreenGoesBackToTheBlackScreen() throws Exception {
        // The one panel the swipe used to skip, because the strip of past drives
        // along its foot scrolls sideways. The strip is four chips wide and the
        // rest of the screen is route and figures, so the gesture belongs to the
        // screen and the strip keeps only what begins inside it.
        installFiveFinishedDrives();
        open("trip");
        assertVisible(R.string.stats_title);

        swipeRightSlowly();
        assertVisible(R.string.tile_nav);
    }

    @Test
    public void draggingTheStripOfPastDrivesDoesNotLeaveTheTripScreen() throws Exception {
        // The other half of the same rule, and the one that is easy to break
        // while fixing the first: a sideways drag that starts on the strip is
        // the strip scrolling, not the screen being left.
        installFiveFinishedDrives();
        open("trip");
        UiObject2 strip = waitFor(By.text(str(R.string.stats_current)));
        Rect at = strip.getVisibleBounds();
        int y = at.centerY();
        device.swipe(at.left + at.width() / 4, y, device.getDisplayWidth() - 20, y, 60);
        device.waitForIdle();
        assertVisible(R.string.stats_title);
    }
}
