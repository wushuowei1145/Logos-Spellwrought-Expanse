package dev.logos.dimension.music;

/**
 * 乐器种类：音域、可闻距离、松键是否止音、是否感力度、是否理会延音踏板。
 * 除管风琴外，声音都来自采样表 assets/logos_dimension/instruments/&lt;id&gt;.json（{@link SampleMap}，
 * 由 tools/instrument_sources.py 从公开采样库生成，来源见 docs/INSTRUMENT_SAMPLES.md）。
 */
public enum InstrumentKind {
    /** 星辰花三角钢琴：88 键 A0–C8，松键止音（踩下延音踏板时不止）。采样：Salamander Grand Piano（Yamaha C5），五层力度。 */
    GRAND_PIANO("grand_piano", "starbloom_grand_piano", 21, 108, 48, true, true, true),
    /** 灵猫竖琴：C1–G7，琴弦拨响后自然衰减，松键不止音。 */
    CONCERT_HARP("concert_harp", "hexen_concert_harp", 24, 103, 40, false, true, false),
    /**
     * 教堂管风琴：C1–C7，按住多久响多久。声音不放 ogg，由客户端按当前音栓合成循环段
     * （{@link dev.logos.dimension.music.organ.OrganSynth}），可闻距离随音管数量变化，见 docs/PIPE_ORGAN.md。
     */
    PIPE_ORGAN("pipe_organ", "hexen_organ_console", 24, 96, 64, true, false, false),
    /** 钟琴：G5–C8（按实际发声的音高），钢条被硬槌敲响，没有制音，自然衰减。 */
    GLOCKENSPIEL("glockenspiel", "hexen_glockenspiel", 79, 108, 40, false, true, false),
    /** 钢片琴：C4–C8，琴槌敲钢片、下面是木共鸣箱，声音甜而轻；有制音和延音踏板，同钢琴。两个采样库都没有，仍是合成的。 */
    CELESTA("celesta", "hexen_celesta", 60, 108, 40, true, true, true),
    /** 管钟：B3–G5（真实管钟 C4–F5 两边各留一点），铜管挂在架上，敲响后余音很长，松手不止。 */
    TUBULAR_BELLS("tubular_bells", "hexen_tubular_bells", 59, 79, 64, false, true, false),
    /** 大键琴：F1–F6，羽管拨弦，不分轻重（琴本身不感力度），松键时制音落下、拨子落回有一下轻响；没有踏板。 */
    HARPSICHORD("harpsichord", "hexen_harpsichord", 29, 89, 40, true, false, false),
    /** 小提琴（手持）：G3–E7，独奏小提琴揉弦的长弓，按住多久拉多久。 */
    VIOLIN("violin", "hexen_violin", 55, 100, 40, true, true, false),
    /** 长笛（手持）：C4–C7，带揉音的长音。 */
    FLUTE("flute", "hexen_flute", 60, 96, 40, true, true, false),
    /** 小号（手持，挂魔女之森王室旗）：F♯3–C6，直音，弱 / 强两层。 */
    TRUMPET("trumpet", "hexen_trumpet", 54, 84, 56, true, true, false);

    private final String id;
    private final int low;
    private final int high;
    private final int broadcastRange;
    private final boolean dampsOnRelease;
    private final String blockId;
    private final boolean velocitySensitive;
    private final boolean usesPedal;

    InstrumentKind(String id, String blockId, int low, int high, int broadcastRange,
                   boolean dampsOnRelease, boolean velocitySensitive, boolean usesPedal) {
        this.id = id;
        this.blockId = blockId;
        this.velocitySensitive = velocitySensitive;
        this.usesPedal = usesPedal;
        this.low = low;
        this.high = high;
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
    /** 声音由客户端现场合成（只有管风琴），没有采样表。 */
    public boolean synthesized() { return this == PIPE_ORGAN; }
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
}
