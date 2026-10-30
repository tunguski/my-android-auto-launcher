package pl.matsuo.navi;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The packed form of a driven route: the whole track of a trip as one blob.
 *
 * <p>Format NVT v1, big-endian:
 *
 * <pre>
 *  0  "NVT" 0x01     magic and version
 *  4  int32  lat0    first fix, micro-degrees
 *  8  int32  lon0
 * 12  int64  t0      first fix, epoch seconds
 * 20  one record per later fix:
 *       int16 dlat                 micro-degrees from the previous fix
 *       if dlat == -32768:         escape, an absolute fix follows
 *         int32 lat, int32 lon
 *       else:
 *         int16 dlon
 *       uint8 dt                   seconds from the previous fix
 *       if dt == 255:              escape, the real gap follows
 *         uint16 dt
 * </pre>
 *
 * <p>Five bytes a fix, against the 31 the one-line-per-fix CSV costs. Micro-degrees hold the six
 * decimal places the CSV wrote, so nothing is lost on the way in. The deltas have room: the widest
 * step in the project fixtures is under 4,000 micro-degrees against a limit of 32,767, which is
 * about 890 km/h at a fix every ten seconds, and the escape covers a GPS glitch beyond that.
 *
 * <p>No Android type appears here, so the format is exercised by plain unit tests.
 */
final class Track {

    /** Degrees to the stored integer unit. */
    static final double SCALE = 1e6;

    static final int VERSION = 1;
    private static final int MAGIC_0 = 'N';
    private static final int MAGIC_1 = 'V';
    private static final int MAGIC_2 = 'T';

    /** Header: magic, first position, first timestamp. */
    static final int HEADER = 20;

    /** A dlat of this value means an absolute fix follows instead of a delta. */
    static final short ESCAPE_FIX = Short.MIN_VALUE;

    /** A dt of this value means the real gap follows as a uint16. */
    static final int ESCAPE_DT = 255;

    /** The largest gap a long-form dt can carry, in seconds. */
    static final int MAX_DT = 65535;

    private Track() {}

    /** Degrees to micro-degrees, the unit everything below uses. */
    static int micro(double degrees) {
        return (int) Math.round(degrees * SCALE);
    }

    /** Micro-degrees back to degrees. */
    static double degrees(int micro) {
        return micro / SCALE;
    }

    /**
     * Packs {@code n} fixes.
     *
     * @param t seconds since the epoch, one per fix, not decreasing
     * @param lat micro-degrees
     * @param lon micro-degrees
     * @param n how many entries of each array to use
     * @return the packed track, or an empty array when there is nothing to pack
     */
    static byte[] encode(long[] t, int[] lat, int[] lon, int n) {
        if (n <= 0) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(HEADER + 5 * n);
        out.write(MAGIC_0);
        out.write(MAGIC_1);
        out.write(MAGIC_2);
        out.write(VERSION);
        writeInt(out, lat[0]);
        writeInt(out, lon[0]);
        writeLong(out, t[0]);

        for (int i = 1; i < n; i++) {
            long dlat = (long) lat[i] - lat[i - 1];
            long dlon = (long) lon[i] - lon[i - 1];
            if (dlat > Short.MAX_VALUE
                    || dlat <= Short.MIN_VALUE
                    || dlon > Short.MAX_VALUE
                    || dlon < Short.MIN_VALUE) {
                writeShort(out, ESCAPE_FIX);
                writeInt(out, lat[i]);
                writeInt(out, lon[i]);
            } else {
                writeShort(out, (int) dlat);
                writeShort(out, (int) dlon);
            }
            // A clock that jumps backwards would otherwise write a negative gap;
            // the recorder rejects out-of-order fixes, and this is the backstop.
            long dt = Math.max(0, t[i] - t[i - 1]);
            if (dt < ESCAPE_DT) {
                out.write((int) dt);
            } else {
                out.write(ESCAPE_DT);
                writeShort(out, (int) Math.min(dt, MAX_DT));
            }
        }
        return out.toByteArray();
    }

    /** What {@link #decode} gives back: parallel arrays, {@code n} entries used. */
    static final class Fixes {
        final long[] t;
        final int[] lat;
        final int[] lon;
        final int n;

        Fixes(long[] t, int[] lat, int[] lon, int n) {
            this.t = t;
            this.lat = lat;
            this.lon = lon;
            this.n = n;
        }

        /** The positions as {latitude, longitude} degree pairs, for drawing. */
        List<double[]> latLon() {
            List<double[]> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                out.add(new double[] {degrees(lat[i]), degrees(lon[i])});
            }
            return out;
        }
    }

    private static final Fixes EMPTY = new Fixes(new long[0], new int[0], new int[0], 0);

    /**
     * Unpacks a track.
     *
     * <p>A blob that is empty, truncated, or not a track at all yields no fixes rather than an
     * exception: a damaged row must cost one trip on the history strip, never the screen.
     */
    static Fixes decode(byte[] blob) {
        if (blob == null || blob.length < HEADER) {
            return EMPTY;
        }
        if ((blob[0] & 0xFF) != MAGIC_0
                || (blob[1] & 0xFF) != MAGIC_1
                || (blob[2] & 0xFF) != MAGIC_2
                || (blob[3] & 0xFF) != VERSION) {
            return EMPTY;
        }
        // Five bytes is the shortest a record can be, so this never under-allocates.
        int capacity = 1 + (blob.length - HEADER) / 5;
        long[] t = new long[capacity];
        int[] lat = new int[capacity];
        int[] lon = new int[capacity];

        lat[0] = readInt(blob, 4);
        lon[0] = readInt(blob, 8);
        t[0] = readLong(blob, 12);
        int n = 1;
        int p = HEADER;

        while (p < blob.length && n < capacity) {
            if (p + 2 > blob.length) {
                break; // truncated: keep what was read
            }
            short dlat = (short) readShort(blob, p);
            p += 2;
            if (dlat == ESCAPE_FIX) {
                if (p + 8 > blob.length) {
                    break;
                }
                lat[n] = readInt(blob, p);
                lon[n] = readInt(blob, p + 4);
                p += 8;
            } else {
                if (p + 2 > blob.length) {
                    break;
                }
                lat[n] = lat[n - 1] + dlat;
                lon[n] = lon[n - 1] + (short) readShort(blob, p);
                p += 2;
            }
            if (p >= blob.length) {
                break;
            }
            int dt = blob[p++] & 0xFF;
            if (dt == ESCAPE_DT) {
                if (p + 2 > blob.length) {
                    break;
                }
                dt = readShort(blob, p) & 0xFFFF;
                p += 2;
            }
            t[n] = t[n - 1] + dt;
            n++;
        }
        return new Fixes(t, lat, lon, n);
    }

    /** How many fixes a blob holds, without rebuilding the arrays. */
    static int points(byte[] blob) {
        return decode(blob).n;
    }

    /** Length of a packed track in metres, by the same arithmetic the recorder uses. */
    static double meters(Fixes fixes) {
        double sum = 0;
        for (int i = 1; i < fixes.n; i++) {
            sum +=
                    Geo.distance(
                            degrees(fixes.lat[i - 1]),
                            degrees(fixes.lon[i - 1]),
                            degrees(fixes.lat[i]),
                            degrees(fixes.lon[i]));
        }
        return sum;
    }

    // ───────────────────────────── byte plumbing ─────────────────────────────

    private static void writeShort(ByteArrayOutputStream out, int v) {
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write((v >> 24) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeLong(ByteArrayOutputStream out, long v) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) ((v >> shift) & 0xFF));
        }
    }

    private static int readShort(byte[] b, int p) {
        return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
    }

    private static int readInt(byte[] b, int p) {
        return ((b[p] & 0xFF) << 24)
                | ((b[p + 1] & 0xFF) << 16)
                | ((b[p + 2] & 0xFF) << 8)
                | (b[p + 3] & 0xFF);
    }

    private static long readLong(byte[] b, int p) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[p + i] & 0xFF);
        }
        return v;
    }
}
