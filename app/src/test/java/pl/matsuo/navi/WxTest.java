package pl.matsuo.navi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * The warning rules, against the canned readings in {@code tools/wx}.
 *
 * <p>The same files drive the instrumented tests and the screenshots, so a rule proved here is the
 * rule the screenshots show.
 */
public class WxTest {

    /** Unit tests run with the module as the working directory. */
    static JSONObject reading(String name) throws Exception {
        File f = new File("../tools/wx/" + name + ".json");
        if (!f.exists()) {
            f = new File("tools/wx/" + name + ".json");
        }
        assertTrue("missing canned reading: " + f.getAbsolutePath(), f.exists());
        return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
    }

    private List<Integer> warnings(String name) throws Exception {
        return Wx.warnings(reading(name));
    }

    // ────────────────────────────── black ice ──────────────────────────────

    @Test
    public void calmWeatherRaisesNothing() throws Exception {
        assertFalse(Wx.iceRisk(reading("calm")));
        assertTrue(warnings("calm").isEmpty());
    }

    @Test
    public void aTemperatureAtOrBelowThreeDegreesIsBlackIce() throws Exception {
        assertTrue(Wx.iceRisk(reading("ice")));
        assertEquals(Arrays.asList(R.string.warn_ice), warnings("ice"));
    }

    @Test
    public void freezingRainWithinThreeHoursIsBlackIce() throws Exception {
        // Six degrees now, freezing rain two hours out.
        JSONObject w = reading("icerain");
        assertEquals(6.0, w.getJSONObject("current").getDouble("temperature_2m"), 0.001);
        assertTrue(Wx.iceRisk(w));
    }

    @Test
    public void theIceThresholdIsInclusive() throws Exception {
        JSONObject w = reading("calm");
        w.getJSONObject("current").put("temperature_2m", Wx.ICE_TEMP);
        assertTrue(Wx.iceRisk(w));
        w.getJSONObject("current").put("temperature_2m", Wx.ICE_TEMP + 0.1);
        assertFalse(Wx.iceRisk(w));
    }

    @Test
    public void freezingRainRightNowIsBlackIceWhateverTheTemperature() throws Exception {
        JSONObject w = reading("calm");
        w.getJSONObject("current").put("temperature_2m", 12.0).put("weather_code", 67);
        assertTrue(Wx.iceRisk(w));
    }

    @Test
    public void rainWhileTheTemperatureSitsAtTheThresholdIsBlackIce() throws Exception {
        JSONObject w = reading("calm");
        JSONObject hourly = w.getJSONObject("hourly");
        hourly.getJSONArray("temperature_2m").put(8, 2.0);
        hourly.getJSONArray("weather_code").put(8, 61);
        assertTrue(Wx.iceRisk(w));
    }

    @Test
    public void freezingRainBeyondThreeHoursIsNotYetAWarning() throws Exception {
        JSONObject w = reading("calm");
        w.getJSONObject("hourly").getJSONArray("weather_code").put(12, 66);
        assertFalse(Wx.iceRisk(w));
    }

    @Test
    public void onlyFreezingCodesCountAsFreezing() throws Exception {
        assertTrue(Wx.isFreezing(56));
        assertTrue(Wx.isFreezing(57));
        assertTrue(Wx.isFreezing(66));
        assertTrue(Wx.isFreezing(67));
        assertFalse(Wx.isFreezing(61));
        assertFalse(Wx.isFreezing(71));
        assertFalse(Wx.isFreezing(0));
    }

    // ─────────────────────────── the other warnings ───────────────────────────

    @Test
    public void aThunderstormRaisesTheStormWarning() throws Exception {
        assertEquals(Arrays.asList(R.string.warn_storm), warnings("storm"));
    }

    @Test
    public void gustsAtOrAboveSeventyRaiseTheWindWarning() throws Exception {
        assertEquals(Arrays.asList(R.string.warn_wind), warnings("wind"));

        JSONObject w = reading("calm");
        w.getJSONObject("current").put("wind_gusts_10m", Wx.GUST_KMH);
        assertEquals(Arrays.asList(R.string.warn_wind), Wx.warnings(w));
        w.getJSONObject("current").put("wind_gusts_10m", Wx.GUST_KMH - 1);
        w.getJSONObject("hourly").getJSONArray("wind_gusts_10m").put(9, Wx.GUST_KMH + 5);
        assertEquals(Arrays.asList(R.string.warn_wind), Wx.warnings(w));
    }

    @Test
    public void denseFogRaisesTheFogWarning() throws Exception {
        assertEquals(Arrays.asList(R.string.warn_fog), warnings("fog"));
    }

    @Test
    public void fogOnlyCountsForTheComingHour() throws Exception {
        JSONObject w = reading("calm");
        JSONArray vis = w.getJSONObject("hourly").getJSONArray("visibility");
        vis.put(8, 100.0); // the next hour
        assertEquals(Arrays.asList(R.string.warn_fog), Wx.warnings(w));

        w = reading("calm");
        w.getJSONObject("hourly").getJSONArray("visibility").put(10, 100.0);
        assertTrue(Wx.warnings(w).isEmpty());
    }

    @Test
    public void heavySnowRaisesIceAndSnow() throws Exception {
        assertEquals(Arrays.asList(R.string.warn_ice, R.string.warn_snow), warnings("snow"));
    }

    @Test
    public void snowfallAboveOneCentimetreAnHourIsEnough() throws Exception {
        JSONObject w = reading("calm");
        w.getJSONObject("hourly").getJSONArray("snowfall").put(9, Wx.SNOW_CM_H);
        assertEquals(Arrays.asList(R.string.warn_snow), Wx.warnings(w));
    }

    @Test
    public void allFiveWarningsComeInTheDocumentedOrder() throws Exception {
        assertEquals(
                Arrays.asList(
                        R.string.warn_ice,
                        R.string.warn_storm,
                        R.string.warn_wind,
                        R.string.warn_fog,
                        R.string.warn_snow),
                warnings("all"));
    }

    @Test
    public void everyWarningIsListedAtMostOnce() throws Exception {
        List<Integer> ws = warnings("all");
        assertEquals(ws.size(), new java.util.HashSet<>(ws).size());
    }

    // ──────────────────────────── robustness ────────────────────────────

    @Test
    public void anEmptyReadingRaisesNothingInsteadOfThrowing() throws Exception {
        assertFalse(Wx.iceRisk(new JSONObject()));
        assertTrue(Wx.warnings(new JSONObject()).isEmpty());
    }

    @Test
    public void aReadingWithoutHourlyDataStillUsesTheCurrentValues() throws Exception {
        JSONObject w = new JSONObject();
        w.put(
                "current",
                new JSONObject()
                        .put("time", "2026-01-15T07:00")
                        .put("temperature_2m", 15.0)
                        .put("weather_code", 95));
        assertEquals(Arrays.asList(R.string.warn_storm), Wx.warnings(w));
    }

    @Test
    public void aCurrentTimeOutsideTheHourlySeriesIsNotFatal() throws Exception {
        JSONObject w = reading("calm");
        w.getJSONObject("current").put("time", "2030-07-01T13:00").put("weather_code", 95);
        assertEquals(Arrays.asList(R.string.warn_storm), Wx.warnings(w));
    }

    @Test
    public void theHourIndexFindsTheMatchingSlot() throws Exception {
        JSONArray times = reading("calm").getJSONObject("hourly").getJSONArray("time");
        assertEquals(7, Wx.hourIndex(times, "2026-01-15T07:00"));
        assertEquals(0, Wx.hourIndex(times, "2026-01-15T00:00"));
        assertEquals(-1, Wx.hourIndex(times, "2026-01-16T07:00"));
    }

    // ───────────────────────── weather-code wording ─────────────────────────

    @Test
    public void everyWmoCodeGetsASentence() {
        assertEquals(R.string.wmo_clear, Wx.describe(0));
        assertEquals(R.string.wmo_mostly_clear, Wx.describe(1));
        assertEquals(R.string.wmo_partly_cloudy, Wx.describe(2));
        assertEquals(R.string.wmo_overcast, Wx.describe(3));
        assertEquals(R.string.wmo_fog, Wx.describe(45));
        assertEquals(R.string.wmo_fog, Wx.describe(48));
        assertEquals(R.string.wmo_drizzle, Wx.describe(51));
        assertEquals(R.string.wmo_drizzle, Wx.describe(57));
        assertEquals(R.string.wmo_rain, Wx.describe(63));
        assertEquals(R.string.wmo_freezing_rain, Wx.describe(66));
        assertEquals(R.string.wmo_freezing_rain, Wx.describe(67));
        assertEquals(R.string.wmo_snow, Wx.describe(75));
        assertEquals(R.string.wmo_showers, Wx.describe(81));
        assertEquals(R.string.wmo_snow_showers, Wx.describe(86));
        assertEquals(R.string.wmo_storm, Wx.describe(95));
        assertEquals(R.string.wmo_storm, Wx.describe(99));
    }

    @Test
    public void freezingRainIsNotMistakenForDrizzle() {
        // 66 and 67 fall inside the 51..67 drizzle range and must be checked first.
        assertEquals(R.string.wmo_freezing_rain, Wx.describe(66));
    }

    @Test
    public void anUnknownCodeHasNoSentence() {
        assertEquals(0, Wx.describe(7));
        assertEquals(0, Wx.describe(-1));
        assertEquals(0, Wx.describeShort(7));
    }

    @Test
    public void shortWordsCoverTheStrips() {
        assertEquals(R.string.sh_clear, Wx.describeShort(0));
        assertEquals(R.string.sh_clear, Wx.describeShort(1));
        assertEquals(R.string.sh_clouds, Wx.describeShort(2));
        assertEquals(R.string.sh_overcast, Wx.describeShort(3));
        assertEquals(R.string.sh_fog, Wx.describeShort(48));
        assertEquals(R.string.sh_rain, Wx.describeShort(63));
        assertEquals(R.string.sh_rain, Wx.describeShort(66));
        assertEquals(R.string.sh_snow, Wx.describeShort(73));
        assertEquals(R.string.sh_showers, Wx.describeShort(81));
        assertEquals(R.string.sh_snow, Wx.describeShort(86));
        assertEquals(R.string.sh_storm, Wx.describeShort(95));
    }

    @Test
    public void everyWmoCodeInTheOpenMeteoRangeIsEitherNamedOrDeliberatelyUnknown() {
        // Codes Open-Meteo actually emits must all have wording.
        int[] real = {
            0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 71, 73, 75, 77, 80, 81, 82,
            85, 86, 95, 96, 99
        };
        for (int c : real) {
            assertTrue("no sentence for code " + c, Wx.describe(c) != 0);
            assertTrue("no short word for code " + c, Wx.describeShort(c) != 0);
        }
    }

    // ──────────────────────── the hour the strip starts at ────────────────────────

    private static JSONArray times(String... iso) {
        return new JSONArray(Arrays.asList(iso));
    }

    @Test
    public void theStripStartsAtTheHourAfterThisOne() throws Exception {
        JSONArray t = times("2026-11-06T07:00", "2026-11-06T08:00", "2026-11-06T09:00");

        assertEquals(2, Wx.nextHourIndex(t, "2026-11-06T08:41"));
    }

    @Test
    public void aForecastThatStartsLaterBeginsAtItsFirstHour() throws Exception {
        JSONArray t = times("2026-11-06T15:00", "2026-11-06T16:00");

        assertEquals(0, Wx.nextHourIndex(t, "2026-11-06T08:41"));
    }

    @Test
    public void aForecastEntirelyBehindUsHasNoNextHour() throws Exception {
        // The old code fell back to index 0 here, which put the small hours of a
        // past day on screen as if they were the afternoon ahead.
        JSONArray t = times("2026-11-05T01:00", "2026-11-05T02:00", "2026-11-05T03:00");

        assertEquals(-1, Wx.nextHourIndex(t, "2026-11-06T08:41"));
    }

    @Test
    public void theLastHourOfTheSeriesLeavesNothingAfterIt() throws Exception {
        JSONArray t = times("2026-11-06T07:00", "2026-11-06T08:00");

        assertEquals(-1, Wx.nextHourIndex(t, "2026-11-06T08:00"));
    }

    @Test
    public void theCurrentHourItselfIsNeverTheFirstOneShown() throws Exception {
        JSONArray t = times("2026-11-06T08:00", "2026-11-06T09:00");

        assertEquals(
                "the hour already half gone is not the hour ahead",
                1,
                Wx.nextHourIndex(t, "2026-11-06T08:00"));
    }

    // ───────────────────────── how old is too old to show ─────────────────────────

    private static final long HOUR_MS = 60 * 60 * 1000L;

    @Test
    public void aReadingIsShownForThreeHoursAndNoLonger() {
        long now = 1_762_000_000_000L;
        assertFalse(Wx.isStale(now, now));
        assertFalse(Wx.isStale(now - 2 * HOUR_MS, now));
        assertFalse(Wx.isStale(now - 3 * HOUR_MS + 1000L, now));
        assertTrue(Wx.isStale(now - 3 * HOUR_MS, now));
        assertTrue(Wx.isStale(now - 9 * HOUR_MS, now));
    }

    @Test
    public void aReadingOfUnknownAgeIsTooOldToShow() {
        // An install upgraded from a version that stored no timestamp. The cost
        // is one dash until the first fetch of the run; the alternative is
        // presenting a reading of any age as the weather outside.
        long now = 1_762_000_000_000L;
        assertTrue(Wx.isStale(0L, now));
        assertTrue(Wx.isStale(-1L, now));
    }

    @Test
    public void showingIsStricterThanTheSunIsByAWideMargin() {
        // The two thresholds answer different questions and must not be made
        // to match: a week-old reading still says when it gets dark, and says
        // nothing whatever about the temperature or a gale.
        assertTrue(Wx.STALE_AFTER_MS < Sun.USABLE_FOR_MS);
        assertEquals(3 * HOUR_MS, Wx.STALE_AFTER_MS);
    }
}
