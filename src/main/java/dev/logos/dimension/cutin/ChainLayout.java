package dev.logos.dimension.cutin;

import java.util.ArrayList;
import java.util.List;

/**
 * 连携切入的分格（画面坐标：x 从左到右 0..1）。玩家永远在正中间那一格，同伴从中间往两边排：第一位在玩家左边，第二位在右边，
 * 第三位在最左，第四位在最右。人数是偶数时左边多一格。
 *
 * <ul>
 *   <li>两人：玩家和一位同伴两个大斜格，右边露出压暗的战场。</li>
 *   <li>三人：三格几乎占满，两边只剩很窄的暗边。</li>
 *   <li>四人、五人：五格的格距铺满全屏。四人时右边那位的格子一直延到屏幕边上。</li>
 * </ul>
 *
 * <p>分割线都带倾角，越靠外越斜（左边 {@code \}、右边 {@code /}），形成向上张开的扇形；不竖直均分。
 *
 * @param lines     分割线，从左到右；第 i 格在第 i 和第 i+1 道之间
 * @param slots     各格，从左到右
 * @param fullPlate 黑底是否盖满全屏（两人时只盖格子里，外面是压暗的战场）
 */
public record ChainLayout(List<Line> lines, List<Slot> slots, boolean fullPlate) {
    /**
     * 一道分割线。
     *
     * @param x      在画面半高处的位置
     * @param tilt   倾角（度，正数是上端往右倒）
     * @param pierce 刺穿那一拍刺到多高（画面高度）
     * @param edge   是不是最外面的边（在屏幕外时不画）
     */
    public record Line(float x, float tilt, float pierce, boolean edge) {}

    /**
     * 一格。
     *
     * @param center 角色站在哪（画面 x）
     * @param member 谁：{@link #PLAYER} 是玩家，否则是第几位同伴（{@link ChainCutin#members()} 的下标）
     */
    public record Slot(float center, int member) {}

    public static final int PLAYER = -1;
    /** 刺穿那一拍各道线刺到的高度，错落着（中间最高）。 */
    private static final float[] PIERCE = {.4f, .55f, .95f, .3f, .75f, .5f};

    /** {@code companions} 位同伴时的分格（不在 1..4 时按最近的算）。 */
    public static ChainLayout of(int companions) {
        int n = Math.clamp(companions, 1, ChainCutin.MAX_MEMBERS);
        return switch (n) {
            case 1 -> build(new float[] {.05f, .35f, .65f}, new float[] {.2f, .5f}, new int[] {0, PLAYER}, false);
            case 2 -> build(new float[] {.035f, .345f, .655f, .965f}, new float[] {.19f, .5f, .81f}, new int[] {0, PLAYER, 1}, true);
            case 3 -> build(new float[] {-.03f, .2f, .4f, .6f, 1.03f}, new float[] {.1f, .3f, .5f, .7f}, new int[] {2, 0, PLAYER, 1}, true);
            default -> build(new float[] {-.03f, .2f, .4f, .6f, .8f, 1.03f}, new float[] {.1f, .3f, .5f, .7f, .9f}, new int[] {2, 0, PLAYER, 1, 3}, true);
        };
    }

    private static ChainLayout build(float[] xs, float[] centers, int[] members, boolean full) {
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < xs.length; i++) {
            boolean edge = i == 0 || i == xs.length - 1;
            lines.add(new Line(xs[i], tilt(xs[i]), PIERCE[i % PIERCE.length], edge));
        }
        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < centers.length; i++) slots.add(new Slot(centers[i], members[i]));
        return new ChainLayout(List.copyOf(lines), List.copyOf(slots), full);
    }

    /** 越靠外越斜：中间附近约 5°，最外约 12°。 */
    static float tilt(float x) {
        float off = x - .5f;
        if (Math.abs(off) < .01f) return 0;
        return Math.signum(off) * (4 + 16 * Math.min(.5f, Math.abs(off)));
    }

    /** 玩家在第几格（从左数）。 */
    public int playerSlot() {
        for (int i = 0; i < slots.size(); i++) if (slots.get(i).member() == PLAYER) return i;
        return -1;
    }
}
