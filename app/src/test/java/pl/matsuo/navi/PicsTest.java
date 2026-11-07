package pl.matsuo.navi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
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

    /** One answer from Openverse, shaped as it really arrives. */
    private static final String ONE_RESULT =
            "{\"result_count\":240,\"page_count\":12,\"results\":[{"
                    + "\"id\":\"84f906dc\",\"title\":\"Kitten Animal\","
                    + "\"url\":\"https://cdn.stocksnap.io/one.jpg\","
                    + "\"creator\":\"Ghost Presenter\",\"license\":\"cc0\","
                    + "\"width\":5184,\"height\":3456}]}";

    @Test
    public void eachSetAsksForItsOwnThing() {
        assertEquals("lego star wars", Pics.query(Pics.LEGO));
        assertEquals("kitten", Pics.query(Pics.KITTY));
    }

    @Test
    public void theCachedPicturesLiveUnderTheirSetsName() {
        assertEquals("pics/lego", Pics.cacheDir(Pics.LEGO));
        assertEquals("pics/kitty", Pics.cacheDir(Pics.KITTY));
    }

    // ---- Unsplash ----

    @Test
    public void theUnsplashRequestCarriesTheQueryTheKeyAndNoBareSpace() {
        String url = Pics.randomUrl(Pics.LEGO, "secretkey", 20);
        assertTrue(url, url.startsWith("https://api.unsplash.com/photos/random?"));
        assertTrue("the query is escaped", url.contains("lego%20star%20wars"));
        assertFalse("a raw space would make it an invalid URL", url.contains(" "));
        assertTrue("landscape only, for a 1280x720 screen", url.contains("orientation=landscape"));
        assertTrue("a page, not one picture", url.contains("count=20"));
        assertTrue(url.contains("client_id=secretkey"));
    }

    @Test
    public void aKeyWithSomethingOddInItIsStillSentSafely() {
        // Typed in by hand on a head unit, so it may arrive with a space around
        // it or a character that would otherwise end the query string.
        String url = Pics.randomUrl(Pics.KITTY, "  ab+cd/ef  ", 10);
        assertFalse(url, url.contains(" "));
        assertTrue(url, url.contains("client_id=ab%2Bcd%2Fef"));
    }

    @Test
    public void theUnsplashAnswerGivesUpEveryAddressAndPhotographer() {
        List<Pics.Shot> shots = Pics.fromUnsplash(ONE_SHOT);
        assertEquals(1, shots.size());
        assertEquals("https://images.unsplash.com/photo-1?w=1080", shots.get(0).url);
        assertEquals("A Photographer", shots.get(0).who);
        assertEquals("Unsplash", shots.get(0).source);
    }

    @Test
    public void aPhotographerWithNoNameIsCreditedByHandle() {
        String json = ONE_SHOT.replace("\"name\":\"A Photographer\"", "\"name\":\"\"");
        assertEquals("someone", Pics.fromUnsplash(json).get(0).who);
    }

    @Test
    public void nothingUsableFromUnsplashComesBackAsNothing() {
        assertTrue("an empty list", Pics.fromUnsplash("[]").isEmpty());
        assertTrue("not JSON at all", Pics.fromUnsplash("<html>rate limited</html>").isEmpty());
        assertTrue("no urls", Pics.fromUnsplash("[{\"id\":\"x\"}]").isEmpty());
        assertTrue(
                "urls but no regular one",
                Pics.fromUnsplash("[{\"urls\":{\"raw\":\"r\"}}]").isEmpty());
        assertTrue(
                "an empty address is no address",
                Pics.fromUnsplash("[{\"urls\":{\"regular\":\"\"}}]").isEmpty());
    }

    @Test
    public void onePictureWithoutAnAddressDoesNotLoseTheRest() {
        // The whole page is the point of asking for a page; one bad entry in it
        // must not send the caller away empty-handed.
        String json =
                "[{\"id\":\"x\"},{\"urls\":{\"regular\":\"https://x/2.jpg\"},"
                        + "\"user\":{\"name\":\"Someone\"}}]";
        List<Pics.Shot> shots = Pics.fromUnsplash(json);
        assertEquals(1, shots.size());
        assertEquals("https://x/2.jpg", shots.get(0).url);
    }

    @Test
    public void aPictureWithNoPhotographerStillShows() {
        List<Pics.Shot> shots = Pics.fromUnsplash("[{\"urls\":{\"regular\":\"https://x/1.jpg\"}}]");
        assertEquals("the credit is a courtesy, not a condition", 1, shots.size());
        assertEquals("", shots.get(0).who);
    }

    // ---- Openverse: the source that needs no key ----

    @Test
    public void theOpenverseRequestAsksForCc0Only() {
        String url = Pics.openverseUrl(Pics.KITTY, 1, 20);
        assertTrue(url, url.startsWith("https://api.openverse.org/v1/images/?"));
        assertTrue("CC0 carries no condition at all", url.contains("license=cc0"));
        assertTrue("a 1280x720 screen wants landscape", url.contains("aspect_ratio=wide"));
        assertTrue("a button a child may press", url.contains("mature=false"));
        assertTrue(url.contains("page_size=20"));
        assertTrue(url.contains("q=kitten"));
        assertFalse("no key is sent, because none is needed", url.contains("client_id"));
        assertFalse(url.contains(" "));
    }

    @Test
    public void eachRefillAsksForALaterPage() {
        // A cache Android emptied should not come back as the same ten pictures.
        assertTrue(Pics.openverseUrl(Pics.KITTY, 1, 10).contains("page=1"));
        assertTrue(Pics.openverseUrl(Pics.KITTY, 4, 10).contains("page=4"));
    }

    @Test
    public void aPageBeforeTheFirstOneIsStillTheFirstOne() {
        assertTrue(Pics.openverseUrl(Pics.KITTY, 0, 10).contains("page=1"));
        assertTrue(Pics.openverseUrl(Pics.KITTY, -3, 10).contains("page=1"));
    }

    @Test
    public void theOpenverseAnswerGivesUpItsAddressAndCreator() {
        List<Pics.Shot> shots = Pics.fromOpenverse(ONE_RESULT);
        assertEquals(1, shots.size());
        assertEquals("https://cdn.stocksnap.io/one.jpg", shots.get(0).url);
        assertEquals("Ghost Presenter", shots.get(0).who);
        assertEquals("CC0", shots.get(0).source);
    }

    @Test
    public void aPictureTooNarrowForTheScreenIsLeftBehind() {
        // Openverse's own size filter cut the Lego results from eighty-six to
        // eleven, so the width is judged here instead.
        String narrow = ONE_RESULT.replace("\"width\":5184", "\"width\":400");
        assertTrue(
                "stretched up to 1280 it would look like a mistake",
                Pics.fromOpenverse(narrow).isEmpty());

        String justWide = ONE_RESULT.replace("\"width\":5184", "\"width\":1000");
        assertEquals(1, Pics.fromOpenverse(justWide).size());

        String justUnder = ONE_RESULT.replace("\"width\":5184", "\"width\":999");
        assertTrue(Pics.fromOpenverse(justUnder).isEmpty());
    }

    @Test
    public void aResultWithNoWidthIsNotTrusted() {
        String noWidth = ONE_RESULT.replace("\"width\":5184,", "");
        assertTrue(
                "an unmeasured picture could be a thumbnail",
                Pics.fromOpenverse(noWidth).isEmpty());
    }

    @Test
    public void nothingUsableFromOpenverseComesBackAsNothing() {
        assertTrue(Pics.fromOpenverse("{\"results\":[]}").isEmpty());
        assertTrue(
                "no results field at all",
                Pics.fromOpenverse("{\"detail\":\"throttled\"}").isEmpty());
        assertTrue("not JSON at all", Pics.fromOpenverse("<html>429</html>").isEmpty());
        assertTrue(Pics.fromOpenverse("").isEmpty());
    }

    // ---- which source answers ----

    @Test
    public void unsplashIsTriedFirstOnlyWhenThereIsAKeyAndASignal() {
        assertTrue(Pics.preferUnsplash("akey", true));
        assertFalse("no signal", Pics.preferUnsplash("akey", false));
        assertFalse("no key: Openverse asks for none", Pics.preferUnsplash("", true));
        assertFalse("a key of spaces is no key", Pics.preferUnsplash("   ", true));
        assertFalse("no key at all", Pics.preferUnsplash(null, true));
    }

    @Test
    public void theKeyIsWhateverWasTypedIntoTheSettings() {
        assertEquals("typed", Pics.key("typed"));
        assertEquals("trimmed", Pics.key("  trimmed  "));
    }

    @Test
    public void withNothingTypedThereIsNoKeyAtAll() {
        // Deliberately nowhere else to look: a key compiled into the build
        // would ship inside every APK.
        assertEquals("", Pics.key(""));
        assertEquals("", Pics.key("   "));
        assertEquals("", Pics.key(null));
        assertFalse(Pics.preferUnsplash(Pics.key(""), true));
    }

    // ---- spreading the ten across more than one photographer ----

    private static Pics.Shot by(String who, String what) {
        return new Pics.Shot("https://x/" + what + ".jpg", who, "CC0");
    }

    private static String whoOf(List<Pics.Shot> shots, int upTo) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(upTo, shots.size()); i++) {
            out.append(shots.get(i).who).append(' ');
        }
        return out.toString().trim();
    }

    @Test
    public void oneAlbumDoesNotFillTheWholeCache() {
        // What really came back for Lego Star Wars: nine of the ten by one
        // person, which is one picture wearing ten hats.
        List<Pics.Shot> shots = new java.util.ArrayList<>();
        shots.add(by("freestocks.org", "a"));
        for (int i = 0; i < 9; i++) {
            shots.add(by("pasukaru76", "b" + i));
        }
        assertEquals(
                "the second photographer waits nine turns",
                "freestocks.org pasukaru76 pasukaru76",
                whoOf(shots, 3));

        List<Pics.Shot> spread = Pics.spread(shots, Pics.PER_CREATOR);
        assertEquals("nothing may be thrown away", shots.size(), spread.size());
        assertEquals("freestocks.org pasukaru76", whoOf(spread, 2));
    }

    @Test
    public void everyPhotographerGetsATurnBeforeAnyoneGetsASecond() {
        List<Pics.Shot> shots = new java.util.ArrayList<>();
        shots.add(by("Ann", "1"));
        shots.add(by("Ann", "2"));
        shots.add(by("Bo", "1"));
        shots.add(by("Cy", "1"));
        assertEquals("Ann Bo Cy Ann", whoOf(Pics.spread(shots, Pics.PER_CREATOR), 4));
    }

    @Test
    public void aThinAnswerCanStillFillTheCache() {
        // Ten pictures by two people is still ten pictures. Spreading must
        // reorder them, never refuse them.
        List<Pics.Shot> shots = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) {
            shots.add(by("Ann", "a" + i));
        }
        for (int i = 0; i < 3; i++) {
            shots.add(by("Bo", "b" + i));
        }
        List<Pics.Shot> spread = Pics.spread(shots, Pics.PER_CREATOR);
        assertEquals(10, spread.size());
        assertEquals("Ann Bo Ann Bo Ann Bo Ann", whoOf(spread, 7));
    }

    @Test
    public void picturesWithNobodyNamedAreNotTreatedAsOnePhotographer() {
        // Three unnamed pictures are three pictures, not one person's album.
        List<Pics.Shot> shots = new java.util.ArrayList<>();
        shots.add(by("", "1"));
        shots.add(by("", "2"));
        shots.add(by("", "3"));
        assertEquals(3, Pics.spread(shots, Pics.PER_CREATOR).size());
    }

    @Test
    public void spreadingNothingIsNotAnError() {
        assertTrue(Pics.spread(new java.util.ArrayList<>(), Pics.PER_CREATOR).isEmpty());
    }

    @Test
    public void onePhotographerAloneKeepsTheirOrder() {
        List<Pics.Shot> shots = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            shots.add(by("Ann", "a" + i));
        }
        List<Pics.Shot> spread = Pics.spread(shots, Pics.PER_CREATOR);
        assertEquals(5, spread.size());
        for (int i = 0; i < 5; i++) {
            assertEquals("https://x/a" + i + ".jpg", spread.get(i).url);
        }
    }

    // ---- the cache on disk ----

    @Test
    public void onlyTheJpegsCountAsPictures() {
        // The folder carries credits.txt as well. Counting it as a picture would
        // show a blank screen one time in eleven.
        String[] kept = Pics.pictures(new String[] {"01.jpg", "credits.txt", "02.jpg"});
        assertEquals(2, kept.length);
        assertEquals("01.jpg", kept[0]);
        assertEquals("02.jpg", kept[1]);
    }

    @Test
    public void anEmptyCacheHasNoPicturesRatherThanCrashing() {
        assertEquals(0, Pics.pictures(null).length);
        assertEquals(0, Pics.pictures(new String[0]).length);
        assertEquals(0, Pics.pictures(new String[] {"credits.txt"}).length);
    }

    @Test
    public void picturesAreNamedSoTheySortIntoTheOrderTheyArrived() {
        assertEquals("01.jpg", Pics.fileName(1));
        assertEquals("09.jpg", Pics.fileName(9));
        assertEquals("10.jpg", Pics.fileName(10));
    }

    @Test
    public void aCreditIsWrittenAndReadBackTheSameWay() {
        Pics.Shot shot = new Pics.Shot("https://x/1.jpg", "Ghost Presenter", "CC0");
        String line = Pics.creditLine("01.jpg", shot);
        Pics.Credit back = Pics.creditFor(line, "01.jpg");
        assertNotNull(back);
        assertEquals("Ghost Presenter", back.who);
        assertEquals("CC0", back.licence);
    }

    @Test
    public void theNameBesideAPictureComesFromTheCreditsFile() {
        String credits = "01.jpg\tJennifer C.\tCC0\n02.jpg\tkcxd\tUnsplash\n";
        Pics.Credit first = Pics.creditFor(credits, "01.jpg");
        assertNotNull(first);
        assertEquals("Jennifer C.", first.who);
        assertEquals("CC0", first.licence);

        Pics.Credit second = Pics.creditFor(credits, "02.jpg");
        assertNotNull(second);
        assertEquals("kcxd", second.who);
        assertEquals("Unsplash", second.licence);
    }

    @Test
    public void aPictureNoOneIsNamedForIsShownWithoutALine() {
        // Not an error: CC0 asks for no name at all. What must never happen is a
        // name attached to the wrong picture.
        assertNull(Pics.creditFor("01.jpg\tSomeone\tCC0", "07.jpg"));
        assertNull(Pics.creditFor(null, "01.jpg"));
        assertNull(Pics.creditFor("01.jpg\t\tCC0", "01.jpg"));
        assertNull(
                "a line that lost its source column", Pics.creditFor("01.jpg\tSomeone", "01.jpg"));
    }

    @Test
    public void aNameIsNotMatchedByAPrefixOfIt() {
        // "1.jpg" must not be answered with the line for "11.jpg".
        String credits = "11.jpg\tEleven\tCC0\n1.jpg\tOne\tCC0";
        assertEquals("One", Pics.creditFor(credits, "1.jpg").who);
        assertEquals("Eleven", Pics.creditFor(credits, "11.jpg").who);
    }

    @Test
    public void theCreditsFileSurvivesWindowsLineEndings() {
        Pics.Credit c =
                Pics.creditFor("01.jpg\tSomeone\tUnsplash\r\n02.jpg\tElse\tCC0\r\n", "02.jpg");
        assertNotNull(c);
        assertEquals("Else", c.who);
        assertEquals("CC0", c.licence);
    }

    // ---- which picture comes next ----

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
        for (int last = 0; last < 10; last++) {
            for (int i = 0; i < 200; i++) {
                assertTrue("the same picture twice running", Pics.pickOther(10, last, r) != last);
            }
        }
    }

    @Test
    public void everyOtherPictureCanTurnUp() {
        Set<Integer> seen = new HashSet<>();
        Random r = new Random(11);
        for (int i = 0; i < 2000; i++) {
            seen.add(Pics.pickOther(10, 5, r));
        }
        assertEquals("nine of the ten are reachable", 9, seen.size());
        assertFalse(seen.contains(5));
    }

    @Test
    public void withNothingShownYetTheFirstPictureIsReachableToo() {
        // The obvious way to write pickOther - count past the last index - skips
        // index 0 for ever when nothing has been shown, because every index
        // counts past -1. Ten pictures, one of which never appears.
        Set<Integer> seen = new HashSet<>();
        Random r = new Random(3);
        for (int i = 0; i < 2000; i++) {
            seen.add(Pics.pickOther(10, -1, r));
        }
        assertEquals("all ten are reachable before anything has been shown", 10, seen.size());
    }

    @Test
    public void aCacheHalfFilledStillOffersWhatItHas() {
        // The cache is topped up behind the picture that is already showing, so
        // a set of three must behave like a set of three and not like a set of
        // ten with seven holes in it.
        Random r = new Random(5);
        for (int i = 0; i < 200; i++) {
            int at = Pics.pickOther(3, 1, r);
            assertTrue("outside the three that exist", at >= 0 && at < 3);
            assertTrue(at != 1);
        }
    }
}
