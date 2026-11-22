package dev.logos.dimension;

import dev.logos.dimension.elven.blight.HeartPurification;
import dev.logos.dimension.elven.blight.HeartPurification.Event;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HeartPurificationTest {
    private static int ticksUntil(HeartPurification p, Event wanted) {
        for (int t = 1; t < 5000; t++) if (p.tick(true, false) == wanted) return t;
        return -1;
    }

    @Test void eachSegmentTakesAboutTwentySeconds() {
        var p = new HeartPurification(); p.reset(1);
        assertEquals(HeartPurification.SEGMENT_TICKS, ticksUntil(p, Event.OPENED), 2);
        assertEquals(.2f, p.progress(), 1e-6);
        // 降下来时进度停住。
        for (int i = 0; i < 50; i++) p.tick(true, false);
        assertEquals(.2f, p.progress(), 1e-6);
        assertEquals(Event.CLEARED, p.cut());
        assertEquals(1, p.cleared());
        assertEquals(HeartPurification.SEGMENT_TICKS, ticksUntil(p, Event.OPENED), 2);
        assertEquals(.4f, p.progress(), 1e-6);
    }

    @Test void oneCutIsAFixedFifthAndOnlyWhileDown() {
        var p = new HeartPurification(); p.reset(1);
        assertEquals(Event.NONE, p.cut(), "没降下来时砍不到");
        assertEquals(0, p.cleared());
        ticksUntil(p, Event.OPENED);
        assertEquals(Event.CLEARED, p.cut());
        assertFalse(p.open());
        assertEquals(.8f, p.health(), 1e-6);
        assertEquals(Event.NONE, p.cut(), "一次降下只能斩一段");
        assertEquals(1, p.cleared());
    }

    @Test void missedWindowSetsProgressBack() {
        var p = new HeartPurification(); p.reset(1);
        ticksUntil(p, Event.OPENED);
        Event last = Event.NONE;
        for (int i = 0; i < HeartPurification.WINDOW_TICKS && last != Event.FAILED; i++) last = p.tick(true, false);
        assertEquals(Event.FAILED, last);
        assertEquals(Math.max(0, .2f - HeartPurification.FAIL_SETBACK), p.progress(), 1e-6);
        assertEquals(0, p.cleared());
        // 涨回刻度时重新降下。
        assertTrue(ticksUntil(p, Event.OPENED) > 0);
    }

    @Test void downedPrincessDecaysAndSelfRiseSetsBack() {
        var p = new HeartPurification(); p.reset(1);
        for (int i = 0; i < 300; i++) p.tick(true, false);
        float before = p.progress();
        for (int i = 0; i < 20; i++) p.tick(false, true);
        assertEquals(before - .01f, p.progress(), 1e-5);
        p.setback(HeartPurification.SELF_RISE_SETBACK);
        assertEquals(Math.max(0, before - .01f - .25f), p.progress(), 1e-5);
    }

    @Test void progressNeverFallsBelowTheLastCut() {
        var p = new HeartPurification(); p.reset(1);
        ticksUntil(p, Event.OPENED);
        p.cut();
        assertEquals(.2f, p.floor(), 1e-6);
        for (int i = 0; i < 2000; i++) p.tick(false, true);
        assertEquals(.2f, p.progress(), 1e-6);
        p.setback(HeartPurification.SELF_RISE_SETBACK);
        assertEquals(.2f, p.progress(), 1e-6);
    }

    @Test void ringClosesTowardTheNextDescent() {
        var p = new HeartPurification(); p.reset(1);
        assertEquals(0, p.toNext(), 1e-6);
        for (int i = 0; i < HeartPurification.SEGMENT_TICKS / 2; i++) p.tick(true, false);
        assertEquals(.5f, p.toNext(), .01);
        ticksUntil(p, Event.OPENED);
        assertEquals(1, p.toNext(), 1e-6);
        p.cut();
        assertEquals(0, p.toNext(), 1e-6);
    }

    @Test void multiplayerScalesHealthNotCuts() {
        var duo = new HeartPurification(); duo.reset(1.5f);
        assertEquals(HeartPurification.SEGMENT * 1.5f * HeartPurification.SEGMENTS, duo.maxHealth(), 1e-3);
        for (int w = 0; w < HeartPurification.SEGMENTS; w++) { ticksUntil(duo, Event.OPENED); duo.cut(); }
        assertTrue(duo.done());
    }

    @Test void fiveCutsFinish() {
        var p = new HeartPurification(); p.reset(1);
        assertEquals(5, HeartPurification.SEGMENTS);
        for (int w = 0; w < HeartPurification.SEGMENTS; w++) {
            assertTrue(ticksUntil(p, Event.OPENED) > 0);
            assertEquals(Event.CLEARED, p.cut());
        }
        assertTrue(p.done());
        assertEquals(0, p.health());
        assertEquals(1, p.toNext());
        assertEquals(Event.NONE, p.tick(true, false));
    }
}
