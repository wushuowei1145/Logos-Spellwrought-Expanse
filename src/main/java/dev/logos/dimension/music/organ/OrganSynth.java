package dev.logos.dimension.music.organ;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * 管风琴的合成：一套音栓组合 + 一种房间 → 每隔 3 个半音一个采样（C1–C7，25 个，32 kHz 单声道 16 位）。
 *
 * <p>每个采样是「起音段 + 循环段」：
 * <ul>
 *   <li><b>起音段</b>（{@link #PRE}，0.75 秒）：每排管的每个泛音各有自己的起振延迟、上升时间和冲高（加法合成里给每个泛音
 *       单独的起音，是 Aeolus 的做法）；长笛、主音栓的高泛音比基音早到几毫秒、略微冒头，弦音高泛音先到，
 *       簧管很快、音高从略低处滑上来。<b>不叠任何噪声冲击</b>：起音只是各泛音和气声按各自的快慢推起来，
 *       每个频段都不超过它稳定时的响度（噪声冲击听起来像一下鼓声，手持乐器和第一稿管风琴都踩过）。混响在这一段里渐渐长起来。</li>
 *   <li><b>循环段</b>（{@link #LOOP}，2¹⁶ 点 ≈ 2.05 秒）：稳定的音。每个分音都取成循环段里整数个周期，于是首尾正好接上；
 *       各排管彼此略有失谐（只在高音区才拍得出来，同真管风琴一样），每排管的音高与响度带着很慢的随机起伏，
 *       还有一层沿泛音分布的气声。这些起伏本身也是循环段里整数个周期的，所以同样无缝。</li>
 * </ul>
 * 房间开着时，干声先经 {@link OrganReverb} 走过起音段和两遍循环段：第二遍的湿声已经稳定，作为循环段的湿声；
 * 接着输入静音，得到的就是松键后的混响尾音（{@link Sample#tail()}）。{@link Sample#bloom()} 记着按住 n tick 时
 * 混响已经长到稳定值的几成，松得早的音尾音也相应轻。
 *
 * <p>采样的实际音高有一点偏差（16′ 基音取整数个周期），记在 {@link Sample#hz()}，播放时按目标音高除以它来变调（{@link #ratio}）。
 *
 * <p>纯 Java、不依赖游戏类：客户端在后台线程调用，单元测试直接测。音色参数都在本类里（{@link #ranks}、{@link Voice}）。
 */
public final class OrganSynth {
    public static final int RATE = 32_000;
    public static final int LOW = 24;
    public static final int HIGH = 96;
    public static final int STEP = 3;
    public static final int COUNT = (HIGH - LOW) / STEP + 1;
    /** 循环段长度（2¹⁶ 点）。 */
    public static final int LOOP = 1 << 16;
    /** 循环段之前的起音段长度。 */
    public static final int PRE = 24_000;
    /** 一个游戏刻的采样点数。 */
    public static final int TICK = RATE / 20;
    /** 播放时最多升 1.5 个半音，分音不超过 12.8 kHz，变调后仍低于奈奎斯特频率。 */
    private static final double NYQUIST_GUARD = 0.40 * RATE;
    /** 每排管最多这么多个泛音；最后三分之一渐弱到 0（硬截断会在波形陡边上留下振铃）。 */
    private static final int MAX_HARMONICS = 96;
    private static final int TAPER_FROM = 64;
    private static final double CENT = Math.log(2) / 1200;
    /** 起音段末尾把包络并到 1 的过渡长度。 */
    private static final int SETTLE = 3200;
    /** 起音段湿声并入稳定湿声的过渡长度。 */
    private static final int WET_BLEND = 6400;

    /**
     * 一个采样。
     *
     * @param note      中心音高
     * @param hz        实际的 8′ 基音频率
     * @param pcm       起音段 + 循环段
     * @param loopStart 循环起点（含）
     * @param loopEnd   循环终点（不含）
     * @param tail      松键后的混响尾音（没有房间时为空）
     * @param bloom     按住第 i 个游戏刻时，混响长到稳定值的几成（尾音音量乘它）
     */
    public record Sample(int note, double hz, short[] pcm, int loopStart, int loopEnd, short[] tail, float[] bloom) {
        public boolean hasTail() {
            return tail.length > 0;
        }

        /** 按住 ticks 个游戏刻后松键时，尾音的音量倍数。 */
        public float bloomAt(int ticks) {
            if (bloom.length == 0) return 1f;
            return bloom[Math.max(0, Math.min(bloom.length - 1, ticks))];
        }
    }

    private OrganSynth() {}

    public static double hz(double midi) {
        return 440.0 * Math.pow(2.0, (midi - 69) / 12.0);
    }

    /** 离这个音最近的采样序号。 */
    public static int index(int pitch) {
        int p = Math.max(LOW, Math.min(HIGH, pitch));
        return Math.round((p - LOW) / (float) STEP);
    }

    /** 第 i 个采样的中心音高。 */
    public static int note(int index) {
        return LOW + index * STEP;
    }

    /** 用 sample 播放 pitch 时的变调倍率（已经补上采样的音高偏差）。 */
    public static float ratio(Sample sample, int pitch) {
        return (float) (hz(pitch) / sample.hz());
    }

    /** 合成整套组合（逐个音依次算；客户端用 {@link #raw} 并行算再 {@link #finish}）。mask 为 0 时按长笛 8′ 处理。 */
    public static Sample[] render(int mask, RoomAcoustics.Reverb room) {
        Raw[] raws = new Raw[COUNT];
        for (int i = 0; i < COUNT; i++) raws[i] = raw(note(i), mask, room);
        return finish(raws);
    }

    /** 一个音缩放成 16 位之前的样子：起音段 + 循环段、尾音、混响长起来的程度。 */
    public record Raw(int note, double hz, float[] pcm, float[] tail, float[] bloom) {
        double peak() {
            double m = 0;
            for (float v : pcm) m = Math.max(m, Math.abs(v));
            for (float v : tail) m = Math.max(m, Math.abs(v));
            return m;
        }
    }

    /** 相邻两个采样的缩放倍数最多差这么多（dB）：弹半音阶跨过采样的分界时听不出台阶。 */
    private static final double STEP_DB = 1.0;

    /**
     * 一整套音缩放成 16 位：每个采样各自让最响的那个点到 1.1 倍满幅，再经 {@link #KNEE} 以上的软拐点压回满幅以内
     * （只有最高的那几个尖峰被压）。各采样的倍数再拉平到相邻相差不超过 {@link #STEP_DB}（只往小里改，不会削波）：
     * 低音波形尖、峰值高，若整套共用一个倍数，高音会被低音压轻好几 dB。尾音用同一个采样的倍数。
     * 不同组合之间的响度差由播放音量（{@link OrganStop#loudness}）给。
     */
    public static Sample[] finish(Raw[] raws) {
        int n = raws.length;
        double[] gain = new double[n];
        for (int i = 0; i < n; i++) gain[i] = 1.1 / Math.max(1e-9, raws[i].peak());
        double step = Math.pow(10, STEP_DB / 20);
        for (boolean changed = true; changed; ) {
            changed = false;
            for (int i = 0; i < n; i++) {
                double limit = Math.min(i > 0 ? gain[i - 1] * step : Double.MAX_VALUE, i < n - 1 ? gain[i + 1] * step : Double.MAX_VALUE);
                if (gain[i] > limit * (1 + 1e-12)) {
                    gain[i] = limit;
                    changed = true;
                }
            }
        }
        Sample[] out = new Sample[n];
        for (int i = 0; i < n; i++) {
            Raw r = raws[i];
            out[i] = new Sample(r.note(), r.hz(), toShort(r.pcm(), gain[i]), PRE, PRE + LOOP, toShort(r.tail(), gain[i]), r.bloom());
        }
        return out;
    }

    // —— 音色 ——

    /** 一类管子的发声方式：起音、滑音、起伏、气声。 */
    enum Voice {
        /** 闭管长笛、低音 16′（木）。 */
        STOPPED(-10, 0.018, 0.4, 0.020, 0.010),
        /** 开管长笛（木）。 */
        OPEN_FLUTE(-8, 0.016, 0.4, 0.020, 0.008),
        /** 主音栓一族（锡）：主音栓、八度、十五度、混合音栓。 */
        PRINCIPAL(-6, 0.020, 0.35, 0.015, 0.005),
        /** 细弦音（锡）：天籁。 */
        STRING(-6, 0.030, 0.6, 0.030, 0.004),
        /** 号角（簧管）。 */
        TRUMPET(-20, 0.016, 0.8, 0.030, 0.001),
        /** 双簧管（簧管）。 */
        OBOE(-16, 0.018, 0.8, 0.030, 0.001);

        /** 起音时的音高（音分，负为偏低）与它回到准音的时间常数（秒）。 */
        final double glide, glideTau;
        /** 稳定后音高缓慢起伏的幅度（音分，均方根）。 */
        final double wander;
        /** 响度缓慢起伏的幅度（均方根，倍数）。 */
        final double am;
        /** 持续气声（相对这排管的响度）。 */
        final double breath;

        Voice(double glide, double glideTau, double wander, double am, double breath) {
            this.glide = glide;
            this.glideTau = glideTau;
            this.wander = wander;
            this.am = am;
            this.breath = breath;
        }

        /** 起音随音高变快：以中央 C 为准，低音慢、高音快。 */
        static double speed(double fr) {
            return Math.max(0.6, Math.min(2.2, Math.pow(fr / 261.6, -0.35)));
        }

        /** 基音这么多毫秒开始长、这么快长足（上升时间常数），以中央 C 为准。 */
        double delay() {
            return 0.003;
        }

        double rise() {
            return switch (this) {
                case STOPPED -> 0.022;
                case OPEN_FLUTE, PRINCIPAL -> 0.020;
                case STRING -> 0.030;
                case TRUMPET -> 0.012;
                case OBOE -> 0.014;
            };
        }

        /**
         * 一个分音的起音：{开始的延迟, 上升时间常数, 冲高（稳定值的倍数）, 冲高的时刻}，单位秒。
         * 包络 = (1 − e^(−u/τ))² + 冲高·(u/t₀)·e^(1 − u/t₀)，u 为延迟之后的时间；平方让开头是缓的 S 形。
         *
         * <p>快慢只看琴键的音高（{@code fKey}），不看这排管自己的音高：同一个键上 8′、4′、2′、混合音栓一起起音。
         * 分音越高（{@code h8} = 相对 8′ 基音的倍数）开始得越晚一点、长得越慢一点，所以起音先暗后亮，像音在「开花」。
         * 反过来（小管子、高泛音先到，或高泛音冲高）起音头几十毫秒会比稳定时亮一截，柔和那样近乎纯音的音栓尤其听得出，
         * 像「嗒」的一下；第一、二稿都踩过。簧管快，但同样不冲高。
         */
        double[] speech(double h8, double fKey) {
            double s = speed(fKey);
            double lh = Math.log(Math.max(1, h8)) / Math.log(2);
            double late = reed() ? 0.0005 : 0.0008;
            double slower = reed() ? 0.15 : 0.12;
            return new double[]{(delay() + late * lh) * s, rise() * (1 + slower * lh) * s, 0, 0};
        }

        /** 第 k 个泛音（频率 f）稳定时的强弱，未归一。 */
        double harmonic(int k, double f, double fr, boolean bourdon) {
            double treble = fr > 523 ? Math.pow(fr / 523, -0.6) : 1;
            return switch (this) {
                case STOPPED -> {
                    double[] a = bourdon
                        ? new double[]{1, 0.03, 0.20, 0.012, 0.06, 0.006, 0.02, 0.004, 0.008, 0.002, 0.004}
                        : new double[]{1, 0.025, 0.14, 0.012, 0.045, 0.006, 0.018, 0.004, 0.008, 0.002, 0.004};
                    yield k > a.length ? 0 : a[k - 1] * (k == 1 ? 1 : treble);
                }
                case OPEN_FLUTE -> {
                    double[] a = {1, 0.18, 0.08, 0.035, 0.016, 0.008, 0.004};
                    yield k > a.length ? 0 : a[k - 1] * (k == 1 ? 1 : treble);
                }
                // 低音管的泛音按次数再收一点（第 24 次约 −3 dB），低音圆润、不发「嗞」
                case PRINCIPAL -> Math.pow(k, -0.85) * (k == 2 ? 1.25 : 1) / (1 + Math.pow(k / 24.0, 2)) / (1 + Math.pow(f / 5200, 2));
                case STRING -> Math.pow(k, -0.5) * (k >= 2 && k <= 6 ? 1.25 : 1) * (k == 1 ? 0.8 : 1) / (1 + Math.pow(f / 7500, 2));
                case TRUMPET -> Math.pow(k, -0.25) * (k == 1 ? 0.65 : 1)
                    * (1 + 2.2 * Math.exp(-Math.pow((f - 1500) / 900, 2)) + 0.8 * Math.exp(-Math.pow((f - 3200) / 1400, 2)))
                    / (1 + Math.pow(f / 8000, 2));
                case OBOE -> (0.35 / k + 1.3 * Math.exp(-Math.pow((f - 1350) / 450, 2)) + 0.45 * Math.exp(-Math.pow((f - 2900) / 800, 2)))
                    * (k == 1 ? 0.5 : 1) / (1 + Math.pow(f / 7000, 2));
            };
        }

        boolean reed() {
            return this == TRUMPET || this == OBOE;
        }
    }

    /** 一排管：音高（相对 8′ 琴键）、失谐（音分）、响度、发声方式。seed 决定它的随机起伏和逐个泛音的小差异。 */
    record Rank(OrganStop stop, int index, double mult, double detune, double level, Voice voice, boolean bourdon) {
        long seed(int note) {
            return note * 0x9E3779B97F4A7C15L + stop.ordinal() * 0xC2B2AE3D27D4EB4FL + index * 0x165667B19E3779F9L;
        }
    }

    /** 每个音栓有哪几排管（只在这里调音色、响度、失谐）。 */
    static List<Rank> ranks(int note, int mask) {
        if (mask == 0) mask = OrganStop.ALWAYS;
        double f8 = hz(note);
        List<Rank> out = new ArrayList<>();
        for (OrganStop stop : OrganStop.values()) {
            if (!stop.in(mask)) continue;
            switch (stop) {
                case FLUTE_8 -> out.add(new Rank(stop, 0, 1, -0.9, 0.75, Voice.STOPPED, false));
                case FLUTE_4 -> out.add(new Rank(stop, 0, 2, 1.1, 0.42, Voice.OPEN_FLUTE, false));
                case BOURDON_16 -> out.add(new Rank(stop, 0, 0.5, -0.6, 0.70, Voice.STOPPED, true));
                case PRINCIPAL_8 -> out.add(new Rank(stop, 0, 1, 0, 0.80, Voice.PRINCIPAL, false));
                case OCTAVE_4 -> out.add(new Rank(stop, 0, 2, 0.8, 0.55, Voice.PRINCIPAL, false));
                case FIFTEENTH_2 -> out.add(new Rank(stop, 0, 4, -0.7, 0.40, Voice.PRINCIPAL, false));
                case MIXTURE -> {
                    // 四排：2′、1⅓′、1′、⅔′；超过约 4.8 kHz 的一排按八度折回（真混合音栓的 break），每根管随机差一两音分
                    double[] mults = {4, 6, 8, 12};
                    double[] levels = {0.30, 0.23, 0.21, 0.16};
                    SplittableRandom rng = new SplittableRandom(note * 31L + 7);
                    for (int i = 0; i < mults.length; i++) {
                        double m = mults[i];
                        while (m * f8 > 4800 && m > 1) m /= 2;
                        out.add(new Rank(stop, i, m, (rng.nextDouble() * 2 - 1) * 1.6, levels[i], Voice.PRINCIPAL, false));
                    }
                }
                case CELESTE_8 -> {
                    // 一对弦音管：一支准，一支偏高，拍频从低音约 0.9 次/秒到高音约 4 次/秒
                    double beat = 0.7 + f8 / 350;
                    double cents = 1200 * Math.log(1 + beat / f8) / Math.log(2);
                    out.add(new Rank(stop, 0, 1, -0.3, 0.38, Voice.STRING, false));
                    out.add(new Rank(stop, 1, 1, cents, 0.38, Voice.STRING, false));
                }
                case TRUMPET_8 -> out.add(new Rank(stop, 0, 1, -1.2, 0.85, Voice.TRUMPET, false));
                case OBOE_8 -> out.add(new Rank(stop, 0, 1, 1.4, 0.55, Voice.OBOE, false));
            }
        }
        return out;
    }

    // —— 合成 ——

    /** 一个分音。 */
    private static final class Partial {
        int rankIndex;
        double kRel;      // 相对这排管基音的倍数（音高起伏、滑音按它放大）
        double omega;     // 每采样点的弧度（整数个周期 / LOOP）
        double phase;     // 循环起点处的相位
        double amp;
        double delay, tau, peak, peakTau;
    }

    /** 一排管的慢起伏：音高（相位）与响度，都是循环段里整数个周期的正弦之和；另有起音的滑音。 */
    private static final class Drift {
        final double[] pa = new double[5], pf = new double[5], pp = new double[5];
        final double[] aa = new double[5], af = new double[5], ap = new double[5];
        double glide;     // 起音滑音的相位深度（弧度，乘以 kRel）
        double glideTau;  // 采样点
        double am;

        /** 这排管基音上的相位偏移（弧度）。 */
        double psi(int n) {
            double s = 0;
            for (int j = 0; j < pa.length; j++) s += pa[j] * Math.sin(pf[j] * n + pp[j]);
            if (glide != 0 && n < PRE) s += glide * Math.exp(-n / glideTau);
            return s;
        }

        double gain(int n) {
            double s = 0;
            for (int j = 0; j < aa.length; j++) s += aa[j] * Math.sin(af[j] * n + ap[j]);
            return 1 + am * s;
        }
    }

    /** 合成一个音（纯计算、线程安全，可以并行调用）。 */
    public static Raw raw(int note, int mask, RoomAcoustics.Reverb room) {
        if (mask == 0) mask = OrganStop.ALWAYS;
        double f8 = hz(note);
        int c16 = Math.max(1, (int) Math.round(f8 / 2 * LOOP / RATE));
        double fq = 2.0 * c16 * RATE / LOOP;      // 采样实际的 8′ 基音
        List<Rank> ranks = ranks(note, mask);
        int total = PRE + LOOP;

        List<Partial> partials = new ArrayList<>();
        Drift[] drifts = new Drift[ranks.size()];
        for (int ri = 0; ri < ranks.size(); ri++) {
            Rank r = ranks.get(ri);
            double fr = r.mult() * fq * Math.pow(2, r.detune() / 1200);
            SplittableRandom rng = new SplittableRandom(r.seed(note));
            drifts[ri] = drift(r, fr, rng);
            double slope = Math.pow(f8 / 261.6, r.voice().reed() ? -0.06 : -0.03);
            List<Partial> mine = new ArrayList<>();
            double norm = 0;
            for (int k = 1; k <= MAX_HARMONICS; k++) {
                double f = k * fr;
                if (f > NYQUIST_GUARD) break;
                double a = r.voice().harmonic(k, f, fr, r.bourdon());
                if (a <= 0) continue;
                // 逐个泛音约 ±1 dB 的小差异：每根管都不完全一样
                a *= Math.pow(10, (rng.nextDouble() * 2 - 1) * 0.05);
                // 高频渐弱，免得接近奈奎斯特的分音发刺
                a /= 1 + Math.pow(f / 10_000, 4);
                if (k > TAPER_FROM) a *= 0.5 + 0.5 * Math.cos(Math.PI * (k - TAPER_FROM) / (MAX_HARMONICS + 1 - TAPER_FROM));
                norm += a * a;
                int cycles = (int) Math.round(f * LOOP / RATE);
                double[] sp = r.voice().speech(k * r.mult(), f8);
                double jitter = 1 + (rng.nextDouble() * 2 - 1) * 0.08;
                if (cycles <= 0) continue;
                Partial p = new Partial();
                p.rankIndex = ri;
                p.kRel = cycles / (fr * LOOP / RATE);
                p.omega = 2 * Math.PI * cycles / LOOP;
                // 所有分音在循环起点同为正弦零相位：一个周期里波形是紧凑的（主音栓近似锯齿、闭管近似方波、簧管近似脉冲），
                // 同真管子一样；几排管落在同一频率上时在每个琴键上都同样叠加（#243）。不能把相位打散：低音区一个周期长达
                // 30–60 ms，耳朵听得出周期里的细节，打散的相位会变成一层「沙沙」的电子味（第一稿的低音踏板）
                p.phase = 0;
                p.amp = a;
                p.delay = sp[0] * jitter;
                p.tau = sp[1];
                p.peak = sp[2];
                p.peakTau = sp[3];
                mine.add(p);
            }
            double scale = r.level() * slope / Math.sqrt(Math.max(1e-12, norm));
            for (Partial p : mine) {
                p.amp *= scale;
                if (p.amp > 1e-5) partials.add(p);
            }
        }

        double[] dry = new double[total];
        synthesize(partials, drifts, dry);
        noise(ranks, note, fq, dry);

        float[] tail = new float[0];
        float[] bloom = new float[0];
        if (room.on()) {
            OrganReverb.Room params = OrganReverb.Room.of(room);
            OrganReverb rev = new OrganReverb(params, RATE);
            double[] wet = new double[total];
            double[] wetLoop = new double[LOOP];
            // 第一遍：起音段 + 一遍循环段（混响在长）；第二遍循环段的湿声已经稳定；然后输入静音得到尾音
            for (int n = 0; n < total; n++) {
                double y = rev.process(dry[n]);
                if (n < PRE) wet[n] = y;
            }
            for (int n = 0; n < LOOP; n++) wetLoop[n] = rev.process(dry[PRE + n]);
            int tailLen = (int) Math.min(RATE * 4.6, params.length() * RATE);
            double[] tailWet = new double[tailLen];
            for (int n = 0; n < tailLen; n++) tailWet[n] = rev.process(0);
            // 尾音开头 10 ms 淡入：声音引擎开始播放时不会从半路的振幅一下跳出来
            int in = RATE / 100;
            for (int n = 0; n < Math.min(in, tailLen); n++) tailWet[n] *= 0.5 - 0.5 * Math.cos(Math.PI * n / in);
            // 起音段的湿声在最后 0.2 秒并进稳定的湿声
            System.arraycopy(wetLoop, 0, wet, PRE, LOOP);
            for (int n = PRE - WET_BLEND; n < PRE; n++) {
                double w = smooth((n - (PRE - WET_BLEND)) / (double) WET_BLEND);
                wet[n] = (1 - w) * wet[n] + w * wetLoop[(n - PRE + LOOP) % LOOP];
            }
            // 混响长到稳定值的几成：按刻取 0.1 秒窗口的均方根，只增不减、不超过 1（拍频会让短窗口忽高忽低）
            double steady = rms(wetLoop, 0, LOOP);
            bloom = new float[PRE / TICK + 1];
            float best = 0;
            for (int t = 0; t < bloom.length; t++) {
                int from = Math.max(0, t * TICK - TICK), to = Math.min(PRE, t * TICK + TICK);
                float b = t == bloom.length - 1 ? 1f : (float) Math.min(1, rms(wet, from, Math.max(from + 1, to)) / Math.max(1e-12, steady));
                best = Math.max(best, b);
                bloom[t] = best;
            }
            // 尾音的末尾 0.2 秒淡出到 0
            int fade = Math.min(tailLen, RATE / 5);
            for (int n = tailLen - fade; n < tailLen; n++) tailWet[n] *= (tailLen - n) / (double) fade;
            double w = params.wet();
            for (int n = 0; n < total; n++) dry[n] += w * wet[n];
            tail = new float[tailLen];
            for (int n = 0; n < tailLen; n++) tail[n] = (float) (tailWet[n] * w);
        }
        float[] pcm = new float[total];
        for (int n = 0; n < total; n++) pcm[n] = (float) dry[n];
        return new Raw(note, fq, pcm, tail, bloom);
    }

    private static Drift drift(Rank r, double fr, SplittableRandom rng) {
        Drift d = new Drift();
        Voice v = r.voice();
        int[] pj = {1, 2, 3, 4, 6};
        int[] aj = {1, 2, 3, 5, 7};
        double seconds = LOOP / (double) RATE;
        // 音高起伏：频率偏差 D(t) = Σ aⱼ·cos(2πjt/T + φⱼ)（均方根 = wander 音分），相位是它的积分
        double[] a = new double[pj.length];
        double sum = 0;
        for (int j = 0; j < pj.length; j++) {
            a[j] = (0.5 + rng.nextDouble()) / Math.sqrt(pj[j]);
            sum += a[j] * a[j] / 2;
        }
        double hzRms = fr * v.wander * CENT;
        for (int j = 0; j < pj.length; j++) {
            double dj = a[j] / Math.sqrt(sum) * hzRms;        // 这一项的频率偏差幅度（Hz）
            d.pa[j] = dj * seconds / pj[j];                    // ∫2π·dj·cos(2πjt/T) dt = dj·T/j·sin(…)
            d.pf[j] = 2 * Math.PI * pj[j] / LOOP;
            d.pp[j] = rng.nextDouble() * 2 * Math.PI;
        }
        double asum = 0;
        for (int j = 0; j < aj.length; j++) {
            d.aa[j] = (0.5 + rng.nextDouble()) / Math.sqrt(aj[j]);
            asum += d.aa[j] * d.aa[j] / 2;
            d.af[j] = 2 * Math.PI * aj[j] / LOOP;
            d.ap[j] = rng.nextDouble() * 2 * Math.PI;
        }
        for (int j = 0; j < aj.length; j++) d.aa[j] /= Math.sqrt(asum);
        d.am = v.am;
        // 滑音：音高从 glide 音分处按 glideTau 回到准音；相位偏移 = −ω·c·δ₀·τ·e^(−t/τ)，t→∞ 时为 0，循环段里不留痕迹
        double tauN = v.glideTau * Voice.speed(fr) * RATE;
        d.glideTau = tauN;
        d.glide = -(2 * Math.PI * fr / RATE) * CENT * v.glide * tauN;
        return d;
    }

    /** 加法合成：分块（起音段 32 点、循环段 128 点）算出每个分音块首的精确相位与幅度，块内用复数旋转递推。 */
    private static void synthesize(List<Partial> partials, Drift[] drifts, double[] out) {
        int total = out.length;
        int blocks = PRE / 32 + LOOP / 128;
        int[] starts = new int[blocks + 1];
        int b = 0;
        for (int n = 0; n < PRE; n += 32) starts[b++] = n;
        for (int n = PRE; n < total; n += 128) starts[b++] = n;
        starts[b] = total;
        // 每排管在各块边界的相位偏移和响度起伏（同一排管的所有分音共用）
        double[][] psi = new double[drifts.length][blocks + 1];
        double[][] am = new double[drifts.length][blocks + 1];
        for (int r = 0; r < drifts.length; r++) {
            for (int i = 0; i <= blocks; i++) {
                // 循环终点与循环起点是同一个相位：直接取起点的值，保证首尾一致
                int n = starts[i] == total ? PRE : starts[i];
                psi[r][i] = drifts[r].psi(n);
                am[r][i] = drifts[r].gain(n);
            }
        }
        for (Partial p : partials) {
            double[] ps = psi[p.rankIndex], ag = am[p.rankIndex];
            double prevAmp = p.amp * envelope(p, 0) * ag[0];
            for (int i = 0; i < blocks; i++) {
                int n0 = starts[i], n1 = starts[i + 1], len = n1 - n0;
                double th0 = p.omega * (n0 - PRE) + p.phase + p.kRel * ps[i];
                double th1 = p.omega * (n1 - PRE) + p.phase + p.kRel * ps[i + 1];
                double a0 = prevAmp;
                double a1 = p.amp * envelope(p, n1) * ag[i + 1];
                prevAmp = a1;
                if (a0 < 1e-7 && a1 < 1e-7) continue;
                double step = (th1 - th0) / len;
                double cr = Math.cos(step), ci = Math.sin(step);
                double zr = Math.cos(th0), zi = Math.sin(th0);
                double a = a0, da = (a1 - a0) / len;
                for (int n = n0; n < n1; n++) {
                    out[n] += a * zi;
                    double t = zr * cr - zi * ci;
                    zi = zr * ci + zi * cr;
                    zr = t;
                    a += da;
                }
            }
        }
    }

    /** 分音的起音包络；起音段末尾并到 1，循环段里恒为 1。 */
    private static double envelope(Partial p, int n) {
        if (n >= PRE) return 1;
        double t = n / (double) RATE;
        double u = t - p.delay;
        double e = 0;
        if (u > 0) {
            double rise = 1 - Math.exp(-u / p.tau);
            e = rise * rise;
            if (p.peak > 0) e += p.peak * (u / p.peakTau) * Math.exp(1 - u / p.peakTau);
        }
        if (n > PRE - SETTLE) {
            double w = smooth((n - (PRE - SETTLE)) / (double) SETTLE);
            e = e * (1 - w) + w;
        }
        return e;
    }

    /**
     * 气声：白噪声经各排管前四个泛音处的共振滤波（循环段里正好首尾相接），再加很轻的一点嘶声。起音时随音一起
     * 按约 30 ms 的 S 形推上来，没有单独的噪声冲击。
     */
    private static void noise(List<Rank> ranks, int note, double fq, double[] out) {
        Map<Long, double[]> breath = new LinkedHashMap<>();   // 频率 → {频率, 幅度}
        double hiss = 0;
        double[] bw = {1, 0.55, 0.35, 0.2};
        for (Rank r : ranks) {
            Voice v = r.voice();
            double fr = r.mult() * fq * Math.pow(2, r.detune() / 1200);
            double level = r.level() * Math.pow(hz(note) / 261.6, -0.03);
            if (v.breath > 0) {
                for (int k = 1; k <= bw.length; k++) add(breath, k * fr, bw[k - 1] * v.breath * level);
                hiss += 0.12 * v.breath * level;
            }
        }
        SplittableRandom rng = new SplittableRandom(note * 0x2545F4914F6CDD1DL + 99);
        // 持续气声：循环段长度的白噪声，滤两遍、取第二遍（滤波器的状态也绕了一圈），于是首尾相接
        if (!breath.isEmpty()) {
            double[] white = new double[LOOP];
            for (int n = 0; n < LOOP; n++) white[n] = rng.nextGaussian();
            double[] loop = new double[LOOP];
            for (double[] fa : breath.values()) resonate(white, loop, fa[0], 12, fa[1], true);
            if (hiss > 0) highpass(white, loop, 2500, hiss, true);
            // 起音段里随音一起推上来（S 形，约 30 ms）
            for (int n = 0; n < PRE; n++) {
                double rise = 1 - Math.exp(-n / (0.015 * RATE));
                out[n] += loop[(n - PRE + LOOP) % LOOP] * rise * rise;
            }
            for (int n = 0; n < LOOP; n++) out[PRE + n] += loop[n];
        }
    }

    private static void add(Map<Long, double[]> bank, double f, double a) {
        if (f > 8000 || a <= 0) return;
        long key = Math.round(f * 2);
        double[] fa = bank.get(key);
        if (fa == null) bank.put(key, new double[]{f, a});
        else fa[1] += a;
    }

    /**
     * 二阶带通（峰值增益 1）。输入单位方差白噪声时输出方差约 π·B/fs（B = f/Q），这里除掉它，使输出的均方根约为 gain。
     * periodic 时把输入当作首尾相接的一圈：先空滤一圈再记录。
     */
    private static void resonate(double[] in, double[] out, double f, double q, double gain, boolean periodic) {
        double w = 2 * Math.PI * f / RATE;
        double alpha = Math.sin(w) / (2 * q);
        double a0 = 1 + alpha;
        double b0 = alpha / a0, b2 = -alpha / a0, a1 = -2 * Math.cos(w) / a0, a2 = (1 - alpha) / a0;
        double scale = gain / Math.sqrt(Math.PI * (f / q) / RATE);
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        int n = in.length;
        for (int pass = periodic ? 0 : 1; pass < 2; pass++) {
            for (int i = 0; i < n; i++) {
                double x = in[i];
                double y = b0 * x + b2 * x2 - a1 * y1 - a2 * y2;
                x2 = x1;
                x1 = x;
                y2 = y1;
                y1 = y;
                if (pass == 1) out[i] += y * scale;
            }
        }
    }

    /** 一阶高通（嘶声）。 */
    private static void highpass(double[] in, double[] out, double f, double gain, boolean periodic) {
        double c = Math.exp(-2 * Math.PI * f / RATE);
        double prevIn = 0, prevOut = 0;
        for (int pass = periodic ? 0 : 1; pass < 2; pass++) {
            for (int i = 0; i < in.length; i++) {
                double y = c * (prevOut + in[i] - prevIn);
                prevIn = in[i];
                prevOut = y;
                if (pass == 1) out[i] += y * gain;
            }
        }
    }

    private static double smooth(double x) {
        x = Math.max(0, Math.min(1, x));
        return x * x * (3 - 2 * x);
    }

    private static double rms(double[] a, int from, int to) {
        double s = 0;
        for (int n = from; n < to; n++) s += a[n] * a[n];
        return Math.sqrt(s / Math.max(1, to - from));
    }

    /** 软拐点：这以下原样，以上用 tanh 渐近到满幅。 */
    private static final double KNEE = 0.8;

    private static short[] toShort(float[] x, double gain) {
        short[] out = new short[x.length];
        for (int n = 0; n < x.length; n++) {
            double v = x[n] * gain, a = Math.abs(v);
            if (a > KNEE) a = KNEE + (1 - KNEE) * Math.tanh((a - KNEE) / (1 - KNEE));
            out[n] = (short) Math.round(Math.copySign(Math.min(1, a), v) * 32767);
        }
        return out;
    }
}
