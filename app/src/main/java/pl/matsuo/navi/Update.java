package pl.matsuo.navi;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * What a GitHub release says, and whether it is worth installing.
 *
 * <p>No network and no Android here, so the two things that can quietly be wrong - deciding which
 * version is newer, and picking the right file out of a release - are covered by plain unit tests.
 * {@link Updater} does the fetching.
 */
final class Update {

    /** The releases endpoint for a repository, e.g. {@code owner/name}. */
    static String latestUrl(String repo) {
        return "https://api.github.com/repos/" + repo + "/releases/latest";
    }

    /** What the launcher needs to know about a published release. */
    static final class Release {
        String tag; // as published, e.g. "v2.2"
        String version; // the numbers alone, e.g. "2.2"
        String notes;
        String apkName;
        String apkUrl;
        long apkBytes;

        boolean installable() {
            return apkUrl != null && !apkUrl.isEmpty();
        }
    }

    private Update() {}

    /**
     * The version numbers out of whatever a tag or a version name looks like.
     *
     * <p>Tags are written {@code v2.1} and the debug build calls itself {@code 2.1-debug}; both
     * have to compare as the same release.
     */
    static String normalise(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        int end = 0;
        while (end < s.length() && (Character.isDigit(s.charAt(end)) || s.charAt(end) == '.')) {
            end++;
        }
        // Trim a trailing dot, so "2.1." and "2.1" are the same thing.
        String numbers = s.substring(0, end);
        while (numbers.endsWith(".")) {
            numbers = numbers.substring(0, numbers.length() - 1);
        }
        return numbers;
    }

    /**
     * Compares two versions component by component.
     *
     * @return negative when {@code a} is older, zero when they are the same release, positive when
     *     {@code a} is newer
     */
    static int compare(String a, String b) {
        String[] left = normalise(a).split("\\.");
        String[] right = normalise(b).split("\\.");
        int parts = Math.max(left.length, right.length);
        for (int i = 0; i < parts; i++) {
            // A missing component is a zero, so 2.1 and 2.1.0 are the same release.
            int l = number(left, i);
            int r = number(right, i);
            if (l != r) {
                return l < r ? -1 : 1;
            }
        }
        return 0;
    }

    private static int number(String[] parts, int i) {
        if (i >= parts.length || parts[i].isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[i]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** True when {@code candidate} is a later release than what is installed. */
    static boolean isNewer(String installed, String candidate) {
        if (normalise(candidate).isEmpty()) {
            return false; // a release with no version number is not an upgrade
        }
        return compare(installed, candidate) < 0;
    }

    /**
     * Reads the answer from the releases endpoint.
     *
     * @return the release, or null when there is nothing usable in it
     */
    static Release parse(String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (o.optBoolean("draft", false) || o.optBoolean("prerelease", false)) {
                return null; // not finished, or not meant for everyone
            }
            Release r = new Release();
            r.tag = o.optString("tag_name", "");
            r.version = normalise(r.tag);
            if (r.version.isEmpty()) {
                return null;
            }
            r.notes = o.optString("body", "");
            pickApk(o.optJSONArray("assets"), r);
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Chooses the file to offer.
     *
     * <p>A release carries the guide and a zip of screenshots as well, and there can be both a
     * signed and a debug APK; the signed one is the one an installed app can be replaced with.
     */
    private static void pickApk(JSONArray assets, Release r) {
        if (assets == null) {
            return;
        }
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null) {
                continue;
            }
            String name = a.optString("name", "");
            if (!name.endsWith(".apk")) {
                continue;
            }
            boolean release = name.contains("-release");
            if (r.apkUrl == null || (release && !r.apkName.contains("-release"))) {
                r.apkName = name;
                r.apkUrl = a.optString("browser_download_url", "");
                r.apkBytes = a.optLong("size", 0);
            }
        }
    }
}
