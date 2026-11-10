package dev.logos.dimension.music.organ;

/**
 * 管风琴的音栓。每个音栓属于一种音管材质：只要结构里有一根这种音管，这一类音栓就能用
 * （没有音管时只有长笛 8′）。音栓的组合用位掩码表示，第 i 位对应 {@code values()[i]}。
 * 合成方法见 {@link OrganSynth}。
 */
public enum OrganStop {
    FLUTE_8("flute_8", Family.WOOD, 0.8),
    FLUTE_4("flute_4", Family.WOOD, 0.5),
    BOURDON_16("bourdon_16", Family.WOOD, 0.7),
    PRINCIPAL_8("principal_8", Family.TIN, 1.0),
    OCTAVE_4("octave_4", Family.TIN, 0.7),
    FIFTEENTH_2("fifteenth_2", Family.TIN, 0.5),
    MIXTURE("mixture", Family.TIN, 0.55),
    CELESTE_8("celeste_8", Family.TIN, 0.7),
    TRUMPET_8("trumpet_8", Family.REED, 0.9),
    OBOE_8("oboe_8", Family.REED, 0.7);

    /** 音管材质。 */
    public enum Family { WOOD, TIN, REED }

    public static final int ALL = (1 << values().length) - 1;
    /** 没有任何音管时也能用的音栓。 */
    public static final int ALWAYS = FLUTE_8.bit();

    private final String id;
    private final Family family;
    private final double level;

    OrganStop(String id, Family family, double level) {
        this.id = id;
        this.family = family;
        this.level = level;
    }

    public String id() { return id; }
    public Family family() { return family; }
    /** 在混音里的相对响度。 */
    public double level() { return level; }

    public int bit() {
        return 1 << ordinal();
    }

    public boolean in(int mask) {
        return (mask & bit()) != 0;
    }

    /** 某些材质可用时能开的音栓掩码。 */
    public static int available(boolean wood, boolean tin, boolean reed) {
        int mask = ALWAYS;
        for (OrganStop s : values()) {
            boolean ok = switch (s.family) {
                case WOOD -> wood;
                case TIN -> tin;
                case REED -> reed;
            };
            if (ok) mask |= s.bit();
        }
        return mask;
    }

    /** 这套组合的总体响度（0–1），给发声音量用：音栓越多越响，但不是线性叠加。 */
    public static double loudness(int mask) {
        double sum = 0;
        for (OrganStop s : values()) if (s.in(mask)) sum += s.level;
        return 0.45 + 0.55 * (1 - Math.exp(-sum / 1.5));
    }

    /** 预设：一键切换的常用组合。 */
    public enum Preset {
        SOFT("soft", FLUTE_8),
        MATINS("matins", PRINCIPAL_8, FLUTE_4),
        CHORUS("chorus", PRINCIPAL_8, OCTAVE_4, FIFTEENTH_2),
        PLENUM("plenum", BOURDON_16, PRINCIPAL_8, OCTAVE_4, FIFTEENTH_2, MIXTURE, TRUMPET_8),
        TRUMPET("trumpet", TRUMPET_8, FLUTE_8),
        CELESTE("celeste", CELESTE_8, FLUTE_8);

        private final String id;
        private final int mask;

        Preset(String id, OrganStop... stops) {
            this.id = id;
            int m = 0;
            for (OrganStop s : stops) m |= s.bit();
            this.mask = m;
        }

        public String id() { return id; }
        public int mask() { return mask; }
    }
}
