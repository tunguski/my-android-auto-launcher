package pl.matsuo.navi;

import java.util.Locale;

/** Number formatting shared by the stats screen and the recording notification. */
final class Format {

    private Format() {}

    static String distance(double meters) {
        if (meters < 1000) {
            return Math.round(meters) + " m";
        }
        return String.format(Locale.getDefault(), "%.1f km", meters / 1000);
    }

    static String duration(long millis) {
        long minutes = Math.max(0, millis) / 60000L;
        if (minutes < 60) {
            return minutes + " min";
        }
        return String.format(Locale.getDefault(), "%d:%02d", minutes / 60, minutes % 60);
    }
}
