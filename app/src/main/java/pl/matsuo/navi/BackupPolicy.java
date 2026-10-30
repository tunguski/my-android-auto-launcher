package pl.matsuo.navi;

/**
 * When it is worth trying to back up again.
 *
 * <p>A head unit spends most of its life without a usable network, so failure is the normal case,
 * not the exception. Retrying every ten seconds would burn battery and fill the log; this backs off
 * to six-hourly and stays there until something succeeds.
 *
 * <p>Pure arithmetic, so the schedule is unit tested rather than observed over a day.
 */
final class BackupPolicy {

    /** Waits after 0, 1, 2, ... consecutive failures. */
    static final long[] BACKOFF_MS = {
        0L,
        60_000L, // a minute
        5 * 60_000L,
        15 * 60_000L,
        60 * 60_000L,
        6 * 60 * 60_000L, // and no slower than this
    };

    /** How many trips one run uploads before leaving the rest for the next. */
    static final int BATCH = 10;

    private BackupPolicy() {}

    /** The wait after this many consecutive failures. */
    static long backoff(int failures) {
        if (failures <= 0) {
            return BACKOFF_MS[0];
        }
        return BACKOFF_MS[Math.min(failures, BACKOFF_MS.length - 1)];
    }

    /** True when enough time has passed since the last attempt to try again. */
    static boolean due(int failures, long lastAttemptMillis, long nowMillis) {
        if (lastAttemptMillis <= 0) {
            return true;
        }
        // A clock that jumped backwards must not postpone the next attempt for ever.
        if (nowMillis < lastAttemptMillis) {
            return true;
        }
        return nowMillis - lastAttemptMillis >= backoff(failures);
    }
}
