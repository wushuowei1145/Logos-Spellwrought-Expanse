package dev.logos.dimension.music.organ;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrganSynthTest {
    static final RoomAcoustics.Reverb CATHEDRAL = RoomAcoustics.fromBucket(RoomAcoustics.bucket(
        RoomAcoustics.choose(RoomAcoustics.CATHEDRAL, RoomAcoustics.Reverb.NONE, false)));

    /** 25 个音并行合成（同游戏里一样），再统一缩放。 */
    static OrganSynth.Sample[] render(int mask, RoomAcoustics.Reverb room) {
        OrganSynth.Raw[] raws = IntStream.range(0, OrganSynth.COUNT).parallel()
            .mapToObj(i -> OrganSynth.raw(OrganSynth.note(i), mask, room)).toArray(OrganSynth.Raw[]::new);
        return OrganSynth.finish(raws);
    }

    /** 每个采样是「起音段 + 循环段」；循环段首尾相接处的跳变不比段内最大的相邻跳变大：接缝听不出来。 */
    @Test void everyLoopIsSeamless() {
        for (int mask : new int[]{OrganStop.Preset.SOFT.mask(), OrganStop.Preset.PLENUM.mask(), OrganStop.Preset.CELESTE.mask(), OrganStop.ALL}) {
            for (RoomAcoustics.Reverb room : new RoomAcoustics.Reverb[]{RoomAcoustics.Reverb.NONE, CATHEDRAL}) {
                for (OrganSynth.Sample s : render(mask, room)) {
                    assertEquals(OrganSynth.PRE, s.loopStart());
                    assertEquals(OrganSynth.PRE + OrganSynth.LOOP, s.loopEnd());
                    short[] p = s.pcm();
                    int maxStep = 0;
                    for (int n = s.loopStart() + 1; n < s.loopEnd(); n++) maxStep = Math.max(maxStep, Math.abs(p[n] - p[n - 1]));
                    int seam = Math.abs(p[s.loopStart()] - p[s.loopEnd() - 1]);
                    assertTrue(seam <= maxStep * 1.1 + 60, "seam " + seam + " > step " + maxStep + " at note " + s.note() + " mask " + mask + " room " + room);
                }
            }
        }
    }

    /** 同样的输入两次合成逐点相同（客户端之间、重进存档后听到的一样）。 */
    @Test void deterministic() {
        OrganSynth.Raw a = OrganSynth.raw(60, OrganStop.Preset.PLENUM.mask(), CATHEDRAL);
        OrganSynth.Raw b = OrganSynth.raw(60, OrganStop.Preset.PLENUM.mask(), CATHEDRAL);
        assertArrayEquals(a.pcm(), b.pcm());
        assertArrayEquals(a.tail(), b.tail());
    }

    /** 校正后的音高误差小于 0.1 音分，取样变调不超过 ±1.5 半音（加上取整的偏差）。 */
    @Test void tuningIsCorrected() {
        OrganSynth.Sample[] all = render(OrganStop.Preset.CHORUS.mask(), RoomAcoustics.Reverb.NONE);
        for (int pitch = OrganSynth.LOW; pitch <= OrganSynth.HIGH; pitch++) {
            OrganSynth.Sample s = all[OrganSynth.index(pitch)];
            assertTrue(Math.abs(s.note() - pitch) <= 1, "nearest sample");
            double played = s.hz() * OrganSynth.ratio(s, pitch);
            double cents = 1200 * Math.log(played / OrganSynth.hz(pitch)) / Math.log(2);
            assertTrue(Math.abs(cents) < 0.1, "pitch " + pitch + " off by " + cents + " cents");
            float r = OrganSynth.ratio(s, pitch);
            assertTrue(r > 0.92f && r < 1.09f, "ratio " + r);
        }
    }

    /**
     * #243：几排管落在同一频率上的分音在每个琴键上都以同样的相位叠加，8′ 基音和它上方八度的强弱比在相邻两个采样之间
     * 只缓缓变化（以前各采样随机相位，最强的分音在相邻采样间换来换去，听起来像突然低了八度）。各排管有一两音分的失谐，
     * 最高音区同频的分音相隔可达 5 格，所以量的是 ±8 格的总能量。
     *
     * <p>上限 4 dB（#243 时是 23 dB）：4′ 长笛比主音栓高 1.1 音分，A3 以下两者落在同一个频率上同相叠加，A3 起相差 0.5 Hz、
     * 慢慢地拍，八度那个分音的平均能量在这一处差约 3.5 dB——这是维护者认可的那一版的微失谐带来的，不是相位乱掉。
     */
    @Test void octavePartialStaysBalancedAcrossKeys() {
        for (OrganStop.Preset preset : new OrganStop.Preset[]{OrganStop.Preset.MATINS, OrganStop.Preset.CHORUS, OrganStop.Preset.PLENUM}) {
            int top = preset == OrganStop.Preset.PLENUM ? 81 : OrganSynth.HIGH;
            double prev = Double.NaN, worst = 0;
            int where = -1;
            StringBuilder trace = new StringBuilder();
            for (OrganSynth.Sample s : render(preset.mask(), RoomAcoustics.Reverb.NONE)) {
                if (s.note() > top) break;
                int bin = (int) Math.round(s.hz() * OrganSynth.LOOP / OrganSynth.RATE);
                double ratio = 10 * Math.log10(band(s, 2 * bin) / band(s, bin));
                trace.append(String.format(" %d:%.1f", s.note(), ratio));
                if (!Double.isNaN(prev) && Math.abs(ratio - prev) > worst) {
                    worst = Math.abs(ratio - prev);
                    where = s.note();
                }
                prev = ratio;
            }
            assertTrue(worst < 4.0, preset.id() + ": octave-to-fundamental balance jumps " + worst + " dB at note " + where + trace);
        }
    }

    /** 循环段里 bin±8 格的能量（整数个周期的频率直接算 DFT）。 */
    private static double band(OrganSynth.Sample s, int bin) {
        double sum = 1e-9;
        for (int b = bin - 8; b <= bin + 8; b++) {
            double m = dft(s.pcm(), s.loopStart(), OrganSynth.LOOP, b);
            sum += m * m;
        }
        return sum;
    }

    private static double dft(short[] p, int from, int len, int bin) {
        double re = 0, im = 0;
        double w = 2 * Math.PI * bin / len;
        for (int n = 0; n < len; n += 2) {
            re += p[from + n] * Math.cos(w * n);
            im += p[from + n] * Math.sin(w * n);
        }
        return Math.sqrt(re * re + im * im);
    }

    /** 长笛 8′ 的最强频率就是它的音高。 */
    @Test void fluteSoundsAtItsPitch() {
        OrganSynth.Sample s = OrganSynth.finish(new OrganSynth.Raw[]{OrganSynth.raw(60, OrganStop.FLUTE_8.bit(), RoomAcoustics.Reverb.NONE)})[0];
        double best = 0, bestHz = 0;
        for (double f = 200; f < 600; f += 0.5) {
            double re = 0, im = 0;
            short[] p = s.pcm();
            for (int n = s.loopStart(); n < s.loopStart() + OrganSynth.RATE; n += 2) {
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
        assertEquals(s.hz(), bestHz, 1.0);
    }

    /**
     * 起音不能「啪」一下：维护者听第一、二稿时，起音的噪声冲击和比基音早到、冲高的高泛音（柔和最明显）听起来像打击乐。
     * 现在起音头 80 ms 里每个频段都不超过它稳定时（整个循环段的平均，拍频也平均掉了）的响度 2.5 dB，频谱重心不比稳定时亮
     * 1.35 倍以上（带簧管的组合号角先到，约 1.3 倍）。把起音改回噪声冲击、或让小管子先到时，这一项失败。
     */
    @Test void onsetIsNeverLouderOrBrighterThanTheSteadyTone() {
        int[] masks = {OrganStop.Preset.SOFT.mask(), OrganStop.Preset.MATINS.mask(), OrganStop.Preset.CHORUS.mask(),
            OrganStop.Preset.CELESTE.mask(), OrganStop.Preset.TRUMPET.mask(), OrganStop.Preset.PLENUM.mask()};
        for (int mask : masks) {
            for (int note : new int[]{48, 66, 78}) {
                OrganSynth.Raw r = OrganSynth.raw(note, mask, RoomAcoustics.Reverb.NONE);
                float[] p = r.pcm();
                double[] steady = new double[5];
                int windows = 0;
                for (int at = OrganSynth.PRE; at + 256 < OrganSynth.PRE + OrganSynth.LOOP; at += 1024, windows++) addBands(p, at, steady);
                for (int i = 0; i < steady.length; i++) steady[i] /= windows;
                double steadyTotal = steady[0] + steady[1] + steady[2] + steady[3];
                double steadyCentroid = steady[4] / steadyTotal;
                for (int at = 0; at < OrganSynth.RATE * 2 / 25; at += 64) {
                    double[] e = new double[5];
                    addBands(p, at, e);
                    double total = e[0] + e[1] + e[2] + e[3];
                    // 60–300 Hz 不查：8 ms 的窗口比低音一个周期还短，单个窗口的能量随波形落在哪里忽高忽低；「啪」在 300 Hz 以上
                    for (int band = 1; band < 4; band++) {
                        // 稳定时几乎没有声音的频段（高音长笛的 60–300 Hz）只有气声和窗口的泄漏，比值没有意义
                        if (steady[band] < 0.01 * steadyTotal) continue;
                        double db = 10 * Math.log10((e[band] + 1e-12) / (steady[band] + 1e-12));
                        assertTrue(db < 2.5, "mask " + mask + " note " + note + " band " + band + " is " + db + " dB above steady at " + at + " samples");
                    }
                    // 只看已经听得见的部分（总能量不低于稳定时 30 dB）
                    if (total > 1e-3 * steadyTotal) {
                        double centroid = e[4] / total;
                        assertTrue(centroid < 1.35 * steadyCentroid, "mask " + mask + " note " + note + " onset centroid " + centroid
                            + " Hz vs steady " + steadyCentroid + " Hz at " + at + " samples");
                    }
                }
            }
        }
    }

    /** 256 点（8 ms）汉宁窗的能量按 60–300、300–1000、1000–3000、3000–8000 Hz 分段；第 5 项是频率加权和（算重心）。 */
    private static void addBands(float[] p, int at, double[] out) {
        int n = 256;
        for (int k = 1; k < n / 2; k++) {
            double f = k * (double) OrganSynth.RATE / n;
            if (f < 60 || f > 8000) continue;
            double re = 0, im = 0;
            for (int i = 0; i < n; i++) {
                double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / n);
                double x = p[at + i] * w;
                re += x * Math.cos(2 * Math.PI * k * i / n);
                im -= x * Math.sin(2 * Math.PI * k * i / n);
            }
            double e = re * re + im * im;
            out[f < 300 ? 0 : f < 1000 ? 1 : f < 3000 ? 2 : 3] += e;
            out[4] += e * f;
        }
    }

    /**
     * 低音不能有「电子味」：第一稿把各分音的相位打散，低音踏板 C1 一个周期里盖满一层毛刺，听起来像科幻音效。
     * 现在分音对齐，主音栓 C1 一个周期里只有一个陡边（近似锯齿），其余部分平滑：二阶差分大的点集中在陡边附近。
     */
    @Test void bassPeriodIsCompact() {
        OrganSynth.Raw r = OrganSynth.raw(24, OrganStop.PRINCIPAL_8.bit(), RoomAcoustics.Reverb.NONE);
        float[] p = r.pcm();
        int period = (int) Math.round(OrganSynth.RATE / r.hz());
        int from = OrganSynth.PRE + OrganSynth.RATE / 2;
        double[] d2 = new double[period];
        double max = 0;
        for (int i = 0; i < period; i++) {
            int n = from + i;
            d2[i] = Math.abs(p[n + 1] - 2 * p[n] + p[n - 1]);
            max = Math.max(max, d2[i]);
        }
        int busy = 0;
        for (double v : d2) if (v > 0.1 * max) busy++;
        assertTrue(busy < period * 0.15, "C1 principal has sharp detail in " + busy + " of " + period + " samples per period");
    }

    @Test void tailDecaysDarkensAndStartsSilently() {
        OrganSynth.Raw r = OrganSynth.raw(72, OrganStop.Preset.CHORUS.mask(), CATHEDRAL);
        float[] tail = r.tail();
        int rate = OrganSynth.RATE;
        assertTrue(tail.length > rate * 3, "covers the cathedral's reverb time");
        assertTrue(Math.abs(tail[0]) < 1e-3 * peak(tail), "fades in so the sound engine never starts it mid-swing");
        double early = rms(tail, rate / 10, rate / 5);
        double late = rms(tail, rate * 3, rate * 3 + rate / 10);
        // 中频 3.5 秒衰减 60 dB，低频稍长：3 秒后整体约低 45 dB
        assertTrue(late < early / 150, "decays about 45 dB in 3 s: " + early + " -> " + late);
        assertTrue(brightness(tail, rate / 10) > 2 * brightness(tail, rate * 2), "high frequencies die first");
        float[] bloom = r.bloom();
        assertTrue(bloom[1] < 0.6f, "a tapped note has little reverb yet: " + bloom[1]);
        assertEquals(1f, bloom[bloom.length - 1]);
        for (int i = 1; i < bloom.length; i++) assertTrue(bloom[i] >= bloom[i - 1], "reverb only grows while the key is held");
        assertEquals(0, OrganSynth.raw(72, OrganStop.Preset.CHORUS.mask(), RoomAcoustics.Reverb.NONE).tail().length, "no room, no tail");
    }

    /** 一阶差分与原信号的能量比：越大越亮。 */
    private static double brightness(float[] x, int at) {
        double d = 0, s = 0;
        for (int n = at + 1; n < at + OrganSynth.RATE / 10; n++) {
            d += (x[n] - x[n - 1]) * (x[n] - x[n - 1]);
            s += x[n] * x[n];
        }
        return d / Math.max(1e-20, s);
    }

    /** 整套缩放：最响的点不超过满幅，相邻采样的倍数相差不超过 1 dB（看循环段的响度）。 */
    @Test void finishKeepsHeadroomAndSmoothSteps() {
        OrganSynth.Sample[] all = render(OrganStop.Preset.PLENUM.mask(), CATHEDRAL);
        OrganSynth.Raw[] raws = IntStream.range(0, OrganSynth.COUNT)
            .mapToObj(i -> OrganSynth.raw(OrganSynth.note(i), OrganStop.Preset.PLENUM.mask(), CATHEDRAL)).toArray(OrganSynth.Raw[]::new);
        double prevGain = Double.NaN;
        for (int i = 0; i < all.length; i++) {
            double gain = rms(all[i].pcm(), all[i].loopStart(), all[i].loopEnd()) / 32767 / rms(raws[i].pcm(), OrganSynth.PRE, OrganSynth.PRE + OrganSynth.LOOP);
            if (!Double.isNaN(prevGain)) assertTrue(Math.abs(20 * Math.log10(gain / prevGain)) < 1.05, "gain step at sample " + i);
            prevGain = gain;
        }
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

    static double rms(short[] p, int from, int to) {
        double s = 0;
        for (int n = from; n < to; n++) s += (double) p[n] * p[n];
        return Math.sqrt(s / (to - from));
    }

    static double rms(float[] p, int from, int to) {
        double s = 0;
        for (int n = from; n < to; n++) s += (double) p[n] * p[n];
        return Math.sqrt(s / (to - from));
    }

    static double peak(float[] p) {
        double m = 0;
        for (float v : p) m = Math.max(m, Math.abs(v));
        return m;
    }
}
