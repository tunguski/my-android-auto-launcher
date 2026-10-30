package pl.matsuo.navi;

import android.content.Context;
import android.location.Location;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Recorded trips. One CSV file per trip, one fix per line: {@code epochSeconds,latitude,longitude}.
 * About 32 bytes a fix, so a fix every ten seconds costs roughly 11 KB per hour of driving.
 *
 * <p>Files live in the private app folder on external storage, which needs no permission and stays
 * reachable with a file manager or over USB — handy for the planned sync to Google Drive.
 *
 * <p>Every method that touches storage takes the folder as an argument, with a {@link Context}
 * convenience overload on top. That keeps the whole recording rulebook — new trip after a pause,
 * noise filtering, resume after a restart — testable on a plain JVM against a temporary directory.
 */
public final class Trip {

    /** A longer pause than this starts a new trip. */
    static final long GAP_MS = 5 * 60 * 1000L;

    /** Fixes less precise than this are noise and get dropped. */
    static final float MAX_ACCURACY_M = 50f;

    /** Below this the car is standing still and the fix is not worth storing. */
    static final float MIN_STEP_M = 5f;

    /** Above this it is a GPS jump, not a driven distance. */
    static final float MAX_STEP_M = 2000f;

    /** Unknown accuracy, for callers that have no estimate. */
    static final float NO_ACCURACY = -1f;

    // Live state of the trip being recorded; read by the stats screen.
    static volatile long startedAt;
    static volatile double meters;
    static volatile int points;
    static volatile double lastLat = Double.NaN;
    static volatile double lastLon = Double.NaN;

    private static File file;
    private static long lastFixAt;
    private static double prevLat;
    private static double prevLon;
    private static boolean hasPrev;

    private Trip() {}

    static File dir(Context c) {
        File d = new File(c.getExternalFilesDir(null), "trips");
        if (!d.exists()) {
            d.mkdirs();
        }
        return d;
    }

    static File currentFile() {
        return file;
    }

    /** Forgets the live trip without touching the stored files. For tests. */
    static synchronized void forget() {
        file = null;
        lastFixAt = 0;
        hasPrev = false;
        prevLat = 0;
        prevLon = 0;
        startedAt = 0;
        meters = 0;
        points = 0;
        lastLat = Double.NaN;
        lastLon = Double.NaN;
    }

    static void resume(Context c) {
        resume(dir(c), System.currentTimeMillis());
    }

    /**
     * Picks up the most recent trip file again if its last fix is recent enough.
     *
     * <p>Without this, a restarted process (a crash, the system reclaiming the service, a
     * reinstall) would zero the figures and split one drive across several files. The five-minute
     * rule stays the same, it is just applied to what is on disk.
     */
    static synchronized void resume(File dir, long now) {
        File[] files = list(dir);
        if (files.length == 0) {
            return;
        }
        File newest = files[0];
        Summary s = summarize(newest);
        if (s.points == 0 || now - s.endedAt > GAP_MS) {
            return; // too old: the next fix opens a new trip
        }
        List<double[]> pts = read(newest);
        if (pts.isEmpty()) {
            return;
        }
        double[] tail = pts.get(pts.size() - 1);

        file = newest;
        startedAt = s.startedAt;
        meters = s.meters;
        points = s.points;
        lastFixAt = s.endedAt;
        lastLat = tail[0];
        lastLon = tail[1];
        prevLat = tail[0];
        prevLon = tail[1];
        hasPrev = true;
    }

    static void record(Context c, Location l) {
        record(
                dir(c),
                l.getTime() > 0 ? l.getTime() : System.currentTimeMillis(),
                l.getLatitude(),
                l.getLongitude(),
                l.hasAccuracy() ? l.getAccuracy() : NO_ACCURACY);
    }

    /**
     * Adds one fix to the current trip, starting a new one after a long pause.
     *
     * @return true when the fix was written to disk
     */
    static synchronized boolean record(File dir, long now, double lat, double lon, float accuracy) {
        if (accuracy >= 0 && accuracy > MAX_ACCURACY_M) {
            return false;
        }
        if (file == null || now - lastFixAt > GAP_MS) {
            startNew(dir, now);
        }
        if (hasPrev) {
            float step = Geo.distance(prevLat, prevLon, lat, lon);
            if (step < MIN_STEP_M) {
                lastFixAt = now; // parked: keep the trip alive, store nothing
                return false;
            }
            if (step <= MAX_STEP_M) {
                meters += step;
            }
        }

        append(now, lat, lon);
        prevLat = lat;
        prevLon = lon;
        hasPrev = true;
        lastFixAt = now;
        points++;
        lastLat = lat;
        lastLon = lon;
        return true;
    }

    private static void startNew(File dir, long now) {
        if (!dir.exists()) {
            dir.mkdirs();
        }
        String name =
                "trip-"
                        + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(now))
                        + ".csv";
        file = new File(dir, name);
        startedAt = now;
        meters = 0;
        points = 0;
        hasPrev = false;
    }

    private static void append(long millis, double lat, double lon) {
        try (FileWriter w = new FileWriter(file, true)) {
            w.write(String.format(Locale.US, "%d,%.6f,%.6f%n", millis / 1000, lat, lon));
        } catch (Exception ignored) {
            // A failed write costs one fix; it must never take the launcher down.
        }
    }

    /** All points of a trip file, as {latitude, longitude} pairs. */
    static List<double[]> read(File f) {
        List<double[]> out = new ArrayList<>();
        if (f == null || !f.exists()) {
            return out;
        }
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length < 3) {
                    continue;
                }
                try {
                    out.add(
                            new double[] {
                                Double.parseDouble(parts[1]), Double.parseDouble(parts[2])
                            });
                } catch (NumberFormatException ignored) {
                    // Skip a truncated line, keep the rest of the trip.
                }
            }
        } catch (Exception ignored) {
            // Unreadable file: show whatever was parsed so far.
        }
        return out;
    }

    static File[] list(Context c) {
        return list(dir(c));
    }

    /** Trip files, newest first. */
    static File[] list(File dir) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".csv"));
        if (files == null) {
            return new File[0];
        }
        Arrays.sort(files, Comparator.comparing(File::getName).reversed());
        return files;
    }

    /** What the history list shows about a stored trip. */
    static final class Summary {
        long startedAt;
        long endedAt;
        double meters;
        int points;
    }

    static Summary summarize(File f) {
        Summary s = new Summary();
        double lat = Double.NaN;
        double lon = Double.NaN;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length < 3) {
                    continue;
                }
                try {
                    long t = Long.parseLong(parts[0]) * 1000L;
                    double la = Double.parseDouble(parts[1]);
                    double lo = Double.parseDouble(parts[2]);
                    if (s.points == 0) {
                        s.startedAt = t;
                    } else {
                        float step = Geo.distance(lat, lon, la, lo);
                        if (step <= MAX_STEP_M) {
                            s.meters += step;
                        }
                    }
                    s.endedAt = t;
                    lat = la;
                    lon = lo;
                    s.points++;
                } catch (NumberFormatException ignored) {
                    // Skip a damaged line.
                }
            }
        } catch (Exception ignored) {
            // Unreadable file: return what was parsed.
        }
        return s;
    }
}
