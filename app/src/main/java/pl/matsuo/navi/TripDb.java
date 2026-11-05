package pl.matsuo.navi;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

/**
 * Every recorded drive, in one SQLite database.
 *
 * <p>A finished trip is a single row carrying its summary and its whole route packed by {@link
 * Track} - about six bytes a fix, against the thirty-one the one-file-per-trip CSV used. The drive
 * in progress is the exception: it accumulates one small row per fix in {@code open_fix}, because
 * appending to a blob would mean rewriting it every ten seconds, and is packed into the trip row
 * when it ends.
 *
 * <p>The database lives in internal storage. External storage would be easier to reach with a
 * cable, but a database on the emulated-storage layer is a good way to lose every trip at once
 * instead of one file; what external storage was giving is replaced by {@link Artifact} exports.
 */
final class TripDb extends SQLiteOpenHelper implements TripStore {

    static final String NAME = "navi.db";
    static final int VERSION = 1;

    /** Marks a trip the app recorded itself, as opposed to one imported or downloaded. */
    static final String SOURCE_DEVICE = "device";

    static final String SOURCE_CSV = "csv";

    private static TripDb instance;

    /** One database per process; SQLiteOpenHelper is thread-safe. */
    static synchronized TripDb of(Context c) {
        if (instance == null) {
            instance = new TripDb(c.getApplicationContext());
        }
        return instance;
    }

    /** Drops the shared instance. For tests that want a clean database. */
    static synchronized void resetForTests() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }

    TripDb(Context c) {
        this(c, NAME);
    }

    /**
     * Opens a database by name.
     *
     * <p>Only the shared one matters in the app. Tests use a name of their own so that deleting and
     * recreating the file cannot race the recording service, which holds the shared instance open
     * and writes to it every second.
     */
    TripDb(Context c, String name) {
        super(c, name, null, VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        // Has to happen before any table exists, or it does nothing: without it,
        // deleting trips leaves the file as large as it ever was.
        db.execSQL("PRAGMA auto_vacuum=INCREMENTAL");
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE trip ("
                        + "id INTEGER PRIMARY KEY,"
                        + "started_at INTEGER NOT NULL," // epoch seconds
                        + "ended_at INTEGER NOT NULL DEFAULT 0,"
                        + "points INTEGER NOT NULL DEFAULT 0,"
                        + "meters REAL NOT NULL DEFAULT 0,"
                        + "closed INTEGER NOT NULL DEFAULT 0,"
                        + "track BLOB,"
                        + "source TEXT NOT NULL DEFAULT '"
                        + SOURCE_DEVICE
                        + "',"
                        + "remote_id TEXT,"
                        + "remote_hash TEXT,"
                        + "uploaded_at INTEGER)");
        // The start of a drive identifies it, which is what makes importing the
        // old CSV files idempotent however often the migration runs.
        db.execSQL("CREATE UNIQUE INDEX trip_started ON trip(started_at)");
        db.execSQL("CREATE INDEX trip_closed ON trip(closed, started_at)");
        db.execSQL(
                "CREATE TABLE open_fix ("
                        + "trip_id INTEGER NOT NULL,"
                        + "t INTEGER NOT NULL,"
                        + "lat INTEGER NOT NULL,"
                        + "lon INTEGER NOT NULL,"
                        + "PRIMARY KEY (trip_id, t)) WITHOUT ROWID");
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int from, int to) {
        // Version 1 is the first; later versions add migrations here rather than
        // dropping anything, because these rows are the only copy of the data.
    }

    // ───────────────────────────── recording ─────────────────────────────

    @Override
    public long open(long tSeconds, int latMicro, int lonMicro) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long startedAt = tSeconds;
            long id;
            // started_at is unique. Two drives starting in the same second is
            // only possible with a confused clock, and shifting by a second
            // beats refusing to record.
            while (true) {
                ContentValues v = new ContentValues();
                v.put("started_at", startedAt);
                v.put("source", SOURCE_DEVICE);
                id = db.insert("trip", null, v);
                if (id != -1) {
                    break;
                }
                startedAt++;
            }
            insertFix(db, id, tSeconds, latMicro, lonMicro);
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public void append(long tripId, long tSeconds, int latMicro, int lonMicro) {
        insertFix(getWritableDatabase(), tripId, tSeconds, latMicro, lonMicro);
    }

    private static void insertFix(SQLiteDatabase db, long tripId, long tSeconds, int lat, int lon) {
        ContentValues v = new ContentValues();
        v.put("trip_id", tripId);
        v.put("t", tSeconds);
        v.put("lat", lat);
        v.put("lon", lon);
        // Two fixes in the same second: the later one wins rather than failing.
        db.insertWithOnConflict("open_fix", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    @Override
    public void close(long tripId) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            // Closing an already closed trip must not repack it from the working
            // rows that are no longer there, which would empty it and then delete
            // it. Resume and the gap rule can both ask twice.
            Row existing = trip(tripId);
            if (existing == null || existing.closed) {
                db.setTransactionSuccessful();
                return;
            }
            Track.Fixes fixes = openFixes(db, tripId);
            byte[] track = Track.encode(fixes.t, fixes.lat, fixes.lon, fixes.n);
            ContentValues v = new ContentValues();
            v.put("closed", 1);
            v.put("points", fixes.n);
            v.put("meters", Track.meters(fixes));
            v.put("ended_at", fixes.n > 0 ? fixes.t[fixes.n - 1] : 0);
            v.put("track", track);
            db.update("trip", v, "id=?", new String[] {Long.toString(tripId)});
            db.delete("open_fix", "trip_id=?", new String[] {Long.toString(tripId)});
            // A drive that recorded nothing leaves no trace at all.
            if (fixes.n == 0) {
                db.delete(
                        "trip",
                        "id=? AND source=?",
                        new String[] {Long.toString(tripId), SOURCE_DEVICE});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        compact(db);
    }

    /**
     * Gives back the space a finished drive stopped needing.
     *
     * <p>The working rows of a four-hour drive are a few thousand, and deleting them leaves that
     * many free pages. Two steps are needed, and both have to be a query rather than an execSQL,
     * because these pragmas return a row and {@code execSQL} quietly does nothing with them: {@code
     * incremental_vacuum} moves the free pages out, and the checkpoint is what actually shortens
     * the file, since in WAL mode everything until then lives in the write-ahead log - a log that
     * was four hundred kilobytes for a database of seventy.
     */
    private static void compact(SQLiteDatabase db) {
        for (String pragma :
                new String[] {"PRAGMA incremental_vacuum", "PRAGMA wal_checkpoint(TRUNCATE)"}) {
            try (Cursor c = db.rawQuery(pragma, null)) {
                c.moveToFirst();
            } catch (Exception ignored) {
                // Housekeeping; never worth failing a finished trip over.
            }
        }
    }

    @Override
    public Open findOpen() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c =
                db.rawQuery(
                        "SELECT id, started_at FROM trip WHERE closed=0 "
                                + "ORDER BY started_at DESC LIMIT 1",
                        null)) {
            if (!c.moveToFirst()) {
                return null;
            }
            long id = c.getLong(0);
            long startedAt = c.getLong(1);
            Track.Fixes fixes = openFixes(db, id);
            if (fixes.n == 0) {
                return new Open(id, startedAt, startedAt, 0, 0, 0, 0);
            }
            return new Open(
                    id,
                    fixes.t[0],
                    fixes.t[fixes.n - 1],
                    fixes.n,
                    Track.meters(fixes),
                    fixes.lat[fixes.n - 1],
                    fixes.lon[fixes.n - 1]);
        }
    }

    /** The fixes of a drive in progress, in order. */
    Track.Fixes openFixes(long tripId) {
        return openFixes(getReadableDatabase(), tripId);
    }

    private static Track.Fixes openFixes(SQLiteDatabase db, long tripId) {
        try (Cursor c =
                db.rawQuery(
                        "SELECT t, lat, lon FROM open_fix WHERE trip_id=? ORDER BY t",
                        new String[] {Long.toString(tripId)})) {
            int n = c.getCount();
            long[] t = new long[n];
            int[] lat = new int[n];
            int[] lon = new int[n];
            int i = 0;
            while (c.moveToNext()) {
                t[i] = c.getLong(0);
                lat[i] = c.getInt(1);
                lon[i] = c.getInt(2);
                i++;
            }
            return new Track.Fixes(t, lat, lon, n);
        }
    }

    // ───────────────────────────── reading ─────────────────────────────

    /** The summary of one trip, without its route. */
    static final class Row {
        long id;
        long startedAt; // epoch seconds
        long endedAt;
        int points;
        double meters;
        boolean closed;
        String source;
        String remoteId;
        long uploadedAt;

        long durationMillis() {
            return Math.max(0, endedAt - startedAt) * 1000L;
        }
    }

    private static final String SUMMARY =
            "SELECT id, started_at, ended_at, points, meters, closed, source, "
                    + "remote_id, uploaded_at FROM trip";

    private static Row row(Cursor c) {
        Row r = new Row();
        r.id = c.getLong(0);
        r.startedAt = c.getLong(1);
        r.endedAt = c.getLong(2);
        r.points = c.getInt(3);
        r.meters = c.getDouble(4);
        r.closed = c.getInt(5) != 0;
        r.source = c.getString(6);
        r.remoteId = c.getString(7);
        r.uploadedAt = c.isNull(8) ? 0 : c.getLong(8);
        return r;
    }

    /** Finished trips, newest first. One indexed query instead of opening every file. */
    List<Row> recent(int limit) {
        List<Row> out = new ArrayList<>();
        try (Cursor c =
                getReadableDatabase()
                        .rawQuery(
                                SUMMARY
                                        + " WHERE closed=1 AND points>1 ORDER BY started_at DESC"
                                        + " LIMIT ?",
                                new String[] {Integer.toString(limit)})) {
            while (c.moveToNext()) {
                out.add(row(c));
            }
        }
        return out;
    }

    /** One trip by id, or null. */
    Row trip(long id) {
        try (Cursor c =
                getReadableDatabase()
                        .rawQuery(SUMMARY + " WHERE id=?", new String[] {Long.toString(id)})) {
            return c.moveToFirst() ? row(c) : null;
        }
    }

    /** How many trips are stored, finished or not. */
    int count() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT count(*) FROM trip", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /**
     * Where the newest stored drive ended: {latitude, longitude}, or null when nothing is stored.
     *
     * <p>Read once when recording starts, to offer {@link Trip#armSeed} a start for the next drive.
     * Newest by start, finished or not, because a drive the previous run left open has already been
     * closed by {@link Trip#resume} if it was stale, and is the live one if it was not.
     */
    double[] lastPoint() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c =
                db.rawQuery("SELECT id, closed FROM trip ORDER BY started_at DESC LIMIT 1", null)) {
            if (!c.moveToFirst()) {
                return null;
            }
            long id = c.getLong(0);
            if (c.getInt(1) == 0) {
                // One row, by the primary key, rather than every fix of the drive:
                // this is asked for while the unit is starting up, and reading a
                // three-hundred-kilometre drive to look at its last point was
                // enough to hold the launcher off the screen.
                try (Cursor f =
                        db.rawQuery(
                                "SELECT lat, lon FROM open_fix WHERE trip_id=?"
                                        + " ORDER BY t DESC LIMIT 1",
                                new String[] {Long.toString(id)})) {
                    return f.moveToFirst()
                            ? new double[] {Track.degrees(f.getInt(0)), Track.degrees(f.getInt(1))}
                            : null;
                }
            }
            Track.Fixes fixes = Track.decode(track(id));
            if (fixes.n == 0) {
                return null;
            }
            return new double[] {
                Track.degrees(fixes.lat[fixes.n - 1]), Track.degrees(fixes.lon[fixes.n - 1])
            };
        }
    }

    /** The packed route of a finished trip, or an empty array. */
    byte[] track(long id) {
        try (Cursor c =
                getReadableDatabase()
                        .rawQuery(
                                "SELECT track FROM trip WHERE id=?",
                                new String[] {Long.toString(id)})) {
            if (c.moveToFirst() && !c.isNull(0)) {
                return c.getBlob(0);
            }
        }
        return new byte[0];
    }

    /** The route of any trip, finished or in progress, as degree pairs for drawing. */
    List<double[]> route(long id) {
        Row r = trip(id);
        if (r == null) {
            return new ArrayList<>();
        }
        return (r.closed ? Track.decode(track(id)) : openFixes(id)).latLon();
    }

    // ───────────────────────────── importing ─────────────────────────────

    /**
     * Stores a trip that came from somewhere else: an imported CSV, or a download.
     *
     * @return the new id, or -1 when a trip with that start is already stored
     */
    long importTrip(Track.Fixes fixes, String source) {
        if (fixes.n == 0) {
            return -1;
        }
        ContentValues v = new ContentValues();
        v.put("started_at", fixes.t[0]);
        v.put("ended_at", fixes.t[fixes.n - 1]);
        v.put("points", fixes.n);
        v.put("meters", Track.meters(fixes));
        v.put("closed", 1);
        v.put("track", Track.encode(fixes.t, fixes.lat, fixes.lon, fixes.n));
        v.put("source", source);
        return getWritableDatabase()
                .insertWithOnConflict("trip", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    // ───────────────────────────── backup bookkeeping ─────────────────────────────

    /** Finished trips that have not been backed up yet, oldest first. */
    List<Row> pendingBackup(int limit) {
        List<Row> out = new ArrayList<>();
        try (Cursor c =
                getReadableDatabase()
                        .rawQuery(
                                SUMMARY
                                        + " WHERE closed=1 AND points>1 AND uploaded_at IS NULL"
                                        + " ORDER BY started_at LIMIT ?",
                                new String[] {Integer.toString(limit)})) {
            while (c.moveToNext()) {
                out.add(row(c));
            }
        }
        return out;
    }

    void markBackedUp(long id, String remoteId, String hash, long whenSeconds) {
        ContentValues v = new ContentValues();
        v.put("remote_id", remoteId);
        v.put("remote_hash", hash);
        v.put("uploaded_at", whenSeconds);
        getWritableDatabase().update("trip", v, "id=?", new String[] {Long.toString(id)});
    }

    /** Forgets that anything was backed up, so the next run uploads everything again. */
    void clearBackupState() {
        getWritableDatabase()
                .execSQL("UPDATE trip SET remote_id=NULL, remote_hash=NULL, uploaded_at=NULL");
    }

    // ───────────────────────────── notes to self ─────────────────────────────

    String meta(String key, String fallback) {
        try (Cursor c =
                getReadableDatabase()
                        .rawQuery("SELECT value FROM meta WHERE key=?", new String[] {key})) {
            return c.moveToFirst() ? c.getString(0) : fallback;
        }
    }

    void setMeta(String key, String value) {
        ContentValues v = new ContentValues();
        v.put("key", key);
        v.put("value", value);
        getWritableDatabase()
                .insertWithOnConflict("meta", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }
}
