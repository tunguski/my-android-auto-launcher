package pl.matsuo.navi;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * <p>Nothing travels inside the APK. The first time a set is asked for, ten pictures are fetched
 * and kept in the cache directory, and from then on that is what answers: instantly, with no
 * network, and no megabytes carried by every car that never presses the button. Android may empty
 * that directory when it needs the room, and then the next tap fills it again.
 *
 * <p>Two sources, in this order. With an Unsplash access key - typed into the settings, or compiled
 * in - Unsplash answers, because it is curated and deep. Without one, or when it fails, Openverse
 * answers: it needs no key at all, and asked for CC0 alone it returns pictures that carry no
 * condition whatsoever. The photographer is named on screen either way; CC0 does not ask for it,
 * but the Unsplash terms do and the people who took them deserve it.
 *
 * <p>Everything here is a pure function over a string or a count, so the rules can be pinned down
 * on a plain JVM: what is asked of each source, how an answer is read, and which picture comes
 * next. The reaching out and the drawing live in {@link MainActivity}.
 */
final class Pics {

    static final String LEGO = "lego";
    static final String KITTY = "kitty";

    /** Both sets, in the order their buttons appear. */
    static final String[] SETS = {LEGO, KITTY};

    /**
     * How many pictures a set keeps on disk.
     *
     * <p>Ten rather than twenty: it is half the waiting on the first tap and half the room, and the
     * cache is refilled from a different page of results each time Android empties it, so the ten
     * are not the same ten for ever.
     */
    static final int WANTED = 10;

    /**
     * The narrowest picture worth keeping.
     *
     * <p>The screen is 1280 wide. Openverse's own {@code size=large} filter cut the Lego results
     * from eighty-six to eleven, which is too few to fill a cache from, so the width is checked
     * here instead: it keeps everything that will not look soft and costs nothing.
     */
    static final int MIN_WIDTH = 1000;

    /** The file that sits beside a set's cached pictures, naming who took each one. */
    static final String CREDITS = "credits.txt";

    private Pics() {}

    /** What to ask a source for. */
    static String query(String set) {
        return LEGO.equals(set) ? "lego star wars" : "kitten";
    }

    /** Where a set's cached pictures live, under the cache directory. */
    static String cacheDir(String set) {
        return "pics/" + set;
    }

    /** One picture: where to fetch it, and who took it. */
    static final class Shot {
        final String url;
        final String who;
        final String source;

        Shot(String url, String who, String source) {
            this.url = url;
            this.who = who;
            this.source = source;
        }
    }

    // ---- Unsplash: the better pictures, and the one that wants a key ----

    /**
     * The address of a page of random pictures of a set.
     *
     * <p>The random endpoint rather than a search page, because every refill should bring something
     * the last one did not: this picks from everything Unsplash has for the query, not from the
     * first page of it. Landscape only - the screen is 1280x720, and a portrait photograph would
     * show as two black bands and a stripe.
     */
    static String randomUrl(String set, String key, int count) {
        return "https://api.unsplash.com/photos/random?query="
                + encode(query(set))
                + "&orientation=landscape&count="
                + count
                + "&client_id="
                + encode(key.trim());
    }

    /**
     * Reads what the random endpoint answered.
     *
     * <p>The photographer's name comes out with the address because the Unsplash API terms ask for
     * the credit to be shown wherever the picture is, and a corner of the screen costs nothing.
     *
     * @return every picture the answer holds, in its order, or an empty list
     */
    static List<Shot> fromUnsplash(String json) {
        List<Shot> found = new ArrayList<>();
        try {
            JSONArray shots = new JSONArray(json);
            for (int i = 0; i < shots.length(); i++) {
                JSONObject shot = shots.optJSONObject(i);
                if (shot == null) {
                    continue;
                }
                JSONObject urls = shot.optJSONObject("urls");
                String url = urls == null ? "" : urls.optString("regular", "");
                if (url.isEmpty()) {
                    continue;
                }
                JSONObject user = shot.optJSONObject("user");
                found.add(new Shot(url, user == null ? "" : nameOf(user), "Unsplash"));
            }
        } catch (Exception e) {
            // A diversion is never worth a crash; an empty list sends the caller
            // to the other source.
        }
        return found;
    }

    private static String nameOf(JSONObject user) {
        String name = user.optString("name", "");
        return name.isEmpty() ? user.optString("username", "") : name;
    }

    // ---- Openverse: no key, and nothing owed for what it returns ----

    /**
     * The address of a page of Openverse results for a set.
     *
     * <p>{@code license=cc0} and nothing else: a picture under CC0 may be shown, kept and carried
     * with no condition at all, which is the right footing for something a car downloads by itself.
     * {@code mature=false} because this is a button a child may press. The page is asked for in a
     * single request - anonymous callers get twenty a minute and two hundred a day, and one request
     * per refill leaves that limit untouched.
     *
     * @param page which page of results, counted from 1; a later page each time the cache is
     *     refilled, so the same ten do not come back
     */
    static String openverseUrl(String set, int page, int count) {
        return "https://api.openverse.org/v1/images/?q="
                + encode(query(set))
                + "&license=cc0&aspect_ratio=wide&mature=false&page_size="
                + count
                + "&page="
                + Math.max(1, page);
    }

    /**
     * Reads what Openverse answered.
     *
     * <p>Anything narrower than {@link #MIN_WIDTH} is left behind: the screen is 1280 across and a
     * picture stretched up to it from 400 looks like a mistake rather than a photograph.
     *
     * @return every picture worth keeping, in the order given, or an empty list
     */
    static List<Shot> fromOpenverse(String json) {
        List<Shot> found = new ArrayList<>();
        try {
            JSONArray results = new JSONObject(json).optJSONArray("results");
            if (results == null) {
                return found;
            }
            for (int i = 0; i < results.length(); i++) {
                JSONObject shot = results.optJSONObject(i);
                if (shot == null) {
                    continue;
                }
                String url = shot.optString("url", "");
                if (url.isEmpty() || shot.optInt("width", 0) < MIN_WIDTH) {
                    continue;
                }
                found.add(new Shot(url, shot.optString("creator", ""), "CC0"));
            }
        } catch (Exception e) {
            // As above: no picture is worth a crash.
        }
        return found;
    }

    /**
     * How many of a set's ten any one photographer should contribute before others get a turn.
     *
     * <p>A search for Lego Star Wars came back with nine of ten by the same person: one Flickr
     * album, which is one picture wearing ten hats.
     */
    static final int PER_CREATOR = 4;

    /**
     * The same pictures, reordered so that no photographer fills the cache on their own.
     *
     * <p>Taken in rounds: the first picture of each photographer, then the second of each, and so
     * on. Nothing is thrown away - a thin result set must still be able to fill ten - but what is
     * taken first is spread across as many people as the answer holds. Within a photographer the
     * order the source gave is kept, because that order is its idea of relevance.
     *
     * <p>Pictures with no photographer named are each treated as their own, since there is nothing
     * to tell them apart by.
     */
    static List<Shot> spread(List<Shot> shots, int perCreator) {
        List<List<Shot>> byCreator = new ArrayList<>();
        java.util.Map<String, List<Shot>> seen = new java.util.LinkedHashMap<>();
        for (Shot shot : shots) {
            if (shot.who == null || shot.who.isEmpty()) {
                List<Shot> alone = new ArrayList<>();
                alone.add(shot);
                byCreator.add(alone);
                continue;
            }
            List<Shot> theirs = seen.get(shot.who);
            if (theirs == null) {
                theirs = new ArrayList<>();
                seen.put(shot.who, theirs);
                byCreator.add(theirs);
            }
            theirs.add(shot);
        }

        List<Shot> out = new ArrayList<>();
        int round = 0;
        while (out.size() < shots.size()) {
            boolean tookAny = false;
            for (List<Shot> theirs : byCreator) {
                if (round < theirs.size()) {
                    out.add(theirs.get(round));
                    tookAny = true;
                }
            }
            if (!tookAny) {
                break;
            }
            round++;
            if (round == perCreator) {
                // Everyone has had their share; the rest follow in whatever
                // order is left, so that ten can still be filled from few.
                for (List<Shot> theirs : byCreator) {
                    for (int i = perCreator; i < theirs.size(); i++) {
                        out.add(theirs.get(i));
                    }
                }
                break;
            }
        }
        return out;
    }

    // ---- the cache on disk ----

    /** Who took a cached picture, and what it is carried under. */
    static final class Credit {
        final String who;
        final String licence;

        Credit(String who, String licence) {
            this.who = who;
            this.licence = licence;
        }
    }

    /**
     * The pictures among the names of a set's cache, in the order they are listed.
     *
     * <p>The folder holds {@link #CREDITS} as well, and counting that as a picture would show a
     * blank screen one time in eleven.
     */
    static String[] pictures(String[] names) {
        if (names == null) {
            return new String[0];
        }
        int count = 0;
        for (String name : names) {
            if (isPicture(name)) {
                count++;
            }
        }
        String[] kept = new String[count];
        int at = 0;
        for (String name : names) {
            if (isPicture(name)) {
                kept[at++] = name;
            }
        }
        return kept;
    }

    private static boolean isPicture(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".jpg");
    }

    /** What the picture at an index is called on disk. */
    static String fileName(int index) {
        return String.format(Locale.ROOT, "%02d.jpg", index);
    }

    /** One line of the credits file, as it is written. */
    static String creditLine(String name, Shot shot) {
        return name + "\t" + shot.who + "\t" + shot.source;
    }

    /**
     * Who to name beside a cached picture.
     *
     * @param credits the whole of the credits file, or null when there is none
     * @param name the file being shown, as the folder lists it
     * @return who took it, or null when the file says nothing about it
     */
    static Credit creditFor(String credits, String name) {
        if (credits == null || name == null) {
            return null;
        }
        for (String line : credits.split("\n")) {
            String[] parts = line.trim().split("\t");
            if (parts.length >= 3 && parts[0].equals(name)) {
                String who = parts[1].trim();
                return who.isEmpty() ? null : new Credit(who, parts[2].trim());
            }
        }
        return null;
    }

    /**
     * The index of the next picture to show.
     *
     * <p>Never the one just shown, when there is more than one to choose from: ten pictures that
     * can repeat immediately feel like three.
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
     * Whether Unsplash is the source to try first.
     *
     * <p>Only with a key: the public source carries none, and a car that has not been given one
     * goes to Openverse, which asks for nothing.
     */
    static boolean preferUnsplash(String key, boolean online) {
        return online && key != null && !key.trim().isEmpty();
    }

    /**
     * The key to use: whatever was typed into the settings, tidied up.
     *
     * <p>There is deliberately no second place to look. A key compiled into the build would travel
     * inside every copy of the APK, in plain sight of anyone who cares to look - so the only key
     * the app will use is the one its owner typed into this unit, and an empty field means
     * Openverse rather than a broken Unsplash.
     */
    static String key(String typed) {
        return typed == null ? "" : typed.trim();
    }

    /** Percent-encoding for the few characters a query or a key can hold. */
    private static String encode(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean plain =
                    (c >= 'a' && c <= 'z')
                            || (c >= 'A' && c <= 'Z')
                            || (c >= '0' && c <= '9')
                            || c == '-'
                            || c == '_'
                            || c == '.'
                            || c == '~';
            if (plain) {
                out.append(c);
            } else {
                out.append('%').append(String.format(Locale.ROOT, "%02X", (int) c));
            }
        }
        return out.toString();
    }
}
