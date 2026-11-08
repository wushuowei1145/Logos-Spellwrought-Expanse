package dev.logos.dimension.client.explosion;

import static dev.logos.dimension.client.explosion.Draw.GOLD;
import static dev.logos.dimension.client.explosion.Draw.PALE_GOLD;
import static dev.logos.dimension.client.explosion.Draw.RED;
import static dev.logos.dimension.client.explosion.Draw.RED_DEEP;
import static dev.logos.dimension.client.explosion.Draw.argb;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.client.VfxParts;
import dev.logos.dimension.client.screenfx.PostVfx;
import dev.logos.dimension.explosion.ExplosionTimeline;
import java.util.Arrays;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 目的地上空那一串红色法阵：大小不一，竖直叠成一列，吟唱中一个接一个出现（从下往上），每个出现时从小涨开、闪一圈光。
 * 一个法阵是几层叠起来的：外面一圈暗红的光晕、火焰花瓣的法阵、金色的符文环、亮金的外圈、中心一个小太阳纹；相邻两层反着转，
 * 上下相邻的法阵也反着转。一根细光柱穿过所有法阵的中心。都在后期里画（{@link PostVfx}），周围变暗时它们照样亮。
 */
final class SkyCircles {
    private static final int N = ExplosionTimeline.CIRCLE_HEIGHT.length;
    /** 出现用多少刻。 */
    private static final float APPEAR = 18;
    /** 底下那层半透明深红用的圆盘（Logos 的贴图里没有带透明度的实心圆）。 */
    static final net.minecraft.resources.Identifier DISC = dev.logos.dimension.LogosDimension.id("textures/vfx/explosion/disc.png");

    /** 每个法阵出现时的时刻（客户端刻），还没出现的是 NaN。 */
    private final float[] appeared = new float[N];

    SkyCircles() {
        Arrays.fill(appeared, Float.NaN);
    }

    /** 已经出现了 {@code count} 个；新出现的记下这一刻。 */
    void show(int count, float now) {
        for (int i = 0; i < Math.min(count, N); i++) if (Float.isNaN(appeared[i])) appeared[i] = now;
    }

    /** 一下子全都在（没看到吟唱就收到释放时）。 */
    void all(float now) {
        for (int i = 0; i < N; i++) if (Float.isNaN(appeared[i])) appeared[i] = now - APPEAR;
    }

    int shown() {
        int n = 0;
        for (float a : appeared) if (!Float.isNaN(a)) n++;
        return n;
    }

    /** 第 {@code i} 个法阵的中心。 */
    static Vec3 center(Vec3 target, float scale, int i) {
        return target.add(0, ExplosionTimeline.CIRCLE_HEIGHT[i] * scale, 0);
    }

    /**
     * @param bright 整体亮度（吟唱时随进度变亮，蓄满更亮，释放时一下更亮）
     * @param fade   整体的不透明度（中断或爆炸开始后淡出）
     */
    void submit(PoseStack stack, VfxParts.View view, Vec3 target, float scale, float now, float bright, float fade) {
        if (fade <= .003f) return;
        PoseStack.Pose pose = stack.last();
        Vec3 x = new Vec3(1, 0, 0), z = new Vec3(0, 0, 1);
        float pulse = .9f + .1f * Mth.sin(now * .15f);
        // 先画一根穿过所有中心的细光柱
        int top = -1;
        for (int i = 0; i < N; i++) if (!Float.isNaN(appeared[i])) top = i;
        if (top >= 1) {
            Vec3 a = center(target, scale, 0), b = center(target, scale, top);
            float k = fade * Math.min(1, bright) * .45f;
            VfxParts.streak(pose, PostVfx.add(PostVfx.logos("force/streak"), false), view, a, b, 1.6f * scale, argb(k, GOLD));
            VfxParts.streak(pose, PostVfx.add(PostVfx.logos("heal/glow"), false), view, a.add(0, -4 * scale, 0), b.add(0, 4 * scale, 0), 7 * scale, argb(k * .5f, RED));
        }
        for (int i = 0; i < N; i++) {
            if (Float.isNaN(appeared[i])) continue;
            float k = Mth.clamp((now - appeared[i]) / APPEAR, 0, 1);
            float grow = .35f + .65f * (1 - (1 - k) * (1 - k) * (1 - k));
            float shown = fade * Draw.smooth(0, .45f, k) * pulse;
            // 加法层的亮度封顶：再亮就把红色冲成白色了。更亮时（蓄满、释放）由外圈的一层光晕表现
            float a = shown * Math.min(1, bright), surge = Math.max(0, bright - 1);
            if (shown <= .003f) continue;
            Vec3 c = center(target, scale, i);
            float r = ExplosionTimeline.CIRCLE_RADIUS[i] * scale * grow;
            float dir = i % 2 == 0 ? 1 : -1;
            // 出现时转得快，慢慢稳下来
            float spin = dir * (now * .006f + (1 - k) * 1.2f);
            // 先涂一层半透明的深红：白天亮蓝的天空上，光靠加法只会冲成粉白
            VfxParts.flat(pose, PostVfx.blend(DISC, false), c.add(0, -.03, 0), x, z, r * .98f, 0, argb(shown * .62f, 0x86060A));
            VfxParts.flat(pose, PostVfx.add(PostVfx.logos("heal/glow"), false), c, x, z, r * 1.35f, 0, argb(a * .45f, RED_DEEP));
            VfxParts.flat(pose, PostVfx.add(PostVfx.logos("fire_field/circle_05"), false), c, x, z, r, spin, argb(a * .75f, RED));
            String runes = i % 2 == 0 ? "charge/circle_11" : "arcane_charge/circle_04";
            VfxParts.flat(pose, PostVfx.add(PostVfx.logos(runes), false), c.add(0, .02, 0), x, z, r * .84f, -spin * 1.7f, argb(a * .5f, GOLD));
            VfxParts.flat(pose, PostVfx.add(PostVfx.logos("heal/ring"), false), c, x, z, r * 1.08f, spin * .3f, argb(a * .85f, GOLD));
            VfxParts.flat(pose, PostVfx.add(PostVfx.logos("fire_charge/sun_circle"), false), c.add(0, .04, 0), x, z, r * .42f, spin * 2.6f,
                    argb(a * .5f, PALE_GOLD));
            if (surge > 0) VfxParts.flat(pose, PostVfx.add(PostVfx.logos("heal/ring"), false), c.add(0, .05, 0), x, z, r * 1.18f, 0,
                    argb(shown * Math.min(1, surge) * .6f, RED));
            if (k < 1) {
                // 出现的一圈光
                float ring = r * (.7f + 1.1f * k);
                VfxParts.flat(pose, PostVfx.add(PostVfx.logos("heal/ring"), false), c, x, z, ring, 0, argb(fade * (1 - k) * (1 - k), PALE_GOLD));
            }
        }
    }
}
