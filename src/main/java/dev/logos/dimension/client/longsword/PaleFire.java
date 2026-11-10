package dev.logos.dimension.client.longsword;

import net.minecraft.util.Mth;

/**
 * 表面火焰（Logos 的 {@code SurfaceAura} FIRE 样式）的淡色版。Logos 的表面火焰只收一个色相：火焰的几种底色转到这个色相，
 * 饱和度保持原样，所以橙红转出来的粉也是浓的。这里不改 Logos，只在色相上做个记号：色相加上 {@link #MARK} 时，
 * {@code VfxParts.hue} 照常转色相后再把饱和度乘上 {@link #SATURATION}（{@code mixin/PaleFireMixin}）。
 * 色相只按小数部分用，加上整数不改颜色；刀身上的火、往外飘的火星、发光层都经过这一个方法，所以一起变淡。
 */
public final class PaleFire {
    /** 加在色相上的记号：Logos 的色相都在 0 到 1 之间，大于等于它的就是要淡的。 */
    public static final float MARK = 16;
    /** 淡的程度：饱和度乘多少（流火刀身的狐火，2026-11-10 维护者嫌原来太浓）。 */
    public static final float SATURATION = .55f;

    private PaleFire() {}

    /** 一个带着“淡”记号的色相。 */
    public static float hue(float hue) {
        return hue + MARK;
    }

    /** 这个色相带着“淡”记号。 */
    public static boolean marked(float turns) {
        return turns >= MARK;
    }

    /** 去掉记号后的色相。 */
    public static float unmarked(float turns) {
        return turns - MARK;
    }

    /** 把 {@code rgb} 的饱和度乘上 {@link #SATURATION}，明度不变。 */
    public static int pale(int rgb) {
        float r = (rgb >> 16 & 255) / 255f, g = (rgb >> 8 & 255) / 255f, b = (rgb & 255) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        if (d <= 0 || max <= 0) return rgb & 0xFFFFFF;
        float h = max == r ? (g - b) / d : max == g ? 2 + (b - r) / d : 4 + (r - g) / d;
        h /= 6;
        return Mth.hsvToRgb(h - Mth.floor(h), d / max * SATURATION, max) & 0xFFFFFF;
    }
}
