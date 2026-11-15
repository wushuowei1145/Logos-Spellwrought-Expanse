package dev.logos.dimension.elven.blight;

/** 核心生命、断链和结局共用的时间表；不依赖客户端或帧率。 */
public final class QueenBattleRules {
    public static final float HEALTH = 400;
    public static final double MAGIC_RESISTANCE = .75;
    public static final int TETHERED = 1, REBELLION = 2, RECKONING = 3, ENDING = 4;
    public static final int ROOTS = 3, RESIST_TICKS = 120;
    public static final int EXPLODE = 80, WAVE_START = 100, WAVE_END = 260, END = 320;
    public static final int PURIFY_RADIUS = 256;
    private QueenBattleRules() {}
    public static float rootDamage(int phase) { return phase < RECKONING ? HEALTH / ROOTS : 0; }
    public static int rootHealth(int phase) { return phase == REBELLION ? 32 : 24; }
    public static boolean severed(int mask, int slot) { return slot >= 0 && slot < ROOTS && (mask & 1 << slot) != 0; }
    public static boolean coreExposed(int phase, int mask) { return phase == RECKONING && (mask & 7) == 7; }
    public static float smooth(float t) { t = Math.clamp(t, 0, 1); return t * t * (3 - 2 * t); }
    public static float radius(float age) { return PURIFY_RADIUS * smooth((age - WAVE_START) / (WAVE_END - WAVE_START)); }
    public static double coreDrop(int phase, float age) { return phase >= ENDING ? -6 : phase == RECKONING ? -6 * smooth((age - 24) / 40) : 0; }
}
