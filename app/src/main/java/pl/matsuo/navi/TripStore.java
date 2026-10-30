package pl.matsuo.navi;

/**
 * Where a drive is written while it is being driven.
 *
 * <p>{@link Trip} decides what counts as a fix, when a drive ends and when the next one begins;
 * this is the only thing it needs from storage. Keeping the two apart means the rules can be
 * exercised on a plain JVM against an in-memory store, while {@link TripDb} is checked on a real
 * device where SQLite actually exists.
 *
 * <p>Coordinates are micro-degrees and times are epoch seconds, the units the packed track uses.
 */
interface TripStore {

    /**
     * Starts a drive at this fix.
     *
     * @return the id of the new trip
     */
    long open(long tSeconds, int latMicro, int lonMicro);

    /** Adds one fix to a drive in progress. */
    void append(long tripId, long tSeconds, int latMicro, int lonMicro);

    /** Packs a finished drive into its permanent form. Doing it twice is harmless. */
    void close(long tripId);

    /**
     * The drive a previous run left open, or null.
     *
     * <p>This is how a restarted process picks up a drive instead of splitting it: everything
     * needed to carry on counting comes back from storage.
     */
    Open findOpen();

    /** The state of a drive left in progress. */
    final class Open {
        final long id;
        final long startedAtSeconds;
        final long lastFixSeconds;
        final int points;
        final double meters;
        final int lastLatMicro;
        final int lastLonMicro;

        Open(
                long id,
                long startedAtSeconds,
                long lastFixSeconds,
                int points,
                double meters,
                int lastLatMicro,
                int lastLonMicro) {
            this.id = id;
            this.startedAtSeconds = startedAtSeconds;
            this.lastFixSeconds = lastFixSeconds;
            this.points = points;
            this.meters = meters;
            this.lastLatMicro = lastLatMicro;
            this.lastLonMicro = lastLonMicro;
        }
    }
}
