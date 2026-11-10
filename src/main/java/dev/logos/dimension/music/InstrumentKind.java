package dev.logos.dimension.music;

import dev.logos.dimension.LogosDimension;
import net.minecraft.resources.Identifier;

/**
 * 乐器种类：音域、采样（每隔小三度一个，最近的采样变调不超过 ±1 个半音）、可闻距离、松键是否止音。
 * 采样由 tools/instrument_samples.py 合成，声音事件 instrument.&lt;id&gt;.s&lt;音高&gt; 写在 sounds.json。
 */
public enum InstrumentKind {
    /** 星辰花三角钢琴：88 键 A0–C8，松键止音（踩下延音踏板时不止）。 */
    GRAND_PIANO("grand_piano", 21, 108, 21, 108, 48, true),
    /** 灵猫竖琴：C1–G7，琴弦拨响后自然衰减，松键不止音。 */
    CONCERT_HARP("concert_harp", 24, 103, 24, 102, 40, false);

    public static final int SAMPLE_STEP = 3;

    private final String id;
    private final int low;
    private final int high;
    private final int sampleLow;
    private final int sampleHigh;
    private final int broadcastRange;
    private final boolean dampsOnRelease;

    InstrumentKind(String id, int low, int high, int sampleLow, int sampleHigh, int broadcastRange, boolean dampsOnRelease) {
        this.id = id;
        this.low = low;
        this.high = high;
        this.sampleLow = sampleLow;
        this.sampleHigh = sampleHigh;
        this.broadcastRange = broadcastRange;
        this.dampsOnRelease = dampsOnRelease;
    }

    public String id() { return id; }
    public int low() { return low; }
    public int high() { return high; }
    /** 实时演奏转发给其他玩家的半径（格）。 */
    public int broadcastRange() { return broadcastRange; }
    public boolean dampsOnRelease() { return dampsOnRelease; }

    /** 超出音域的音按八度移进来。 */
    public int fold(int pitch) {
        while (pitch < low) pitch += 12;
        while (pitch > high) pitch -= 12;
        return pitch;
    }

    /** 离这个音最近的采样音高。 */
    public int sampleFor(int pitch) {
        int s = sampleLow + Math.round((pitch - sampleLow) / (float) SAMPLE_STEP) * SAMPLE_STEP;
        return Math.max(sampleLow, Math.min(sampleHigh, s));
    }

    /** 采样变调到目标音高的倍率（0.94–1.06）。 */
    public float pitchRatio(int pitch) {
        return (float) Math.pow(2.0, (pitch - sampleFor(pitch)) / 12.0);
    }

    public Iterable<Integer> samples() {
        java.util.List<Integer> list = new java.util.ArrayList<>();
        for (int s = sampleLow; s <= sampleHigh; s += SAMPLE_STEP) list.add(s);
        return list;
    }

    public Identifier soundId(int sample) {
        return LogosDimension.id("instrument." + id + ".s" + sample);
    }
}
