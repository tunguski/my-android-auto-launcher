package pl.matsuo.navi;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/** The packed track format: what goes in comes out, and damage costs one trip at most. */
public class TrackTest {

    /** Builds a track from {second, latitude, longitude} triples in degrees. */
    private static byte[] pack(double[]... fixes) {
        long[] t = new long[fixes.length];
        int[] lat = new int[fixes.length];
        int[] lon = new int[fixes.length];
        for (int i = 0; i < fixes.length; i++) {
            t[i] = (long) fixes[i][0];
            lat[i] = Track.micro(fixes[i][1]);
            lon[i] = Track.micro(fixes[i][2]);
        }
        return Track.encode(t, lat, lon, fixes.length);
    }

    private static byte[] straightLine(int n, int stepMicro, int dtSeconds) {
        long[] t = new long[n];
        int[] lat = new int[n];
        int[] lon = new int[n];
        for (int i = 0; i < n; i++) {
            t[i] = 1_700_000_000L + (long) i * dtSeconds;
            lat[i] = Track.micro(52.0) + i * stepMicro;
            lon[i] = Track.micro(21.0) + i * stepMicro;
        }
        return Track.encode(t, lat, lon, n);
    }

    // ───────────────────────────── the unit ─────────────────────────────

    @Test
    public void degreesSurviveTheTripThroughMicroDegrees() {
        assertEquals(52.2297, Track.degrees(Track.micro(52.2297)), 1e-9);
        assertEquals(-18.6466, Track.degrees(Track.micro(-18.6466)), 1e-9);
        assertEquals(0.0, Track.degrees(Track.micro(0.0)), 1e-9);
    }

    @Test
    public void microDegreesHoldTheSixDecimalsTheCsvWrote() {
        // The CSV is written with %.6f, so this is what makes the migration lossless.
        assertEquals(52_229_700, Track.micro(52.229700));
        assertEquals(21_012_200, Track.micro(21.012200));
    }

    @Test
    public void roundingIsToNearestNotTowardsZero() {
        assertEquals(1_000_001, Track.micro(1.0000005));
        assertEquals(-1_000_001, Track.micro(-1.0000005));
    }

    // ───────────────────────────── round trips ─────────────────────────────

    @Test
    public void nothingInNothingOut() {
        assertEquals(0, Track.encode(new long[0], new int[0], new int[0], 0).length);
        assertEquals(0, Track.encode(new long[1], new int[1], new int[1], 0).length);
        assertEquals(0, Track.encode(new long[1], new int[1], new int[1], -1).length);
    }

    @Test
    public void aSingleFixIsJustTheHeader() {
        byte[] blob = pack(new double[] {1_700_000_000.0, 52.2297, 21.0122});
        assertEquals(Track.HEADER, blob.length);

        Track.Fixes out = Track.decode(blob);
        assertEquals(1, out.n);
        assertEquals(1_700_000_000L, out.t[0]);
        assertEquals(52.2297, Track.degrees(out.lat[0]), 1e-9);
        assertEquals(21.0122, Track.degrees(out.lon[0]), 1e-9);
    }

    @Test
    public void everyFixComesBackExactly() {
        byte[] blob =
                pack(
                        new double[] {1_700_000_000.0, 52.229700, 21.012200},
                        new double[] {1_700_000_010.0, 52.230238, 21.013300},
                        new double[] {1_700_000_020.0, 52.230775, 21.014400},
                        new double[] {1_700_000_030.0, 52.231313, 21.015500});

        Track.Fixes out = Track.decode(blob);
        assertEquals(4, out.n);
        assertArrayEquals(
                new long[] {1_700_000_000L, 1_700_000_010L, 1_700_000_020L, 1_700_000_030L},
                java.util.Arrays.copyOf(out.t, out.n));
        assertEquals(52.230775, Track.degrees(out.lat[2]), 1e-9);
        assertEquals(21.015500, Track.degrees(out.lon[3]), 1e-9);
    }

    @Test
    public void fiveBytesPerFixIsWhatTheDesignPromises() {
        byte[] blob = straightLine(1000, 500, 10);
        assertEquals(Track.HEADER + 5 * 999, blob.length);
        assertEquals(1000, Track.decode(blob).n);
    }

    @Test
    public void theWholeFixtureDriveRoundTrips() {
        List<double[]> drive = TripFixtures.expand(TripFixtures.WARSAW_GDANSK, 1_700_000_000_000L);
        long[] t = new long[drive.size()];
        int[] lat = new int[drive.size()];
        int[] lon = new int[drive.size()];
        for (int i = 0; i < drive.size(); i++) {
            t[i] = (long) (drive.get(i)[0] / 1000);
            lat[i] = Track.micro(drive.get(i)[1]);
            lon[i] = Track.micro(drive.get(i)[2]);
        }
        byte[] blob = Track.encode(t, lat, lon, drive.size());

        // No escape should be needed anywhere on a real-shaped drive.
        assertEquals(Track.HEADER + 5 * (drive.size() - 1), blob.length);

        Track.Fixes out = Track.decode(blob);
        assertEquals(drive.size(), out.n);
        for (int i = 0; i < out.n; i++) {
            assertEquals(t[i], out.t[i]);
            assertEquals(lat[i], out.lat[i]);
            assertEquals(lon[i], out.lon[i]);
        }
        // And the distance survives: the same 320 km the CSV form measures.
        assertEquals(320_000, Track.meters(out), 10_000);
    }

    // ───────────────────────────── the escapes ─────────────────────────────

    @Test
    public void aGapLongerThanAByteTakesTheLongForm() {
        byte[] blob =
                pack(
                        new double[] {1_000_000.0, 52.0, 21.0},
                        new double[] {1_000_300.0, 52.001, 21.001}); // five minutes
        assertEquals(Track.HEADER + 2 + 2 + 1 + 2, blob.length);

        Track.Fixes out = Track.decode(blob);
        assertEquals(2, out.n);
        assertEquals(1_000_300L, out.t[1]);
    }

    @Test
    public void theGapEscapeStartsExactlyAtTwoHundredAndFiftyFive() {
        assertEquals(
                Track.HEADER + 5,
                pack(new double[] {0, 52, 21}, new double[] {254, 52.001, 21.001}).length);
        assertEquals(
                Track.HEADER + 7,
                pack(new double[] {0, 52, 21}, new double[] {255, 52.001, 21.001}).length);
    }

    @Test
    public void anImpossiblyLongGapIsClampedRatherThanWrapped() {
        byte[] blob = pack(new double[] {0, 52.0, 21.0}, new double[] {200_000.0, 52.001, 21.001});
        Track.Fixes out = Track.decode(blob);
        assertEquals(Track.MAX_DT, out.t[1] - out.t[0]);
    }

    @Test
    public void aClockThatJumpsBackwardsDoesNotWriteANegativeGap() {
        byte[] blob = pack(new double[] {1000, 52.0, 21.0}, new double[] {900, 52.001, 21.001});
        Track.Fixes out = Track.decode(blob);
        assertEquals(2, out.n);
        assertEquals(1000L, out.t[1]); // clamped to no time passing
        assertEquals(52.001, Track.degrees(out.lat[1]), 1e-9);
    }

    @Test
    public void aJumpTooBigForADeltaStoresAnAbsoluteFix() {
        // Warsaw to Gdansk in one step: far beyond what 16 bits can hold.
        byte[] blob =
                pack(
                        new double[] {0, 52.229700, 21.012200},
                        new double[] {10, 54.352000, 18.646600});
        assertEquals(Track.HEADER + 2 + 8 + 1, blob.length);

        Track.Fixes out = Track.decode(blob);
        assertEquals(2, out.n);
        assertEquals(54.3520, Track.degrees(out.lat[1]), 1e-9);
        assertEquals(18.6466, Track.degrees(out.lon[1]), 1e-9);
    }

    @Test
    public void theDeltaEscapeStartsExactlyWhereSixteenBitsRunOut() {
        // The largest delta that still fits, and the first one that does not.
        assertEquals(Track.HEADER + 5, straightLine(2, Short.MAX_VALUE, 10).length);
        assertEquals(Track.HEADER + 11, straightLine(2, Short.MAX_VALUE + 1, 10).length);
        // Negative: MIN_VALUE itself is the escape marker, so it must take the long form.
        assertEquals(Track.HEADER + 5, straightLine(2, Short.MIN_VALUE + 1, 10).length);
        assertEquals(Track.HEADER + 11, straightLine(2, Short.MIN_VALUE, 10).length);
    }

    @Test
    public void bothEscapesCanLandOnTheSameFix() {
        byte[] blob =
                pack(
                        new double[] {0, 52.229700, 21.012200},
                        new double[] {400, 54.352000, 18.646600});
        Track.Fixes out = Track.decode(blob);
        assertEquals(2, out.n);
        assertEquals(400L, out.t[1]);
        assertEquals(54.3520, Track.degrees(out.lat[1]), 1e-9);
    }

    @Test
    public void deltasKeepAccumulatingAfterAnAbsoluteFix() {
        byte[] blob =
                pack(
                        new double[] {0, 52.000000, 21.000000},
                        new double[] {10, 54.000000, 19.000000}, // escape
                        new double[] {20, 54.001000, 19.001000}); // delta from the escape
        Track.Fixes out = Track.decode(blob);
        assertEquals(3, out.n);
        assertEquals(54.001, Track.degrees(out.lat[2]), 1e-9);
        assertEquals(19.001, Track.degrees(out.lon[2]), 1e-9);
    }

    // ───────────────────────────── damaged input ─────────────────────────────

    @Test
    public void nothingUsableGivesNoFixes() {
        assertEquals(0, Track.decode(null).n);
        assertEquals(0, Track.decode(new byte[0]).n);
        assertEquals(0, Track.decode(new byte[Track.HEADER - 1]).n);
    }

    @Test
    public void aBlobThatIsNotATrackIsRejected() {
        byte[] blob = straightLine(5, 500, 10);
        byte[] wrongMagic = blob.clone();
        wrongMagic[1] = 'X';
        assertEquals(0, Track.decode(wrongMagic).n);

        byte[] wrongVersion = blob.clone();
        wrongVersion[3] = 99;
        assertEquals(0, Track.decode(wrongVersion).n);
    }

    @Test
    public void atruncatedTrackKeepsTheFixesItStillHas() {
        byte[] blob = straightLine(10, 500, 10);
        // Cut in every possible place inside the records and never lose the header.
        for (int cut = Track.HEADER; cut < blob.length; cut++) {
            byte[] shorter = java.util.Arrays.copyOf(blob, cut);
            Track.Fixes out = Track.decode(shorter);
            int expected = 1 + (cut - Track.HEADER) / 5;
            assertEquals("cut at " + cut, expected, out.n);
            assertEquals(Track.micro(52.0), out.lat[0]);
        }
    }

    @Test
    public void aTrackTruncatedInsideAnEscapeKeepsWhatCameBefore() {
        byte[] blob =
                pack(
                        new double[] {0, 52.000000, 21.000000},
                        new double[] {10, 54.000000, 19.000000}); // absolute escape
        for (int cut = Track.HEADER; cut < blob.length; cut++) {
            assertEquals("cut at " + cut, 1, Track.decode(java.util.Arrays.copyOf(blob, cut)).n);
        }
    }

    @Test
    public void aTrackTruncatedInsideALongGapKeepsWhatCameBefore() {
        byte[] blob = pack(new double[] {0, 52.0, 21.0}, new double[] {300, 52.001, 21.001});
        assertEquals(1, Track.decode(java.util.Arrays.copyOf(blob, blob.length - 1)).n);
        assertEquals(1, Track.decode(java.util.Arrays.copyOf(blob, blob.length - 2)).n);
        assertEquals(2, Track.decode(blob).n);
    }

    // ───────────────────────────── derived figures ─────────────────────────────

    @Test
    public void pointsCountsWithoutRebuildingTheRoute() {
        assertEquals(7, Track.points(straightLine(7, 500, 10)));
        assertEquals(0, Track.points(new byte[0]));
        assertEquals(0, Track.points(null));
    }

    @Test
    public void metersMatchesTheDistanceTheRecorderWouldHaveCounted() {
        byte[] blob =
                pack(
                        new double[] {0, 52.000000, 21.000000},
                        new double[] {10, 52.001000, 21.000000},
                        new double[] {20, 52.002000, 21.000000});
        double expected =
                Geo.distance(52.0, 21.0, 52.001, 21.0) + Geo.distance(52.001, 21.0, 52.002, 21.0);
        assertEquals(expected, Track.meters(Track.decode(blob)), 0.5);
    }

    @Test
    public void aSingleFixHasNoLength() {
        assertEquals(0.0, Track.meters(Track.decode(pack(new double[] {0, 52.0, 21.0}))), 0.0);
        assertEquals(0.0, Track.meters(Track.decode(new byte[0])), 0.0);
    }

    @Test
    public void theRouteComesOutAsDegreePairsForDrawing() {
        List<double[]> route = Track.decode(straightLine(3, 1000, 10)).latLon();
        assertEquals(3, route.size());
        assertEquals(52.0, route.get(0)[0], 1e-9);
        assertEquals(21.002, route.get(2)[1], 1e-9);
    }

    @Test
    public void anEmptyTrackDrawsNothing() {
        assertTrue(Track.decode(new byte[0]).latLon().isEmpty());
    }
}
