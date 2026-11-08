package pl.matsuo.navi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * The sun rules, against canned readings.
 *
 * <p>Every case here is one that cannot be arranged on a running device: three in the morning, a
 * forecast that has run out, a reading a week old, and a reading from a version that never asked
 * for sun times. That is the whole reason the rules live in a class with no context in it.
 */
public class SunTest {

    /** Warsaw in November: up at 06:45, down at 16:10, an hour ahead of UTC. */
    private static final int OFFSET_S = 3600;

    private static long at(String local) {
        return LocalDateTime.parse(local)
                .atZone(ZoneOffset.ofTotalSeconds(OFFSET_S))
                .toInstant()
                .toEpochMilli();
    }

    /** A reading shaped like Open-Meteo's, with as many days of sun times as asked for. */
    private static JSONObject reading(int days) throws Exception {
        JSONArray rise = new JSONArray();
        JSONArray set = new JSONArray();
        for (int d = 0; d < days; d++) {
            rise.put(String.format("2026-11-%02dT06:45", 8 + d));
            set.put(String.format("2026-11-%02dT16:10", 8 + d));
        }
        JSONObject daily = new JSONObject();
        daily.put("sunrise", rise);
        daily.put("sunset", set);
        JSONObject weather = new JSONObject();
        weather.put("utc_offset_seconds", OFFSET_S);
        weather.put("daily", daily);
        return weather;
    }

    private static final long DAY_MS = 24 * 60 * 60 * 1000L;

    // ───────────────────────────── which phase it is ─────────────────────────────

    @Test
    public void betweenSunriseAndSunsetItIsDay() throws Exception {
        JSONObject wx = reading(6);
        assertEquals(Sun.Phase.DAY, Sun.phase(wx, at("2026-11-08T12:00")));
        assertEquals(Sun.Phase.DAY, Sun.phase(wx, at("2026-11-08T06:46")));
        assertEquals(Sun.Phase.DAY, Sun.phase(wx, at("2026-11-08T16:09")));
    }

    @Test
    public void afterSunsetItIsNight() throws Exception {
        JSONObject wx = reading(6);
        assertEquals(Sun.Phase.NIGHT, Sun.phase(wx, at("2026-11-08T16:11")));
        assertEquals(Sun.Phase.NIGHT, Sun.phase(wx, at("2026-11-08T23:30")));
    }

    @Test
    public void theSmallHoursBeforeTheFirstSunriseAreNight() throws Exception {
        // The case that cannot be staged on a device, and the one a forecast is
        // least able to answer: it begins at midnight, so there is no earlier
        // crossing in it at all. Before the first sunrise it is night by
        // definition, and saying so is not a guess.
        assertEquals(Sun.Phase.NIGHT, Sun.phase(reading(6), at("2026-11-08T03:00")));
    }

    @Test
    public void theSecondDayIsReadFromItsOwnTimes() throws Exception {
        JSONObject wx = reading(6);
        assertEquals(Sun.Phase.NIGHT, Sun.phase(wx, at("2026-11-09T05:00")));
        assertEquals(Sun.Phase.DAY, Sun.phase(wx, at("2026-11-09T09:00")));
        assertEquals(Sun.Phase.NIGHT, Sun.phase(wx, at("2026-11-09T18:00")));
    }

    // ───────────────────────── when it refuses to answer ─────────────────────────

    @Test
    public void aReadingWithoutSunTimesSaysNothing() throws Exception {
        // What every canned reading the screen tests use looks like, and what an
        // install upgraded from a version that did not ask for sun times has
        // stored. It must leave the screen alone rather than guess at it.
        JSONObject wx = new JSONObject();
        wx.put("daily", new JSONObject().put("weather_code", new JSONArray().put(0)));
        assertEquals(Sun.Phase.UNKNOWN, Sun.phase(wx, at("2026-11-08T12:00")));
        assertEquals(Long.MIN_VALUE, Sun.lastChangeAtOrBefore(wx, at("2026-11-08T12:00")));
    }

    @Test
    public void nothingAtAllSaysNothing() throws Exception {
        assertEquals(Sun.Phase.UNKNOWN, Sun.phase(null, at("2026-11-08T12:00")));
        assertEquals(Sun.Phase.UNKNOWN, Sun.phase(new JSONObject(), at("2026-11-08T12:00")));
    }

    @Test
    public void pastTheEndOfTheForecastItSaysNothing() throws Exception {
        // A six-day forecast has nothing to say about the eighth day, and
        // carrying its last evening forward would be dimming by a week-old
        // guess rather than by the sun.
        JSONObject wx = reading(6);
        assertEquals(Sun.Phase.NIGHT, Sun.phase(wx, at("2026-11-13T20:00")));
        assertEquals(Sun.Phase.UNKNOWN, Sun.phase(wx, at("2026-11-16T20:00")));
    }

    @Test
    public void anUnreadableStampCostsOneEventAndNotTheWeek() throws Exception {
        JSONObject wx = reading(6);
        wx.getJSONObject("daily").getJSONArray("sunrise").put(0, "not a time");
        // The ruined sunrise is gone, so the first thing the reading knows about
        // is that day's sunset - a shape this deliberately will not guess at.
        assertEquals(Sun.Phase.UNKNOWN, Sun.phase(wx, at("2026-11-08T12:00")));
        // The rest of the week still answers.
        assertEquals(Sun.Phase.DAY, Sun.phase(wx, at("2026-11-09T09:00")));
    }

    // ───────────────────────────── how old is too old ─────────────────────────────

    @Test
    public void aReadingStaysUsableForAWeek() throws Exception {
        long now = at("2026-11-08T12:00");
        assertFalse(Sun.tooOldToUse(now - 60_000L, now));
        assertFalse(Sun.tooOldToUse(now - 6 * DAY_MS, now));
        assertTrue(Sun.tooOldToUse(now - 7 * DAY_MS, now));
        assertTrue(Sun.tooOldToUse(now - 30 * DAY_MS, now));
    }

    @Test
    public void aReadingOfUnknownAgeIsTooOld() throws Exception {
        // An install upgraded from a version that stored no timestamp. Treating
        // "no idea" as fresh would dim the screen by a reading of any age.
        long now = at("2026-11-08T12:00");
        assertTrue(Sun.tooOldToUse(0L, now));
        assertTrue(Sun.tooOldToUse(-1L, now));
    }

    // ────────────────────── the driver's own choice standing ──────────────────────

    @Test
    public void theLastCrossingIsTheOneBeforeNow() throws Exception {
        JSONObject wx = reading(6);
        assertEquals(at("2026-11-08T06:45"), Sun.lastChangeAtOrBefore(wx, at("2026-11-08T12:00")));
        assertEquals(at("2026-11-08T16:10"), Sun.lastChangeAtOrBefore(wx, at("2026-11-08T20:00")));
        assertEquals(at("2026-11-09T06:45"), Sun.lastChangeAtOrBefore(wx, at("2026-11-09T07:00")));
    }

    @Test
    public void aTapAfterTheLastCrossingStandsAndOneBeforeItDoesNot() throws Exception {
        // The rule the screen applies, stated in the terms it applies it in: the
        // tap stands while no sun event has happened since it.
        JSONObject wx = reading(6);
        long evening = at("2026-11-08T20:00");
        long crossing = Sun.lastChangeAtOrBefore(wx, evening);

        long tappedAfterSunset = at("2026-11-08T18:00");
        assertTrue("a tap made after sunset should still stand", crossing <= tappedAfterSunset);

        long tappedBeforeSunset = at("2026-11-08T14:00");
        assertTrue("sunset has happened since the tap", crossing > tappedBeforeSunset);
    }

    @Test
    public void inTheSmallHoursAChoiceStandsUntilDawn() throws Exception {
        // Documented rather than merely tolerated: the forecast carries no
        // crossing before its first midnight, so a choice made at two in the
        // morning holds until the sun comes up. That is the conservative
        // direction - the screen keeps what it was asked for.
        JSONObject wx = reading(6);
        assertEquals(Long.MIN_VALUE, Sun.lastChangeAtOrBefore(wx, at("2026-11-08T02:00")));
        assertEquals(at("2026-11-08T06:45"), Sun.lastChangeAtOrBefore(wx, at("2026-11-08T07:00")));
    }
}
