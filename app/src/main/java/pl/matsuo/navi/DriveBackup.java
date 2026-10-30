package pl.matsuo.navi;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Backs trips up to the owner's own Google Drive.
 *
 * <p>Written against {@link java.net.HttpURLConnection} and {@link DriveApi}, so it adds no
 * dependency to an app that has none. Sign-in is the limited-input device flow: the unit shows a
 * code, the owner types it on their phone, and a refresh token is kept in the app's private
 * settings.
 *
 * <p>Every method here can fail, and failing must cost nothing but an unsynced trip. Nothing in
 * this class touches the database except through {@link BackupRunner}, nothing runs on the main
 * thread, and the recorder never waits on any of it.
 */
final class DriveBackup implements Backup {

    static final String PREF_REFRESH = "drive_refresh_token";
    static final String PREF_ACCESS = "drive_access_token";
    static final String PREF_EXPIRES = "drive_access_expires";
    static final String PREF_FOLDER = "drive_folder_id";

    /** Refresh this long before the token actually expires. */
    private static final long EARLY_REFRESH_MS = 60_000L;

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private final SharedPreferences prefs;
    private final String clientId;
    private final String clientSecret;

    DriveBackup(Context c, SharedPreferences prefs) {
        this.prefs = prefs;
        this.clientId = c.getString(R.string.drive_client_id).trim();
        this.clientSecret = c.getString(R.string.drive_client_secret).trim();
    }

    /** True when this build carries an OAuth client; see docs/DESIGN_STORAGE.md. */
    boolean hasClient() {
        return !clientId.isEmpty() && !clientSecret.isEmpty();
    }

    /** True when the owner has signed in. */
    boolean signedIn() {
        return prefs.getString(PREF_REFRESH, null) != null;
    }

    @Override
    public boolean configured() {
        return hasClient() && signedIn();
    }

    void signOut() {
        prefs.edit()
                .remove(PREF_REFRESH)
                .remove(PREF_ACCESS)
                .remove(PREF_EXPIRES)
                .remove(PREF_FOLDER)
                .apply();
    }

    // ───────────────────────────── signing in ─────────────────────────────

    /** Asks Google for a code to show on screen. */
    DriveApi.DeviceCode requestCode() throws IOException {
        if (!hasClient()) {
            throw new IOException("no OAuth client in this build");
        }
        Response r = post(DriveApi.DEVICE_CODE_URL, DriveApi.deviceCodeBody(clientId));
        if (!r.ok()) {
            throw new IOException(reason(r, "device code refused"));
        }
        return DriveApi.parseDeviceCode(r.body);
    }

    /**
     * One poll of the token endpoint.
     *
     * @return null while the owner has not finished, otherwise the flow is done
     * @throws IOException when the flow has failed for good
     */
    DriveApi.Poll poll(String deviceCode) throws IOException {
        Response r =
                post(DriveApi.TOKEN_URL, DriveApi.pollBody(clientId, clientSecret, deviceCode));
        if (r.ok()) {
            DriveApi.Token token = DriveApi.parseToken(r.body);
            prefs.edit()
                    .putString(PREF_REFRESH, token.refreshToken)
                    .putString(PREF_ACCESS, token.accessToken)
                    .putLong(
                            PREF_EXPIRES,
                            System.currentTimeMillis() + token.expiresInSeconds * 1000L)
                    .apply();
            return null; // done
        }
        DriveApi.Poll state = DriveApi.classify(r.body);
        if (state == DriveApi.Poll.FAILED) {
            throw new IOException(reason(r, "sign-in failed"));
        }
        return state;
    }

    /** A valid access token, refreshing it if necessary. */
    private String accessToken() throws IOException {
        long expires = prefs.getLong(PREF_EXPIRES, 0);
        String access = prefs.getString(PREF_ACCESS, null);
        if (access != null && System.currentTimeMillis() < expires - EARLY_REFRESH_MS) {
            return access;
        }
        String refresh = prefs.getString(PREF_REFRESH, null);
        if (refresh == null) {
            throw new IOException("not signed in");
        }
        Response r =
                post(DriveApi.TOKEN_URL, DriveApi.refreshBody(clientId, clientSecret, refresh));
        if (!r.ok()) {
            // An expired or revoked refresh token never recovers on its own, and
            // leaving it in place would retry for ever.
            if (r.code == 400 || r.code == 401) {
                signOut();
            }
            throw new IOException(reason(r, "could not refresh the sign-in"));
        }
        DriveApi.Token token = DriveApi.parseToken(r.body);
        prefs.edit()
                .putString(PREF_ACCESS, token.accessToken)
                .putLong(PREF_EXPIRES, System.currentTimeMillis() + token.expiresInSeconds * 1000L)
                .apply();
        return token.accessToken;
    }

    // ───────────────────────────── uploading ─────────────────────────────

    @Override
    public String upload(String folder, String name, byte[] data) throws IOException {
        String token = accessToken();
        String appFolder = folderId(token, DriveApi.FOLDER, null, PREF_FOLDER);
        String yearFolder = folderId(token, folder, appFolder, null);

        String existing =
                DriveApi.parseFirstId(get(DriveApi.fileQuery(yearFolder, name), token).body);
        if (existing != null) {
            // Trips are immutable once closed, so the same name is the same drive.
            return existing;
        }
        String boundary = "navi" + System.nanoTime();
        byte[] body =
                DriveApi.multipart(
                        boundary,
                        DriveApi.fileMetadata(name, yearFolder),
                        data,
                        DriveApi.ARTIFACT_MIME);
        Response r =
                send(
                        DriveApi.UPLOAD_URL,
                        "POST",
                        token,
                        "multipart/related; boundary=" + boundary,
                        body);
        if (!r.ok()) {
            throw new IOException(reason(r, "upload refused"));
        }
        String id = DriveApi.parseId(r.body);
        if (id == null) {
            throw new IOException("upload returned no file id");
        }
        return id;
    }

    /** Finds or creates a folder, remembering the app folder so it is looked up once. */
    private String folderId(String token, String name, String parent, String cacheKey)
            throws IOException {
        if (cacheKey != null) {
            String cached = prefs.getString(cacheKey, null);
            if (cached != null) {
                return cached;
            }
        }
        String query = parent == null ? DriveApi.folderQuery() : DriveApi.fileQuery(parent, name);
        String id = DriveApi.parseFirstId(get(query, token).body);
        if (id == null) {
            Response r =
                    send(
                            DriveApi.FILES_URL,
                            "POST",
                            token,
                            "application/json; charset=UTF-8",
                            DriveApi.folderMetadata(name, parent).getBytes(StandardCharsets.UTF_8));
            if (!r.ok()) {
                throw new IOException(reason(r, "could not create the folder"));
            }
            id = DriveApi.parseId(r.body);
            if (id == null) {
                throw new IOException("folder creation returned no id");
            }
        }
        if (cacheKey != null) {
            prefs.edit().putString(cacheKey, id).apply();
        }
        return id;
    }

    // ───────────────────────────── plumbing ─────────────────────────────

    /** A response worth looking at: the code and the body, whichever stream it came from. */
    static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }

        boolean ok() {
            return code >= 200 && code < 300;
        }
    }

    private static String reason(Response r, String fallback) {
        String message = DriveApi.errorMessage(r.body);
        return message == null || message.isEmpty() ? fallback + " (HTTP " + r.code + ")" : message;
    }

    private Response post(String url, String form) throws IOException {
        return send(
                url,
                "POST",
                null,
                "application/x-www-form-urlencoded",
                form.getBytes(StandardCharsets.UTF_8));
    }

    private Response get(String url, String token) throws IOException {
        return send(url, "GET", token, null, null);
    }

    private Response send(String url, String method, String token, String contentType, byte[] body)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            if (token != null) {
                c.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (body != null) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.length);
                c.setRequestProperty("Content-Type", contentType);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body);
                }
            }
            int code = c.getResponseCode();
            return new Response(code, read(code >= 400 ? c.getErrorStream() : c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
