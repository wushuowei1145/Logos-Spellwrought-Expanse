package dev.logos.dimension.music.organ;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrganSynthTest {
    /** 循环段首尾相接处的跳变不比段内最大的相邻跳变大：接缝听不出来。 */
    @Test void everyLoopIsSeamless() {
        for (int mask : new int[]{OrganStop.Preset.SOFT.mask(), OrganStop.Preset.PLENUM.mask(), OrganStop.Preset.CELESTE.mask(), OrganStop.ALL}) {
            for (OrganSynth.Sample s : OrganSynth.render(mask)) {
                short[] p = s.pcm();
                int maxStep = 0;
                for (int n = 1; n < p.length; n++) maxStep = Math.max(maxStep, Math.abs(p[n] - p[n - 1]));
                int seam = Math.abs(p[0] - p[p.length - 1]);
                assertTrue(seam <= maxStep * 1.05 + 60, "seam " + seam + " > step " + maxStep + " at note " + s.note() + " mask " + mask);
                assertTrue(p.length > OrganSynth.RATE * 0.9 && p.length < OrganSynth.RATE * 1.1, "about one second long");
            }
        }
    }

    /** 校正后的音高误差小于 0.1 音分，取样变调不超过 ±1 半音。 */
    @Test void tuningIsCorrected() {
        OrganSynth.Sample[] all = OrganSynth.render(OrganStop.Preset.CHORUS.mask());
        for (int pitch = OrganSynth.LOW; pitch <= OrganSynth.HIGH; pitch++) {
            OrganSynth.Sample s = all[OrganSynth.index(pitch)];
            assertTrue(Math.abs(s.note() - pitch) <= 1, "nearest sample");
            double played = s.hz() * OrganSynth.ratio(s, pitch);
            double cents = 1200 * Math.log(played / OrganSynth.hz(pitch)) / Math.log(2);
            assertTrue(Math.abs(cents) < 0.1, "pitch " + pitch + " off by " + cents + " cents");
            float r = OrganSynth.ratio(s, pitch);
            assertTrue(r > 0.93f && r < 1.07f, "ratio " + r);
        }
    }

    /**
     * #243：几排管落在同一频率上的分音（晨祷里 8′ 主音栓的二次泛音和 4′ 长笛的基音）在每个琴键上都以同样的相位叠加，
     * 8′ 基音和它上方八度的强弱比在相邻两个采样之间只缓缓变化，不会一会儿加强一会儿抵消（以前各采样随机相位，
     * 最强的分音在相邻采样间换来换去，听起来像突然低了八度）。
     */
    @Test void octavePartialStaysBalancedAcrossKeys() {
        // 全奏带混合音栓：它的几排在超过约 4 kHz 处按八度折回（C6 起 2′ 那排落到八度上、C7 落到同度上），
        // 那两处本来就会变，同真的混合音栓一样；只核对折回之前
        for (OrganStop.Preset preset : new OrganStop.Preset[]{OrganStop.Preset.MATINS, OrganStop.Preset.CHORUS, OrganStop.Preset.PLENUM}) {
            int top = preset == OrganStop.Preset.PLENUM ? 81 : OrganSynth.HIGH;
            double prev = Double.NaN, worst = 0;
            int where = -1;
            StringBuilder trace = new StringBuilder();
            for (OrganSynth.Sample s : OrganSynth.render(preset.mask())) {
                if (s.note() > top) break;
                short[] p = s.pcm();
                int bin = (int) Math.round(s.hz() * p.length / OrganSynth.RATE);
                double ratio = 20 * Math.log10(magnitude(p, 2 * bin) / magnitude(p, bin));
                trace.append(String.format(" %d:%.1f", s.note(), ratio));
                if (!Double.isNaN(prev) && Math.abs(ratio - prev) > worst) {
                    worst = Math.abs(ratio - prev);
                    where = s.note();
                }
                prev = ratio;
            }
            assertTrue(worst < 3.0, preset.id() + ": octave-to-fundamental balance jumps " + worst + " dB at note " + where + trace);
        }
    }

    /** 循环段里整数个周期的频率：直接算那一格的 DFT。 */
    private static double magnitude(short[] p, int bin) {
        double re = 0, im = 0;
        for (int n = 0; n < p.length; n++) {
            double w = 2 * Math.PI * (double) bin * n / p.length;
            re += p[n] * Math.cos(w);
            im += p[n] * Math.sin(w);
        }
        return Math.sqrt(re * re + im * im) + 1e-9;
    }

    /** 基音在频谱里：用 DFT 量一下 8′ 长笛的最强频率。 */
    @Test void fluteSoundsAtItsPitch() {
        OrganSynth.Sample s = OrganSynth.render(60, OrganStop.FLUTE_8.bit());
        double best = 0, bestHz = 0;
        for (double f = 200; f < 600; f += 0.5) {
            double re = 0, im = 0;
            short[] p = s.pcm();
            for (int n = 0; n < p.length; n += 2) {
                double w = 2 * Math.PI * f * n / OrganSynth.RATE;
                re += p[n] * Math.cos(w);
                im += p[n] * Math.sin(w);
            }
            double mag = re * re + im * im;
            if (mag > best) {
                best = mag;
                bestHz = f;
            }
        }
        assertEquals(OrganSynth.hz(60), bestHz, 1.5);
    }

    @Test void tailDecaysAndDarkens() {
        OrganSynth.Sample s = OrganSynth.render(72, OrganStop.Preset.CHORUS.mask());
        short[] tail = OrganSynth.tail(s.pcm(), 2.0, 0.7);
        assertTrue(tail.length > OrganSynth.RATE * 2, "covers the reverb time");
        double early = rms(tail, OrganSynth.RATE / 10, OrganSynth.RATE / 5);
        double late = rms(tail, OrganSynth.RATE * 2 - OrganSynth.RATE / 10, OrganSynth.RATE * 2);
        assertTrue(late < early / 100, "decays about 60 dB over the reverb time: " + early + " -> " + late);
        assertEquals(0, tail[0], 200, "fades in");
    }

    @Test void stopsAndPresets() {
        assertEquals(OrganStop.FLUTE_8.bit(), OrganStop.available(false, false, false));
        int tinOnly = OrganStop.available(false, true, false);
        assertTrue(OrganStop.PRINCIPAL_8.in(tinOnly) && OrganStop.MIXTURE.in(tinOnly) && !OrganStop.TRUMPET_8.in(tinOnly));
        assertEquals(OrganStop.ALL, OrganStop.available(true, true, true));
        assertTrue(OrganStop.loudness(OrganStop.Preset.PLENUM.mask()) > OrganStop.loudness(OrganStop.Preset.SOFT.mask()));
        assertTrue(OrganStop.loudness(OrganStop.ALL) <= 1.0);
        for (OrganStop.Preset p : OrganStop.Preset.values()) assertTrue(p.mask() != 0 && (p.mask() & ~OrganStop.ALL) == 0);
    }

    @Test void roomEstimates() {
        assertFalse(RoomAcoustics.estimate(0.3, 10, 0.05).on(), "open air has no reverb");
        var small = RoomAcoustics.estimate(1.0, 3, 0.1);
        var hall = RoomAcoustics.estimate(1.0, 12, 0.04);
        var cathedral = RoomAcoustics.estimate(1.0, 24, 0.03);
        var woolRoom = RoomAcoustics.estimate(1.0, 12, 0.6);
        assertTrue(small.seconds() < hall.seconds() && hall.seconds() < cathedral.seconds(), small + " " + hall + " " + cathedral);
        assertTrue(cathedral.seconds() <= RoomAcoustics.MAX_SECONDS);
        assertTrue(woolRoom.seconds() < hall.seconds() / 2, "wool absorbs");
        assertTrue(woolRoom.brightness() < hall.brightness());
        assertFalse(RoomAcoustics.choose(RoomAcoustics.AUTO, hall, true).on(), "auto yields to Sound Physics");
        assertTrue(RoomAcoustics.choose(RoomAcoustics.CATHEDRAL, RoomAcoustics.Reverb.NONE, true).on(), "manual modes still apply");
        assertFalse(RoomAcoustics.choose(RoomAcoustics.OFF, cathedral, false).on());
        var b = RoomAcoustics.fromBucket(RoomAcoustics.bucket(hall));
        assertEquals(hall.seconds(), b.seconds(), 0.13);
    }

    private static double rms(short[] p, int from, int to) {
        double s = 0;
        for (int n = from; n < to; n++) s += (double) p[n] * p[n];
        return Math.sqrt(s / (to - from));
    }
}
