package dev.logos.dimension.music.held;

import dev.logos.dimension.music.InstrumentKind;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HeldSynthTest {
    private static final InstrumentKind[] HELD = {InstrumentKind.VIOLIN, InstrumentKind.FLUTE, InstrumentKind.TRUMPET};

    /** 循环段首尾相接处的跳变不比段内最大的相邻跳变大（揉弦与气声都接得上）。 */
    @Test void loopsAreSeamless() {
        for (InstrumentKind kind : HELD) {
            for (int layer = 0; layer < 2; layer++) {
                for (HeldSynth.Sample s : HeldSynth.render(kind, layer)) {
                    short[] p = s.pcm();
                    int maxStep = 0;
                    for (int n = 1; n < p.length; n++) maxStep = Math.max(maxStep, Math.abs(p[n] - p[n - 1]));
                    int seam = Math.abs(p[0] - p[p.length - 1]);
                    assertTrue(seam <= maxStep * 1.05 + 60, kind + " layer " + layer + " note " + s.note() + ": seam " + seam + " > " + maxStep);
                }
            }
        }
    }

    @Test void tuningAndSampleChoice() {
        for (InstrumentKind kind : HELD) {
            HeldSynth.Sample[] all = HeldSynth.render(kind, 1);
            assertEquals(HeldSynth.count(kind), all.length);
            for (int pitch = kind.low(); pitch <= kind.high(); pitch++) {
                HeldSynth.Sample s = all[HeldSynth.index(kind, pitch)];
                assertTrue(Math.abs(s.note() - pitch) <= 1, kind + " " + pitch + " -> " + s.note());
                double cents = 1200 * Math.log(s.hz() * HeldSynth.ratio(s, pitch) / HeldSynth.hz(pitch)) / Math.log(2);
                assertTrue(Math.abs(cents) < 0.1, kind + " " + pitch + " off by " + cents);
            }
        }
    }

    /** 基音附近最强（长笛几乎只有基音），而且重的一层比轻的一层亮（高频能量占比更大）。 */
    @Test void pitchAndBrightness() {
        HeldSynth.Sample flute = HeldSynth.render(InstrumentKind.FLUTE, 0, 72);
        assertEquals(HeldSynth.hz(72), strongest(flute, 300, 1200), 3.0);
        for (InstrumentKind kind : new InstrumentKind[]{InstrumentKind.VIOLIN, InstrumentKind.TRUMPET}) {
            double soft = brightness(HeldSynth.render(kind, 0, 69)), loud = brightness(HeldSynth.render(kind, 1, 69));
            assertTrue(loud > soft, kind + " loud layer is brighter: " + soft + " vs " + loud);
        }
        assertEquals(0, HeldSynth.layer(60));
        assertEquals(1, HeldSynth.layer(100));
    }

    @Test void attacksFadeIn() {
        for (InstrumentKind kind : HELD) {
            short[] a = HeldSynth.attack(kind);
            assertTrue(a.length > 3000);
            assertTrue(Math.abs(a[0]) < 200, "starts quiet");
            assertThrows(IllegalArgumentException.class, () -> HeldSynth.attack(InstrumentKind.GRAND_PIANO));
        }
    }

    @Test void kindsAreHandheldAndSynthesized() {
        for (InstrumentKind kind : HELD) {
            assertTrue(kind.handheld() && kind.synthesized() && kind.nameKey().startsWith("item."));
        }
        assertFalse(InstrumentKind.GRAND_PIANO.handheld());
        assertTrue(InstrumentKind.GRAND_PIANO.nameKey().startsWith("block."));
    }

    private static double strongest(HeldSynth.Sample s, double from, double to) {
        double best = 0, bestHz = 0;
        short[] p = s.pcm();
        for (double f = from; f < to; f += 1) {
            double re = 0, im = 0;
            for (int n = 0; n < p.length; n += 2) {
                double w = 2 * Math.PI * f * n / HeldSynth.RATE;
                re += p[n] * Math.cos(w);
                im += p[n] * Math.sin(w);
            }
            double mag = re * re + im * im;
            if (mag > best) {
                best = mag;
                bestHz = f;
            }
        }
        return bestHz;
    }

    /** 一阶差分的能量与原信号能量之比：越亮越大。 */
    private static double brightness(HeldSynth.Sample s) {
        short[] p = s.pcm();
        double e = 0, d = 0;
        for (int n = 1; n < p.length; n++) {
            e += (double) p[n] * p[n];
            double diff = p[n] - p[n - 1];
            d += diff * diff;
        }
        return d / e;
    }
}
