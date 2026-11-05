package pl.matsuo.navi;

import android.location.Location;

/**
 * The rules of recording: which fixes count, how far the car has gone, and when one drive ends and
 * the next begins.
 *
 * <p>Nothing here knows about SQLite. Everything that touches storage goes through {@link
 * TripStore}, which keeps these rules testable on a plain JVM and lets the database be checked
 * separately on a device.
 *
 * <p>The live figures are static because there is exactly one drive in progress in one process: the
 * service records it and the trip screen reads it.
 */
public final class Trip {

    /** A longer pause than this ends the drive; the next fix starts a new one. */
    static final long GAP_MS = 5 * 60 * 1000L;

    /** Fixes less precise than this are noise and get dropped. */
    static final float MAX_ACCURACY_M = 50f;

    /** Below this the car is standing still and the fix is not worth storing. */
    static final float MIN_STEP_M = 5f;

    /** Above this it is a GPS jump, not a driven distance. */
    static final float MAX_STEP_M = 2000f;

    /** Unknown accuracy, for callers that have no estimate. */
    static final float NO_ACCURACY = -1f;

    /** Unknown speed, for callers and for fixes that do not report one. */
    static final float NO_SPEED = -1f;

    /**
     * Below this the car is not moving, whatever the position says.
     *
     * <p>A standing car with a noisy GPS wanders. The distance rule alone caps how far it can
     * wander between two stored fixes, but not how often, and recording every second rather than
     * every ten gives it ten times the opportunities to random-walk a parked car down the road. A
     * fix that reports its own speed settles it outright.
     */
    static final float MIN_SPEED_MS = 0.6f;

    /**
     * How long after recording starts the last known parking spot may still begin a drive.
     *
     * <p>A cold receiver needs the best part of a minute to find itself, and whatever the car drove
     * in that minute is lost: the drive appears to begin wherever the first fix lands, a kilometre
     * or two down the road. The car was not there, though - it was where it parked. Within this
     * window that spot is a better start than the first fix.
     */
    static final long SEED_WINDOW_MS = 3 * 60 * 1000L;

    /**
     * How far from the parking spot the first fix may be, per minute of waiting.
     *
     * <p>This is what tells a drive that began where it parked from one that did not begin here at
     * all: a car on a trailer, or a unit that sat in a garage while the fix it finally got came
     * from somewhere else entirely. Two kilometres a minute is generous for a car pulling out of a
     * parking space and mean enough to reject a jump.
     */
    static final float SEED_DRIFT_M_PER_MIN = 2000f;

    // The drive in progress, read by the trip screen.
    static volatile long startedAt; // epoch millis
    static volatile double meters;
    static volatile int points;
    static volatile double lastLat = Double.NaN;
    static volatile double lastLon = Double.NaN;

    private static long tripId; // 0 when no drive is open
    private static long lastFixAt; // epoch millis
    private static double prevLat;
    private static double prevLon;
    private static boolean hasPrev;

    // Where the last drive ended, offered as the start of the next one.
    private static boolean seedArmed;
    private static long seedFrom; // when recording started, epoch millis
    private static double seedLat;
    private static double seedLon;

    private Trip() {}

    /** The drive being recorded, or 0. */
    static synchronized long currentId() {
        return tripId;
    }

    /** Forgets the live drive without touching what is stored. For tests and restarts. */
    static synchronized void forget() {
        tripId = 0;
        lastFixAt = 0;
        hasPrev = false;
        prevLat = 0;
        prevLon = 0;
        startedAt = 0;
        meters = 0;
        points = 0;
        lastLat = Double.NaN;
        lastLon = Double.NaN;
        seedArmed = false;
    }

    /**
     * Offers the spot where the last drive ended as the start of the next one.
     *
     * <p>Called when recording starts and no drive was resumed. Nothing is stored yet: the spot is
     * only used if the first fix to arrive agrees that the car was still standing there, by {@link
     * #seedApplies}. One drive gets to use it, and only its first fix.
     *
     * @param startedListeningAt when recording started, epoch millis
     */
    static synchronized void armSeed(long startedListeningAt, double lat, double lon) {
        seedArmed = true;
        seedFrom = startedListeningAt;
        seedLat = lat;
        seedLon = lon;
    }

    /**
     * Whether the parking spot may begin the drive that this fix belongs to.
     *
     * <p>Two conditions, both necessary. The fix came soon enough after recording started that the
     * car cannot have been anywhere else in between - this is a cold receiver being slow, not a
     * drive that started elsewhere. And the car is near enough to the parking spot for the wait:
     * every minute of waiting allows another {@link #SEED_DRIFT_M_PER_MIN}, rounded up, because a
     * minute of waiting is a minute of driving away from where it parked.
     */
    static boolean seedApplies(
            long startedListeningAt,
            long nowMillis,
            double fromLat,
            double fromLon,
            double lat,
            double lon) {
        long waited = nowMillis - startedListeningAt;
        if (waited < 0 || waited > SEED_WINDOW_MS) {
            return false;
        }
        long minutes = Math.max(1, (waited + 59_999) / 60_000); // rounded up, never zero
        return Geo.distance(fromLat, fromLon, lat, lon) < SEED_DRIFT_M_PER_MIN * minutes;
    }

    /**
     * Picks up a drive a previous run left open.
     *
     * <p>Without this a restarted process - a crash, the system reclaiming the service, an update -
     * would zero the figures and split one drive into two. A drive whose last fix is already older
     * than the gap is closed instead, so it lands in the history rather than staying open forever.
     */
    static synchronized void resume(TripStore store, long nowMillis) {
        TripStore.Open open = store.findOpen();
        if (open == null) {
            return;
        }
        if (open.points == 0) {
            // Opened but never written to; adopt it so the next fix has a home.
            tripId = open.id;
            startedAt = open.startedAtSeconds * 1000L;
            lastFixAt = open.startedAtSeconds * 1000L;
            return;
        }
        if (nowMillis - open.lastFixSeconds * 1000L > GAP_MS) {
            store.close(open.id);
            return;
        }
        tripId = open.id;
        startedAt = open.startedAtSeconds * 1000L;
        lastFixAt = open.lastFixSeconds * 1000L;
        meters = open.meters;
        points = open.points;
        lastLat = Track.degrees(open.lastLatMicro);
        lastLon = Track.degrees(open.lastLonMicro);
        prevLat = lastLat;
        prevLon = lastLon;
        hasPrev = true;
    }

    /**
     * Offers one fix to the recorder.
     *
     * @param nowMillis when the fix was taken
     * @param accuracy metres, or {@link #NO_ACCURACY}
     * @return true when the fix was stored
     */
    static synchronized boolean record(
            TripStore store, long nowMillis, double lat, double lon, float accuracy) {
        return record(store, nowMillis, lat, lon, accuracy, NO_SPEED);
    }

    /**
     * Offers one fix to the recorder.
     *
     * @param nowMillis when the fix was taken
     * @param accuracy metres, or {@link #NO_ACCURACY}
     * @param speed metres a second, or {@link #NO_SPEED}
     * @return true when the fix was stored
     */
    static synchronized boolean record(
            TripStore store, long nowMillis, double lat, double lon, float accuracy, float speed) {
        if (accuracy >= 0 && accuracy > MAX_ACCURACY_M) {
            return false;
        }
        // Standing still, by the receiver's own reckoning. The drive stays open.
        if (speed >= 0 && speed < MIN_SPEED_MS && tripId != 0) {
            lastFixAt = nowMillis;
            return false;
        }
        if (tripId == 0 || nowMillis - lastFixAt > GAP_MS) {
            if (tripId != 0) {
                store.close(tripId);
            }
            if (seedArmed && seedApplies(seedFrom, nowMillis, seedLat, seedLon, lat, lon)) {
                // Begin where the car parked, at the moment recording started, and
                // let this fix be the second point. That restores both the missing
                // leg and the missing minutes: without it the drive would appear to
                // start wherever the receiver happened to find itself.
                seedArmed = false;
                tripId = store.open(seedFrom / 1000L, Track.micro(seedLat), Track.micro(seedLon));
                startedAt = seedFrom;
                points = 1;
                setLast(seedFrom, seedLat, seedLon);
                // Counted whatever its length, unlike an ordinary step: both ends
                // are known positions, so this is a driven leg and not a GPS jump.
                meters = Geo.distance(seedLat, seedLon, lat, lon);
                store.append(tripId, nowMillis / 1000L, Track.micro(lat), Track.micro(lon));
                points++;
                setLast(nowMillis, lat, lon);
                return true;
            }
            seedArmed = false;
            tripId = store.open(nowMillis / 1000L, Track.micro(lat), Track.micro(lon));
            startedAt = nowMillis;
            meters = 0;
            points = 1;
            setLast(nowMillis, lat, lon);
            return true;
        }
        if (hasPrev) {
            float step = Geo.distance(prevLat, prevLon, lat, lon);
            if (step < MIN_STEP_M) {
                lastFixAt = nowMillis; // parked: keep the drive alive, store nothing
                return false;
            }
            if (step <= MAX_STEP_M) {
                meters += step;
            }
        }
        store.append(tripId, nowMillis / 1000L, Track.micro(lat), Track.micro(lon));
        points++;
        setLast(nowMillis, lat, lon);
        return true;
    }

    private static void setLast(long nowMillis, double lat, double lon) {
        prevLat = lat;
        prevLon = lon;
        hasPrev = true;
        lastFixAt = nowMillis;
        lastLat = lat;
        lastLon = lon;
    }

    /** Ends the drive in progress, if there is one, so it joins the history. */
    static synchronized void closeCurrent(TripStore store) {
        if (tripId != 0) {
            store.close(tripId);
        }
        forget();
    }

    // ───────────────────────────── the Android side ─────────────────────────────

    static void record(TripDb db, Location l) {
        record(
                db,
                l.getTime() > 0 ? l.getTime() : System.currentTimeMillis(),
                l.getLatitude(),
                l.getLongitude(),
                l.hasAccuracy() ? l.getAccuracy() : NO_ACCURACY,
                l.hasSpeed() ? l.getSpeed() : NO_SPEED);
    }
}
