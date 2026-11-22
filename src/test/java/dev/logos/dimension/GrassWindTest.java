package dev.logos.dimension;

import dev.logos.dimension.wind.GrassWind;
import dev.logos.dimension.wind.WindField;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 检查根点锚定、双层接缝、负坐标和远坐标，以及配置关闭后的静止编码。 */
class GrassWindTest {
    @Test void rootsStayPinnedAndTallSeamMatches() {
        assertEquals(0, GrassWind.weight(0, false));
        assertEquals(0, GrassWind.weight(0, true));
        assertEquals(255, GrassWind.weight(1, false));
        assertEquals(255, GrassWind.weight(2, true));
        assertEquals(GrassWind.weight(1, true), GrassWind.weight(0 + 1, true));
        assertEquals(128, GrassWind.weight(1, true));
        assertEquals(0, GrassWind.weight(-1, true));
        assertEquals(255, GrassWind.weight(10, true));
    }

    @Test void disableAndZeroWindProduceNoDisplacement() {
        var wind = new WindField.Wind(1.2f, .8f, 1);
        assertEquals(0, GrassWind.wind(wind, 0) >> 8);
        assertEquals(0, GrassWind.wind(new WindField.Wind(0, .8f, 1), 1) >> 8);
        assertEquals(255, GrassWind.wind(wind, 1) >> 8);
    }

    @Test void phasesAreFiniteAcrossNegativeAndFarCoordinates() {
        var wind = new WindField.Wind(.6f, 1.37f, 1);
        for (double p : new double[]{-29999999.5, -65536.5, -1.5, -.5, .5, 65536.5, 29999999.5}) {
            int phase = GrassWind.phase(p, -p, wind);
            assertTrue(phase >= 0 && phase <= 65535);
            assertEquals(phase, GrassWind.phase(p, -p, wind));
        }
        assertNotEquals(GrassWind.phase(29999990.5, 0, wind), GrassWind.phase(29999991.5, 0, wind));
    }

    @Test void neighbouringRootsDoNotAcquireHugePhaseJumps() {
        var wind = new WindField.Wind(.6f, 0, 1);
        for (int x = -300; x < 300; x++) {
            int a = GrassWind.phase(x + .5, 0, wind) & 255;
            int b = GrassWind.phase(x + 1.5, 0, wind) & 255;
            int delta = Math.abs(a - b);
            assertTrue(Math.min(delta, 255 - delta) <= 15);
        }
    }

    @Test void farGrassReturnsToItsStaticPoseBeforeEviction() {
        assertEquals(1, GrassWind.fade(0));
        assertEquals(1, GrassWind.fade(32));
        assertEquals(.5, GrassWind.fade(40), 1e-9);
        assertEquals(0, GrassWind.fade(48));
        assertEquals(0, GrassWind.fade(100));
    }

    @Test void strongerWindAlwaysProducesAtLeastAsMuchBending() {
        int previous = -1;
        for (int step = 0; step <= 120; step++) {
            int amount = GrassWind.wind(new WindField.Wind(step / 100f, .8f, 1), 1) >> 8;
            assertTrue(amount >= previous, "风变大时摆幅不能变小");
            previous = amount;
        }
    }
}
