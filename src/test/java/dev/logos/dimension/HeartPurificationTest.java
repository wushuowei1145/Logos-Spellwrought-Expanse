package dev.logos.dimension;

import dev.logos.dimension.elven.blight.HeartPurification;
import dev.logos.dimension.elven.blight.HeartPurification.Event;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HeartPurificationTest {
    private static int ticksUntil(HeartPurification p, int phase, Event wanted) {
        for (int t = 1; t < 5000; t++) if (p.tick(phase, true, false) == wanted) return t;
        return -1;
    }

    @Test void phaseTwoReachesBothThresholdsInAboutFiftySeconds() {
        var p = new HeartPurification(); p.reset(1);
        int first = ticksUntil(p, 2, Event.OPENED);
        assertEquals(500, first, 2);
        assertEquals(.33f, p.progress(), 1e-6);
        // 窗口里进度停住。
        for (int i = 0; i < 50; i++) p.tick(2, true, false);
        assertEquals(.33f, p.progress(), 1e-6);
        while (p.open()) { p.hit(true, 0); p.settle(); }
        assertEquals(1, p.cleared());
        int second = ticksUntil(p, 2, Event.OPENED);
        assertEquals(500, second, 2);
        assertEquals(.66f, p.progress(), 1e-6);
    }

    @Test void phaseThreeIsFasterThanPhaseTwo() {
        assertTrue(HeartPurification.rate(3) > HeartPurification.rate(2));
        assertEquals(.34f / 500, HeartPurification.rate(3), 1e-9);
    }

    @Test void hitsArePercentOfMaxHealthAndCappedPerSegment() {
        var p = new HeartPurification(); p.reset(1);
        ticksUntil(p, 2, Event.OPENED);
        assertEquals(900 * .06f, p.hit(true, 0), 1e-3);
        assertEquals(900 * .02f, p.hit(false, 0), 1e-3);
        // 核心庇护减 60% 时同样扣 40%。
        assertEquals(900 * .06f * .4f, p.hit(true, .6), 1e-3);
        int hits = 0;
        while (p.settle() != Event.CLEARED && hits++ < 100) p.hit(true, 0);
        assertEquals(1, p.cleared());
        assertFalse(p.open());
        assertEquals(300, p.segment(), 1e-3);
        assertEquals(2f / 3, p.health(), 1e-6);
    }

    @Test void failedWindowSetsProgressBackAndKeepsDamage() {
        var p = new HeartPurification(); p.reset(1);
        ticksUntil(p, 2, Event.OPENED);
        p.hit(true, 0);
        float left = p.segment();
        Event last = Event.NONE;
        for (int i = 0; i < HeartPurification.WINDOW_TICKS && last != Event.FAILED; i++) last = p.tick(2, true, false);
        assertEquals(Event.FAILED, last);
        assertEquals(.33f - HeartPurification.FAIL_SETBACK, p.progress(), 1e-6);
        assertEquals(left, p.segment(), 1e-6);
        assertEquals(0, p.cleared());
        // 涨回刻度时窗口重开。
        assertTrue(ticksUntil(p, 2, Event.OPENED) > 0);
    }

    @Test void downedPrincessDecaysAndSelfRiseSetsBack() {
        var p = new HeartPurification(); p.reset(1);
        for (int i = 0; i < 300; i++) p.tick(2, true, false);
        float before = p.progress();
        for (int i = 0; i < 20; i++) p.tick(2, false, true);
        assertEquals(before - .01f, p.progress(), 1e-5);
        p.setback(HeartPurification.SELF_RISE_SETBACK);
        assertEquals(Math.max(0, before - .01f - .25f), p.progress(), 1e-5);
    }

    @Test void progressNeverFallsBelowTheLastClearedThreshold() {
        var p = new HeartPurification(); p.reset(1);
        ticksUntil(p, 2, Event.OPENED);
        while (p.settle() != Event.CLEARED) p.hit(true, 0);
        assertEquals(.33f, p.floor(), 1e-6);
        for (int i = 0; i < 2000; i++) p.tick(2, false, true);
        assertEquals(.33f, p.progress(), 1e-6);
        p.setback(HeartPurification.SELF_RISE_SETBACK);
        assertEquals(.33f, p.progress(), 1e-6);
    }

    @Test void multiplayerScalesHealthNotHitCount() {
        var solo = new HeartPurification(); solo.reset(1);
        var duo = new HeartPurification(); duo.reset(1.5f);
        assertEquals(1350, duo.maxHealth(), 1e-3);
        ticksUntil(solo, 2, Event.OPENED); ticksUntil(duo, 2, Event.OPENED);
        int a = 0, b = 0;
        while (solo.settle() != Event.CLEARED) { solo.hit(true, 0); a++; }
        while (duo.settle() != Event.CLEARED) { duo.hit(true, 0); b++; }
        assertEquals(a, b);
    }

    @Test void lastWindowFinishes() {
        var p = new HeartPurification(); p.reset(1);
        for (int w = 0; w < 3; w++) {
            assertTrue(ticksUntil(p, w < 2 ? 2 : 3, Event.OPENED) > 0);
            while (p.settle() != Event.CLEARED) p.hit(true, 0);
        }
        assertTrue(p.done());
        assertEquals(0, p.health());
        assertEquals(Event.NONE, p.tick(3, true, false));
    }
}
