package pl.matsuo.navi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The Google Drive protocol, with no network in it.
 *
 * <p>Everything here builds a request body or reads a response, so the fiddly parts - which OAuth
 * error means "keep waiting" and which means "give up", what a multipart upload looks like - are
 * covered by plain unit tests. {@link DriveBackup} does the talking.
 *
 * <p>The sign-in is the limited-input device flow: the unit shows a short code, the owner types it
 * on a phone. A head unit has no keyboard worth using and may have no browser at all, so the usual
 * redirect-based flow is not an option.
 */
final class DriveApi {

    /** Per-file access: the app sees only what it created, and nothing else in the drive. */
    static final String SCOPE = "https://www.googleapis.com/auth/drive.file";

    static final String DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code";
    static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    static final String FILES_URL = "https://www.googleapis.com/drive/v3/files";
    static final String UPLOAD_URL =
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart";
    static final String DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";
    static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    static final String ARTIFACT_MIME = "application/gzip";

    /** The name of the folder the app creates in the owner's drive. */
    static final String FOLDER = "Navi simple";

    private DriveApi() {}

    // ───────────────────────────── requests ─────────────────────────────

    /** A form body from alternating names and values, percent-encoded. */
    static String form(String... keysAndValues) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(encode(keysAndValues[i])).append('=').append(encode(keysAndValues[i + 1]));
        }
        return sb.toString();
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("every JRE has UTF-8", e);
        }
    }

    static String deviceCodeBody(String clientId) {
        return form("client_id", clientId, "scope", SCOPE);
    }

    static String pollBody(String clientId, String clientSecret, String deviceCode) {
        return form(
                "client_id", clientId,
                "client_secret", clientSecret,
                "device_code", deviceCode,
                "grant_type", DEVICE_GRANT);
    }

    static String refreshBody(String clientId, String clientSecret, String refreshToken) {
        return form(
                "client_id", clientId,
                "client_secret", clientSecret,
                "refresh_token", refreshToken,
                "grant_type", "refresh_token");
    }

    /** A query that finds the app's own folder; with this scope only its own files are visible. */
    static String folderQuery() {
        return FILES_URL
                + "?spaces=drive&fields=files(id)&q="
                + encode(
                        "name='"
                                + FOLDER
                                + "' and mimeType='"
                                + FOLDER_MIME
                                + "' and trashed=false");
    }

    /** A query that finds an already uploaded trip inside the folder. */
    static String fileQuery(String folderId, String name) {
        return FILES_URL
                + "?spaces=drive&fields=files(id)&q="
                + encode("name='" + name + "' and '" + folderId + "' in parents and trashed=false");
    }

    /** Metadata for a folder: the app's own folder when {@code parentId} is null, else a year. */
    static String folderMetadata(String name, String parentId) {
        return metadata(name, FOLDER_MIME, parentId);
    }

    static String fileMetadata(String name, String parentId) {
        return metadata(name, ARTIFACT_MIME, parentId);
    }

    private static String metadata(String name, String mime, String parentId) {
        StringBuilder sb = new StringBuilder("{\"name\":").append(JSONObject.quote(name));
        sb.append(",\"mimeType\":").append(JSONObject.quote(mime));
        if (parentId != null) {
            sb.append(",\"parents\":[").append(JSONObject.quote(parentId)).append(']');
        }
        return sb.append('}').toString();
    }

    /** A multipart/related body: the metadata, then the bytes. */
    static byte[] multipart(String boundary, String metadataJson, byte[] content, String mime) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 512);
        write(out, "--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n");
        write(out, metadataJson);
        write(out, "\r\n--" + boundary + "\r\nContent-Type: " + mime + "\r\n\r\n");
        out.write(content, 0, content.length);
        write(out, "\r\n--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.write(b, 0, b.length);
    }

    // ───────────────────────────── responses ─────────────────────────────

    /** What the unit shows while the owner signs in on another device. */
    static final class DeviceCode {
        String deviceCode;
        String userCode;
        String verificationUrl;
        int intervalSeconds;
        int expiresInSeconds;
    }

    static DeviceCode parseDeviceCode(String json) throws IOException {
        try {
            JSONObject o = new JSONObject(json);
            DeviceCode d = new DeviceCode();
            d.deviceCode = o.getString("device_code");
            d.userCode = o.getString("user_code");
            // Google has sent this under two names over the years.
            d.verificationUrl =
                    o.optString(
                            "verification_url",
                            o.optString("verification_uri", "google.com/device"));
            d.intervalSeconds = o.optInt("interval", 5);
            d.expiresInSeconds = o.optInt("expires_in", 1800);
            return d;
        } catch (Exception e) {
            throw new IOException("unexpected device code response", e);
        }
    }

    /** What came back from the token endpoint. */
    static final class Token {
        String accessToken;
        String refreshToken;
        long expiresInSeconds;
    }

    static Token parseToken(String json) throws IOException {
        try {
            JSONObject o = new JSONObject(json);
            Token t = new Token();
            t.accessToken = o.getString("access_token");
            t.refreshToken = o.optString("refresh_token", null);
            t.expiresInSeconds = o.optLong("expires_in", 3600);
            return t;
        } catch (Exception e) {
            throw new IOException("unexpected token response", e);
        }
    }

    /** How a failed poll should be treated. */
    enum Poll {
        /** Nobody has typed the code yet. */
        PENDING,
        /** Polling too fast; wait longer. */
        SLOW_DOWN,
        /** The owner said no. */
        DENIED,
        /** The code is too old; start again. */
        EXPIRED,
        /** Something else, including a misconfigured client or an unsupported scope. */
        FAILED
    }

    static Poll classify(String json) {
        String error = errorOf(json);
        if (error == null) {
            return Poll.FAILED;
        }
        switch (error) {
            case "authorization_pending":
                return Poll.PENDING;
            case "slow_down":
                return Poll.SLOW_DOWN;
            case "access_denied":
                return Poll.DENIED;
            case "expired_token":
                return Poll.EXPIRED;
            default:
                return Poll.FAILED;
        }
    }

    /** The {@code error} field of a Google error body, or null. */
    static String errorOf(String json) {
        if (json == null) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(json);
            if (o.has("error") && o.get("error") instanceof String) {
                return o.getString("error");
            }
            // The Drive API nests it: {"error":{"message":"..."}}
            JSONObject nested = o.optJSONObject("error");
            if (nested != null) {
                return nested.optString("status", nested.optString("message", "error"));
            }
        } catch (Exception ignored) {
            // Not JSON at all; the caller reports it as a plain failure.
        }
        return null;
    }

    /** A human-readable reason for the settings screen, or null when there is none. */
    static String errorMessage(String json) {
        if (json == null) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(json);
            JSONObject nested = o.optJSONObject("error");
            if (nested != null) {
                return nested.optString("message", nested.optString("status", null));
            }
            String description = o.optString("error_description", null);
            if (description != null && !description.isEmpty()) {
                return description;
            }
            return o.optString("error", null);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** The id of a created file, or null. */
    static String parseId(String json) {
        try {
            return new JSONObject(json).optString("id", null);
        } catch (Exception e) {
            return null;
        }
    }

    /** The first id of a files.list response, or null when nothing matched. */
    static String parseFirstId(String json) {
        try {
            JSONArray files = new JSONObject(json).optJSONArray("files");
            if (files == null || files.length() == 0) {
                return null;
            }
            return files.getJSONObject(0).optString("id", null);
        } catch (Exception e) {
            return null;
        }
    }
}
