package pl.matsuo.navi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * What a backed-up trip looks like outside the app.
 *
 * <p>A gzipped CSV, one fix a line, exactly the format earlier versions wrote. The packed binary
 * track would be smaller still, but the whole point of putting a drive on the owner's own drive is
 * that they can open it: this reads in any spreadsheet and feeds any tool, and ten bytes a fix
 * against six is not worth giving that up for.
 *
 * <p>Plain Java only, so the format, the names and the checksums are unit tested.
 */
final class Artifact {

    static final String EXTENSION = ".csv.gz";

    private Artifact() {}

    /** UTC, because a trip recorded abroad must not be named by the unit's idea of local time. */
    private static SimpleDateFormat stamp(String pattern) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f;
    }

    /** The file name of a trip: sortable, unique, and legal on every filesystem. */
    static String name(long startedAtSeconds) {
        return stamp("yyyy-MM-dd'T'HH-mm-ss").format(new Date(startedAtSeconds * 1000L))
                + EXTENSION;
    }

    /** The folder a trip belongs in, so a decade of driving is not one flat list. */
    static String year(long startedAtSeconds) {
        return stamp("yyyy").format(new Date(startedAtSeconds * 1000L));
    }

    /** The trip as CSV text, the same shape the old files had. */
    static String csv(Track.Fixes fixes) {
        StringBuilder sb = new StringBuilder(fixes.n * 31);
        for (int i = 0; i < fixes.n; i++) {
            sb.append(fixes.t[i])
                    .append(',')
                    .append(String.format(Locale.US, "%.6f", Track.degrees(fixes.lat[i])))
                    .append(',')
                    .append(String.format(Locale.US, "%.6f", Track.degrees(fixes.lon[i])))
                    .append('\n');
        }
        return sb.toString();
    }

    /** The bytes that get uploaded or exported. */
    static byte[] build(Track.Fixes fixes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(csv(fixes).getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    /** Reads an artifact back, for restoring a trip or for a test. */
    static Track.Fixes parse(byte[] artifact) throws IOException {
        try (GZIPInputStream gz = new GZIPInputStream(new java.io.ByteArrayInputStream(artifact))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = gz.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return Migration.parse(
                    new java.io.StringReader(
                            new String(out.toByteArray(), StandardCharsets.UTF_8)));
        }
    }

    /** Lower-case hex SHA-256, used to tell "already uploaded" from "uploaded differently". */
    static String sha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JRE", e);
        }
    }
}
