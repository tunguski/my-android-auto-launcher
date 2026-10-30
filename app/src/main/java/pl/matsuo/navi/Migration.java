package pl.matsuo.navi;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Moves the trips of earlier versions - one CSV file each - into the database.
 *
 * <p>Three rules make this safe to run on a car that is about to be driven:
 *
 * <ul>
 *   <li><b>Nothing is deleted.</b> An imported file moves to {@code trips/imported/}, where the
 *       owner can still read it with a file manager and delete it from the settings once satisfied.
 *   <li><b>It is idempotent.</b> A trip is identified by the second it started, so running the
 *       migration twice, or interrupting it and starting again, imports nothing twice.
 *   <li><b>A trip counts only once it has been read back.</b> Every imported track is decoded from
 *       the database and compared with the file it came from before that file is moved aside.
 * </ul>
 *
 * <p>A file that cannot be read is left exactly where it is and noted; it never stops the
 * migration, and it never stops the launcher from starting.
 */
final class Migration {

    /** Where imported files are kept, under the old trips folder. */
    static final String IMPORTED = "imported";

    /** Set in {@code meta} once every file has been accounted for. */
    static final String META_DONE = "csv_import_done";

    /** Counts files the migration could not read, for the settings screen. */
    static final String META_FAILED = "csv_import_failed";

    /** What a run did. */
    static final class Result {
        int imported;
        int alreadyThere;
        int failed;

        int seen() {
            return imported + alreadyThere + failed;
        }
    }

    private Migration() {}

    /**
     * Reads one trip CSV.
     *
     * <p>The format is the one earlier versions wrote: {@code epochSeconds,latitude,longitude}, one
     * fix a line. Damaged lines are skipped, exactly as the old reader skipped them, so a file
     * truncated by a power cut still yields the drive up to that point.
     */
    static Track.Fixes parse(Reader reader) throws IOException {
        int capacity = 64;
        long[] t = new long[capacity];
        int[] lat = new int[capacity];
        int[] lon = new int[capacity];
        int n = 0;

        BufferedReader in =
                reader instanceof BufferedReader
                        ? (BufferedReader) reader
                        : new BufferedReader(reader);
        String line;
        while ((line = in.readLine()) != null) {
            String[] parts = line.split(",");
            if (parts.length < 3) {
                continue;
            }
            try {
                long seconds = Long.parseLong(parts[0].trim());
                int latMicro = Track.micro(Double.parseDouble(parts[1].trim()));
                int lonMicro = Track.micro(Double.parseDouble(parts[2].trim()));
                if (n == capacity) {
                    capacity *= 2;
                    t = Arrays.copyOf(t, capacity);
                    lat = Arrays.copyOf(lat, capacity);
                    lon = Arrays.copyOf(lon, capacity);
                }
                t[n] = seconds;
                lat[n] = latMicro;
                lon[n] = lonMicro;
                n++;
            } catch (NumberFormatException ignored) {
                // A truncated or damaged line costs one fix, not the trip.
            }
        }
        return new Track.Fixes(t, lat, lon, n);
    }

    /** True when what was stored matches what was read from the file. */
    static boolean matches(Track.Fixes from, Track.Fixes stored) {
        if (from.n != stored.n) {
            return false;
        }
        if (from.n == 0) {
            return true;
        }
        return from.t[0] == stored.t[0]
                && from.t[from.n - 1] == stored.t[stored.n - 1]
                && from.lat[0] == stored.lat[0]
                && from.lon[0] == stored.lon[0]
                && from.lat[from.n - 1] == stored.lat[stored.n - 1]
                && from.lon[from.n - 1] == stored.lon[stored.n - 1];
    }

    /** Where earlier versions kept one CSV file per trip. */
    static File csvDir(android.content.Context c) {
        return new File(c.getExternalFilesDir(null), "trips");
    }

    /** Trip CSV files waiting to be imported, oldest first. */
    static File[] pending(File csvDir) {
        File[] files = csvDir.listFiles((d, name) -> name.endsWith(".csv"));
        if (files == null) {
            return new File[0];
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        return files;
    }

    /**
     * Imports every CSV trip in {@code csvDir} that is not in the database yet.
     *
     * <p>Safe to call on every start: when there is nothing to do it costs one directory listing.
     */
    static Result run(File csvDir, TripDb db) {
        Result result = new Result();
        File[] files = pending(csvDir);
        if (files.length == 0) {
            db.setMeta(META_DONE, "1");
            return result;
        }
        File done = new File(csvDir, IMPORTED);
        for (File f : files) {
            try {
                if (importOne(f, done, db, result)) {
                    continue;
                }
            } catch (Exception e) {
                // Unreadable file, full disk, anything: note it and carry on.
            }
            result.failed++;
        }
        db.setMeta(META_FAILED, Integer.toString(result.failed));
        if (result.failed == 0) {
            db.setMeta(META_DONE, "1");
        }
        return result;
    }

    private static boolean importOne(File f, File done, TripDb db, Result result)
            throws IOException {
        Track.Fixes fixes;
        try (FileReader r = new FileReader(f)) {
            fixes = parse(r);
        }
        if (fixes.n == 0) {
            // Nothing in it; move it aside so it is not examined again.
            result.alreadyThere++;
            return moveAside(f, done);
        }
        long id = db.importTrip(fixes, TripDb.SOURCE_CSV);
        if (id == -1) {
            result.alreadyThere++; // the same drive is already stored
            return moveAside(f, done);
        }
        if (!matches(fixes, Track.decode(db.track(id)))) {
            return false; // leave the file alone; the row stays, the file is the backup
        }
        result.imported++;
        return moveAside(f, done);
    }

    private static boolean moveAside(File f, File done) {
        if (!done.exists() && !done.mkdirs()) {
            return false;
        }
        File target = new File(done, f.getName());
        return f.renameTo(target) || (target.exists() && f.delete());
    }

    /** How much space the imported CSV files still occupy, for the settings screen. */
    static long importedBytes(File csvDir) {
        File[] files = new File(csvDir, IMPORTED).listFiles();
        long sum = 0;
        if (files != null) {
            for (File f : files) {
                sum += f.length();
            }
        }
        return sum;
    }

    /** Deletes the imported CSV files. Only ever called from an explicit settings action. */
    static int deleteImported(File csvDir) {
        File[] files = new File(csvDir, IMPORTED).listFiles();
        int deleted = 0;
        if (files != null) {
            for (File f : files) {
                if (f.delete()) {
                    deleted++;
                }
            }
        }
        return deleted;
    }
}
