package pl.matsuo.navi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.Test;

/** The rules behind the two picture buttons: what is asked for, what comes back, and which next. */
public class PicsTest {

    /** One answer from the Unsplash random endpoint, shaped as it really arrives. */
    private static final String ONE_SHOT =
            "[{\"id\":\"abc123\",\"width\":4000,\"height\":2250,"
                    + "\"urls\":{\"raw\":\"https://images.unsplash.com/photo-1?raw\","
                    + "\"regular\":\"https://images.unsplash.com/photo-1?w=1080\"},"
                    + "\"user\":{\"username\":\"someone\",\"name\":\"A Photographer\"}}]";

    @Test
    public void eachSetAsksForItsOwnThing() {
        assertEquals("lego star wars", Pics.query(Pics.LEGO));
        assertEquals("kitten", Pics.query(Pics.KITTY));
    }

    @Test
    public void theBundledPicturesLiveUnderTheirSetsName() {
        assertEquals("pics/lego", Pics.assetDir(Pics.LEGO));
        assertEquals("pics/kitty", Pics.assetDir(Pics.KITTY));
    }

    @Test
    public void theRequestCarriesTheQueryTheKeyAndNoBareSpace() {
        String url = Pics.randomUrl(Pics.LEGO, "secretkey");
        assertTrue(url, url.startsWith("https://api.unsplash.com/photos/random?"));
        assertTrue("the query is escaped", url.contains("lego%20star%20wars"));
        assertFalse("a raw space would make it an invalid URL", url.contains(" "));
        assertTrue("landscape only, for a 1280x720 screen", url.contains("orientation=landscape"));
        assertTrue(url.contains("client_id=secretkey"));
    }

    @Test
    public void theAnswerGivesUpItsAddressAndItsPhotographer() {
        Pics.Shot shot = Pics.fromRandom(ONE_SHOT);
        assertNotNull(shot);
        assertEquals("https://images.unsplash.com/photo-1?w=1080", shot.url);
        assertEquals("A Photographer", shot.who);
    }

    @Test
    public void aPhotographerWithNoNameIsCreditedByHandle() {
        String json = ONE_SHOT.replace("\"name\":\"A Photographer\"", "\"name\":\"\"");
        assertEquals("someone", Pics.fromRandom(json).who);
    }

    @Test
    public void nothingUsableComesBackAsNothing() {
        assertNull("an empty list", Pics.fromRandom("[]"));
        assertNull("not JSON at all", Pics.fromRandom("<html>rate limited</html>"));
        assertNull("no urls", Pics.fromRandom("[{\"id\":\"x\"}]"));
        assertNull("urls but no regular one", Pics.fromRandom("[{\"urls\":{\"raw\":\"r\"}}]"));
        assertNull(
                "an empty address is no address",
                Pics.fromRandom("[{\"urls\":{\"regular\":\"\"}}]"));
    }

    @Test
    public void aPictureWithNoPhotographerStillShows() {
        Pics.Shot shot = Pics.fromRandom("[{\"urls\":{\"regular\":\"https://x/1.jpg\"}}]");
        assertNotNull("the credit is a courtesy, not a condition", shot);
        assertEquals("", shot.who);
    }

    @Test
    public void anEmptySetOffersNothing() {
        assertEquals(-1, Pics.pickOther(0, -1, new Random(1)));
    }

    @Test
    public void aSetOfOneAlwaysOffersThatOne() {
        assertEquals(0, Pics.pickOther(1, 0, new Random(1)));
        assertEquals(0, Pics.pickOther(1, -1, new Random(1)));
    }

    @Test
    public void thePictureJustShownIsNeverTheNextOne() {
        Random r = new Random(7);
        for (int last = 0; last < 20; last++) {
            for (int i = 0; i < 200; i++) {
                assertTrue("the same picture twice running", Pics.pickOther(20, last, r) != last);
            }
        }
    }

    @Test
    public void everyOtherPictureCanTurnUp() {
        Set<Integer> seen = new HashSet<>();
        Random r = new Random(11);
        for (int i = 0; i < 2000; i++) {
            seen.add(Pics.pickOther(20, 5, r));
        }
        assertEquals("nineteen of the twenty are reachable", 19, seen.size());
        assertFalse(seen.contains(5));
    }

    @Test
    public void withNothingShownYetTheFirstPictureIsReachableToo() {
        // The obvious way to write pickOther - count past the last index - skips
        // index 0 for ever when nothing has been shown, because every index
        // counts past -1. Twenty pictures, one of which never appears.
        Set<Integer> seen = new HashSet<>();
        Random r = new Random(3);
        for (int i = 0; i < 2000; i++) {
            seen.add(Pics.pickOther(20, -1, r));
        }
        assertEquals("all twenty are reachable before anything has been shown", 20, seen.size());
    }

    @Test
    public void goingOnlineNeedsAKeyAndASignal() {
        assertTrue(Pics.preferOnline("akey", true));
        assertFalse("no signal", Pics.preferOnline("akey", false));
        assertFalse("no key: a build from the public source", Pics.preferOnline("", true));
        assertFalse("a key of spaces is no key", Pics.preferOnline("   ", true));
        assertFalse("no key at all", Pics.preferOnline(null, true));
    }
}
