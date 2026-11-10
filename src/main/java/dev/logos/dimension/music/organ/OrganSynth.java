package dev.logos.dimension.music.organ;

import java.util.SplittableRandom;

/**
 * 管风琴的合成：一套音栓组合 → 每隔 3 个半音一个、约 1 秒的无缝循环段（32 kHz 单声道 16 位）。
 *
 * <p>无缝的做法：循环段长 L 个采样点；以 16′ 的基音为单位，令它在 L 里正好 c 个周期，所有音栓的分音都是它的整数倍
 * （16′=½、8′=1、4′=2、2⅔′=3、2′=4、1⅓′=6、1′=8、⅔′=12 个 8′ 基音），所以在 L 里都是整数个周期；
 * 天籁的那一支多 1–2 个周期，拍频也正好接上。这样采样的实际基音会偏一点，记在 {@link Sample#hz()}，
 * 播放时按目标音高除以它来变调（{@link #ratio}）。
 *
 * <p>纯 Java、不依赖游戏类：客户端在后台线程调用，单元测试直接测。
 */
public final class OrganSynth {
    public static final int RATE = 32_000;
    public static final int LOW = 24;
    public static final int HIGH = 96;
    public static final int STEP = 3;
    public static final int COUNT = (HIGH - LOW) / STEP + 1;
    private static final double LOOP_SECONDS = 1.0;
    private static final double NYQUIST_GUARD = 0.42 * RATE;

    /** 一个采样：中心音高、实际基音频率、循环段 PCM。 */
    public record Sample(int note, double hz, short[] pcm) {}

    private OrganSynth() {}

    public static double hz(double midi) {
        return 440.0 * Math.pow(2.0, (midi - 69) / 12.0);
    }

    /** 离这个音最近的采样序号。 */
    public static int index(int pitch) {
        int p = Math.max(LOW, Math.min(HIGH, pitch));
        return Math.round((p - LOW) / (float) STEP);
    }

    /** 用 sample 播放 pitch 时的变调倍率（已经补上循环段的音高偏差）。 */
    public static float ratio(Sample sample, int pitch) {
        return (float) (hz(pitch) / sample.hz());
    }

    /** 合成整套组合。mask 为 0 时按长笛 8′ 处理。 */
    public static Sample[] render(int mask) {
        if (mask == 0) mask = OrganStop.ALWAYS;
        Sample[] out = new Sample[COUNT];
        for (int i = 0; i < COUNT; i++) out[i] = render(LOW + i * STEP, mask);
        return out;
    }

    public static Sample render(int note, int mask) {
        double f0 = hz(note);
        double fb = f0 / 2;                       // 16′ 的基音
        int c = Math.max(1, (int) Math.round(fb * LOOP_SECONDS));
        int len = (int) Math.round(c * RATE / fb);
        double unit = 2.0 * c / len;              // 8′ 基音：每个采样点走过的周期数
        double[] mix = new double[len];
        SplittableRandom rng = new SplittableRandom(note * 7919L + mask * 104729L);
        for (OrganStop stop : OrganStop.values()) {
            if (!stop.in(mask)) continue;
            switch (stop) {
                case FLUTE_8 -> rank(mix, 2 * c, len, stopped(), 0.8, rng);
                case FLUTE_4 -> rank(mix, 4 * c, len, new double[]{1, 0.12, 0.05, 0.02}, 0.5, rng);
                case BOURDON_16 -> rank(mix, c, len, stopped(), 0.7, rng);
                case PRINCIPAL_8 -> rank(mix, 2 * c, len, principal(f0), 1.0, rng);
                case OCTAVE_4 -> rank(mix, 4 * c, len, principal(f0 * 2), 0.7, rng);
                case FIFTEENTH_2 -> rank(mix, 8 * c, len, principal(f0 * 4), 0.5, rng);
                case MIXTURE -> {
                    // 四排：2′、1⅓′、1′、⅔′（8′ 基音的 4、6、8、12 倍）；超过约 4 kHz 的一排按八度折回
                    for (int mult : new int[]{4, 6, 8, 12}) {
                        double m = mult;
                        while (m * f0 > 4000 && m >= 2) m /= 2;
                        int cycles = (int) Math.round(2 * c * m);
                        rank(mix, cycles, len, new double[]{1, 0.4, 0.2, 0.1, 0.05}, 0.55 / 4 * (mult == 4 ? 1.4 : 1), rng);
                    }
                }
                case CELESTE_8 -> {
                    // 一对细弦音：一支准、一支多 j 个周期（约 j Hz 的慢拍）
                    int j = note < 48 ? 1 : 2;
                    rank(mix, 2 * c, len, string(f0), 0.35, rng);
                    rank(mix, 2 * c + j, len, string(f0), 0.35, rng);
                }
                case TRUMPET_8 -> rank(mix, 2 * c, len, trumpet(f0), 0.9, rng);
                case OBOE_8 -> rank(mix, 2 * c, len, oboe(f0), 0.7, rng);
            }
        }
        // 很轻的风声：本身就是循环段里的一段噪声，接缝处听不出来
        double wind = 0;
        for (int n = 0; n < len; n++) {
            wind += 0.08 * (rng.nextDouble() * 2 - 1 - wind);
            mix[n] += wind * 0.01;
        }
        double peak = 1e-9;
        for (double v : mix) peak = Math.max(peak, Math.abs(v));
        short[] pcm = new short[len];
        double gain = 0.9 * 32767 / peak;
        for (int n = 0; n < len; n++) pcm[n] = (short) Math.round(mix[n] * gain);
        return new Sample(note, unit * RATE, pcm);
    }

    /**
     * 叠加一排管：基音在循环段里 cycles 个周期，第 k 个分音 k·cycles 个周期；amps[k-1] 是第 k 个分音的强弱。
     * 用二阶递推（s[n] = 2cos(w)·s[n-1] − s[n-2]）生成正弦，比逐点调用 sin 快一个数量级。
     */
    private static void rank(double[] mix, int cycles, int len, double[] amps, double level, SplittableRandom rng) {
        double norm = 0;
        for (double a : amps) norm += a * a;
        norm = level / Math.sqrt(norm);
        for (int k = 1; k <= amps.length; k++) {
            double a = amps[k - 1] * norm;
            if (a < 1e-4) continue;
            long cyc = (long) k * cycles;
            double freq = (double) cyc * RATE / len;
            if (freq > NYQUIST_GUARD) break;
            // 高频渐弱，免得接近奈奎斯特的分音发刺
            a *= 1.0 / (1.0 + Math.pow(freq / 9000.0, 4));
            double w = 2 * Math.PI * cyc / len;
            double phase = rng.nextDouble() * 2 * Math.PI;
            double c2 = 2 * Math.cos(w);
            double s1 = Math.sin(phase - w), s0 = Math.sin(phase);
            for (int n = 0; n < len; n++) {
                mix[n] += a * s0;
                double s = c2 * s0 - s1;
                s1 = s0;
                s0 = s;
            }
        }
    }

    /** 闭管：几乎只有奇次泛音。 */
    private static double[] stopped() {
        return new double[]{1, 0, 0.12, 0, 0.04, 0, 0.015};
    }

    /** 主音栓：泛音丰富、逐渐减弱，第二泛音稍强。 */
    private static double[] principal(double f0) {
        double[] a = new double[16];
        for (int k = 1; k <= a.length; k++) a[k - 1] = Math.pow(k, -0.9) * Math.exp(-k * f0 / 6000.0) * (k == 2 ? 1.3 : 1);
        return a;
    }

    /** 细弦音：高泛音衰减很慢，音色细而亮。 */
    private static double[] string(double f0) {
        double[] a = new double[20];
        for (int k = 1; k <= a.length; k++) a[k - 1] = Math.pow(k, -0.6) * Math.exp(-k * f0 / 8000.0);
        return a;
    }

    /** 号角：频谱在 1–2 kHz 鼓起，很多泛音。 */
    private static double[] trumpet(double f0) {
        double[] a = new double[30];
        for (int k = 1; k <= a.length; k++) a[k - 1] = Math.pow(k, 0.35) * Math.exp(-k * f0 / 2200.0);
        return a;
    }

    /** 双簧管：1.4 kHz 附近一个共振峰，带鼻音。 */
    private static double[] oboe(double f0) {
        double[] a = new double[25];
        for (int k = 1; k <= a.length; k++) {
            double f = k * f0;
            a[k - 1] = 0.25 / k + Math.exp(-Math.pow((f - 1400) / 600, 2));
        }
        return a;
    }

    /**
     * 混响尾音：同一个循环段在 seconds 秒里按 60 dB 衰减，同时逐渐变暗（高频先消失）。开头 5 ms 淡入免得爆音。
     * brightness 0–1：墙越硬越亮。
     */
    public static short[] tail(short[] loop, double seconds, double brightness) {
        int len = (int) Math.min(RATE * 4.5, RATE * seconds * 1.15);
        short[] out = new short[Math.max(1, len)];
        double y = 0;
        double fadeIn = RATE * 0.005;
        for (int n = 0; n < out.length; n++) {
            double t = n / (double) RATE;
            double env = Math.exp(-6.91 * t / seconds) * Math.min(1, n / fadeIn);
            // 低通的系数从 1（不滤）慢慢降到 0.05
            double a = 0.05 + 0.95 * Math.exp(-t / (seconds * (0.25 + 0.5 * brightness)));
            y += a * (loop[n % loop.length] - y);
            out[n] = (short) Math.round(y * env);
        }
        return out;
    }
}
