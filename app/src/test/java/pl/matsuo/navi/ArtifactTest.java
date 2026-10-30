package pl.matsuo.navi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/** What a backed-up trip looks like outside the app. */
public class ArtifactTest {

    private static Track.Fixes fixtureDrive() {
        List<double[]> drive = TripFixtures.expand(TripFixtures.SHORT_COMMUTE, 1_700_000_000_000L);
        long[] t = new long[drive.size()];
        int[] lat = new int[drive.size()];
        int[] lon = new int[drive.size()];
        for (int i = 0; i < drive.size(); i++) {
            t[i] = (long) (drive.get(i)[0] / 1000);
            lat[i] = Track.micro(drive.get(i)[1]);
            lon[i] = Track.micro(drive.get(i)[2]);
        }
        return new Track.Fixes(t, lat, lon, drive.size());
    }

    // ───────────────────────────── names ─────────────────────────────

    @Test
    public void theNameSortsByWhenTheDriveStarted() {
        assertEquals("2023-11-14T22-13-20.csv.gz", Artifact.name(1_700_000_000L));
        assertTrue(Artifact.name(1_700_000_000L).compareTo(Artifact.name(1_700_000_001L)) < 0);
    }

    @Test
    public void theNameIsLegalOnEveryFilesystem() {
        String name = Artifact.name(1_700_000_000L);
        for (char c : ":*?\"<>|\\/".toCharArray()) {
            assertEquals("the name must not contain " + c, -1, name.indexOf(c));
        }
    }

    @Test
    public void namesAreInUtcSoADriveAbroadIsNotMisfiled() {
        // Midnight UTC on new year, whatever the unit thinks the local time is.
        assertEquals("2026-01-01T00-00-00.csv.gz", Artifact.name(1_767_225_600L));
        assertEquals("2026", Artifact.year(1_767_225_600L));
        assertEquals("2025", Artifact.year(1_767_225_599L));
    }

    // ───────────────────────────── contents ─────────────────────────────

    @Test
    public void theCsvIsTheFormatTheOldFilesHad() {
        Track.Fixes f =
                new Track.Fixes(
                        new long[] {1766740800L, 1766740810L},
                        new int[] {52_229_700, 52_230_238},
                        new int[] {21_012_200, 21_013_300},
                        2);
        assertEquals(
                "1766740800,52.229700,21.012200\n1766740810,52.230238,21.013300\n",
                Artifact.csv(f));
    }

    @Test
    public void anEmptyTripProducesAnEmptyCsv() {
        assertEquals("", Artifact.csv(Track.decode(new byte[0])));
    }

    @Test
    public void whatIsBuiltCanBeReadBack() throws Exception {
        Track.Fixes drive = fixtureDrive();
        Track.Fixes back = Artifact.parse(Artifact.build(drive));

        assertEquals(drive.n, back.n);
        for (int i = 0; i < drive.n; i++) {
            assertEquals(drive.t[i], back.t[i]);
            assertEquals(drive.lat[i], back.lat[i]);
            assertEquals(drive.lon[i], back.lon[i]);
        }
    }

    @Test
    public void compressionEarnsItsPlace() throws Exception {
        Track.Fixes drive = fixtureDrive();
        int plain = Artifact.csv(drive).length();
        int gzipped = Artifact.build(drive).length;
        assertTrue("gzip " + gzipped + " vs plain " + plain, gzipped < plain / 2);
    }

    // ───────────────────────────── checksums ─────────────────────────────

    @Test
    public void theSameBytesHashTheSame() {
        assertEquals(Artifact.sha256("abc".getBytes()), Artifact.sha256("abc".getBytes()));
        assertNotEquals(Artifact.sha256("abc".getBytes()), Artifact.sha256("abd".getBytes()));
    }

    @Test
    public void theHashIsTheKnownSha256() {
        // The published digest of "abc", so a mistake in the hex conversion shows.
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                Artifact.sha256("abc".getBytes()));
    }

    @Test
    public void theHashOfNothingIsStillAHash() {
        assertEquals(64, Artifact.sha256(new byte[0]).length());
    }
}
