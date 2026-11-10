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
