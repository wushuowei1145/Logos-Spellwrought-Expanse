package dev.logos.dimension.dawnvow;

/**
 * 巨型剑气走到头之后、光柱冲起之前的时间轴（刻，从剑气到头算起）。服务端按它放光柱、结算伤害，客户端按它播后期，两边对得上。
 * 节奏参考爆裂魔法（{@code ExplosionTimeline}）：先在地上蓄一团光，再一阵很快的几何黑白闪，最后极亮的一下接光柱。
 * 和爆裂魔法的写实黑白闪（取画面里的剪影）不同，这里的黑白闪全是几何图形：黑色的尖刺、反色、黑白的星。
 * <pre>
 *  0 ── 剑气扎进地面：地上一团扁扁的光，四周暗下去，火星四溅
 *  0–14 ── 光团鼓成穹顶，越来越亮，像要迸出来
 * 14 ── 黑色爆发：黑色的尖刺从穹顶炸满画面，外围反而是亮的，亮光向内收紧
 * 20 ── 几何黑白闪（按 24fps 一拍，见 {@link #BEATS}）：反色两拍，白底黑星两拍，黑底白星，再一拍白底黑星
 * 25 ── 爆发的前兆：穹顶猛地涨大，扑满画面
 * 29 ── 极亮：画面整个白掉
 * 33 ── 光柱冲起，结算伤害；白光退去
 * </pre>
 */
public final class DawnvowBurst {
    private DawnvowBurst() {}

    public static final float BLACK = 14, CONTRAST = 20, EXPAND = 25, WHITE = 29;
    /** 光柱冲起、结算伤害的那一刻。 */
    public static final int PILLAR = 33;
    /** 几何黑白闪一拍多少刻：动画的 24fps，一拍 1/24 秒。 */
    public static final float BEAT = 20f / 24;

    /** 几何黑白闪的一拍。 */
    public enum Beat {
        /** 反色：整个画面颜色取反。 */
        INVERT,
        /** 白底：穹顶反色，画面上散着黑色的四芒星与墨点。 */
        STARS_ON_WHITE,
        /** 黑底：穹顶原色，画面上散着白色带金边的星。 */
        STARS_ON_BLACK
    }

    /** 黑白闪的拍子，从 {@link #CONTRAST} 开始一拍一拍放，一共 6 拍（约 0.25 秒）。 */
    public static final Beat[] BEATS = {Beat.INVERT, Beat.INVERT, Beat.STARS_ON_WHITE, Beat.STARS_ON_WHITE, Beat.STARS_ON_BLACK, Beat.STARS_ON_WHITE};
}
