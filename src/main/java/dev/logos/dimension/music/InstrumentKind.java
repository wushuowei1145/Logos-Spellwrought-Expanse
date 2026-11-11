package dev.logos.dimension.music;

import dev.logos.dimension.LogosDimension;
import net.minecraft.resources.Identifier;

/**
 * 乐器种类：音域、采样（每隔小三度一个，最近的采样变调不超过 ±1 个半音）、可闻距离、松键是否止音。
 * 采样由 tools/instrument_samples.py 合成，声音事件 instrument.&lt;id&gt;.s&lt;音高&gt; 写在 sounds.json。
 */
public enum InstrumentKind {
    /** 星辰花三角钢琴：88 键 A0–C8，松键止音（踩下延音踏板时不止）。 */
    GRAND_PIANO("grand_piano", "starbloom_grand_piano", 21, 108, 21, 108, 48, true, true, true),
    /** 灵猫竖琴：C1–G7，琴弦拨响后自然衰减，松键不止音。 */
    CONCERT_HARP("concert_harp", "hexen_concert_harp", 24, 103, 24, 102, 40, false, true, false),
    /**
     * 教堂管风琴：C1–C7，按住多久响多久。声音不放 ogg，由客户端按当前音栓合成循环段
     * （{@link dev.logos.dimension.music.organ.OrganSynth}），可闻距离随音管数量变化，见 docs/PIPE_ORGAN.md。
     */
    PIPE_ORGAN("pipe_organ", "hexen_organ_console", 24, 96, 24, 96, 64, true, false, false),
    /** 钟琴：G5–C8（按实际发声的音高），钢条被硬槌敲响，没有制音，自然衰减。 */
    GLOCKENSPIEL("glockenspiel", "hexen_glockenspiel", 79, 108, 78, 108, 40, false, true, false),
    /** 钢片琴：C4–C8，琴槌敲钢片、下面是木共鸣箱，声音甜而轻；有制音和延音踏板，同钢琴。 */
    CELESTA("celesta", "hexen_celesta", 60, 108, 60, 108, 40, true, true, true),
    /** 管钟：G3–C6，铜管挂在架上，敲响后余音很长，松手不止。 */
    TUBULAR_BELLS("tubular_bells", "hexen_tubular_bells", 55, 84, 54, 84, 64, false, true, false),
    /** 大键琴：F1–F6，羽管拨弦，不分轻重（琴本身不感力度），松键时制音落下；没有踏板。 */
    HARPSICHORD("harpsichord", "hexen_harpsichord", 29, 89, 29, 89, 40, true, false, false),
    /** 小提琴（手持）：G3–G7，运弓的长音，揉弦做进循环段；声音由客户端合成（{@link dev.logos.dimension.music.held.HeldSynth}）。 */
    VIOLIN("violin", "hexen_violin", 55, 103, 55, 103, 40, true, true, false),
    /** 长笛（手持）：C4–C7，带气声的长音。 */
    FLUTE("flute", "hexen_flute", 60, 96, 60, 96, 40, true, true, false),
    /** 小号（手持，挂魔女之森王室旗）：F♯3–C6，明亮的铜管长音。 */
    TRUMPET("trumpet", "hexen_trumpet", 54, 84, 54, 84, 56, true, true, false);

    public static final int SAMPLE_STEP = 3;

    private final String id;
    private final int low;
    private final int high;
    private final int sampleLow;
    private final int sampleHigh;
    private final int broadcastRange;
    private final boolean dampsOnRelease;
    private final String blockId;
    private final boolean velocitySensitive;
    private final boolean usesPedal;

    InstrumentKind(String id, String blockId, int low, int high, int sampleLow, int sampleHigh, int broadcastRange,
                   boolean dampsOnRelease, boolean velocitySensitive, boolean usesPedal) {
        this.id = id;
        this.blockId = blockId;
        this.velocitySensitive = velocitySensitive;
        this.usesPedal = usesPedal;
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
    /** 方块的注册名（也用来取乐器的名字 block.logos_dimension.&lt;blockId&gt;）。 */
    public String blockId() { return blockId; }
    /** 按得重响不响：大键琴和管风琴不分力度。 */
    public boolean velocitySensitive() { return velocitySensitive; }
    /** 延音踏板对它有没有用：钢琴、钢片琴有；其余乐器收到踏板事件时不理会。 */
    public boolean usesPedal() { return usesPedal; }
    /** 声音由客户端现场合成（管风琴与手持乐器），不注册声音事件、没有 ogg。 */
    public boolean synthesized() { return this == PIPE_ORGAN || handheld(); }
    /** 拿在手上演奏的乐器（物品），也能放在乐谱架上。 */
    public boolean handheld() { return this == VIOLIN || this == FLUTE || this == TRUMPET; }
    /** 乐器名字的翻译键：手持乐器是物品名，其余是方块名。 */
    public String nameKey() { return (handheld() ? "item.logos_dimension." : "block.logos_dimension.") + blockId; }

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
