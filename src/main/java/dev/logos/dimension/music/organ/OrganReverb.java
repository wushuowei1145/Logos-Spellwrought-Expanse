package dev.logos.dimension.music.organ;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管风琴的房间混响：一个 8 路反馈延迟网络（FDN），纯 Java、逐采样点计算，单声道进、单声道出。
 *
 * <p>结构按公开文献自己实现（Jot 与 Chaigne 1991 的单位反馈 FDN；Schroeder 全通扩散），参数沿用 zita-rev1 使用说明里的
 * 那一套：预延迟、低频与中频两段混响时间、分频点、「混响时间减半处」的高频阻尼频率。没有拷贝 zita-rev1 或 Aeolus 的代码
 * （它们是 GPL）。
 *
 * <pre>
 * 输入 ─┬─ 早期反射（8 个抽头，7–60 ms）──────────────────────────────┐
 *       └─ 预延迟 → 4 级全通扩散 → ┌→ 8 条延迟线（各带一级全通）→ 两段衰减 → 高频阻尼 ┐ → 求和 ─┴→ 低切 → 输出
 *                                  └──────────── 8×8 哈达玛矩阵（正交、无损）←─────────┘
 * </pre>
 *
 * 每条延迟线每走一圈乘一次衰减：中频 g = 10^(−3·D/(RT·fs))，分频点以下换成低频的混响时间；高频阻尼是一个一阶低通，
 * 系数取到「在阻尼频率处再多衰减一个 g」，于是那里的混响时间正好是中频的一半。输出按冲激响应的总能量归一（湿声能量 1），
 * 调用方再乘自己的湿声比例。
 */
public final class OrganReverb {
    /** 延迟线的基准长度（采样点，32 kHz 时约 37–82 ms），按房间大小缩放。 */
    private static final int[] LINES = {1193, 1361, 1559, 1733, 1951, 2153, 2399, 2621};
    /** 每条线里那一级全通的长度。 */
    private static final int[] LINE_AP = {131, 173, 223, 263, 307, 349, 389, 433};
    /** 输入扩散的四级全通。 */
    private static final int[] DIFFUSE = {149, 113, 397, 293};
    /** 早期反射的时刻（ms）与强弱（正负交替）。 */
    private static final double[] ER_MS = {7.3, 11.9, 17.1, 23.7, 31.3, 38.9, 47.3, 58.1};
    private static final double[] ER_GAIN = {0.70, -0.58, 0.50, -0.44, 0.37, -0.31, 0.26, -0.21};
    /** 早期反射各自再经两级很短的全通摊开（单个尖脉冲会听成「嗒」的回声）。 */
    private static final int[] ER_SMEAR = {37, 61};
    private static final int[] IN_SIGN = {1, -1, 1, -1, -1, 1, -1, 1};
    private static final int[] OUT_SIGN = {1, 1, -1, -1, 1, -1, 1, -1};
    private static final Map<Room, Double> NORM = new ConcurrentHashMap<>();

    /**
     * 一个房间。
     *
     * @param rtMid       中频混响时间（秒，60 dB）
     * @param rtLow       分频点以下的混响时间（石头房间通常更长）
     * @param crossoverHz 低频与中频的分频点
     * @param dampHz      高频阻尼：这个频率处的混响时间是中频的一半
     * @param predelay    混响尾巴相对直达声的预延迟（秒）
     * @param size        房间大小（延迟线长度的倍数，约 0.6–1.3）
     * @param wet         湿声（混响）与干声的幅度比
     */
    public record Room(double rtMid, double rtLow, double crossoverHz, double dampHz, double predelay, double size, double wet) {
        /** 由射线估计（或手动档位）的混响时间与明亮程度换算出整套参数。 */
        public static Room of(RoomAcoustics.Reverb r) {
            double rt = Math.max(0.2, r.seconds());
            double bright = Math.max(0, Math.min(1, r.brightness()));
            return new Room(
                rt,
                rt * (1.12 + 0.18 * (1 - bright)),
                220,
                1800 + 4200 * bright,
                Math.min(0.03, 0.004 + 0.005 * rt),
                Math.max(0.6, Math.min(1.3, 0.55 + 0.19 * rt)),
                Math.min(0.7, 0.28 + 0.1 * rt));
        }

        /** 冲激响应到 −70 dB 大约多长（秒）。 */
        public double length() {
            return Math.max(rtMid, rtLow) * 70.0 / 60.0 + predelay;
        }
    }

    private final int rate;
    private final double[] input;
    private int inPos;
    private final int preDelay;
    private final int[] erTap;
    private final double[] erGain;
    private final double[][] diffuse;
    private final int[] diffusePos = new int[DIFFUSE.length];
    private final double[][] smear;
    private final int[] smearPos = new int[ER_SMEAR.length];
    private final double[][] line = new double[8][];
    private final int[] linePos = new int[8];
    private final double[][] lineAp = new double[8][];
    private final int[] lineApPos = new int[8];
    private final double[] gLow = new double[8], gMid = new double[8], damp = new double[8];
    private final double[] lpState = new double[8], dampState = new double[8];
    private final double cross;
    private final double hpCoef;
    private double hpIn, hpOut;
    private final double gain;
    private final double[] v = new double[8];

    public OrganReverb(Room room, int rate) {
        this(room, rate, true);
    }

    private OrganReverb(Room room, int rate, boolean normalize) {
        this.rate = rate;
        double scale = rate / 32_000.0;
        this.preDelay = Math.max(1, (int) Math.round(room.predelay() * rate));
        this.erTap = new int[ER_MS.length];
        this.erGain = new double[ER_MS.length];
        int longest = preDelay;
        for (int i = 0; i < ER_MS.length; i++) {
            erTap[i] = Math.max(1, (int) Math.round(ER_MS[i] * 0.001 * room.size() * rate));
            erGain[i] = ER_GAIN[i];
            longest = Math.max(longest, erTap[i]);
        }
        this.input = new double[longest + 1];
        this.diffuse = new double[DIFFUSE.length][];
        double dScale = Math.sqrt(room.size()) * scale;
        for (int i = 0; i < DIFFUSE.length; i++) diffuse[i] = new double[Math.max(1, (int) Math.round(DIFFUSE[i] * dScale))];
        this.smear = new double[ER_SMEAR.length][];
        for (int i = 0; i < ER_SMEAR.length; i++) smear[i] = new double[Math.max(1, (int) Math.round(ER_SMEAR[i] * scale))];
        this.cross = 1 - Math.exp(-2 * Math.PI * room.crossoverHz() / rate);
        for (int i = 0; i < 8; i++) {
            line[i] = new double[Math.max(8, (int) Math.round(LINES[i] * room.size() * scale))];
            lineAp[i] = new double[Math.max(1, (int) Math.round(LINE_AP[i] * dScale))];
            double d = line[i].length + lineAp[i].length;
            gMid[i] = Math.pow(10, -3 * d / (room.rtMid() * rate));
            gLow[i] = Math.pow(10, -3 * d / (room.rtLow() * rate));
            damp[i] = dampCoefficient(gMid[i], 2 * Math.PI * Math.min(room.dampHz(), 0.45 * rate) / rate);
        }
        this.hpCoef = Math.exp(-2 * Math.PI * 45.0 / rate);
        this.gain = normalize ? NORM.computeIfAbsent(room, r -> measure(r, rate)) : 1;
    }

    /**
     * 一阶低通 (1−a)/(1−a·z⁻¹) 的系数 a，使它在角频率 w 处的增益正好是 g（直流为 1）：
     * (1−a)² = g²(1 − 2a·cos w + a²)，解这个二次方程取小于 1 的根。
     */
    static double dampCoefficient(double g, double w) {
        double g2 = g * g;
        if (g2 >= 1) return 0;
        double c = Math.cos(w);
        double b = 1 - g2 * c;
        double disc = b * b - (1 - g2) * (1 - g2);
        return Math.max(0, Math.min(0.99, (b - Math.sqrt(Math.max(0, disc))) / (1 - g2)));
    }

    /** 冲激响应的总能量 → 归一化倍数。每种房间只算一次。 */
    private static double measure(Room room, int rate) {
        OrganReverb r = new OrganReverb(room, rate, false);
        int n = (int) (room.length() * rate);
        double e = 0;
        for (int i = 0; i < n; i++) {
            double y = r.process(i == 0 ? 1 : 0);
            e += y * y;
        }
        return 1 / Math.sqrt(Math.max(1e-12, e));
    }

    /** 处理一个采样点，返回湿声（已按冲激响应能量归一，未乘湿声比例）。 */
    public double process(double x) {
        int size = input.length;
        input[inPos] = x;
        // 早期反射：直达声之后几十毫秒内的几次离散反射
        double early = 0;
        for (int i = 0; i < erTap.length; i++) early += erGain[i] * input[(inPos - erTap[i] + size) % size];
        double late = input[(inPos - preDelay + size) % size];
        inPos = (inPos + 1) % size;

        for (int i = 0; i < smear.length; i++) early = allpass(smear[i], smearPos, i, early, 0.55);
        // 输入扩散：四级全通把一个脉冲摊成一串，尾巴一开始就密
        double u = late + 0.35 * early;
        for (int i = 0; i < diffuse.length; i++) u = allpass(diffuse[i], diffusePos, i, u, 0.62);

        // 延迟线输出 → 线内全通 → 两段衰减 → 高频阻尼
        double out = 0;
        for (int i = 0; i < 8; i++) {
            double o = line[i][linePos[i]];
            o = allpass(lineAp[i], lineApPos, i, o, 0.5);
            out += OUT_SIGN[i] * o;
            lpState[i] += cross * (o - lpState[i]);
            double low = lpState[i];
            double w = gLow[i] * low + gMid[i] * (o - low);
            dampState[i] = (1 - damp[i]) * w + damp[i] * dampState[i];
            v[i] = dampState[i];
        }
        hadamard(v);
        for (int i = 0; i < 8; i++) {
            line[i][linePos[i]] = v[i] + IN_SIGN[i] * 0.35 * u;
            linePos[i] = (linePos[i] + 1) % line[i].length;
        }
        // 输出：延迟线（晚期混响）+ 扩散后的输入（填上早期反射与晚期混响之间的空档）+ 早期反射
        double y = out * 0.3535533905932738 + 0.25 * u + 0.2 * early;
        // 低切（约 45 Hz）：去掉直流和闷响
        double hp = hpCoef * (hpOut + y - hpIn);
        hpIn = y;
        hpOut = hp;
        return hp * gain;
    }

    private static double allpass(double[] buf, int[] pos, int i, double x, double g) {
        int p = pos[i];
        double d = buf[p];
        double w = x + g * d;
        buf[p] = w;
        pos[i] = (p + 1) % buf.length;
        return d - g * w;
    }

    /** 8 点快速哈达玛变换（除以 √8，正交）。 */
    private static void hadamard(double[] a) {
        for (int h = 1; h < 8; h <<= 1) {
            for (int i = 0; i < 8; i += h << 1) {
                for (int j = i; j < i + h; j++) {
                    double x = a[j], y = a[j + h];
                    a[j] = x + y;
                    a[j + h] = x - y;
                }
            }
        }
        for (int i = 0; i < 8; i++) a[i] *= 0.3535533905932738;
    }

    public int rate() {
        return rate;
    }
}
