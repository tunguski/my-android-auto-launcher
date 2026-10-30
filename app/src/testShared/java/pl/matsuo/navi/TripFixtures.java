package pl.matsuo.navi;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Synthetic trips used by the unit tests, by the instrumented tests and by the screenshot script,
 * so all three show the same drives.
 *
 * <p>Three trips are generated: two short town drives and, as the newest one, a simulated Warsaw to
 * Gdansk run along the S7 corridor — the real road geometry and a realistic per-leg speed, which
 * comes out at 320 km in 3 h 50, an average of 83 km/h. That is a little short of the 340 km the
 * road actually measures, because the waypoints follow its course but not every bend.
 *
 * <p>The class depends on nothing but {@link Geo} and the JDK, so it can also be compiled and run
 * on its own:
 *
 * <pre>
 * javac -d build/fixtures app/src/main/java/pl/matsuo/navi/Geo.java \
 *       app/src/test/java/pl/matsuo/navi/TripFixtures.java
 * java -cp build/fixtures pl.matsuo.navi.TripFixtures &lt;outputDir&gt;
 * </pre>
 */
public final class TripFixtures {

    /** One fix every ten seconds, the same interval the service records at. */
    static final long STEP_MS = 10_000L;

    private TripFixtures() {}

    /** A leg of a drive: where it goes and how fast traffic moves there. */
    static final class Leg {
        final double lat;
        final double lon;
        final double kmh;

        Leg(double lat, double lon, double kmh) {
            this.lat = lat;
            this.lon = lon;
            this.kmh = kmh;
        }
    }

    /**
     * Warsaw to Gdansk along the S7 corridor: out of Warsaw through Lomianki, then Plonsk,
     * Glinojeck, Mlawa, Nidzica, Olsztynek, Ostroda, Elblag and into Gdansk. The speed on each leg
     * is the one that road actually allows, so the drive comes out at 320 km in 3 h 50.
     */
    static final Leg[] WARSAW_GDANSK = {
        new Leg(52.2297, 21.0122, 25), // Warsaw, city centre
        new Leg(52.2560, 20.9830, 30), // Powazki
        new Leg(52.2900, 20.9300, 40), // Mlociny
        new Leg(52.3330, 20.8830, 55), // Lomianki
        new Leg(52.3900, 20.8000, 75), // Czosnow
        new Leg(52.4350, 20.6100, 95), // Zakroczym, onto the S7
        new Leg(52.5200, 20.4600, 100), // Nowy Dwor Mazowiecki
        new Leg(52.6230, 20.3760, 105), // Plonsk
        new Leg(52.8200, 20.3000, 105), // Glinojeck
        new Leg(52.9500, 20.3200, 90), // Strzegowo, single carriageway
        new Leg(53.1130, 20.3680, 90), // Mlawa
        new Leg(53.2400, 20.4700, 95), // Szczepkowo
        new Leg(53.3600, 20.4300, 95), // Nidzica
        new Leg(53.4800, 20.3600, 95), // Waplewo
        new Leg(53.5870, 20.2830, 100), // Olsztynek
        new Leg(53.6950, 19.9650, 105), // Ostroda
        new Leg(53.8300, 19.8300, 105), // Milomlyn
        new Leg(54.0600, 19.6600, 105), // Paslek
        new Leg(54.1560, 19.4040, 70), // Elblag, through the town
        new Leg(54.2130, 19.1200, 95), // Nowy Dwor Gdanski
        new Leg(54.2600, 18.7900, 85), // Pruszcz Gdanski
        new Leg(54.3200, 18.6800, 45), // Gdansk outskirts
        new Leg(54.3520, 18.6466, 25), // Gdansk, city centre
    };

    /** A short town drive: home to the shops and back, stop-and-go. */
    static final Leg[] SHORT_ERRAND = {
        new Leg(52.2297, 21.0122, 30),
        new Leg(52.2340, 21.0210, 35),
        new Leg(52.2385, 21.0160, 30),
        new Leg(52.2330, 21.0060, 30),
        new Leg(52.2297, 21.0122, 25),
    };

    /** A slightly longer commute across town. */
    static final Leg[] SHORT_COMMUTE = {
        new Leg(52.2297, 21.0122, 35),
        new Leg(52.2190, 21.0410, 45),
        new Leg(52.2050, 21.0700, 50),
        new Leg(52.1900, 21.0900, 45),
        new Leg(52.1810, 21.0640, 35),
    };

    /**
     * Expands a list of legs into one fix every {@link #STEP_MS}, interpolating along each leg at
     * that leg speed.
     *
     * @param startedAt epoch millis of the first fix
     * @return fixes as {epochMillis, latitude, longitude}
     */
    static List<double[]> expand(Leg[] legs, long startedAt) {
        List<double[]> out = new ArrayList<>();
        long t = startedAt;
        out.add(new double[] {t, legs[0].lat, legs[0].lon});
        for (int i = 1; i < legs.length; i++) {
            Leg from = legs[i - 1];
            Leg to = legs[i];
            double metres = Geo.distance(from.lat, from.lon, to.lat, to.lon);
            double metresPerStep = to.kmh * 1000.0 / 3600.0 * (STEP_MS / 1000.0);
            int steps = Math.max(1, (int) Math.round(metres / metresPerStep));
            for (int s = 1; s <= steps; s++) {
                double f = (double) s / steps;
                t += STEP_MS;
                out.add(
                        new double[] {
                            t,
                            from.lat + (to.lat - from.lat) * f,
                            from.lon + (to.lon - from.lon) * f
                        });
            }
        }
        return out;
    }

    /** Length of a generated drive, metres. */
    static double metres(List<double[]> fixes) {
        double sum = 0;
        for (int i = 1; i < fixes.size(); i++) {
            sum +=
                    Geo.distance(
                            fixes.get(i - 1)[1],
                            fixes.get(i - 1)[2],
                            fixes.get(i)[1],
                            fixes.get(i)[2]);
        }
        return sum;
    }

    /** Writes one drive as a trip CSV, named after its start like the app does. */
    static File write(File dir, List<double[]> fixes) throws IOException {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        String name =
                "trip-"
                        + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                                .format(new Date((long) fixes.get(0)[0]))
                        + ".csv";
        File f = new File(dir, name);
        try (FileWriter w = new FileWriter(f)) {
            for (double[] fix : fixes) {
                w.write(
                        String.format(
                                Locale.US, "%d,%.6f,%.6f%n", (long) fix[0] / 1000, fix[1], fix[2]));
            }
        }
        return f;
    }

    /**
     * Writes the whole demo history into {@code dir}: an errand three days back, a commute a day
     * back and the Warsaw to Gdansk drive that ended two minutes ago, which is recent enough for
     * the launcher to pick it up as the trip in progress.
     *
     * @return the files written, newest last
     */
    static List<File> writeAll(File dir, long now) throws IOException {
        List<File> out = new ArrayList<>();
        List<double[]> errand = expand(SHORT_ERRAND, now - 3 * 86400_000L);
        out.add(write(dir, errand));

        List<double[]> commute = expand(SHORT_COMMUTE, now - 86400_000L);
        out.add(write(dir, commute));

        List<double[]> gdansk = expand(WARSAW_GDANSK, 0);
        long duration = (long) gdansk.get(gdansk.size() - 1)[0];
        long start = now - 2 * 60_000L - duration;
        for (double[] fix : gdansk) {
            fix[0] += start;
        }
        out.add(write(dir, gdansk));
        return out;
    }

    public static void main(String[] args) throws IOException {
        File dir = new File(args.length > 0 ? args[0] : "build/fixtures/trips");
        long now = args.length > 1 ? Long.parseLong(args[1]) : System.currentTimeMillis();
        for (File f : writeAll(dir, now)) {
            System.out.println(f.getAbsolutePath());
        }
    }
}
