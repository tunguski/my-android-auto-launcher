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

    /** The same, against a given backend, so a test can supply one that fails. */
    static void runNow(Context c, SharedPreferences prefs, Backup backend) {
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
