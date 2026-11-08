package dev.logos.dimension.explosion;

import net.minecraft.util.Mth;

/**
 * 爆裂魔法松手之后的时间轴（刻，从松手算起）。服务端按它杀伤、掘坑，客户端按它播特效和后期，两边对得上。
 * <pre>
 *  0 ── 火柱从最高的法阵往下贯穿到目的地
 * 12 ── 火柱落地：空气被推开的冲击；之后爆点周围渐渐红热、别处暗淡，最后白热
 * 34 ── 一瞬间整个画面转成蓝色
 * 36 ── 蓝色的画面中心爆出极亮的十字闪光（蓝色一直留到黑白闪）
 * 39 ── 黑白闪（几拍）
 * 42 ── 黑白闪还没结束时，换成白热的球体，开始扩大，掀飞经过的地方
 *112 ── 球体到达爆炸半径
 *162 ── 球体散尽，留下气浪、烟雾和彩色的光点
 * </pre>
 */
public final class ExplosionTimeline {
    private ExplosionTimeline() {}

    public static final int PILLAR_TICKS = 12, HEAT_END = 34, BLUE_END = 36, CROSS_END = 39, SPHERE_AT = 42;
    public static final int EXPAND_TICKS = 70, FADE_TICKS = 50;
    /** 球体扩到最大的那一刻。 */
    public static final int FULL_AT = SPHERE_AT + EXPAND_TICKS;
    /** 球体散尽的那一刻。 */
    public static final int GONE_AT = FULL_AT + FADE_TICKS;
    /** 火柱的半径（按爆炸半径 64 算，跟着缩放）：落地时火柱里的东西直接没了。 */
    public static final double PILLAR_RADIUS = 6;
    /** 法阵叠在目的地上空的高度和半径（按爆炸半径 64 算，跟着缩放），从下往上；数量就是吟唱中先后出现的法阵数。 */
    public static final float[] CIRCLE_HEIGHT = {22, 31, 39, 46, 54, 63, 74};
    public static final float[] CIRCLE_RADIUS = {27, 19, 13, 9.5f, 14, 21, 30};

    /** 球体扩大的进度 0~1：开头冲得快，越往外越慢。{@code age} 是松手后的刻数（可带小数）。 */
    public static float front(float age) {
        float k = Mth.clamp((age - SPHERE_AT) / EXPAND_TICKS, 0, 1);
        float r = 1 - k;
        return 1 - r * r * r;
    }

    /** 按爆炸半径缩放：法阵、火柱都是按半径 64 设计的。 */
    public static float scale(float radius) {
        return radius / 64f;
    }
}
