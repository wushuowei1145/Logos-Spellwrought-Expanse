package dev.logos.dimension.music.held;

import dev.logos.dimension.music.InstrumentKind;
import java.util.SplittableRandom;

/**
 * 手持乐器（小提琴、长笛、小号）的合成：每隔 3 个半音一个、约 0.8 秒的无缝循环段，揉弦做进循环段里。
 *
 * <p>无缝的做法同管风琴（{@link dev.logos.dimension.music.organ.OrganSynth}）：循环段长 L，基音在 L 里正好 c 个周期，
 * 分音是基音的整数倍；揉弦的调频也在 L 里正好 m 个周期，所以相位
 * θ(n) = 2π·c·n/L + β·sin(2π·m·n/L) 在段尾正好回到起点。不靠每 tick 改音高（一秒只能改 20 次，会一顿一顿）。
 * 气声、弓噪是在频带里取整数周期的正弦叠成的噪声，同样能接上。实际基音有一点偏差，记在 {@link Sample#hz()}，
 * 播放时按目标音高补回。每种乐器两层（轻、重），重的那层更亮；起音的弓擦、气流、铜管的冲是一段与音高无关的短声。
 *
 * <p>纯 Java、不依赖游戏类：客户端在后台线程调用，单元测试直接测。
 */
public final class HeldSynth {
    public static final int RATE = 32_000;
    public static final int STEP = 3;
    private static final double LOOP_SECONDS = 0.8;
    private static final double NYQUIST_GUARD = 0.42 * RATE;

    /** 0 是轻的一层，1 是重的一层；力度不小于这个值时用重的一层。 */
    public static final int LOUD_FROM = 88;

    public record Sample(int note, double hz, short[] pcm) {}

    private record Voice(double vibratoHz, double vibratoDepth, double tremolo, double noiseLevel, double noiseLo, double noiseHi) {}

    private HeldSynth() {}

    public static double hz(double midi) {
        return 440.0 * Math.pow(2.0, (midi - 69) / 12.0);
    }

    public static int count(InstrumentKind kind) {
        return (kind.high() - kind.low()) / STEP + 1;
    }

    public static int index(InstrumentKind kind, int pitch) {
        int p = Math.max(kind.low(), Math.min(kind.high(), pitch));
        return Math.min(count(kind) - 1, Math.round((p - kind.low()) / (float) STEP));
    }

    public static float ratio(Sample s, int pitch) {
        return (float) (hz(pitch) / s.hz());
    }

    public static int layer(int velocity) {
        return velocity >= LOUD_FROM ? 1 : 0;
    }

    private static Voice voice(InstrumentKind kind) {
        return switch (kind) {
            case VIOLIN -> new Voice(5.6, 0.005, 0.04, 0.012, 2000, 6000);
            case FLUTE -> new Voice(5.0, 0.003, 0.08, 0.035, 1000, 5000);
            case TRUMPET -> new Voice(5.4, 0.0015, 0.03, 0.006, 1200, 4000);
            default -> throw new IllegalArgumentException("not a handheld instrument: " + kind);
        };
    }

    /** 第 k 个分音（频率 f）的强弱。 */
    private static double partial(InstrumentKind kind, int layer, int k, double f, double f0) {
        return switch (kind) {
            case VIOLIN -> {
                // 运弓的弦近似锯齿波（1/k），琴体共鸣：空气腔 ~275 Hz、主木板 ~460 Hz、~1 kHz，琴马 2–3 kHz 鼓起
                double body = peak(f, 275, 0.35, 2.0) * peak(f, 460, 0.3, 1.6) * peak(f, 1000, 0.4, 1.4) * peak(f, 2700, 0.55, 2.0);
                double roll = 1.0 / (1.0 + Math.pow(f / 5000.0, 4));
                double soft = layer == 0 ? 1.0 / (1.0 + Math.pow(f / 2500.0, 2)) : 1.0;
                yield body * roll * soft / k;
            }
            case FLUTE -> {
                double[] a = layer == 0 ? new double[]{1, 0.30, 0.10, 0.04, 0.015} : new double[]{1, 0.50, 0.22, 0.10, 0.05, 0.02};
                yield k <= a.length ? a[k - 1] : 0;
            }
            case TRUMPET -> {
                double bright = layer == 0 ? 900 : 1700;
                yield Math.pow(k, 1.0) * Math.exp(-k * f0 / bright) * peak(f, 1250, 0.5, 1.5) / (1.0 + Math.pow(f / 7000.0, 4));
            }
            default -> 0;
        };
    }

    /** 对数频率上的一个共振峰：中心 center，宽度 width（倍频程），增益 gain 倍。 */
    private static double peak(double f, double center, double width, double gain) {
        double x = Math.log(Math.max(f, 1) / center) / Math.log(2) / width;
        return 1 + (gain - 1) * Math.exp(-x * x);
    }

    public static Sample[] render(InstrumentKind kind, int layer) {
        Sample[] out = new Sample[count(kind)];
        for (int i = 0; i < out.length; i++) out[i] = render(kind, layer, kind.low() + i * STEP);
        return out;
    }

    public static Sample render(InstrumentKind kind, int layer, int note) {
        Voice v = voice(kind);
        double f0 = hz(note);
        int c = Math.max(1, (int) Math.round(f0 * LOOP_SECONDS));
        int len = (int) Math.round(c * RATE / f0);
        int m = Math.max(1, (int) Math.round(v.vibratoHz * len / RATE));
        double fv = (double) m * RATE / len;
        double beta = v.vibratoDepth * f0 / fv;     // 调频指数：揉弦的音高起伏
        double[] mix = new double[len];
        SplittableRandom rng = new SplittableRandom(kind.ordinal() * 7919L + note * 104729L + layer);
        double tremPhase = rng.nextDouble() * 2 * Math.PI;
        double norm = 0;
        for (int k = 1; k <= 60; k++) {
            double f = k * f0;
            if (f * (1 + v.vibratoDepth * 2) > NYQUIST_GUARD) break;
            double a = partial(kind, layer, k, f, f0);
            if (a < 1e-4) continue;
            norm += a * a;
            double phase0 = rng.nextDouble() * 2 * Math.PI;
            for (int n = 0; n < len; n++) {
                double t = (double) n / len;
                double theta = 2 * Math.PI * c * t + beta * Math.sin(2 * Math.PI * m * t);
                // 高分音的音量随揉弦起伏得更明显（共鸣峰跟着音高晃动的近似）
                double am = 1 + v.tremolo * Math.min(3, Math.sqrt(k)) * Math.sin(2 * Math.PI * m * t + tremPhase);
                mix[n] += a * am * Math.sin(k * theta + phase0);
            }
        }
        double tone = Math.sqrt(norm) + 1e-9;
        // 气声 / 弓噪：频带里整数周期的正弦，随机相位叠加，本身就能循环
        int lo = (int) Math.ceil(v.noiseLo * len / RATE), hi = (int) Math.floor(v.noiseHi * len / RATE);
        int bins = Math.max(1, (hi - lo) / Math.max(1, (hi - lo) / 160));
        double noiseGain = v.noiseLevel * tone * Math.sqrt(2.0 / bins);
        for (int b = 0; b < bins; b++) {
            int cyc = lo + (int) ((long) b * (hi - lo) / bins);
            double w = 2 * Math.PI * cyc / len, ph = rng.nextDouble() * 2 * Math.PI;
            double c2 = 2 * Math.cos(w), s1 = Math.sin(ph - w), s0 = Math.sin(ph);
            for (int n = 0; n < len; n++) {
                mix[n] += noiseGain * s0;
                double s = c2 * s0 - s1;
                s1 = s0;
                s0 = s;
            }
        }
        double peak = 1e-9;
        for (double x : mix) peak = Math.max(peak, Math.abs(x));
        short[] pcm = new short[len];
        double gain = 0.9 * 32767 / peak;
        for (int n = 0; n < len; n++) pcm[n] = (short) Math.round(mix[n] * gain);
        return new Sample(note, (double) c * RATE / len, pcm);
    }

    /** 起音的那一下：弓擦弦、吹气、铜管的冲。与音高无关，约 0.1 秒，开头 2 ms 淡入。 */
    public static short[] attack(InstrumentKind kind) {
        SplittableRandom rng = new SplittableRandom(kind.ordinal() * 31L + 7);
        double lo, hi, decay, level;
        switch (kind) {
            case VIOLIN -> { lo = 1500; hi = 6000; decay = 0.035; level = 0.5; }
            case FLUTE -> { lo = 800; hi = 4000; decay = 0.025; level = 0.6; }
            case TRUMPET -> { lo = 600; hi = 3000; decay = 0.020; level = 0.7; }
            default -> throw new IllegalArgumentException("not a handheld instrument: " + kind);
        }
        int len = (int) (RATE * 0.12);
        double[] out = new double[len];
        // 带通噪声：两级一阶滤波（高通 + 低通）
        double hpA = Math.exp(-2 * Math.PI * lo / RATE), lpA = 1 - Math.exp(-2 * Math.PI * hi / RATE);
        double prevX = 0, hp = 0, lp = 0;
        for (int n = 0; n < len; n++) {
            double x = rng.nextDouble() * 2 - 1;
            hp = hpA * (hp + x - prevX);
            prevX = x;
            lp += lpA * (hp - lp);
            double env = Math.exp(-n / (decay * RATE)) * Math.min(1, n / (RATE * 0.002));
            out[n] = lp * env;
        }
        double peak = 1e-9;
        for (double x : out) peak = Math.max(peak, Math.abs(x));
        short[] pcm = new short[len];
        for (int n = 0; n < len; n++) pcm[n] = (short) Math.round(out[n] / peak * level * 32767);
        return pcm;
    }
}
