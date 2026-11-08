package dev.logos.dimension.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.client.ShaderPackOverlay;
import dev.logos.client.VfxParts;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.longsword.LongswordRig;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.phys.Vec3;

/**
 * 大型蓄力 12 秒起剑身发出的金光：沿剑骨骼画，跟着动作走。三片绕剑身轴线各转 60° 的光（外晕、光身、白芯），
 * 从护手一直伸到剑尖外很远，看上去剑长了一倍多；光一边呼吸一边微微伸缩，剑尖一颗四芒星。蓄满后更长更亮
 * （剑身的金色流火是 Logos 的物品表面火焰，由服务端写在剑上）。
 * <p>
 * 剑骨骼空间以像素为单位，原点在握柄（物品模型的 (8, 1, 8)），剑身沿 +Y：护手约 4，剑尖约 23.4。
 */
public final class DawnvowBlade {
    /** 渲染状态上记着的剑身金光强弱（0 不画），蓄满时为负数的同等强弱。 */
    public static final ContextKey<Float> GLOW = new ContextKey<>(LogosDimension.id("dawnvow_blade"));
    private static final RenderType CROSS = VfxParts.add("arcane_ray/impact_cross");
    private static final float GUARD = 3.6f, TIP = 23.4f;
    /** 金光伸出剑尖多远（像素）。 */
    private static final float EXTEND = 30, EXTEND_FULL = 8;

    private DawnvowBlade() {}

    /** 编码：强弱与是否蓄满（蓄满时为负）。 */
    public static float encode(float glow, boolean full) { return full ? -glow : glow; }

    /** 在剑骨骼上画金光；{@code glow} 是 {@link #encode} 的值。 */
    public static void submit(float glow, LongswordRig.Sample sample, PoseStack pose, SubmitNodeCollector collector, boolean firstPerson, double time) {
        boolean full = glow < 0;
        float g = Math.abs(glow);
        if (g <= 0.002f) return;
        // 第一人称剑就在眼前，光照原样画会糊满屏幕：长度不变，亮度减半
        float light = firstPerson ? .5f : 1;
        SubmitNodeCollector out = ShaderPackOverlay.item(collector, firstPerson);
        float breathe = 1 + .07f * Mth.sin((float) time * .55f) + (full ? .05f * Mth.sin((float) time * 1.7f) : 0);
        float reach = TIP + (EXTEND * g + (full ? EXTEND_FULL : 0)) * breathe;
        pose.pushPose();
        pose.mulPose(sample.weapon());
        out.submitCustomGeometry(pose, DawnvowAura.LASER.world(), (p, buf) -> {
            for (int k = 0; k < 3; k++) {
                double a = k * Math.PI / 3 + time * .02;
                Vec3 across = new Vec3(Math.cos(a), 0, Math.sin(a));
                plane(p, buf, across, reach + 4, 15 * g * breathe, .2f * g * light, DawnvowAura.GOLD);
                plane(p, buf, across, reach, 6 * g, .5f * g * light, DawnvowAura.PALE);
                plane(p, buf, across, reach - 2, 1.8f, .95f * g * light, DawnvowAura.HOT);
            }
        });
        out.submitCustomGeometry(pose, CROSS, (p, buf) -> {
            Vec3 star = new Vec3(0, reach - (reach - TIP) * .15, 0);
            float size = (full ? 12 : 8) * g * breathe;
            for (int k = 0; k < 3; k++) {
                double a = k * Math.PI / 3 + time * .05;
                VfxParts.flat(p, buf, star, new Vec3(Math.cos(a), 0, Math.sin(a)), new Vec3(0, 1, 0), size, (float) time * .03f,
                        VfxParts.argb(.8f * g * light, DawnvowAura.HOT));
            }
        });
        pose.popPose();
    }

    /**
     * 一片光：剑身平面里沿 +Y 的长条，宽 {@code width}（沿 {@code across}），从护手到 {@code reach}；
     * 护手处渐显，剑身全亮，伸出剑尖的那一段渐渐淡到尖上为零。V 沿宽度是贴图的横截面。
     */
    private static void plane(PoseStack.Pose pose, VertexConsumer buf, Vec3 across, float reach, float width, float alpha, int rgb) {
        float[] ys = {GUARD, GUARD + 2.5f, TIP, TIP + (reach - TIP) * .45f, reach};
        float[] as = {0, 1, 1, .55f, 0};
        float[] ws = {.5f, 1, 1, .8f, .25f};
        for (int i = 0; i < ys.length - 1; i++) {
            Vec3 a = new Vec3(0, ys[i], 0), b = new Vec3(0, ys[i + 1], 0);
            Vec3 ha = across.scale(width * ws[i] / 2), hb = across.scale(width * ws[i + 1] / 2);
            int ca = VfxParts.argb(alpha * as[i], rgb), cb = VfxParts.argb(alpha * as[i + 1], rgb);
            VfxParts.vertex(pose, buf, a.add(ha), .5f, 0, ca);
            VfxParts.vertex(pose, buf, a.subtract(ha), .5f, 1, ca);
            VfxParts.vertex(pose, buf, b.subtract(hb), .5f, 1, cb);
            VfxParts.vertex(pose, buf, b.add(hb), .5f, 0, cb);
        }
    }
}
