package dev.logos.dimension.elven.blight;

/** 女王战的数值和时间表（docs/CORRUPTED_QUEEN_REMAKE.md 第 5.4 节）；不依赖客户端或帧率。净化进度和窗口见 {@link HeartPurification}。 */
public final class QueenBattleRules {
    /** 阶段一单人的生命和护甲；多人生命 ×(1 + 0.5 ×(人数 − 1))。 */
    public static final float HEALTH = 700;
    public static final double ARMOR = 12;
    public static final int TETHERED = 1, GUARDING = 2, BROKEN_FREE = 3, ENDING = 4;
    /** 阶段二开头公主跃上祭坛、女王跪地的那几刻：谁都不出招。 */
    public static final int INTRO = 60;
    /** 阶段二打满这么多硬直，女王被压回中央。 */
    public static final float STRAIN_MAX = 30;
    public static final int RESIST_TICKS = 120;
    /** 衍生物：每波间隔（每断一条血管再晚 {@link #WAVE_DELAY_PER_VEIN} 刻）、场上最多几只。 */
    public static final int WAVE_INTERVAL = 300, WAVE_DELAY_PER_VEIN = 50, WAVE_CAP = 10;
    /** 地上战斗里长出的污秽最多几块。 */
    public static final int FILTH_CAP = 160;
    /** 所有人离开这么久就重置。 */
    public static final int ABSENT = 600;
    /** 核心庇护的半径，和 creature_traits/corrupted_queen.json 的 blight_ward.radius 一致（客户端画供养血管用）。 */
    public static final double WARD_RADIUS = 64;
    public static final int EXPLODE = 80, WAVE_START = 100, WAVE_END = 260, END = 320;
    public static final int PURIFY_RADIUS = 256;
    private QueenBattleRules() {}

    public static boolean severed(int mask, int slot) { return slot >= 0 && slot < HeartVeins.MAIN && (mask & 1 << slot) != 0; }

    /** 高台上够得着的主血管有几条。 */
    public static int reachableVeins() {
        int n = 0;
        for (int i = 0; i < HeartVeins.MAIN; i++) if (HeartVeins.reachable(i)) n++;
        return n;
    }

    /** 一波几只：三只起，每多一个人加一只，每断两条血管少一只，至少一只。 */
    public static int waveSize(int fighters, int cutVeins) {
        return Math.max(1, 3 + Math.max(0, fighters - 1) - cutVeins / 2);
    }

    public static float smooth(float t) { t = Math.clamp(t, 0, 1); return t * t * (3 - 2 * t); }
    public static float radius(float age) { return PURIFY_RADIUS * smooth((age - WAVE_START) / (WAVE_END - WAVE_START)); }

    /**
     * 腐败之心往下沉多少格：窗口里压低 5 格（开合各 1 秒），阶段三被女王拉下 2 格，结局时落到 6 格。
     * {@code windowAge}、{@code windowLeft} 是窗口开了几刻、还剩几刻（没开时都是 0）。
     */
    public static double coreDrop(int phase, float phaseAge, float windowAge, float windowLeft) {
        if (phase >= ENDING) return -6;
        double base = phase == BROKEN_FREE ? -2 * smooth((phaseAge - 30) / 40) : 0;
        if (windowLeft <= 0) return base;
        double open = smooth(Math.min(windowAge, windowLeft) / 20);
        return base + (-5 - base) * open;
    }
}
