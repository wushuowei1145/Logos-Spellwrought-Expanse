package dev.logos.dimension.client.vfx;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.client.VfxParts;
import dev.logos.dimension.LogosDimension;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;

/**
 * 超电磁炮共用的材质和几何。材质直接用 Logos 物理射线与雷霆射线的 shader 和贴图（{@code logos:} 路径，不复制）：
 * {@code physical_ray_fx} 的 RAY_LINE 画中间硬、两边软的光带，RAY_FLASH 按贴图透明度画闪光，RAY_AIR 画被噪声撕开的气浪环；
 * {@code lightning_ray_line} 画电弧。几何每个顶点写全位置、颜色、UV、光照和法线。
 */
final class RailgunDraw {
    static final RenderType LINE = material("line", "physical_ray_fx", "physical_ray/glow", "RAY_LINE", true);
    static final RenderType FLASH = material("flash", "physical_ray_fx", "physical_ray/cross", "RAY_FLASH", true);
    static final RenderType GLOW = material("glow", "physical_ray_fx", "physical_ray/glow", "RAY_FLASH", true);
    static final RenderType AIR = material("air", "physical_ray_fx", "physical_ray/air", "RAY_AIR", false);
    static final RenderType BOLT = material("bolt", "lightning_ray_line", "lightning_ray/line", "RAY_LINE", true);
    /** 往后喷的尘土：Logos 物理射线命中烟团的 4×4 序列帧，半透明。 */
    static final RenderType SMOKE = VfxParts.blend("physical_ray/smoke");
    /** 抛起的那枚硬币，就用银元的物品贴图。 */
    static final RenderType COIN = VfxParts.cut(LogosDimension.id("textures/item/imperial_silver.png"));

    /** 电弧：外圈蓝、芯发白（shader 会把中间推向白色）。 */
    static final int ARC = 0x7CC4FF, ARC_HOT = 0xCFEFFF;

    private RailgunDraw() {}

    private static RenderType material(String name, String shader, String texture, String define, boolean additive) {
        boolean air = define.equals("RAY_AIR");
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(LogosDimension.id("pipeline/railgun_" + name)).withFragmentShader(logos("core/" + shader))
                .withShaderDefine(define).withCull(false).withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(additive ? BlendFunction.LIGHTNING : BlendFunction.TRANSLUCENT));
        if (air) pipeline.withSampler("NoiseTex");
        var setup = RenderSetup.builder(pipeline.build()).withTexture("Sampler0", logos("textures/vfx/" + texture + ".png"));
        if (air) setup.withTexture("NoiseTex", logos("textures/vfx/physical_ray/wind.png"));
        return RenderType.create("logos_dimension_railgun_" + name, setup.sortOnUpload().createRenderSetup());
    }

    private static Identifier logos(String path) { return Identifier.fromNamespaceAndPath("logos", path); }

    // ---- 几何 ------------------------------------------------------------------------------------

    static void vertex(PoseStack.Pose pose, VertexConsumer buf, Vec3 p, float u, float v, int color) {
        buf.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(color).setUv(u, v)
                .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
    }

    /** 从 {@code a} 到 {@code b} 的一条朝向镜头的光带，V 横跨带宽（RAY_LINE 靠它画出中间的亮线）。 */
    static void band(PoseStack.Pose pose, VertexConsumer buf, Vec3 a, Vec3 b, Vec3 eye, float width, int color) {
        Vec3 axis = b.subtract(a);
        if (axis.lengthSqr() < 1e-8 || color >>> 24 == 0) return;
        Vec3 side = perpendicular(axis.normalize(), eye.subtract(a.add(b).scale(.5)));
        float wa = width(a, eye, width), wb = width(b, eye, width);
        vertex(pose, buf, a.add(side.scale(wa)), 0, 0, color);
        vertex(pose, buf, a.subtract(side.scale(wa)), 0, 1, color);
        vertex(pose, buf, b.subtract(side.scale(wb)), 1, 1, color);
        vertex(pose, buf, b.add(side.scale(wb)), 1, 0, color);
    }

    /** 半宽：贴近镜头时变细，远处至少一个半像素宽。 */
    static float width(Vec3 p, Vec3 eye, float width) {
        float d = (float) p.distanceTo(eye);
        return Math.max(width * Math.min(1, .08f + d / 6), d * .0055f);
    }

    /** 垂直于 {@code n} 的一圈细亮线（用 LINE 画，每段一条朝向镜头的光带）：冲击环清晰的白边。 */
    static void lineRing(PoseStack.Pose pose, VertexConsumer buf, Vec3 c, Vec3 n, float radius, float width, Vec3 eye, int seed,
            int segments, int color) {
        if (radius <= 0 || color >>> 24 == 0) return;
        Vec3 rr = perpendicular(n, new Vec3(0, 1, 0)), uu = n.cross(rr);
        Vec3 prev = null;
        for (int i = 0; i <= segments; i++) {
            double a = i * Math.PI * 2 / segments;
            double r = radius * (1 + .05 * Math.sin(a * 5 + seed) + .025 * Math.sin(a * 11 - seed * .3));
            Vec3 p = c.add(rr.scale(Math.cos(a) * r)).add(uu.scale(Math.sin(a) * r));
            if (prev != null) band(pose, buf, prev, p, eye, width, color);
            prev = p;
        }
    }

    /** 沿一串折线点画朝向镜头的带子（电弧），V 横跨带宽、U 沿线走；离镜头一格半内淡出，免得贴脸的一段糊住画面。 */
    static void ribbon(PoseStack.Pose pose, VertexConsumer buf, Vec3[] pts, Vec3 eye, float width, int color) {
        int n = pts.length;
        if (n < 2 || color >>> 24 == 0) return;
        float alpha = (color >>> 24) / 255f;
        Vec3 prevL = null, prevR = null;
        int prevC = 0;
        for (int i = 0; i < n; i++) {
            Vec3 tangent = pts[Math.min(n - 1, i + 1)].subtract(pts[Math.max(0, i - 1)]);
            Vec3 across = tangent.cross(eye.subtract(pts[i]));
            if (across.lengthSqr() < 1e-12) across = perpendicular(tangent.lengthSqr() < 1e-12 ? new Vec3(0, 0, 1) : tangent.normalize(), new Vec3(0, 1, 0));
            across = across.normalize().scale(width(pts[i], eye, width));
            Vec3 l = pts[i].add(across), r = pts[i].subtract(across);
            int c = VfxParts.argb(alpha * VfxParts.fade(eye, pts[i], .4, 1.5), color);
            if (i > 0 && (c >>> 24 != 0 || prevC >>> 24 != 0)) {
                float u0 = (i - 1) * .25f, u1 = i * .25f;
                vertex(pose, buf, prevL, u0, 0, prevC);
                vertex(pose, buf, prevR, u0, 1, prevC);
                vertex(pose, buf, r, u1, 1, c);
                vertex(pose, buf, l, u1, 0, c);
            }
            prevL = l;
            prevR = r;
            prevC = c;
        }
    }

    /** 一道锯齿电弧的折点：两端钉在 {@code a}、{@code b}，中间横向乱跳 {@code amp} 格。 */
    static Vec3[] bolt(Vec3 a, Vec3 b, int seed, double amp, double step) {
        Vec3 d = b.subtract(a);
        double len = d.length();
        if (len < 1e-4) return new Vec3[] {a, b};
        Vec3 dir = d.scale(1 / len), right = perpendicular(dir, new Vec3(0, 1, 0)), up = dir.cross(right);
        int n = (int) Math.max(2, Math.min(60, Math.ceil(len / step)));
        Vec3[] pts = new Vec3[n + 1];
        for (int i = 0; i <= n; i++) {
            double s = (double) i / n, env = Math.sin(Math.PI * s);
            double x = (hash(seed, i, 3) * 2 - 1) * amp * env, y = (hash(seed, i, 5) * 2 - 1) * amp * env;
            pts[i] = a.add(d.scale(s)).add(right.scale(x)).add(up.scale(y));
        }
        return pts;
    }

    /** 朝向镜头的方块，半宽 {@code rx}、半高 {@code ry}，转 {@code roll}。 */
    static void flare(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, Vec3 at, float rx, float ry, float roll, int color) {
        if (rx <= 0 || ry <= 0 || color >>> 24 == 0) return;
        float cos = (float) Math.cos(roll), sin = (float) Math.sin(roll);
        Vec3 a = view.right().scale(cos * rx).add(view.up().scale(sin * rx));
        Vec3 b = view.up().scale(cos * ry).subtract(view.right().scale(sin * ry));
        vertex(pose, buf, at.subtract(a).add(b), 0, 0, color);
        vertex(pose, buf, at.subtract(a).subtract(b), 0, 1, color);
        vertex(pose, buf, at.add(a).subtract(b), 1, 1, color);
        vertex(pose, buf, at.add(a).add(b), 1, 0, color);
    }

    /** 垂直于 {@code n} 的一圈气浪环，边缘被扰乱，外沿向后卷 {@code curl}（RAY_AIR：U 绕一圈、V 横跨环宽）。 */
    static void ring(PoseStack.Pose pose, VertexConsumer buf, Vec3 c, Vec3 n, float radius, float width, float alpha,
            float curl, int seed, int segments, int rgb) {
        if (alpha <= .004f || radius <= 0) return;
        Vec3 rr = perpendicular(n, new Vec3(0, 1, 0)), uu = n.cross(rr);
        int color = VfxParts.argb(alpha, rgb);
        int rows = 3;
        for (int i = 0; i < segments; i++) {
            double a0 = i * Math.PI * 2 / segments, a1 = (i + 1) * Math.PI * 2 / segments;
            for (int j = 0; j < rows; j++) {
                float v0 = j / (float) rows, v1 = (j + 1) / (float) rows;
                vertex(pose, buf, ringPoint(c, n, rr, uu, a0, v0, radius, width, curl, seed), i / (float) segments, v0, color);
                vertex(pose, buf, ringPoint(c, n, rr, uu, a1, v0, radius, width, curl, seed), (i + 1) / (float) segments, v0, color);
                vertex(pose, buf, ringPoint(c, n, rr, uu, a1, v1, radius, width, curl, seed), (i + 1) / (float) segments, v1, color);
                vertex(pose, buf, ringPoint(c, n, rr, uu, a0, v1, radius, width, curl, seed), i / (float) segments, v1, color);
            }
        }
    }

    private static Vec3 ringPoint(Vec3 c, Vec3 n, Vec3 rr, Vec3 uu, double angle, float v, float radius, float width, float curl, int seed) {
        double ruffle = .07 * Math.sin(angle * 5 + seed) + .03 * Math.sin(angle * 11 - seed * .3);
        double r = Math.max(.02, radius * (1 + ruffle) + (v * 2 - 1) * width);
        return c.add(rr.scale(Math.cos(angle) * r)).add(uu.scale(Math.sin(angle) * r)).subtract(n.scale(curl * (v * 2 - 1) * (v * 2 - 1)));
    }

    /**
     * 一个音爆锥：外沿逆着 {@code dir} 向后掠的环，向前抛出并张开。{@code t} 是它自己的年龄，画 {@code life} 刻。
     */
    static void boom(PoseStack.Pose pose, VertexConsumer buf, Vec3 base, Vec3 dir, float t, float life, float size, float alpha, int seed, int rgb) {
        if (t < 0 || t >= life) return;
        float p = t / life, e = 1 - (1 - p) * (1 - p) * (1 - p);
        Vec3 c = base.add(dir.scale(.2 + 1.2 * e * size / 2.2));
        float inner = .1f + .45f * size * e, outer = .25f + size * e;
        float sweep = (.15f + .55f * e) * size;
        int color = VfxParts.argb((1 - p) * (1 - p) * alpha, rgb);
        Vec3 rr = perpendicular(dir, new Vec3(0, 1, 0)), uu = dir.cross(rr);
        int segments = 48, rows = 4;
        for (int i = 0; i < segments; i++) {
            double a0 = i * Math.PI * 2 / segments, a1 = (i + 1) * Math.PI * 2 / segments;
            for (int j = 0; j < rows; j++) {
                float v0 = j / (float) rows, v1 = (j + 1) / (float) rows;
                vertex(pose, buf, conePoint(c, dir, rr, uu, a0, v0, inner, outer, sweep, seed), i / (float) segments, v0, color);
                vertex(pose, buf, conePoint(c, dir, rr, uu, a1, v0, inner, outer, sweep, seed), (i + 1) / (float) segments, v0, color);
                vertex(pose, buf, conePoint(c, dir, rr, uu, a1, v1, inner, outer, sweep, seed), (i + 1) / (float) segments, v1, color);
                vertex(pose, buf, conePoint(c, dir, rr, uu, a0, v1, inner, outer, sweep, seed), i / (float) segments, v1, color);
            }
        }
    }

    private static Vec3 conePoint(Vec3 c, Vec3 dir, Vec3 rr, Vec3 uu, double angle, float v, float inner, float outer, float sweep, int seed) {
        double ruffle = 1 + .06 * Math.sin(angle * 6 + seed) + .03 * Math.sin(angle * 13 - seed * .7);
        double r = (inner + (outer - inner) * v) * ruffle;
        return c.add(rr.scale(Math.cos(angle) * r)).add(uu.scale(Math.sin(angle) * r)).subtract(dir.scale(sweep * v * v));
    }

    // ---- 数学 ------------------------------------------------------------------------------------

    /** 一瞬间亮起，然后像曝光闪光一样在 {@code life} 刻内衰减到 0。 */
    static float flash(float t, float life) {
        if (t < 0 || t >= life) return 0;
        if (t < .35f) return .55f + .45f * t / .35f;
        return (float) Math.pow(1 - (t - .35f) / (life - .35f), 1.4);
    }

    static float easeOut(float p) {
        p = Math.max(0, Math.min(1, p));
        return 1 - (1 - p) * (1 - p) * (1 - p);
    }

    /** (seed, i, salt) 对应的一个稳定的 [0, 1) 随机数。 */
    static float hash(int seed, int i, int salt) {
        int h = seed * 0x9E3779B1 + i * 0x85EBCA6B + salt * 0xC2B2AE35;
        h ^= h >>> 15; h *= 0x2C1B3C6D; h ^= h >>> 12; h *= 0x297A2D39; h ^= h >>> 15;
        return (h >>> 8) / (float) (1 << 24);
    }

    static Vec3 perpendicular(Vec3 axis, Vec3 view) {
        Vec3 side = axis.cross(view);
        if (side.lengthSqr() < 1e-8) side = axis.cross(new Vec3(1, 0, 0));
        return side.lengthSqr() < 1e-8 ? new Vec3(0, 0, 1) : side.normalize();
    }
}
