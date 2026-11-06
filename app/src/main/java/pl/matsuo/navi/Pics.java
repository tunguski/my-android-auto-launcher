package pl.matsuo.navi;

import java.util.Random;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Which picture to show, and where to get it.
 *
 * <p>Two sets, chosen from the settings screen and shown full screen: Lego Star Wars and cats. No
 * part of this touches recording, the trip database or the screen - it is a diversion for a parked
 * car, and it is kept away from the code that has a job to do.
 *
 * <p>Everything here is a pure function over a string or a count, so the rules can be pinned down
 * on a plain JVM: what is asked of Unsplash, how an answer is read, and which picture comes next.
 * The reaching out and the drawing live in {@link MainActivity}.
 */
final class Pics {

    static final String LEGO = "lego";
    static final String KITTY = "kitty";

    /** Both sets, in the order their buttons appear. */
    static final String[] SETS = {LEGO, KITTY};

    private Pics() {}

    /** What to ask Unsplash for. */
    static String query(String set) {
        return LEGO.equals(set) ? "lego star wars" : "kitten";
    }

    /** Where the bundled copies of a set live inside the APK. */
    static String assetDir(String set) {
        return "pics/" + set;
    }

    /**
     * The address of one random picture of a set.
     *
     * <p>The random endpoint rather than a search page, because the point of being online is to see
     * something the bundled twenty do not hold: this picks from everything Unsplash has for the
     * query, not from the first page of it. Landscape only - the screen is 1280x720 and a portrait
     * photograph would show as two black bands and a stripe.
     */
    static String randomUrl(String set, String key) {
        return "https://api.unsplash.com/photos/random?query="
                + query(set).replace(" ", "%20")
                + "&orientation=landscape&count=1&client_id="
                + key;
    }

    /** One picture: where to fetch it, and who took it. */
    static final class Shot {
        final String url;
        final String who;

        Shot(String url, String who) {
            this.url = url;
            this.who = who;
        }
    }

    /**
     * Reads what the random endpoint answered.
     *
     * <p>The photographer's name comes out with the address because the Unsplash API terms ask for
     * the credit to be shown wherever the picture is, and a corner of the screen costs nothing.
     *
     * @return the picture, or null if the answer holds none
     */
    static Shot fromRandom(String json) {
        try {
            JSONArray shots = new JSONArray(json);
            if (shots.length() == 0) {
                return null;
            }
            JSONObject shot = shots.getJSONObject(0);
            String url = shot.getJSONObject("urls").optString("regular", "");
            if (url.isEmpty()) {
                return null;
            }
            String who =
                    shot.optJSONObject("user") == null ? "" : nameOf(shot.getJSONObject("user"));
            return new Shot(url, who);
        } catch (Exception e) {
            // A diversion is never worth a crash; the bundled set is the answer.
            return null;
        }
    }

    private static String nameOf(JSONObject user) {
        String name = user.optString("name", "");
        return name.isEmpty() ? user.optString("username", "") : name;
    }

    /**
     * The index of the next picture to show.
     *
     * <p>Never the one just shown, when there is more than one to choose from: twenty pictures that
     * can repeat immediately feel like five.
     *
     * @param count how many there are
     * @param last the index shown last, or -1
     * @return an index, or -1 when there is nothing to show
     */
    static int pickOther(int count, int last, Random random) {
        if (count <= 0) {
            return -1;
        }
        if (count == 1 || last < 0 || last >= count) {
            return random.nextInt(count);
        }
        int next = random.nextInt(count - 1);
        // Counting past the one just shown leaves every other index equally
        // likely, with no loop that could spin on an unlucky generator.
        return next < last ? next : next + 1;
    }

    /**
     * Whether to go to Unsplash rather than open the APK.
     *
     * <p>Only with a key compiled in: the public source has none, so a build made from it is
     * offline by nature and shows the bundled twenty.
     */
    static boolean preferOnline(String key, boolean online) {
        return online && key != null && !key.trim().isEmpty();
    }
}
