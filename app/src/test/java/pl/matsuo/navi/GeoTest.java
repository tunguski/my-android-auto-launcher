package pl.matsuo.navi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Distances and the route projection. */
public class GeoTest {

    private static final double WARSAW_LAT = 52.2297;
    private static final double WARSAW_LON = 21.0122;
    private static final double GDANSK_LAT = 54.3520;
    private static final double GDANSK_LON = 18.6466;

    @Test
    public void zeroDistanceForTheSamePoint() {
        assertEquals(0, Geo.distance(WARSAW_LAT, WARSAW_LON, WARSAW_LAT, WARSAW_LON), 0.001);
    }

    @Test
    public void warsawToGdanskMatchesTheKnownGreatCircle() {
        // 283.5 km as the crow flies; one kilometre of tolerance covers the
        // difference between the sphere used here and the WGS84 ellipsoid.
        double d = Geo.distance(WARSAW_LAT, WARSAW_LON, GDANSK_LAT, GDANSK_LON);
        assertEquals(283_500, d, 1_000);
    }

    @Test
    public void oneDegreeOfLatitudeIsAboutOneHundredAndElevenKilometres() {
        assertEquals(111_200, Geo.distance(52, 21, 53, 21), 500);
    }

    @Test
    public void aDegreeOfLongitudeShrinksWithLatitude() {
        double atEquator = Geo.distance(0, 0, 0, 1);
        double atWarsaw = Geo.distance(52, 21, 52, 22);
        assertEquals(Math.cos(Math.toRadians(52)), atWarsaw / atEquator, 0.01);
    }

    @Test
    public void distanceIsSymmetric() {
        assertEquals(
                Geo.distance(WARSAW_LAT, WARSAW_LON, GDANSK_LAT, GDANSK_LON),
                Geo.distance(GDANSK_LAT, GDANSK_LON, WARSAW_LAT, WARSAW_LON),
                0.001);
    }

    @Test
    public void lengthAddsUpTheLegs() {
        List<double[]> pts =
                Arrays.asList(
                        new double[] {52.0, 21.0},
                        new double[] {52.1, 21.0},
                        new double[] {52.2, 21.0});
        assertEquals(
                Geo.distance(52.0, 21.0, 52.1, 21.0) + Geo.distance(52.1, 21.0, 52.2, 21.0),
                Geo.length(pts),
                0.001);
    }

    @Test
    public void lengthOfASinglePointIsZero() {
        assertEquals(0, Geo.length(new ArrayList<>()), 0.0);
        assertEquals(0, Geo.length(Arrays.asList(new double[] {52.0, 21.0})), 0.0);
    }

    @Test
    public void projectionKeepsTheRouteInsideThePaddedBox() {
        List<double[]> pts =
                Arrays.asList(
                        new double[] {52.0, 21.0},
                        new double[] {53.0, 21.5},
                        new double[] {54.0, 20.5});
        Geo.Projection p = new Geo.Projection(pts, 600, 300, 20);
        for (double[] pt : pts) {
            float x = p.x(pt[1]);
            float y = p.y(pt[0]);
            assertTrue("x=" + x, x >= 20 - 0.01 && x <= 580 + 0.01);
            assertTrue("y=" + y, y >= 20 - 0.01 && y <= 280 + 0.01);
        }
    }

    @Test
    public void northIsUp() {
        List<double[]> pts = Arrays.asList(new double[] {52.0, 21.0}, new double[] {54.0, 21.0});
        Geo.Projection p = new Geo.Projection(pts, 400, 400, 0);
        assertTrue("the northern point must sit higher on screen", p.y(54.0) < p.y(52.0));
    }

    @Test
    public void eastIsRight() {
        List<double[]> pts = Arrays.asList(new double[] {52.0, 21.0}, new double[] {52.0, 22.0});
        Geo.Projection p = new Geo.Projection(pts, 400, 400, 0);
        assertTrue(p.x(22.0) > p.x(21.0));
    }

    @Test
    public void bothAxesShareOneScaleSoTheShapeIsNotStretched() {
        // A square in metres: one degree of latitude by the matching span of
        // longitude at that latitude. It must come out square on screen too.
        double lat = 52;
        double lonSpan = 1 / Math.cos(Math.toRadians(lat + 0.5));
        List<double[]> pts =
                Arrays.asList(new double[] {lat, 21.0}, new double[] {lat + 1, 21.0 + lonSpan});
        Geo.Projection p = new Geo.Projection(pts, 800, 400, 0);
        float width = p.x(21.0 + lonSpan) - p.x(21.0);
        float height = p.y(lat) - p.y(lat + 1);
        assertEquals(1.0, width / height, 0.01);
    }

    @Test
    public void aStandingStillRouteDoesNotDivideByZero() {
        List<double[]> pts = Arrays.asList(new double[] {52.0, 21.0}, new double[] {52.0, 21.0});
        Geo.Projection p = new Geo.Projection(pts, 400, 400, 10);
        assertTrue(Float.isFinite(p.x(21.0)));
        assertTrue(Float.isFinite(p.y(52.0)));
    }

    @Test
    public void theLongFixtureRouteFitsTheTripScreenBox() {
        List<double[]> pts = new ArrayList<>();
        for (double[] fix : TripFixtures.expand(TripFixtures.WARSAW_GDANSK, 0)) {
            pts.add(new double[] {fix[1], fix[2]});
        }
        Geo.Projection p = new Geo.Projection(pts, 1180, 374, 30);
        for (double[] pt : pts) {
            assertTrue(p.x(pt[1]) >= 29 && p.x(pt[1]) <= 1151);
            assertTrue(p.y(pt[0]) >= 29 && p.y(pt[0]) <= 345);
        }
    }
}
