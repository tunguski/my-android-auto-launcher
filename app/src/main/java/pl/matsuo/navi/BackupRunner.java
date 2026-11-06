package pl.matsuo.navi;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Copies finished trips to wherever the owner chose, in the background and out of the way.
 *
 * <p>The contract this class exists to keep: <b>recording never depends on any of it.</b> It runs
 * on its own thread, it is the only thing that talks to a {@link Backup}, it swallows every
 * throwable, and the worst a broken or unreachable backend can do is leave trips marked as not yet
 * uploaded. Switch backup off, or let it fail for a year, and the launcher still records to SQLite
 * exactly as before.
 */
final class BackupRunner {

    static final String PREF_MODE = "backup_mode";
    static final String MODE_OFF = "off";
    static final String MODE_FOLDER = "folder";
    static final String MODE_DRIVE = "drive";

    static final String PREF_FAILURES = "backup_failures";
    static final String PREF_LAST_ATTEMPT = "backup_last_attempt";
    static final String PREF_LAST_OK = "backup_last_ok";
    static final String PREF_LAST_ERROR = "backup_last_error";

    /** One thread for every upload in the process; uploads never overlap. */
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    private static volatile boolean running;

    private BackupRunner() {}

    static String mode(SharedPreferences prefs) {
        return prefs.getString(PREF_MODE, MODE_OFF);
    }

    static boolean enabled(SharedPreferences prefs) {
        return !MODE_OFF.equals(mode(prefs));
    }

    /** The backend for the chosen mode, or null when backup is off or unusable. */
    static Backup backend(Context c, SharedPreferences prefs) {
        switch (mode(prefs)) {
            case MODE_FOLDER:
                return new FolderBackup(c.getExternalFilesDir(null));
            case MODE_DRIVE:
                DriveBackup drive = new DriveBackup(c, prefs);
                return drive.configured() ? drive : null;
            default:
                return null;
        }
    }

    /**
     * Asks for a backup run.
     *
     * <p>Returns immediately. Does nothing at all when backup is off, when a run is already going,
     * or when the previous failure is still being backed off.
     */
    static void trigger(Context c, SharedPreferences prefs) {
        if (!enabled(prefs) || running) {
            return;
        }
        if (!BackupPolicy.due(
                prefs.getInt(PREF_FAILURES, 0),
                prefs.getLong(PREF_LAST_ATTEMPT, 0),
                System.currentTimeMillis())) {
            return;
        }
        final Context app = c.getApplicationContext();
        running = true;
        try {
            WORKER.execute(
                    () -> {
                        try {
                            runNow(app, prefs);
                        } catch (Throwable t) {
                            // Nothing a backup can do is worth taking the launcher down.
                            fail(prefs, String.valueOf(t.getMessage()));
                        } finally {
                            running = false;
                        }
                    });
        } catch (Throwable t) {
            running = false; // the executor refused; try again next tick
        }
    }

    /** The actual work. Package-visible so a test can run it on its own thread. */
    static void runNow(Context c, SharedPreferences prefs) {
        runNow(c, prefs, backend(c, prefs));
    }

    /**
     * Writes every stored trip into the export folder, whether it was backed up before or not.
     *
     * <p>Here rather than on the settings screen, which is where it used to be, so that it shares
     * the lock with {@link #runNow}. It did not, and the two are the app's only two writers into
     * that folder: the driver asks for an export, a fix arrives at that moment and the automatic
     * backup starts, and both write the same files. The files themselves survive that - replacing
     * one is a single atomic move - but one of the two runs is refused for its trouble, and there
     * is no reason to let the app race itself when the lock is already there.
     *
     * @return how many trips were written before anything went wrong
     */
    static synchronized int exportAll(Context c) {
        int done = 0;
        try {
            TripDb db = TripDb.of(c);
            FolderBackup folder = new FolderBackup(c.getExternalFilesDir(null));
            for (TripDb.Row r : db.recent(Integer.MAX_VALUE)) {
                byte[] artifact = Artifact.build(Track.decode(db.track(r.id)));
                folder.upload(Artifact.year(r.startedAt), Artifact.name(r.startedAt), artifact);
                done++;
            }
        } catch (Throwable ignored) {
            // Report what did get out rather than nothing at all.
        }
        return done;
    }

    /**
     * The same, against a given backend, so a test can supply one that fails.
     *
     * <p>One run at a time, whoever asks. {@link #trigger} already serialises its own runs, but a
     * caller that runs the work itself - the settings screen, a test - would otherwise overlap with
     * one already in flight, and two runs uploading the same trip can leave it marked as exported
     * with nothing on disk.
     */
    static synchronized void runNow(Context c, SharedPreferences prefs, Backup backend) {
        prefs.edit().putLong(PREF_LAST_ATTEMPT, System.currentTimeMillis()).apply();
        if (backend == null || !backend.configured()) {
            fail(prefs, c.getString(R.string.backup_not_configured));
            return;
        }
        TripDb db = TripDb.of(c);
        List<TripDb.Row> pending = db.pendingBackup(BackupPolicy.BATCH);
        int done = 0;
        for (TripDb.Row row : pending) {
            // The drive in progress is never uploaded; only closed trips are listed.
            try {
                byte[] artifact = Artifact.build(Track.decode(db.track(row.id)));
                String remoteId =
                        backend.upload(
                                Artifact.year(row.startedAt),
                                Artifact.name(row.startedAt),
                                artifact);
                db.markBackedUp(
                        row.id,
                        remoteId,
                        Artifact.sha256(artifact),
                        System.currentTimeMillis() / 1000L);
                done++;
            } catch (Exception e) {
                // Stop the run: whatever stopped this trip will stop the next.
                fail(prefs, String.valueOf(e.getMessage()));
                return;
            }
        }
        prefs.edit()
                .putInt(PREF_FAILURES, 0)
                .putString(PREF_LAST_ERROR, "")
                .putLong(PREF_LAST_OK, System.currentTimeMillis())
                .apply();
        if (done > 0 && pending.size() == BackupPolicy.BATCH) {
            // More are waiting; the next tick picks them up.
            prefs.edit().putLong(PREF_LAST_ATTEMPT, 0).apply();
        }
    }

    private static void fail(SharedPreferences prefs, String message) {
        prefs.edit()
                .putInt(PREF_FAILURES, prefs.getInt(PREF_FAILURES, 0) + 1)
                .putString(PREF_LAST_ERROR, message == null ? "" : message)
                .apply();
    }

    /** Clears the backoff so the next trigger runs at once, after a settings change. */
    static void resetBackoff(SharedPreferences prefs) {
        prefs.edit().putInt(PREF_FAILURES, 0).putLong(PREF_LAST_ATTEMPT, 0).apply();
    }

    /** How many finished trips are still waiting, for the settings screen. */
    static int waiting(TripDb db) {
        return db.pendingBackup(Integer.MAX_VALUE).size();
    }
}
