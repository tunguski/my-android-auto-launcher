package pl.matsuo.navi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An in-memory {@link TripStore}, so the recording rules can be exercised without a device.
 *
 * <p>It keeps what a real store keeps and nothing else: the fixes of each trip, in order, and
 * whether the trip has been closed. That is enough to assert every rule {@link Trip} implements,
 * and the SQLite implementation is checked separately on a device.
 */
final class FakeStore implements TripStore {

    /** One trip as this store sees it. */
    static final class Stored {
        final long id;

        /** When the trip was opened, which outlives its fixes being dropped. */
        final long openedAt;

        final List<long[]> fixes = new ArrayList<>(); // {seconds, latMicro, lonMicro}
        boolean closed;

        Stored(long id, long openedAt) {
            this.id = id;
            this.openedAt = openedAt;
        }

        int points() {
            return fixes.size();
        }

        long startedAt() {
            return fixes.isEmpty() ? openedAt : fixes.get(0)[0];
        }

        long endedAt() {
            return fixes.isEmpty() ? 0 : fixes.get(fixes.size() - 1)[0];
        }
    }

    final Map<Long, Stored> trips = new LinkedHashMap<>();
    private long nextId = 1;

    /** Counts calls, so a test can prove a trip was not closed twice. */
    int closeCalls;

    @Override
    public long open(long tSeconds, int latMicro, int lonMicro) {
        Stored s = new Stored(nextId++, tSeconds);
        s.fixes.add(new long[] {tSeconds, latMicro, lonMicro});
        trips.put(s.id, s);
        return s.id;
    }

    @Override
    public void append(long tripId, long tSeconds, int latMicro, int lonMicro) {
        Stored s = trips.get(tripId);
        if (s == null) {
            throw new IllegalStateException("append to a trip that was never opened: " + tripId);
        }
        s.fixes.add(new long[] {tSeconds, latMicro, lonMicro});
    }

    @Override
    public void close(long tripId) {
        closeCalls++;
        Stored s = trips.get(tripId);
        if (s != null) {
            s.closed = true;
        }
    }

    @Override
    public Open findOpen() {
        Stored newest = null;
        for (Stored s : trips.values()) {
            if (!s.closed && (newest == null || s.id > newest.id)) {
                newest = s;
            }
        }
        if (newest == null) {
            return null;
        }
        if (newest.fixes.isEmpty()) {
            // The same contract TripDb has: a trip opened but never written to
            // still knows when it was opened.
            return new Open(newest.id, newest.openedAt, newest.openedAt, 0, 0, 0, 0);
        }
        long[] last = newest.fixes.get(newest.fixes.size() - 1);
        double meters = 0;
        for (int i = 1; i < newest.fixes.size(); i++) {
            long[] a = newest.fixes.get(i - 1);
            long[] b = newest.fixes.get(i);
            meters +=
                    Geo.distance(
                            Track.degrees((int) a[1]),
                            Track.degrees((int) a[2]),
                            Track.degrees((int) b[1]),
                            Track.degrees((int) b[2]));
        }
        return new Open(
                newest.id,
                newest.startedAt(),
                last[0],
                newest.points(),
                meters,
                (int) last[1],
                (int) last[2]);
    }

    // ───────────────────────────── for assertions ─────────────────────────────

    int tripCount() {
        return trips.size();
    }

    Stored newest() {
        Stored newest = null;
        for (Stored s : trips.values()) {
            if (newest == null || s.id > newest.id) {
                newest = s;
            }
        }
        return newest;
    }

    Stored get(long id) {
        return trips.get(id);
    }

    int closedCount() {
        int n = 0;
        for (Stored s : trips.values()) {
            if (s.closed) {
                n++;
            }
        }
        return n;
    }

    /** Pretends the process restarted: the store survives, the live state does not. */
    void restart() {
        Trip.forget();
    }
}
