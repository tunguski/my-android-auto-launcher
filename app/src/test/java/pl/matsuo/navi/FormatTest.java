package pl.matsuo.navi;

import static org.junit.Assert.assertEquals;

import java.util.Locale;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** The number formats shown on the trip screen and in the notification. */
public class FormatTest {

    private Locale original;

    @Before
    public void setUp() {
        original = Locale.getDefault();
        Locale.setDefault(Locale.US);
    }

    @After
    public void tearDown() {
        Locale.setDefault(original);
    }

    @Test
    public void metresBelowOneKilometre() {
        assertEquals("0 m", Format.distance(0));
        assertEquals("7 m", Format.distance(7.4));
        assertEquals("999 m", Format.distance(999));
    }

    @Test
    public void kilometresFromOneThousandMetresUp() {
        assertEquals("1.0 km", Format.distance(1000));
        assertEquals("1.5 km", Format.distance(1500));
        assertEquals("340.2 km", Format.distance(340_200));
    }

    @Test
    public void theDecimalSeparatorFollowsTheLanguage() {
        Locale.setDefault(Locale.forLanguageTag("pl"));
        assertEquals("1,5 km", Format.distance(1500));
    }

    @Test
    public void minutesBelowAnHour() {
        assertEquals("0 min", Format.duration(0));
        assertEquals("0 min", Format.duration(59_000));
        assertEquals("1 min", Format.duration(60_000));
        assertEquals("59 min", Format.duration(59 * 60_000L));
    }

    @Test
    public void hoursAndMinutesFromAnHourUp() {
        assertEquals("1:00", Format.duration(60 * 60_000L));
        assertEquals("1:05", Format.duration(65 * 60_000L));
        assertEquals("3:47", Format.duration((3 * 60 + 47) * 60_000L));
    }

    @Test
    public void negativeDurationsReadAsZeroRatherThanCrashing() {
        assertEquals("0 min", Format.duration(-5000));
    }
}
