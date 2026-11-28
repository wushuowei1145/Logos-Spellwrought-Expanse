package dev.logos.dimension.client.companion;

import dev.logos.dimension.companion.KohakuUltimate;

/**
 * 琥珀奥义自己的全屏后期（{@code KohakuUltimateScreen}）这一帧的参数：纯算术，按分镜的节拍（{@link KohakuUltimate.Beats}）和第几刻算出来，单元测试直接测。
 *
 * <ul>
 *   <li><b>凝时</b>（切到敌群那一镜）：第一拍反色一下（开了「隐藏闪电的闪烁」、或者缩短版时不反），随后 RGB 色散、同心波纹往外荡，染上琥珀色；
 *       抓下的那一帧画面从这时起代替活的画面（敌群连同粒子全部定住）。</li>
 *   <li><b>坍缩</b>：那一帧画面沿着漩涡往正中间抽缩，压进一颗悬在黑暗里的琥珀晶石。</li>
 *   <li><b>掌中琥珀、轻弹</b>：晶石画在她手边（世界坐标投到屏幕上），里面还是那一帧；指尖碰到时一颗四芒星，一道裂纹贯穿晶石。</li>
 *   <li><b>碎镜</b>：裂纹放大成盖满全屏的 Voronoi 碎玻璃，每一块折射的角度不一样，裂缝里透出白金色的光。</li>
 *   <li><b>爆破</b>：碎片往四面八方朝镜头飞散，露出后面活的画面（星辰花和冲击帧在那里）。</li>
 * </ul>
 *
 * @param invert   反色几成（0–1）
 * @param split    RGB 色散几成
 * @param ripple   波纹荡到哪了（0–1；小于 0 没有）
 * @param tint     琥珀染色几成
 * @param frozen   底下用抓下的那一帧（而不是活的画面）；晶石、碎片里面总是那一帧
 * @param collapse 坍缩到哪了（0–1；小于 0 没有）
 * @param gem      晶石显出来几成
 * @param centered 晶石在画面正中（坍缩那一镜），否则在她手边
 * @param crack    裂纹长到哪了（0–1）
 * @param glint    四芒星多亮
 * @param shatter  碎镜盖住画面几成
 * @param spread   碎镜的裂缝从中心往外爬到哪了（0–1）
 * @param burst    碎片飞散到哪了（0–1；小于 0 没有）
 */
public record KohakuUltimateLook(float invert, float split, float ripple, float tint, boolean frozen, float collapse, float gem, boolean centered,
                                 float crack, float glint, float shatter, float spread, float burst) {
    /** 什么都不画。 */
    public static final KohakuUltimateLook NONE = new KohakuUltimateLook(0, 0, -1, 0, false, -1, 0, false, 0, 0, 0, 0, -1);
    /** 反色那一下多久（刻）：一两帧。 */
    public static final float INVERT = .75f;

    /** 这一刻有没有要画的。 */
    public boolean active() {
        return invert > 0 || split > 0 || ripple >= 0 || tint > 0 || frozen || collapse >= 0 || gem > 0 || shatter > 0 || burst >= 0;
    }

    /** 碎片飞散用几刻。 */
    public static int burstTicks(KohakuUltimate.Beats b) {
        return b.chant() ? 9 : 6;
    }

    /**
     * 第 {@code age} 刻（带帧内插值）的参数。{@code gentle}：开了「隐藏闪电的闪烁」，跳过反色。
     */
    public static KohakuUltimateLook at(KohakuUltimate.Beats b, float age, boolean gentle) {
        if (age < b.stop()) return NONE;
        if (age < b.collapse()) {
            // 凝时：反色一下（完整版、没开隐藏闪烁时），色散冲一下再收住一半，波纹一圈圈往外荡，琥珀色慢慢染上来
            float local = age - b.stop(), span = b.collapse() - b.stop();
            float invert = !gentle && b.chant() && local < INVERT ? 1 : 0;
            float split = smooth(0, 1.5f, local) * (1 - .5f * smooth(2, span, local));
            float tint = .85f * smooth(0, 3, local);
            return new KohakuUltimateLook(invert, split, local / span, tint, age >= b.capture(), -1, 0, false, 0, 0, 0, 0, -1);
        }
        if (age < b.palm()) {
            // 坍缩：最后三成里晶石的轮廓显出来
            float p = (age - b.collapse()) / (b.palm() - b.collapse());
            return new KohakuUltimateLook(0, .35f * (1 - p), -1, .6f, true, p, smooth(.55f, .9f, p), true, 0, 0, 0, 0, -1);
        }
        if (age < b.shatter()) {
            // 掌中琥珀、轻弹：碰到以后裂纹贯穿晶石，四芒星一闪就收
            float crack = age < b.touch() ? 0 : smooth(0, b.shatter() - b.touch(), age - b.touch());
            float glint = age < b.touch() ? 0 : (float) Math.exp(-(age - b.touch()) * .45f);
            return new KohakuUltimateLook(0, 0, -1, 0, false, -1, 1, false, crack, glint, 0, 0, -1);
        }
        if (age < b.impact()) {
            // 碎镜：一下子盖满，裂缝从中心往外爬
            float spread = smooth(0, b.impact() - b.shatter(), age - b.shatter());
            return new KohakuUltimateLook(0, 0, -1, 0, true, -1, 0, true, 1, 0, 1, .25f + .75f * spread, -1);
        }
        float burst = (age - b.impact()) / burstTicks(b);
        if (burst < 1) return new KohakuUltimateLook(0, 0, -1, 0, true, -1, 0, true, 1, 0, 1, 1, burst);
        return NONE;
    }

    /** {@code from} 到 {@code to} 之间平滑地从 0 走到 1。 */
    static float smooth(float from, float to, float x) {
        float t = Math.clamp((x - from) / Math.max(1e-4f, to - from), 0, 1);
        return t * t * (3 - 2 * t);
    }

    /**
     * 这一版里全屏明暗交替几次（光敏检查）：反色算一次，坍缩进黑暗、从黑暗里切回来各一次，碎片飞散露出冲击帧算一次。
     * 单元测试拿它查任何一秒里不超过 3 次。
     */
    public static float[] luminanceFlips(KohakuUltimate.Beats b, boolean gentle) {
        if (!gentle && b.chant()) return new float[]{b.stop(), b.collapse(), b.palm(), b.impact()};
        return new float[]{b.collapse(), b.palm(), b.impact()};
    }
}
