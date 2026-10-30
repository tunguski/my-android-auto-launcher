package pl.matsuo.navi;

import java.util.List;

/**
 * Geographic maths, deliberately free of any Android class so it can be unit tested on a plain JVM.
 *
 * <p>Distances use the haversine formula on a sphere. Against the ellipsoidal figure of {@code
 * Location.distanceBetween} that is off by up to about half a percent, which is far below the error
 * of a consumer GPS and irrelevant for a trip odometer.
 */
final class Geo {

    /** Mean earth radius, metres (IUGG). */
    private static final double R = 6371008.8;

    private Geo() {}

    static float distance(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a =
                Math.sin(dLat / 2) * Math.sin(dLat / 2)
                        + Math.cos(Math.toRadians(lat1))
                                * Math.cos(Math.toRadians(lat2))
                                * Math.sin(dLon / 2)
                                * Math.sin(dLon / 2);
        return (float) (2 * R * Math.asin(Math.min(1, Math.sqrt(a))));
    }

    /** Total length of a polyline given as {latitude, longitude} pairs. */
    static double length(List<double[]> points) {
        double sum = 0;
        for (int i = 1; i < points.size(); i++) {
            sum +=
                    distance(
                            points.get(i - 1)[0],
                            points.get(i - 1)[1],
                            points.get(i)[0],
                            points.get(i)[1]);
        }
        return sum;
    }

    /**
     * Fits a route into a box of {@code w} x {@code h} pixels inset by {@code pad},
     * equirectangularly, with longitude scaled by the cosine of the mean latitude and a single
     * scale for both axes so the shape is never stretched.
     */
    static final class Projection {

        final double minLat;
        final double maxLat;
        final double minLon;
        final double kx;
        final double scale;
        final float offX;
        final float offY;

        Projection(List<double[]> points, float w, float h, float pad) {
            double loLat = Double.MAX_VALUE;
            double hiLat = -Double.MAX_VALUE;
            double loLon = Double.MAX_VALUE;
            double hiLon = -Double.MAX_VALUE;
            for (double[] p : points) {
                loLat = Math.min(loLat, p[0]);
                hiLat = Math.max(hiLat, p[0]);
                loLon = Math.min(loLon, p[1]);
                hiLon = Math.max(hiLon, p[1]);
            }
            minLat = loLat;
            maxLat = hiLat;
            minLon = loLon;

            kx = Math.cos(Math.toRadians((loLat + hiLat) / 2));
            double spanX = Math.max((hiLon - loLon) * kx, 1e-6);
            double spanY = Math.max(hiLat - loLat, 1e-6);

            float boxW = w - 2 * pad;
            float boxH = h - 2 * pad;
            scale = Math.min(boxW / spanX, boxH / spanY);
            offX = (float) (pad + (boxW - spanX * scale) / 2);
            offY = (float) (pad + (boxH - spanY * scale) / 2);
        }

        float x(double lon) {
            return (float) (offX + (lon - minLon) * kx * scale);
        }

        /** Latitude grows northward, screen coordinates grow downward. */
        float y(double lat) {
            return (float) (offY + (maxLat - lat) * scale);
        }
    }
}
