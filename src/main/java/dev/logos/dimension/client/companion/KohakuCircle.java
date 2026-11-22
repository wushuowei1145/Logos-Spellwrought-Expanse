package dev.logos.dimension.client.companion;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.client.VfxParts;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 琥珀的炼成阵（docs/KOHAKU_COMPANION.md 第 7.6、8.1 节）：登场时她从阵心蹦出来，撤退时阵把她卷走。
 * 用 Logos 现成的法阵贴图（{@link VfxParts}，同附属爆裂魔杖的天空法阵），换成琥珀色叠起来，几层反着转：
 * <ul>
 *   <li>底下一层暗琥珀的柔光（{@code heal/glow}）；</li>
 *   <li>外圈细线的星盘（{@code charge/circle_17}），慢慢转；</li>
 *   <li>中间带炼金符号的星芒阵（{@code arcane_charge/circle_04}），反着转——炼成阵的「星」；</li>
 *   <li>正中一枚粗线纹章（{@code charge/circle_28}），转得快；</li>
 *   <li>边缘一圈光环（{@code heal/ring}）；转开时一圈光往外扩。</li>
 * </ul>
 * 每一层先用半透明的深琥珀画一遍（白天在浅色地面上看得清纹样），再叠一层收着点的光（夜里看得见它在发光）。
 * 撤退时阵中间再升起一道琥珀色的光柱，她化成的光顺着它被卷走。颜色、亮度都收着点（同 Logos 的蓄力特效：一两层点缀就够）。
 */
final class KohakuCircle {
    /** 琥珀：亮、主色、深。 */
    static final int LIGHT = 0xFFD98A, MAIN = 0xE8A043, DEEP = 0x9A5418;
    /** 转开、收起各用几刻。 */
    static final float OPEN = 8, CLOSE = 12;

    private KohakuCircle() {}

    /**
     * 画在 {@code at}（脚下的地面，世界坐标）：半径 {@code radius}，转开了 {@code age} 刻、一共 {@code length} 刻；{@code withdraw} 时加光柱。
     * {@code pose} 是镜头相对的（调用方已经移到世界原点）。
     */
    static void submit(PoseStack pose, SubmitNodeCollector out, CameraRenderState camera, Vec3 at, float radius, float age, int length,
                       boolean withdraw, float clock) {
        float open = VfxParts.smooth(0, OPEN, age), close = 1 - VfxParts.smooth(length - CLOSE, length, age);
        float a = open * close;
        if (a <= .003f) return;
        float ease = 1 - (1 - open) * (1 - open) * (1 - open);
        float R = radius * (.35f + .65f * ease) * (1 + .1f * (1 - close));
        // 转开时转得快，慢慢稳下来
        float spin = clock * .006f + (1 - ease) * 1.4f;
        Vec3 c = at.add(0, .03, 0);
        // 先用半透明的深琥珀把纹样画在地上：白天在浅色的地上，光靠加法只会冲成一片奶白（同爆裂魔杖的天空法阵先垫一层深红）
        blend(pose, out, "heal/ring", c, R * 1.06f, 0, VfxParts.argb(.3f * a, DEEP));
        blend(pose, out, "charge/circle_17", c.add(0, .002, 0), R, spin, VfxParts.argb(.8f * a, DEEP));
        blend(pose, out, "arcane_charge/circle_04", c.add(0, .004, 0), R * .66f, -spin * 2, VfxParts.argb(.65f * a, DEEP));
        blend(pose, out, "charge/circle_28", c.add(0, .006, 0), R * .3f, spin * 4, VfxParts.argb(.75f * a, DEEP));
        // 再叠一层收着点的光：夜里、暗处看得见它在发光
        sub(pose, out, "heal/glow", c.add(0, -.01, 0), R * 1.2f, 0, VfxParts.argb(.08f * a, DEEP));
        sub(pose, out, "charge/circle_17", c.add(0, .01, 0), R, spin, VfxParts.argb(.35f * a, MAIN));
        sub(pose, out, "arcane_charge/circle_04", c.add(0, .012, 0), R * .66f, -spin * 2, VfxParts.argb(.3f * a, VfxParts.lerpColor(MAIN, LIGHT, .4f)));
        sub(pose, out, "charge/circle_28", c.add(0, .014, 0), R * .3f, spin * 4, VfxParts.argb(.35f * a, LIGHT));
        sub(pose, out, "heal/ring", c.add(0, .01, 0), R * 1.06f, 0, VfxParts.argb(.3f * a, MAIN));
        if (age < OPEN + 4) {
            // 转开的一圈光，往外扩着淡掉
            float k = Mth.clamp(age / (OPEN + 4), 0, 1);
            sub(pose, out, "heal/ring", c.add(0, .02, 0), radius * (.6f + .8f * k), 0, VfxParts.argb(.45f * (1 - k) * (1 - k), LIGHT));
        }
        if (withdraw) {
            // 光柱：阵转开以后升起来，她化成光的那一阵最亮，阵收起时一起淡掉
            float rise = VfxParts.smooth(OPEN, OPEN + 10, age), peak = (float) Math.exp(-Math.pow(age - length * .45f, 2) / 60);
            float k = rise * close * (.35f + .65f * peak);
            if (k > .003f) {
                VfxParts.View view = VfxParts.View.of(camera);
                Vec3 top = c.add(0, .6 + 2.2 * rise, 0);
                out.submitCustomGeometry(pose, VfxParts.add("heal/glow"), (p, buf) ->
                        VfxParts.streak(p, buf, view, c, top, radius * 1.1f, VfxParts.argb(.18f * k, MAIN)));
                out.submitCustomGeometry(pose, VfxParts.add("force/streak"), (p, buf) ->
                        VfxParts.streak(p, buf, view, c, top, radius * .3f, VfxParts.argb(.28f * k, MAIN)));
            }
        }
    }

    private static void sub(PoseStack pose, SubmitNodeCollector out, String texture, Vec3 c, float r, float turn, int color) {
        out.submitCustomGeometry(pose, VfxParts.add(texture), (p, buf) -> VfxParts.ground(p, buf, c, r, turn, color));
    }

    private static void blend(PoseStack pose, SubmitNodeCollector out, String texture, Vec3 c, float r, float turn, int color) {
        out.submitCustomGeometry(pose, VfxParts.blend(texture), (p, buf) -> VfxParts.ground(p, buf, c, r, turn, color));
    }
}
