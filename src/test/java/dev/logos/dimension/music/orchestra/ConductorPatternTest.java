package dev.logos.dimension.music.orchestra;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConductorPatternTest {
    private static BeatMap.Beat beat(int beat, double phase, int beatsPerBar, int unit, double beatMs) {
        return new BeatMap.Beat(0, beat, phase, beatsPerBar, unit, beatMs);
    }

    @Test void theTipIsOnTheIctusExactlyOnTheBeat() {
        for (int beats : new int[]{1, 2, 3, 4, 5, 6, 7}) {
            for (int b = 0; b < beats; b++) {
                double[] tip = ConductorPattern.tip(new ConductorPattern.Conducted(beats, b, 0, 500));
                assertArrayEquals(ConductorPattern.ictus(beats, b), tip, 1e-9, beats + "/" + b);
            }
        }
    }

    @Test void theDownbeatIsTheLowestPointAndTheLastBeatRisesHighest() {
        double lowest = Double.MAX_VALUE, highest = -Double.MAX_VALUE;
        int lowBeat = -1, highBeat = -1;
        for (int b = 0; b < 4; b++) {
            for (int i = 0; i < 100; i++) {
                double[] tip = ConductorPattern.tip(new ConductorPattern.Conducted(4, b, i / 100.0, 500));
                if (tip[1] < lowest - 1e-12) {
                    lowest = tip[1];
                    lowBeat = b;
                }
                if (tip[1] > highest) {
                    highest = tip[1];
                    highBeat = b;
                }
            }
        }
        assertEquals(0, lowBeat);
        assertEquals(0, lowest, 1e-9);
        assertEquals(3, highBeat);
    }

    @Test void theTipMovesFastestJustBeforeTheIctus() {
        double mid = speed(0.55), late = speed(0.97);
        assertTrue(late > mid * 2, "late " + late + " mid " + mid);
    }

    private static double speed(double phase) {
        double[] a = ConductorPattern.tip(new ConductorPattern.Conducted(4, 1, phase, 500));
        double[] b = ConductorPattern.tip(new ConductorPattern.Conducted(4, 1, phase + 0.01, 500));
        return Math.hypot(b[0] - a[0], b[1] - a[1]);
    }

    @Test void beatTwoGoesLeftAndBeatThreeRightInFour() {
        assertTrue(ConductorPattern.ictus(4, 1)[0] < 0);
        assertTrue(ConductorPattern.ictus(4, 2)[0] > 0);
        assertTrue(ConductorPattern.ictus(3, 1)[0] > 0, "in three the second beat goes outward to the right");
    }

    @Test void fastCompoundMetersAreConductedInDottedBeats() {
        // 6/8，八分音符 250 毫秒：打二拍，每拍 750 毫秒；第 4 个八分音符是第二拍的拍点
        var c = ConductorPattern.conduct(beat(3, 0, 6, 8, 250));
        assertEquals(2, c.beats());
        assertEquals(1, c.beat());
        assertEquals(0, c.phase(), 1e-9);
        assertEquals(750, c.beatMs(), 1e-9);
        // 慢的 6/8 打六拍，快的 12/8 打四拍
        assertEquals(6, ConductorPattern.conduct(beat(3, 0, 6, 8, 600)).beats());
        assertEquals(4, ConductorPattern.conduct(beat(0, 0, 12, 8, 250)).beats());
    }

    @Test void fastWaltzesAreConductedInOne() {
        var c = ConductorPattern.conduct(beat(2, 0.5, 3, 4, 250));
        assertEquals(1, c.beats());
        assertEquals(2.5 / 3, c.phase(), 1e-9);
        assertEquals(3, ConductorPattern.conduct(beat(2, 0.5, 3, 4, 600)).beats());
    }

    @Test void theCutoffIsAClosedLoopThatSettles() {
        assertArrayEquals(new double[]{0, 0.35}, ConductorPattern.cutoff(0), 1e-9);
        assertArrayEquals(new double[]{0, 0.35}, ConductorPattern.cutoff(1), 1e-9);
        assertArrayEquals(new double[]{0, 0.35}, ConductorPattern.cutoff(2), 1e-9);
    }
}
