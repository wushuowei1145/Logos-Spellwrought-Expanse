package dev.logos.dimension.music.orchestra;

import dev.logos.dimension.music.MusicRoll;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JudgementTest {
    /** 100 bpm 的 4/4：一拍 600 毫秒。 */
    private static final BeatMap MAP = BeatMap.of(new MusicRoll("", "", 10000, 4, 4, List.of(),
        List.of(MusicRoll.Event.note(0, 0, 60, 90, 100)), List.of()));

    @Test void gradesByDistanceFromTheBeat() {
        assertEquals(Judgement.PERFECT, Judgement.of(0));
        assertEquals(Judgement.PERFECT, Judgement.of(-40));
        assertEquals(Judgement.GREAT, Judgement.of(41));
        assertEquals(Judgement.GOOD, Judgement.of(-120));
        assertEquals(Judgement.MISS, Judgement.of(131));
    }

    @Test void comboCountsUntilAMiss() {
        int combo = 0;
        combo = Judgement.PERFECT.combo(combo);
        combo = Judgement.GOOD.combo(combo);
        assertEquals(2, combo);
        assertEquals(0, Judgement.MISS.combo(combo));
    }

    @Test void offsetIsToTheNearestBeatInRealTime() {
        assertEquals(0, Judgement.offsetMs(MAP, 1200, 1), 1e-9);
        assertEquals(50, Judgement.offsetMs(MAP, 1250, 1), 1e-9, "a little after the third beat");
        assertEquals(-100, Judgement.offsetMs(MAP, 1700, 1), 1e-9, "just before the fourth");
        assertEquals(25, Judgement.offsetMs(MAP, 1250, 2), 1e-9, "at double speed 50 score ms are 25 real ms");
    }
}
