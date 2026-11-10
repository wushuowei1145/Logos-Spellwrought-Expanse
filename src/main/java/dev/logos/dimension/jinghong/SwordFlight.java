package dev.logos.dimension.jinghong;

/**
 * 惊鸿剑阵的时间轴（游戏刻，从剑出发那一刻算）。服务端按它结算伤害，客户端按它画飞剑，两边共用这一份。
 *
 * <p>追击（身后的一把剑）：升起 → 赶到出击点 → 穿过目标（这一刻结算）→ 冲过头 → 闪回玩家头顶 → 落回原位。
 * 多段（掷剑时激活的剑协同出手）每多一段，多一次“绕到新方向再穿过去”。
 *
 * <p>掷剑：手里的剑掷出、穿过目标（结算）、回到手里；回手的时刻对上掷剑动作的 {@code catch} 标记。
 *
 * <p>万剑归宗：五把剑升到目标头顶结成剑环，虚影剑从四面八方落下，最后五把剑一齐贯下。
 */
public final class SwordFlight {
    private SwordFlight() {}

    /** 身后剑的数量。 */
    public static final int FAN = 5;

    /** 升起用的刻。 */
    public static final int RISE = 3;
    /** 第一次穿过目标的刻。 */
    public static final int FIRST_HIT = 6;
    /** 多段时两次穿过之间隔的刻。 */
    public static final int PASS = 4;
    /** 最后一次穿过之后到闪回的刻。 */
    public static final int BLINK_AFTER = 2;
    /** 闪回头顶后落回原位用的刻。 */
    public static final int HOME = 5;

    /** 第 {@code pass} 次（从 0 起）穿过目标的刻。 */
    public static int hit(int pass) { return FIRST_HIT + PASS * pass; }
    /** 闪回（在冲过头的地方消失、出现在玩家头顶）的刻。 */
    public static int blink(int passes) { return hit(passes - 1) + BLINK_AFTER; }
    /** 回到原位、可以再出手的刻。 */
    public static int duration(int passes) { return blink(passes) + HOME; }

    /** 掷剑：掷出后穿过目标（结算）的刻。 */
    public static final int THROW_HIT = 3;
    /** 掷剑：在目标处掉头的刻。 */
    public static final int THROW_TURN = 4;
    /** 掷剑：回到手里的刻，等于掷剑动作 catch − release（0.62 − 0.14 秒）。 */
    public static final double THROW_HOME = (0.62 - 0.14) * 20;

    /** 万剑归宗：五把剑在目标头顶结成剑环的刻。 */
    public static final int FINALE_GATHER = 8;
    /** 万剑归宗：虚影剑的数量，以及第一把出现的刻、相邻两把出现的间隔、显现用的刻、显现后落到目标用的刻。 */
    public static final int PHANTOMS = 36;
    public static final double PHANTOM_START = FINALE_GATHER, PHANTOM_GAP = 0.5, PHANTOM_SHOW = 1.2, PHANTOM_FALL = 2.5;
    /** 万剑归宗：虚影剑结算伤害的次数和刻（第一刻、间隔）。 */
    public static final int PHANTOM_HITS = 9, PHANTOM_FIRST_HIT = 12, PHANTOM_HIT_GAP = 2;
    /** 万剑归宗：五把剑从剑环贯下的刻、贯入目标（结算）的刻、闪回的刻、回到原位的刻。 */
    public static final int PLUNGE = 30, PLUNGE_HIT = 33, FINALE_BLINK = 36, FINALE_HOME = FINALE_BLINK + HOME;
    /** 剑环在目标上方的高度和半径（格）。 */
    public static final double RING_HEIGHT = 5.5, RING_RADIUS = 2.2;

    /** 第 {@code i} 把虚影剑出现的刻。 */
    public static double phantomBorn(int i) { return PHANTOM_START + PHANTOM_GAP * i; }
    /** 第 {@code i} 把虚影剑落到目标的刻。 */
    public static double phantomLands(int i) { return phantomBorn(i) + PHANTOM_SHOW + PHANTOM_FALL; }
    /** 第 {@code m} 次虚影剑伤害的刻。 */
    public static int phantomHit(int m) { return PHANTOM_FIRST_HIT + PHANTOM_HIT_GAP * m; }
}
