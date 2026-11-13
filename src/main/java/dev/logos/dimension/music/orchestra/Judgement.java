package dev.logos.dimension.music.orchestra;

/**
 * 一拍打得准不准（docs/ORCHESTRA.md 第 18 节），像音游一样分四档：离拍子（节拍器开着时是节拍器的拍，关着时是乐团的拍）多少真实毫秒。
 * 连着几拍不是 MISS 就算连击。
 */
public enum Judgement {
    PERFECT(40, 0xFFFFD54A),
    GREAT(80, 0xFF7FD3FF),
    GOOD(130, 0xFF8EE08A),
    MISS(Double.MAX_VALUE, 0xFFA0A0A0);

    /** 这一档最多差多少毫秒。 */
    public final double withinMs;
    public final int color;

    Judgement(double withinMs, int color) {
        this.withinMs = withinMs;
        this.color = color;
    }

    public static Judgement of(double errorMs) {
        double e = Math.abs(errorMs);
        for (Judgement j : values()) if (e <= j.withinMs) return j;
        return MISS;
    }

    /** 这一拍之后连击数：不是 MISS 就加一，MISS 归零。 */
    public int combo(int before) {
        return this == MISS ? 0 : before + 1;
    }

    /** 时钟此刻在 positionMs、按 rate 走：离最近的一拍（实际打的拍）差多少真实毫秒，早为负、晚为正。 */
    public static double offsetMs(BeatMap map, double positionMs, double rate) {
        double[] around = BeatFollower.beatAround(map, positionMs);
        double prev = around[0], next = prev + around[1];
        double nearest = positionMs - prev <= next - positionMs ? prev : next;
        return (positionMs - nearest) / Math.max(1e-3, rate);
    }
}
