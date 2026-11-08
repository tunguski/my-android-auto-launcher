package pl.matsuo.navi;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * When the sun rises and sets where the unit is, read out of a stored Open-Meteo reading.
 *
 * <p>No view and no context, so the rules can be unit tested against canned JSON - which matters
 * here more than usual, because the interesting cases are the small hours, a reading that has gone
 * stale in a car left standing, and a forecast that has simply run out. None of those can be
 * arranged on a running device.
 *
 * <p>Every answer is allowed to be "I cannot tell". A screen that dims itself at the wrong moment
 * on a dark road is worse than one that waits to be told, so a reading without sun times, or one
 * too old to trust, produces {@link Phase#UNKNOWN} and the caller leaves the screen alone.
 */
final class Sun {

    /** Night, day, or not knowable from what has been stored. */
    enum Phase {
        DAY,
        NIGHT,
        UNKNOWN
    }

    /**
     * How old a reading may be and still be worth using.
     *
     * <p>Sunrise moves by a couple of minutes a day, so a week-old forecast is still close enough
     * to be useful to a driver - which is the point: a unit that has been offline in a garage for
     * six days should still know roughly when it gets dark. Beyond that the drift is tens of
     * minutes and the forecast has run out of days anyway.
     */
    static final long USABLE_FOR_MS = 7 * 24 * 60 * 60 * 1000L;

    /**
     * How far past its last known sunrise or sunset a reading may be read.
     *
     * <p>Separate from the age of the reading, and about the data rather than the fetch: a six-day
     * forecast stops having anything to say after its last evening, and carrying the last phase
     * forward from there would be guessing.
     */
    static final long BEYOND_LAST_EVENT_MS = 24 * 60 * 60 * 1000L;

    private static final int SUNRISE = 0;
    private static final int SUNSET = 1;

    private Sun() {}

    /**
     * Whether a reading is too old to switch the screen by.
     *
     * <p>A reading with no recorded time counts as too old. That is what an install upgraded from a
     * version that stored no timestamp looks like, and guessing on its behalf would mean dimming by
     * a reading of unknown age.
     */
    static boolean tooOldToUse(long fetchedAt, long now) {
        return fetchedAt <= 0 || now - fetchedAt >= USABLE_FOR_MS;
    }

    /** Whether it is night where the reading was taken, as far as the reading can say. */
    static Phase phase(JSONObject weather, long now) {
        List<long[]> events = events(weather);
        if (events.isEmpty()) {
            return Phase.UNKNOWN;
        }
        long[] last = null;
        for (long[] event : events) {
            if (event[0] > now) {
                break;
            }
            last = event;
        }
        if (last == null) {
            // Before everything the forecast knows about. A forecast starts at
            // midnight of its first day, so this is that day's small hours -
            // night, if the first thing due is a sunrise. Anything else means a
            // shape this was not written for, and is not guessed at.
            return events.get(0)[1] == SUNRISE ? Phase.NIGHT : Phase.UNKNOWN;
        }
        if (now - last[0] > BEYOND_LAST_EVENT_MS) {
            return Phase.UNKNOWN;
        }
        return last[1] == SUNRISE ? Phase.DAY : Phase.NIGHT;
    }

    /**
     * When the sun last rose or set at or before {@code now}, or {@link Long#MIN_VALUE} if the
     * reading does not reach back that far.
     *
     * <p>This is what lets a driver's own choice stand. Tapping the clock is recorded with the time
     * it happened, and the automatic switch only speaks again once the sun has crossed since -
     * which is to say, when the reason for the choice has itself changed.
     *
     * <p>In the small hours of a forecast's first day there is no earlier crossing in it: the one
     * that matters is the previous evening's sunset, which the reading does not carry. MIN_VALUE is
     * returned, and the effect is that a choice made during those hours stands until the sun comes
     * up. That is the conservative direction - the screen keeps what the driver asked for - and the
     * alternative is fetching a day of history to shorten it by a few hours before dawn.
     */
    static long lastChangeAtOrBefore(JSONObject weather, long now) {
        long when = Long.MIN_VALUE;
        for (long[] event : events(weather)) {
            if (event[0] > now) {
                break;
            }
            when = event[0];
        }
        return when;
    }

    /**
     * Every sunrise and sunset in the reading, earliest first, as {@code {epochMillis, kind}}.
     *
     * <p>Empty whenever anything is missing or unreadable, which is the honest answer for the
     * canned readings the tests pin screens with and for a reading saved by a version that did not
     * ask for sun times yet.
     */
    private static List<long[]> events(JSONObject weather) {
        List<long[]> out = new ArrayList<>();
        if (weather == null) {
            return out;
        }
        JSONObject daily = weather.optJSONObject("daily");
        if (daily == null) {
            return out;
        }
        ZoneId zone = zoneOf(weather);
        collect(out, daily.optJSONArray("sunrise"), SUNRISE, zone);
        collect(out, daily.optJSONArray("sunset"), SUNSET, zone);
        Collections.sort(out, (a, b) -> Long.compare(a[0], b[0]));
        return out;
    }

    private static void collect(List<long[]> into, JSONArray times, int kind, ZoneId zone) {
        if (times == null) {
            return;
        }
        for (int i = 0; i < times.length(); i++) {
            String stamp = times.optString(i, "");
            if (stamp.isEmpty()) {
                continue;
            }
            try {
                into.add(
                        new long[] {
                            LocalDateTime.parse(stamp).atZone(zone).toInstant().toEpochMilli(), kind
                        });
            } catch (Exception ignored) {
                // A stamp that will not parse is one event missing, not a
                // reading thrown away: the rest of the week still answers.
            }
        }
    }

    /**
     * The zone the stamps are written in.
     *
     * <p>The request asks for {@code timezone=auto}, so Open-Meteo returns local times for the
     * place the reading is about and says what that offset is. Falling back to this unit's own zone
     * is right for the only case that reaches it - a car is in the same place as the unit in it -
     * and a reading saved before the offset was asked for is only a day from being replaced.
     */
    private static ZoneId zoneOf(JSONObject weather) {
        if (weather.has("utc_offset_seconds")) {
            return ZoneOffset.ofTotalSeconds(weather.optInt("utc_offset_seconds"));
        }
        return ZoneId.systemDefault();
    }
}
