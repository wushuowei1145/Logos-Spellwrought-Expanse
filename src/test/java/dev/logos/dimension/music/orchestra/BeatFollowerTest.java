package dev.logos.dimension.music.orchestra;

import dev.logos.dimension.music.MusicRoll;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BeatFollowerTest {
    /** 100 bpm 的 4/4：一拍 600 毫秒。 */
    private static final BeatMap MAP = BeatMap.of(new MusicRoll("", "", 10000, 4, 4, List.of(),
        List.of(MusicRoll.Event.note(0, 0, 60, 90, 100)), List.of()));

    /** 乐团：按时钟的速度走，指挥每一下拍点之后换成跟随者给的速度。 */
    private static final class Orchestra {
        final BeatFollower follower = new BeatFollower(1.0);
        double position;
        double clockRate = 1.0;
        long now = 1_000_000_000L;

        void run(double realMs) {
            position += realMs * (follower.held() ? 0 : clockRate);
            now += (long) (realMs * 1_000_000);
        }

        BeatFollower.Beat tap() {
            BeatFollower.Beat b = follower.tap(now, position, MAP);
            clockRate = b.clockRate();
            return b;
        }
    }

    @Test void steadyFasterBeatsSpeedTheOrchestraUp() {
        Orchestra o = new Orchestra();
        BeatFollower.Beat last = null;
        for (int i = 0; i < 10; i++) {
            last = o.tap();
            o.run(500);   // 120 bpm
        }
        assertEquals(1.2, o.follower.rate(), 0.02, "follows 120 bpm over a written 100");
        assertTrue(last.onTime(), "after a few beats the conductor and the orchestra are together: " + last.errorMs());
        assertTrue(o.follower.leading());
    }

    @Test void slowingDownSlowsTheOrchestra() {
        Orchestra o = new Orchestra();
        for (int i = 0; i < 6; i++) {
            o.tap();
            o.run(600);
        }
        double before = o.follower.rate();
        for (int i = 0; i < 6; i++) {
            o.tap();
            o.run(600 + 60 * (i + 1));   // 渐慢
        }
        assertEquals(1.0, before, 0.02);
        assertTrue(o.follower.rate() < 0.75, "a ritardando slows it down: " + o.follower.rate());
    }

    @Test void aLateBeatMakesTheClockWaitAndAnEarlyOneHurries() {
        Orchestra o = new Orchestra();
        o.tap();
        o.run(700);   // 晚了 100 毫秒：乐团已经走过了这一拍
        BeatFollower.Beat late = o.tap();
        assertTrue(late.errorMs() > 0 && late.clockRate() < late.rate(), "late: wait a little " + late);
        o.run(450);   // 早了
        BeatFollower.Beat early = o.tap();
        assertTrue(early.errorMs() < 0 && early.clockRate() > early.rate(), "early: hurry " + early);
    }

    @Test void beatingEveryOtherBeatKeepsTheTempo() {
        Orchestra o = new Orchestra();
        for (int i = 0; i < 8; i++) {
            o.tap();
            o.run(1200);   // 只打一、三拍
        }
        assertEquals(1.0, o.follower.rate(), 0.03);
    }

    @Test void aMissingBeatHoldsAndTheNextOneGoesOn() {
        Orchestra o = new Orchestra();
        for (int i = 0; i < 4; i++) {
            o.tap();
            o.run(600);
        }
        // 第五下没来：一拍之后再过不到半拍还不算
        o.run(200);
        assertFalse(o.follower.overdue(o.now), "a little late is not a fermata yet");
        o.run(150);
        assertTrue(o.follower.overdue(o.now), "almost half a beat late: hold");
        assertTrue(o.follower.held());
        double heldAt = o.position;
        o.run(2000);
        assertEquals(heldAt, o.position, 1e-9, "the orchestra waits");
        assertFalse(o.follower.overdue(o.now), "only once");
        BeatFollower.Beat go = o.tap();
        assertFalse(o.follower.held());
        assertEquals(go.rate(), go.clockRate(), 1e-9, "goes on at the tempo it had");
        assertEquals(1.0, go.rate(), 0.02);
    }

    @Test void releasingGivesTheTempoBack() {
        Orchestra o = new Orchestra();
        o.tap();
        o.follower.release();
        assertFalse(o.follower.leading());
        assertFalse(o.follower.overdue(o.now + 10_000_000_000L));
    }

    @Test void twoBeatsBeforeTheStartSetTheTempo() {
        assertEquals(1.2, BeatFollower.countIn(600, 500), 1e-9);
        assertEquals(BeatFollower.MAX_RATE, BeatFollower.countIn(600, 100), 1e-9);
        assertEquals(BeatFollower.MIN_RATE, BeatFollower.countIn(600, 5000), 1e-9);
    }
}
