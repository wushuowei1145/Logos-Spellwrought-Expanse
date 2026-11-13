package dev.logos.dimension.music.orchestra;

/**
 * 乐团跟着指挥的拍子走（docs/ORCHESTRA.md 第 17 节），纯逻辑：
 * 每一下拍点（左键按下、或画拍子时棒尖落到底）对到离乐团此刻位置最近的那一拍（按实际打的拍，{@link ConductorPattern#conduct}），
 * 拍点之间的间隔定速度（几拍平滑一下，真乐团也不会一拍就变速）；位置不跳：差多少就让时钟在下一拍之内追上或等一等。
 * 该来的一拍过了将近半拍还没来，乐团就停在那里（延长音），再给一拍从停住的地方接着走。
 *
 * <p>时间都用纳秒（同一个时钟，{@link System#nanoTime}），位置是纸带上的毫秒；速度是相对纸带原速的倍数。
 */
public final class BeatFollower {
    /** 速度的范围（相对原速）。 */
    public static final double MIN_RATE = 0.4, MAX_RATE = 1.8;
    /** 新量到的速度占几成（其余是原来的速度）。 */
    static final double SMOOTH = 0.6;
    /** 该来的一拍晚了这么多拍还没来：延长。 */
    static final double HOLD_AFTER = 0.45;
    /** 拍点离拍子这么近（真实毫秒）算准。 */
    public static final double ON_TIME_MS = 70;

    /**
     * 一下拍点之后时钟该怎么走。
     * @param rate 量出来的速度（相对原速）
     * @param clockRate 时钟此后的速度：在 rate 上加减一点，到下一拍时追上
     * @param errorMs 这一下比它对上的那一拍早（负）或晚（正）多少真实毫秒
     */
    public record Beat(double rate, double clockRate, double errorMs) {
        public boolean onTime() {
            return Math.abs(errorMs) <= ON_TIME_MS;
        }
    }

    private double rate;
    private boolean leading;
    private boolean held;
    private long lastTapNanos;
    private double lastBeatMs;
    private long expectNanos;

    public BeatFollower(double startRate) {
        rate = clamp(startRate);
    }

    public double rate() { return rate; }
    /** 已经有过拍点：乐团跟着这个人的拍子（不再按设定的速度自己走）。 */
    public boolean leading() { return leading; }
    public boolean held() { return held; }

    /** positionMs 附近一拍的起点和长度（纸带毫秒）：{上一个拍点, 一拍多长}。 */
    static double[] beatAround(BeatMap map, double positionMs) {
        ConductorPattern.Conducted c = ConductorPattern.conduct(map.at(positionMs));
        return new double[]{positionMs - c.phase() * c.beatMs(), c.beatMs()};
    }

    /** 一下拍点：乐团此刻在 positionMs（纸带毫秒）。 */
    public Beat tap(long now, double positionMs, BeatMap map) {
        double[] around = beatAround(map, positionMs);
        double beatMs = around[1];
        if (held) {
            // 延长之后再给一拍：从停住的地方按原来的速度接着走
            held = false;
            lastTapNanos = now;
            lastBeatMs = positionMs;
            expectNanos = now + (long) (beatMs / rate * 1_000_000);
            return new Beat(rate, rate, 0);
        }
        double prev = around[0], next = prev + beatMs;
        double matched = positionMs - prev <= next - positionMs ? prev : next;
        double errorScore = positionMs - matched;
        if (leading && lastTapNanos != 0) {
            double realMs = (now - lastTapNanos) / 1_000_000.0;
            double scoreMs = matched - lastBeatMs;
            // 隔得太久（超过三拍）或者对上的是同一拍：只校正位置，不改速度
            if (realMs > 1 && scoreMs > beatMs * 0.5 && scoreMs <= beatMs * 3.2) {
                double measured = clamp(scoreMs / realMs);
                rate = clamp(rate + (measured - rate) * SMOOTH);
            }
        }
        leading = true;
        lastTapNanos = now;
        lastBeatMs = matched;
        // 到下一拍（matched + 一拍）时正好追上：现在离它还有多远，按量到的速度这一拍该走多久
        double toNext = matched + beatMs - positionMs;
        double clockRate = rate * Math.max(0.6, Math.min(1.5, toNext / beatMs));
        expectNanos = now + (long) (beatMs / rate * 1_000_000);
        return new Beat(rate, clockRate, errorScore / rate);
    }

    /** 每一帧：该来的一拍迟迟不来时返回 true（这一刻起延长，时钟停住）；已经在延长、或还没开始跟拍子时返回 false。 */
    public boolean overdue(long now) {
        if (!leading || held || expectNanos == 0) return false;
        double beatNanos = (now - lastTapNanos) <= 0 ? 0 : (expectNanos - lastTapNanos);
        if (now > expectNanos + beatNanos * HOLD_AFTER) {
            held = true;
            return true;
        }
        return false;
    }

    /**
     * 用两拍起奏之后：起奏那一下（乐团此时在 positionMs，预备拍的拍点）当作上一拍，下一拍在 gapMs 之后，
     * 这样接下来的拍子一开始就量得出速度。
     */
    public void primed(long tapNanos, double positionMs, double gapMs) {
        leading = true;
        held = false;
        lastTapNanos = tapNanos;
        lastBeatMs = positionMs;
        expectNanos = tapNanos + (long) (gapMs * 1_000_000);
    }

    /** 不跟了（放下指挥棒、走下指挥台、乐团停了）：回到按设定的速度自己走。 */
    public void release() {
        leading = false;
        held = false;
        lastTapNanos = 0;
        expectNanos = 0;
    }

    /** 起奏前打的两拍：间隔 intervalMs，第一拍（预备拍）长 prepMs（纸带毫秒）时乐团的速度。 */
    public static double countIn(double prepMs, double intervalMs) {
        return clamp(prepMs / Math.max(1, intervalMs));
    }

    static double clamp(double r) {
        return Math.max(MIN_RATE, Math.min(MAX_RATE, r));
    }
}
