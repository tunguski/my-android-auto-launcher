package pl.matsuo.navi;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Everything the launcher infers from an Open-Meteo reading: the black-ice risk, the list of active
 * warnings and the sentence for a WMO code.
 *
 * <p>No view and no context, so the rules can be unit tested against canned JSON. Warnings and
 * descriptions are returned as string resource ids; the caller turns them into text in the active
 * language.
 */
final class Wx {

    /** At or below this temperature black ice is assumed possible. */
    static final double ICE_TEMP = 3.0;

    /** Gusts: the Polish IMGW level-1 alert threshold. */
    static final double GUST_KMH = 70;

    /** Dense fog. */
    static final double FOG_VIS_M = 200;

    /** Heavy snow, centimetres an hour. */
    static final double SNOW_CM_H = 1.0;

    /** How far ahead a warning looks. */
    static final int LOOKAHEAD_H = 3;

    private Wx() {}

    /** Freezing drizzle and freezing rain. */
    static boolean isFreezing(int code) {
        return code == 56 || code == 57 || code == 66 || code == 67;
    }

    /**
     * Black ice: temperature at or below {@link #ICE_TEMP}, or freezing rain/drizzle now or within
     * the next three hours, or precipitation while the temperature is at the ice threshold.
     */
    static boolean iceRisk(JSONObject w) {
        try {
            JSONObject cur = w.getJSONObject("current");
            if (cur.getDouble("temperature_2m") <= ICE_TEMP) {
                return true;
            }
            if (isFreezing(cur.getInt("weather_code"))) {
                return true;
            }
            JSONObject h = w.getJSONObject("hourly");
            JSONArray t = h.getJSONArray("time");
            JSONArray c = h.getJSONArray("weather_code");
            JSONArray tv = h.getJSONArray("temperature_2m");
            int i = hourIndex(t, cur.getString("time"));
            if (i < 0) {
                return false;
            }
            for (int j = i; j < Math.min(t.length(), i + LOOKAHEAD_H + 1); j++) {
                if (isFreezing(c.getInt(j))) {
                    return true;
                }
                if (tv.getDouble(j) <= ICE_TEMP && c.getInt(j) >= 51) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // Missing fields simply mean "no warning".
        }
        return false;
    }

    /**
     * Every active warning, now or within the next three hours, as string resource ids in display
     * order.
     */
    static List<Integer> warnings(JSONObject w) {
        List<Integer> out = new ArrayList<>();
        if (iceRisk(w)) {
            out.add(R.string.warn_ice);
        }
        boolean storm = false;
        boolean wind = false;
        boolean fog = false;
        boolean snow = false;
        try {
            JSONObject cur = w.getJSONObject("current");
            int cc = cur.getInt("weather_code");
            storm = cc >= 95;
            fog = cc == 45 || cc == 48;
            snow = cc == 75 || cc == 86;
            wind = cur.optDouble("wind_gusts_10m", 0) >= GUST_KMH;

            JSONObject h = w.getJSONObject("hourly");
            JSONArray t = h.getJSONArray("time");
            JSONArray c = h.getJSONArray("weather_code");
            JSONArray g = h.optJSONArray("wind_gusts_10m");
            JSONArray vis = h.optJSONArray("visibility");
            JSONArray sn = h.optJSONArray("snowfall");
            int i = hourIndex(t, cur.getString("time"));
            for (int j = i; i >= 0 && j < Math.min(t.length(), i + LOOKAHEAD_H + 1); j++) {
                int code = c.getInt(j);
                storm |= code >= 95;
                snow |= code == 75 || code == 86 || (sn != null && sn.optDouble(j, 0) >= SNOW_CM_H);
                wind |= g != null && g.optDouble(j, 0) >= GUST_KMH;
                // Visibility only counts for the coming hour.
                if (j <= i + 1) {
                    fog |= vis != null && !vis.isNull(j) && vis.optDouble(j, 99999) < FOG_VIS_M;
                }
            }
        } catch (Exception ignored) {
            // Missing fields simply mean "no warning".
        }
        if (storm) {
            out.add(R.string.warn_storm);
        }
        if (wind) {
            out.add(R.string.warn_wind);
        }
        if (fog) {
            out.add(R.string.warn_fog);
        }
        if (snow) {
            out.add(R.string.warn_snow);
        }
        return out;
    }

    /** Index of the hourly slot that holds {@code isoTime}, or -1. */
    static int hourIndex(JSONArray times, String isoTime) throws Exception {
        String hour = isoTime.substring(0, 13);
        for (int i = 0; i < times.length(); i++) {
            if (times.getString(i).startsWith(hour)) {
                return i;
            }
        }
        return -1;
    }

    /** WMO weather code as a sentence, by string resource id; 0 when unknown. */
    static int describe(int c) {
        if (c == 0) {
            return R.string.wmo_clear;
        }
        if (c == 1) {
            return R.string.wmo_mostly_clear;
        }
        if (c == 2) {
            return R.string.wmo_partly_cloudy;
        }
        if (c == 3) {
            return R.string.wmo_overcast;
        }
        if (c == 45 || c == 48) {
            return R.string.wmo_fog;
        }
        if (c == 66 || c == 67) {
            return R.string.wmo_freezing_rain;
        }
        if (c >= 51 && c <= 57) {
            return R.string.wmo_drizzle;
        }
        if (c >= 61 && c <= 65) {
            return R.string.wmo_rain;
        }
        if (c >= 71 && c <= 77) {
            return R.string.wmo_snow;
        }
        if (c >= 80 && c <= 82) {
            return R.string.wmo_showers;
        }
        if (c == 85 || c == 86) {
            return R.string.wmo_snow_showers;
        }
        if (c >= 95) {
            return R.string.wmo_storm;
        }
        return 0;
    }

    /** WMO weather code as one short word for the hourly and daily strips. */
    static int describeShort(int c) {
        if (c <= 1) {
            return R.string.sh_clear;
        }
        if (c == 2) {
            return R.string.sh_clouds;
        }
        if (c == 3) {
            return R.string.sh_overcast;
        }
        if (c == 45 || c == 48) {
            return R.string.sh_fog;
        }
        if (c >= 51 && c <= 67) {
            return R.string.sh_rain;
        }
        if (c >= 71 && c <= 77) {
            return R.string.sh_snow;
        }
        if (c >= 80 && c <= 82) {
            return R.string.sh_showers;
        }
        if (c == 85 || c == 86) {
            return R.string.sh_snow;
        }
        if (c >= 95) {
            return R.string.sh_storm;
        }
        return 0;
    }
}
