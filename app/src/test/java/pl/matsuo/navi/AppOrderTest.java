package pl.matsuo.navi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** The order the app list puts things in: the driving handful first, then the alphabet. */
public class AppOrderTest {

    @Test
    public void theDrivingHandfulComesBeforeTheAlphabet() {
        assertTrue(
                "settings must come before an unlisted app",
                AppOrder.rank("com.android.settings") < AppOrder.rank("com.acme.whatever"));
    }

    @Test
    public void theListedOrderIsTheOrderGiven() {
        assertTrue(
                "settings before Maps",
                AppOrder.rank("com.android.settings")
                        < AppOrder.rank("com.google.android.apps.maps"));
        assertTrue(
                "Maps before YouTube",
                AppOrder.rank("com.google.android.apps.maps")
                        < AppOrder.rank("com.google.android.youtube"));
    }

    @Test
    public void everythingUnlistedSharesOneRank() {
        assertEquals(AppOrder.rank("com.acme.one"), AppOrder.rank("com.acme.two"));
        assertEquals(AppOrder.FIRST.length, AppOrder.rank("com.acme.one"));
    }

    @Test
    public void noPackageIsListedTwice() {
        List<String> seen = new ArrayList<>(Arrays.asList(AppOrder.FIRST));
        Collections.sort(seen);
        for (int i = 1; i < seen.size(); i++) {
            assertTrue("listed twice: " + seen.get(i), !seen.get(i).equals(seen.get(i - 1)));
        }
    }

    @Test
    public void aMissingAppCostsNothing() {
        // A unit without Gmail simply never matches it; nothing else moves.
        assertTrue(AppOrder.rank("com.android.chrome") > AppOrder.rank("com.google.android.gm"));
        assertEquals(AppOrder.FIRST.length, AppOrder.rank(""));
    }
}
