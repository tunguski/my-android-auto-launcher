package pl.matsuo.navi;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Looks for a newer release on GitHub and offers to install it.
 *
 * <p>The app is not on any store, so the alternative to this is the owner noticing a release by
 * accident and copying an APK onto the unit by hand.
 *
 * <p>One thing to know before relying on it: <b>Android will only replace an app with one signed by
 * the same key.</b> A release signed by the project key cannot install over a locally built,
 * debug-signed copy - the installer refuses it. That is a feature, and it is also why the releases
 * have to be signed by a key that stays the same.
 */
final class Updater {

    static final String PREF_LAST_CHECK = "update_last_check";
    static final String PREF_FOUND = "update_found";
    static final String PREF_OFFERED = "update_offered";
    static final String PREF_AUTO = "update_auto";
    static final String PREF_LAST_ERROR = "update_error";

    /** The installed version as it was at the last check that found nothing newer. */
    static final String PREF_CONFIRMED = "update_confirmed";

    /** How often the automatic check asks GitHub. Their limit is sixty an hour per address. */
    static final long CHECK_EVERY_MS = 24 * 60 * 60 * 1000L;

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    private Updater() {}

    /** The version of the app as installed, e.g. {@code 2.1-debug}. */
    static String installedVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    static boolean autoCheckEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(PREF_AUTO, true);
    }

    /** True when the automatic check has not run for a day. */
    static boolean dueForCheck(SharedPreferences prefs, long nowMillis) {
        long last = prefs.getLong(PREF_LAST_CHECK, 0);
        if (last <= 0 || nowMillis < last) {
            return true; // never checked, or the clock moved backwards
        }
        return nowMillis - last >= CHECK_EVERY_MS;
    }

    /**
     * Asks GitHub what the latest release is.
     *
     * @return the release when it is newer than what is installed, otherwise null
     * @throws IOException for anything that might work next time
     */
    static Update.Release check(Context c, SharedPreferences prefs) throws IOException {
        String body = get(Update.latestUrl(c.getString(R.string.update_repo)));
        Update.Release release = Update.parse(body);
        prefs.edit()
                .putLong(PREF_LAST_CHECK, System.currentTimeMillis())
                .putString(PREF_LAST_ERROR, "")
                .apply();
        if (release == null || !Update.isNewer(installedVersion(c), release.version)) {
            prefs.edit().remove(PREF_FOUND).putString(PREF_CONFIRMED, installedVersion(c)).apply();
            return null;
        }
        prefs.edit().putString(PREF_FOUND, release.version).remove(PREF_CONFIRMED).apply();
        return release;
    }

    /**
     * Whether a check has confirmed that what is installed is the newest there is.
     *
     * <p>Not the same question as "is {@code PREF_FOUND} empty": that is also true before anything
     * has ever been checked, and a tick against a version nobody has asked GitHub about would be a
     * claim the app has no grounds for. It is also why the confirmation is stored as the version it
     * was given for - once a newer build is installed, the old confirmation no longer says anything
     * about it.
     */
    static boolean upToDate(Context c, SharedPreferences prefs) {
        return upToDate(
                installedVersion(c),
                prefs.getString(PREF_CONFIRMED, ""),
                prefs.getString(PREF_FOUND, ""));
    }

    static boolean upToDate(String installed, String confirmed, String found) {
        return found.isEmpty() && !installed.isEmpty() && installed.equals(confirmed);
    }

    /** Remembers that a version has already been offered, so it is not asked about twice. */
    static boolean alreadyOffered(SharedPreferences prefs, String version) {
        return version.equals(prefs.getString(PREF_OFFERED, ""));
    }

    static void markOffered(SharedPreferences prefs, String version) {
        prefs.edit().putString(PREF_OFFERED, version).apply();
    }

    static void noteFailure(SharedPreferences prefs, String message) {
        prefs.edit()
                .putLong(PREF_LAST_CHECK, System.currentTimeMillis())
                .putString(PREF_LAST_ERROR, message == null ? "" : message)
                .apply();
    }

    // ───────────────────────────── downloading ─────────────────────────────

    /**
     * Downloads the APK into the private update folder.
     *
     * <p>Written to a temporary name and renamed, so an interrupted download is never mistaken for
     * a complete one.
     */
    static File download(Context c, Update.Release release) throws IOException {
        File target = ApkProvider.file(c, release.apkName);
        File temp = new File(target.getAbsolutePath() + ".part");
        HttpURLConnection conn = open(release.apkUrl);
        try (InputStream in = conn.getInputStream();
                FileOutputStream out = new FileOutputStream(temp)) {
            byte[] buf = new byte[32 * 1024];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            out.getFD().sync();
            if (release.apkBytes > 0 && total != release.apkBytes) {
                throw new IOException("short download: " + total + " of " + release.apkBytes);
            }
        } finally {
            conn.disconnect();
        }
        if (target.exists() && !target.delete()) {
            throw new IOException("cannot replace " + target);
        }
        if (!temp.renameTo(target)) {
            throw new IOException("cannot finish " + target);
        }
        return target;
    }

    /** True when this build may ask the system to install a package. */
    static boolean canInstall(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return true;
        }
        try {
            return c.getPackageManager().canRequestPackageInstalls();
        } catch (Exception e) {
            return false;
        }
    }

    /** Opens the system settings page where installing from this app is allowed. */
    static Intent unknownSourcesSettings(Context c) {
        Intent i =
                new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:" + c.getPackageName()));
        return i;
    }

    /**
     * Hands a downloaded APK to the package installer.
     *
     * @return the intent to start, or null when nothing can open it
     */
    static Intent installIntent(Context c, File apk) {
        Uri uri = ApkProvider.uriFor(apk.getName());
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, ApkProvider.MIME);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        PackageManager pm = c.getPackageManager();
        return i.resolveActivity(pm) == null ? null : i;
    }

    // ───────────────────────────── plumbing ─────────────────────────────

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setInstanceFollowRedirects(true);
        // The GitHub API refuses requests that do not say who is asking.
        c.setRequestProperty("User-Agent", "navi-simple");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        return c;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = open(url);
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = read(in);
            if (code == 404) {
                // No release published yet; that is an answer, not a failure.
                return "{}";
            }
            if (code >= 400) {
                throw new IOException("GitHub said " + code);
            }
            return body;
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
