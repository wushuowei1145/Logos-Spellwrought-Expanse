package dev.logos.dimension.client.vfx;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntFunction;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.logos.dimension.LogosDimension;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 附属特效的材质与几何：贴图直接读 Logos 已有的 {@code logos:textures/vfx/...}（不复制美术资源），
 * 几何有面向镜头的方片、平放的方片、拖尾条、开口圆筒和小方块。每个顶点写全位置、颜色、UV、光照和法线，
 * 调用方经 Logos 的 ShaderPackOverlay 收集器提交，开光影时同样可见（见 docs/VFX.md）。
 */
public final class VfxParts {
    private VfxParts() {}

    private static final Map<String, RenderType> ADD = new HashMap<>();
    private static final Map<String, RenderType> BLEND = new HashMap<>();

    /** 加法发光材质，贴图为 {@code logos:textures/vfx/<texture>.png}。 */
    public static RenderType add(String texture) {
        return ADD.computeIfAbsent("logos:" + texture, k -> material(Identifier.fromNamespaceAndPath("logos", "textures/vfx/" + texture + ".png"), true));
    }

    /** 半透明混合材质，贴图为 {@code logos:textures/vfx/<texture>.png}。 */
    public static RenderType blend(String texture) {
        return BLEND.computeIfAbsent("logos:" + texture, k -> material(Identifier.fromNamespaceAndPath("logos", "textures/vfx/" + texture + ".png"), false));
    }

    /** 加法发光材质，贴图是附属自己的 {@code logos_dimension:textures/vfx/<texture>.png}（Logos 没有的才放这里）。 */
    public static RenderType addOwn(String texture) {
        return ADD.computeIfAbsent(LogosDimension.ID + ":" + texture, k -> material(LogosDimension.id("textures/vfx/" + texture + ".png"), true));
    }

    /** 半透明混合材质，贴图是附属自己的 {@code logos_dimension:textures/vfx/<texture>.png}。 */
    public static RenderType blendOwn(String texture) {
        return BLEND.computeIfAbsent(LogosDimension.ID + ":" + texture, k -> material(LogosDimension.id("textures/vfx/" + texture + ".png"), false));
    }

    /** 方块图集上的半透明材质：碎块用地面方块自己的贴图，写深度，互相遮挡。 */
    public static final RenderType BLOCKS = RenderType.create("logos_dimension_vfx_blocks", RenderSetup.builder(
            RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET).withLocation(LogosDimension.id("pipeline/vfx_blocks")).withCull(false)
                    .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).build())
            .withTexture("Sampler0", Identifier.withDefaultNamespace("textures/atlas/blocks.png")).sortOnUpload().createRenderSetup());

    private static RenderType material(Identifier texture, boolean additive) {
        String path = texture.getPath().replace("textures/vfx/", "").replace(".png", "");
        String key = (additive ? "add_" : "blend_") + (texture.getNamespace().equals("logos") ? "" : "own_") + path.replace('/', '_');
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET)
                .withLocation(LogosDimension.id("pipeline/vfx_" + key)).withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(additive ? BlendFunction.LIGHTNING : BlendFunction.TRANSLUCENT)).build();
        return RenderType.create("logos_dimension_vfx_" + key, RenderSetup.builder(pipeline)
                .withTexture("Sampler0", texture).sortOnUpload().createRenderSetup());
    }

    /**
     * 按层次先后提交的入口：第 {@code n} 层画在第 {@code n - 1} 层之后。原版把同一批自定义几何按材质放进
     * HashMap，不同材质谁先画每次启动都不一样；半透明的暗层若先画，就会被后画的加法亮层冲掉。所以不开光影时
     * 每层放进各自的 {@code order(n)}（按序号分批，序号小的先画）；开光影时 ShaderPackOverlay 的叠加本来就按提交顺序画，
     * 照常交给它。{@code raw} 是事件原本的收集器，{@code out} 是经过 ShaderPackOverlay 的。
     */
    public static IntFunction<OrderedSubmitNodeCollector> layered(SubmitNodeCollector raw, SubmitNodeCollector out) {
        return out == raw ? n -> raw.order(LAYER_ORDER + n) : n -> out;
    }

    /** 附属特效分层用的起始序号；原版和 Logos 的自定义几何都在 0。 */
    private static final int LAYER_ORDER = 1;

    // ---- 几何 ----------------------------------------------------------------------------------

    public static void vertex(PoseStack.Pose pose, VertexConsumer buf, Vec3 p, float u, float v, int color) {
        buf.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(color).setUv(u, v)
                .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
    }

    /** 以 {@code c} 为中心、由半边向量 {@code a}、{@code b} 张成的方片，铺满整张贴图。 */
    public static void quad(PoseStack.Pose pose, VertexConsumer buf, Vec3 c, Vec3 a, Vec3 b, int color) {
        vertex(pose, buf, c.subtract(a).add(b), 0, 0, color);
        vertex(pose, buf, c.subtract(a).subtract(b), 0, 1, color);
        vertex(pose, buf, c.add(a).subtract(b), 1, 1, color);
        vertex(pose, buf, c.add(a).add(b), 1, 0, color);
    }

    /** 面向镜头、半边长 {@code r}、转过 {@code roll} 的方片。 */
    public static void sprite(PoseStack.Pose pose, VertexConsumer buf, View view, Vec3 c, float r, float roll, int color) {
        float cos = Mth.cos(roll) * r, sin = Mth.sin(roll) * r;
        quad(pose, buf, c, view.right.scale(cos).add(view.up.scale(sin)), view.up.scale(cos).subtract(view.right.scale(sin)), color);
    }

    /** 平放在单位向量 {@code a}、{@code b} 所在平面上的方片，半边长 {@code r}，转过 {@code turn}。 */
    public static void flat(PoseStack.Pose pose, VertexConsumer buf, Vec3 c, Vec3 a, Vec3 b, float r, float turn, int color) {
        float cos = Mth.cos(turn) * r, sin = Mth.sin(turn) * r;
        quad(pose, buf, c, a.scale(cos).add(b.scale(sin)), b.scale(cos).subtract(a.scale(sin)), color);
    }

    /** 平放在地面（水平面）上的方片。 */
    public static void ground(PoseStack.Pose pose, VertexConsumer buf, Vec3 c, float r, float turn, int color) {
        flat(pose, buf, c, new Vec3(1, 0, 0), new Vec3(0, 0, 1), r, turn, color);
    }

    /** 从 {@code tail} 到 {@code head}、宽 {@code w} 的面向镜头条带，贴图 U 从尾到头。 */
    public static void streak(PoseStack.Pose pose, VertexConsumer buf, View view, Vec3 tail, Vec3 head, float w, int color) {
        Vec3 along = head.subtract(tail);
        Vec3 side = along.cross(head.add(tail).scale(.5).subtract(view.cam));
        if (side.lengthSqr() < 1e-10) return;
        side = side.normalize().scale(w * .5);
        vertex(pose, buf, tail.add(side), 0, 0, color);
        vertex(pose, buf, tail.subtract(side), 0, 1, color);
        vertex(pose, buf, head.subtract(side), 1, 1, color);
        vertex(pose, buf, head.add(side), 1, 0, color);
    }

    /** 同 {@link #streak}，但只用贴图的 [u0,u1]×[v0,v1] 一块：序列帧排在一张图里时用。 */
    public static void streak(PoseStack.Pose pose, VertexConsumer buf, View view, Vec3 tail, Vec3 head, float w,
            float u0, float v0, float u1, float v1, int color) {
        Vec3 along = head.subtract(tail);
        Vec3 side = along.cross(head.add(tail).scale(.5).subtract(view.cam));
        if (side.lengthSqr() < 1e-10) return;
        side = side.normalize().scale(w * .5);
        vertex(pose, buf, tail.add(side), u0, v0, color);
        vertex(pose, buf, tail.subtract(side), u0, v1, color);
        vertex(pose, buf, head.subtract(side), u1, v1, color);
        vertex(pose, buf, head.add(side), u1, v0, color);
    }

    /**
     * 从 {@code base} 竖起的开口圆筒，半径 {@code radius}、高 {@code height}：U 从 {@code u0} 起绕一圈
     * 重复 {@code uTiles} 次，V 从底部的 {@code v0} 起跨 {@code vTiles}。底、顶分别是两种颜色。
     */
    public static void cylinder(PoseStack.Pose pose, VertexConsumer buf, Vec3 base, float radius, float height, int segments,
            float u0, float uTiles, float v0, float vTiles, int bottom, int top) {
        for (int i = 0; i < segments; i++) {
            double a0 = Math.PI * 2 * i / segments, a1 = Math.PI * 2 * (i + 1) / segments;
            Vec3 p0 = base.add(Math.cos(a0) * radius, 0, Math.sin(a0) * radius);
            Vec3 p1 = base.add(Math.cos(a1) * radius, 0, Math.sin(a1) * radius);
            float s0 = u0 + uTiles * i / segments, s1 = u0 + uTiles * (i + 1) / segments;
            vertex(pose, buf, p0, s0, v0 + vTiles, bottom);
            vertex(pose, buf, p0.add(0, height, 0), s0, v0, top);
            vertex(pose, buf, p1.add(0, height, 0), s1, v0, top);
            vertex(pose, buf, p1, s1, v0 + vTiles, bottom);
        }
    }

    /** 中心 {@code c}、半边长 {@code r}、转过 {@code turn} 的小方块，每面显示 [u0,u1]×[v0,v1]，按朝向明暗。 */
    public static void cube(PoseStack.Pose pose, VertexConsumer buf, Vec3 c, float r, Quaternionf turn, float u0, float v0, float u1, float v1,
            int rgb, float alpha) {
        Vector3f[] axes = {turn.transform(new Vector3f(r, 0, 0)), turn.transform(new Vector3f(0, r, 0)), turn.transform(new Vector3f(0, 0, r))};
        for (int f = 0; f < 6; f++) {
            Vector3f n = new Vector3f(axes[f % 3]).mul(f < 3 ? 1 : -1);
            Vector3f a = axes[(f + 1) % 3], b = axes[(f + 2) % 3];
            // 顶面最亮、底面最暗，像被上方照亮
            float shade = .62f + .38f * Mth.clamp(n.y / r * .5f + .5f, 0, 1);
            int color = argb(alpha, lerpColor(0, rgb, shade));
            Vec3 m = c.add(n.x, n.y, n.z), av = new Vec3(a.x, a.y, a.z), bv = new Vec3(b.x, b.y, b.z);
            vertex(pose, buf, m.subtract(av).add(bv), u0, v0, color);
            vertex(pose, buf, m.subtract(av).subtract(bv), u0, v1, color);
            vertex(pose, buf, m.add(av).subtract(bv), u1, v1, color);
            vertex(pose, buf, m.add(av).add(bv), u1, v0, color);
        }
    }

    /** 本帧镜头的位置与屏幕坐标轴。 */
    public record View(Vec3 cam, Vec3 right, Vec3 up) {
        public static View of(CameraRenderState camera) {
            return new View(camera.pos, new Vec3(camera.orientation.transform(new Vector3f(1, 0, 0))),
                    new Vec3(camera.orientation.transform(new Vector3f(0, 1, 0))));
        }
    }

    // ---- 数学与颜色 -----------------------------------------------------------------------------

    public static float smooth(float lo, float hi, float x) {
        float k = Mth.clamp((x - lo) / (hi - lo), 0, 1);
        return k * k * (3 - 2 * k);
    }

    /** 离镜头 {@code lo} 格以内为 0，{@code hi} 格以外为 1：贴近镜头的东西淡出，不糊满视野。 */
    public static float fade(Vec3 cam, Vec3 p, double lo, double hi) {
        return smooth((float) lo, (float) hi, (float) cam.distanceTo(p));
    }

    public static int argb(float alpha, int rgb) {
        return (int) (Mth.clamp(alpha, 0, 1) * 255) << 24 | rgb & 0xFFFFFF;
    }

    public static int lerpColor(int a, int b, float k) {
        k = Mth.clamp(k, 0, 1);
        int r = (int) Mth.lerp(k, a >> 16 & 255, b >> 16 & 255);
        int g = (int) Mth.lerp(k, a >> 8 & 255, b >> 8 & 255);
        int bl = (int) Mth.lerp(k, a & 255, b & 255);
        return r << 16 | g << 8 | bl;
    }
}
