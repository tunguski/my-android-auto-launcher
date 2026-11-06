package pl.matsuo.navi;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Which of the five brightness steps a stored value belongs to. */
public class BrightnessTest {

    @Test
    public void aStoredStepIsItsOwnStep() {
        for (int level : MainActivity.BRIGHTNESS) {
            assertEquals(level, MainActivity.nearestBrightness(level));
        }
    }

    @Test
    public void theFiveStepsAreTheOnesTheScreenOffers() {
        assertEquals(5, MainActivity.BRIGHTNESS.length);
        assertEquals(20, MainActivity.BRIGHTNESS[0]);
        assertEquals(100, MainActivity.BRIGHTNESS[4]);
    }

    @Test
    public void aValueLeftBehindByTheOldSliderLandsOnTheNearestStep() {
        // The slider stored anything from 15 to 100, so this is what an install
        // upgraded from 2.4 arrives with.
        assertEquals(20, MainActivity.nearestBrightness(15));
        assertEquals(20, MainActivity.nearestBrightness(27));
        assertEquals(40, MainActivity.nearestBrightness(33));
        assertEquals(60, MainActivity.nearestBrightness(55));
        assertEquals(100, MainActivity.nearestBrightness(99));
    }

    @Test
    public void aValueOffEitherEndStillLandsOnAStep() {
        assertEquals(20, MainActivity.nearestBrightness(0));
        assertEquals(20, MainActivity.nearestBrightness(-40));
        assertEquals(100, MainActivity.nearestBrightness(1000));
    }

    @Test
    public void aValueExactlyBetweenTwoStepsTakesTheLowerOne() {
        // Only so that it is decided rather than incidental: a strict
        // comparison keeps the first of the two, and dimmer is the safer way
        // to be wrong on a screen in a car at night.
        assertEquals(20, MainActivity.nearestBrightness(30));
        assertEquals(80, MainActivity.nearestBrightness(90));
    }
}
