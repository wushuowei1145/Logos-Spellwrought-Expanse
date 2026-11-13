package dev.logos.dimension.music.organ;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrganReverbTest {
    /** 高频阻尼的一阶低通：在阻尼频率处的增益正好是要的那个值，直流为 1。 */
    @Test void dampingFilterHitsItsGain() {
        for (double g : new double[]{0.5, 0.8, 0.95, 0.995}) {
            double w = 2 * Math.PI * 4000 / 32000.0;
            double a = OrganReverb.dampCoefficient(g, w);
            double re = 1 - a * Math.cos(w), im = a * Math.sin(w);
            double gain = (1 - a) / Math.sqrt(re * re + im * im);
            assertEquals(g, gain, 1e-6, "g=" + g);
        }
    }

    /** 冲激响应：中频的混响时间接近设定值，高频（阻尼频率附近）短得多，低频不比中频短；总能量归一为 1。 */
    @Test void decayTimesFollowTheRoom() {
        for (double rt : new double[]{1.2, 3.5}) {
            OrganReverb.Room room = OrganReverb.Room.of(new RoomAcoustics.Reverb(rt, 0.8));
            OrganReverb r = new OrganReverb(room, OrganSynth.RATE);
            float[] ir = new float[(int) (room.length() * OrganSynth.RATE)];
            double energy = 0;
            for (int n = 0; n < ir.length; n++) {
                ir[n] = (float) r.process(n == 0 ? 1 : 0);
                energy += ir[n] * ir[n];
            }
            assertEquals(1.0, energy, 0.05, "normalized to unit energy");
            double mid = t30(ir, 700), high = t30(ir, room.dampHz()), low = t30(ir, 150);
            assertEquals(rt, mid, rt * 0.2, "mid-band RT60 for " + rt + " s");
            assertTrue(high < 0.8 * mid, "highs decay faster: " + high + " vs " + mid);
            assertTrue(low > 0.95 * mid, "lows ring at least as long: " + low + " vs " + mid);
        }
    }

    /** 某个频段的 T30（−5 到 −35 dB 的斜率外推到 60 dB），频段用二阶带通（Q≈1.4）。 */
    private static double t30(float[] ir, double f) {
        double w = 2 * Math.PI * f / OrganSynth.RATE;
        double alpha = Math.sin(w) / (2 * 1.4);
        double a0 = 1 + alpha, b0 = alpha / a0, b2 = -alpha / a0, a1 = -2 * Math.cos(w) / a0, a2 = (1 - alpha) / a0;
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        double[] e = new double[ir.length];
        for (int n = 0; n < ir.length; n++) {
            double y = b0 * ir[n] + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = ir[n];
            y2 = y1;
            y1 = y;
            e[n] = y * y;
        }
        for (int n = ir.length - 2; n >= 0; n--) e[n] += e[n + 1];
        int i5 = -1, i35 = -1;
        for (int n = 0; n < e.length; n++) {
            double db = 10 * Math.log10(e[n] / e[0] + 1e-30);
            if (i5 < 0 && db < -5) i5 = n;
            if (i35 < 0 && db < -35) {
                i35 = n;
                break;
            }
        }
        return (i35 - i5) * 2.0 / OrganSynth.RATE;
    }
}
