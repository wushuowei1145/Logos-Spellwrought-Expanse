package dev.logos.dimension.music.orchestra;

import dev.logos.dimension.music.MusicRoll;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BeatMapTest {
    private static MusicRoll roll(int bpmX100, int beats, int unit, List<MusicRoll.Tempo> tempos) {
        return new MusicRoll("", "", bpmX100, beats, unit, List.of(), List.of(MusicRoll.Event.note(0, 0, 60, 90, 100)), tempos);
    }

    private static void assertBeat(BeatMap.Beat b, int bar, int beat, double phase) {
        assertEquals(bar, b.bar(), "bar");
        assertEquals(beat, b.beat(), "beat");
        assertEquals(phase, b.phase(), 1e-9, "phase");
    }

    @Test void countsBarsAndBeatsAtOneTempo() {
        BeatMap map = BeatMap.of(roll(12000, 4, 4, List.of()));
        assertBeat(map.at(0), 0, 0, 0);
        assertBeat(map.at(750), 0, 1, 0.5);
        assertBeat(map.at(2000), 1, 0, 0);
        assertEquals(500, map.at(0).beatMs(), 1e-9);
        assertEquals(1, map.segmentCount());
    }

    @Test void theUpbeatBeforeTheStartIsBarMinusOne() {
        BeatMap map = BeatMap.of(roll(12000, 4, 4, List.of()));
        assertBeat(map.at(-250), -1, 3, 0.5);
        assertBeat(map.at(-500), -1, 3, 0);
    }

    @Test void eighthNoteMetersBeatInEighths() {
        BeatMap map = BeatMap.of(roll(12000, 6, 8, List.of()));
        assertEquals(250, map.at(0).beatMs(), 1e-9);
        assertBeat(map.at(1500), 1, 0, 0);
        assertBeat(map.at(1625), 1, 0, 0.5);
    }

    @Test void tempoChangesKeepCountingBars() {
        BeatMap map = BeatMap.of(roll(12000, 4, 4, List.of(new MusicRoll.Tempo(4000, 6000, 4, 4))));
        assertBeat(map.at(4000), 2, 0, 0);
        assertBeat(map.at(5000), 2, 1, 0);
        assertEquals(1000, map.at(5000).beatMs(), 1e-9);
        assertEquals(8000, map.barStartMs(3), 1e-9);
    }

    @Test void meterChangesStartANewBar() {
        BeatMap map = BeatMap.of(roll(12000, 4, 4, List.of(new MusicRoll.Tempo(4000, 12000, 3, 4))));
        assertBeat(map.at(4000), 2, 0, 0);
        assertEquals(3, map.at(4000).beatsPerBar());
        assertBeat(map.at(5500), 3, 0, 0);
        assertEquals(2000, map.barStartMs(1), 1e-9);
        assertEquals(4000, map.barStartMs(2), 1e-9);
        assertEquals(5500, map.barStartMs(3), 1e-9);
    }

    @Test void aMeterChangeOffTheBarLineStartsTheNextBarThere() {
        // 第 2 小节的第一拍中间改成 3/4：从那里起是第 3 小节
        BeatMap map = BeatMap.of(roll(12000, 4, 4, List.of(new MusicRoll.Tempo(4250, 12000, 3, 4))));
        assertBeat(map.at(4249), 2, 0, 0.498);
        assertBeat(map.at(4250), 3, 0, 0);
        // 导入时毫秒取整差一点点的也当成小节线
        BeatMap rounded = BeatMap.of(roll(12000, 4, 4, List.of(new MusicRoll.Tempo(3999, 12000, 3, 4))));
        assertBeat(rounded.at(3999), 2, 0, 0);
    }
}
