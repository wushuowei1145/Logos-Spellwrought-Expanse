package dev.logos.dimension.client.vfx;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.dimension.LogosDimension;
import java.io.DataInputStream;
import java.io.IOException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 腐败之心的光柱：Logos 虚空光束（{@code VoidBeamVisual}）的几层管子和丝带照搬，颜色换成腐败的紫红，
 * 内芯不再按屏幕投星空（{@code corruption_pillar.fsh}）。网格和贴图直接按路径引用 Logos 的资源。
 * 落点不画虚空那颗球，换成液体飞溅（{@link CorruptionFx}）。
 */
final class CorruptionPillarVisual {
    /** 顶点色：腐败的紫红（ARGB）。 */
    static final int TINT = 0xB4286E;
    private static @Nullable Mesh tube, swell;
    private static boolean failed;
    private static final RenderType CORE = material("core", "solid", "VOID_CORE", false);
    private static final RenderType SOLID = material("solid", "solid", "VOID_SOLID", true);
    private static final RenderType FLOW = material("flow", "flow", "VOID_FLOW", true);
    private static final RenderType INNER_FLOW = material("inner_flow", "flow", "VOID_INNER_FLOW", true);
    private static final RenderType AURA = material("aura", "ribbon", "VOID_RIBBON", true);
    private static final RenderType PATCHES = material("patches", "solid", "VOID_PATCHES", false);

    private CorruptionPillarVisual() {}

    /** 从 {@code from} 到 {@code to}、半径 {@code radius} 的光柱；{@code alpha} 管淡入淡出。 */
    static void submit(Vec3 from, Vec3 to, float radius, float alpha, double now, Vec3 camera, PoseStack stack, SubmitNodeCollector out) {
        if (!meshes()) return;
        Vec3 delta = to.subtract(from);
        if (delta.lengthSqr() < .0025 || radius < .01f) return;
        Vec3 axis = delta.normalize();
        float length = (float) delta.length();
        int color = (Math.round(Math.clamp(alpha, 0, 1) * 255) << 24) | TINT;
        float neck = Math.min(.25f, 1.2f / length), base = .35f;
        stack.pushPose();
        stack.translate(-camera.x, -camera.y, -camera.z);
        Quaternionf rotation = new Quaternionf().rotationTo(new Vector3f(0, 1, 0), axis.toVector3f());
        tube(stack, out, CORE, tube, from, rotation, radius * .52f, length, now, false, neck, base, color);
        tube(stack, out, SOLID, tube, from, rotation, radius * .85f, length, now, true, neck, base, color);
        tube(stack, out, FLOW, swell, from, rotation, radius * .62f, length, now, false, neck, base, color);
        tube(stack, out, INNER_FLOW, tube, from, rotation, radius * .4f, length, now, false, neck, base, color);
        out.submitCustomGeometry(stack, AURA, (pose, buf) -> aura(pose, buf, from, to, camera, radius, now, color));
        float patchStart = Math.min(length * .25f, radius * 1.4f), patchEnd = Math.min(length * .2f, radius * 1.8f);
        if (length - patchStart - patchEnd > .1f) tube(stack, out, PATCHES, swell, from.add(axis.scale(patchStart)), rotation,
                radius * .95f, length - patchStart - patchEnd, now, false, 0, 1, color);
        stack.popPose();
    }

    private static void tube(PoseStack stack, SubmitNodeCollector out, RenderType material, Mesh mesh, Vec3 from, Quaternionf rotation,
            float radius, float length, double now, boolean displaced, float neck, float base, int color) {
        stack.pushPose();
        stack.translate(from.x, from.y, from.z);
        stack.mulPose(rotation);
        stack.scale(radius, length, radius);
        out.submitCustomGeometry(stack, material, (pose, buf) -> mesh.draw(pose, buf, now, displaced, neck, base, color));
        stack.popPose();
    }

    private static void aura(PoseStack.Pose pose, VertexConsumer buf, Vec3 from, Vec3 to, Vec3 camera, float radius, double now, int color) {
        Vec3 delta = to.subtract(from), side = delta.cross(camera.subtract(from.add(to).scale(.5)));
        if (side.lengthSqr() < 1e-8) side = delta.cross(new Vec3(0, 1, 0));
        side = side.lengthSqr() < 1e-8 ? new Vec3(1, 0, 0) : side.normalize();
        float length = (float) delta.length(), repeat = length / 3;
        for (int i = 0; i < 32; i++) {
            float a = i / 32f, b = (i + 1) / 32f;
            Vec3 p = from.add(delta.scale(a)), q = from.add(delta.scale(b));
            double wa = radius * (1.25 + .1 * Math.sin(a * 19 + now * .035)), wb = radius * (1.25 + .1 * Math.sin(b * 19 + now * .035));
            vertex(pose, buf, p.add(side.scale(wa)), a * repeat, 0, color);
            vertex(pose, buf, p.subtract(side.scale(wa)), a * repeat, 1, color);
            vertex(pose, buf, q.subtract(side.scale(wb)), b * repeat, 1, color);
            vertex(pose, buf, q.add(side.scale(wb)), b * repeat, 0, color);
        }
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buf, Vec3 p, float u, float v, int color) {
        buf.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(color).setUv(u, v)
                .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
    }

    private static boolean meshes() {
        if (tube != null) return true;
        if (failed) return false;
        try {
            tube = Mesh.load("tube_1");
            swell = Mesh.load("tube_2");
            return true;
        } catch (IOException | RuntimeException e) {
            failed = true;
            com.mojang.logging.LogUtils.getLogger().warn("腐败光柱：读不到 Logos 虚空光束的网格，不画光柱", e);
            return false;
        }
    }

    /** Logos 导出的网格：两个大端计数，每个顶点八个浮点（XYZ、UV、法线），再是三角形下标。 */
    private record Mesh(float[] vertices, int[] triangles) {
        static Mesh load(String name) throws IOException {
            var id = Identifier.fromNamespaceAndPath("logos", "models/vfx/void_beam/" + name + ".bin");
            var resource = Minecraft.getInstance().getResourceManager().getResource(id).orElseThrow(() -> new IOException("missing " + id));
            try (var in = new DataInputStream(resource.open())) {
                int count = in.readInt(), indices = in.readInt();
                if (count < 1 || count > 20000 || indices < 3 || indices > 100000 || indices % 3 != 0) throw new IOException("Invalid mesh: " + name);
                float[] vertices = new float[count * 8];
                int[] triangles = new int[indices];
                for (int i = 0; i < vertices.length; i++) vertices[i] = in.readFloat();
                for (int i = 0; i < indices; i++) {
                    triangles[i] = in.readInt();
                    if (triangles[i] < 0 || triangles[i] >= count) throw new IOException("Invalid mesh index");
                }
                return new Mesh(vertices, triangles);
            }
        }

        void draw(PoseStack.Pose pose, VertexConsumer buf, double now, boolean displaced, float neck, float base, int color) {
            for (int i = 0; i < triangles.length; i += 3) {
                put(pose, buf, triangles[i], now, displaced, neck, base, color);
                put(pose, buf, triangles[i + 1], now, displaced, neck, base, color);
                put(pose, buf, triangles[i + 2], now, displaced, neck, base, color);
                put(pose, buf, triangles[i + 2], now, displaced, neck, base, color);
            }
        }

        private void put(PoseStack.Pose pose, VertexConsumer buf, int index, double now, boolean displaced, float neck, float base, int color) {
            int k = index * 8;
            float bulge = displaced ? (float) (.035 * Math.sin(vertices[k + 3] * 18 + now * .08) * Math.sin(vertices[k + 4] * 25 - now * .05)) : 0;
            float narrow = neck > 0 ? base + (1 - base) * smooth(vertices[k + 1] / neck) : 1;
            buf.addVertex(pose, (vertices[k] + vertices[k + 5] * bulge) * narrow, vertices[k + 1], (vertices[k + 2] + vertices[k + 7] * bulge) * narrow)
                    .setColor(color).setUv(vertices[k + 3], vertices[k + 4])
                    .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, vertices[k + 5], vertices[k + 6], vertices[k + 7]);
        }
    }

    private static float smooth(float x) { x = Math.max(0, Math.min(1, x)); return x * x * (3 - 2 * x); }

    private static RenderType material(String name, String texture, String define, boolean additive) {
        boolean opaque = name.equals("patches");
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(LogosDimension.id("pipeline/corruption_pillar_" + name)).withVertexShader(logos("core/void_projectile"))
                .withFragmentShader(LogosDimension.id("core/corruption_pillar")).withShaderDefine(define).withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, opaque))
                .withColorTargetState(new ColorTargetState(additive ? BlendFunction.LIGHTNING : BlendFunction.TRANSLUCENT));
        // 同一份着色器的各层按宏取用不同的贴图，采样器都绑上。
        pipeline.withSampler("NoiseTex").withSampler("WarpTex").withSampler("StarTex").withSampler("AuraTex");
        var setup = RenderSetup.builder(pipeline.build()).withTexture("Sampler0", logos("textures/vfx/void_beam/" + texture + ".png"))
                .withTexture("NoiseTex", logos("textures/vfx/void_beam/dissolve.png"))
                .withTexture("WarpTex", logos("textures/vfx/void_beam/warp.png"))
                .withTexture("StarTex", Identifier.withDefaultNamespace("textures/entity/end_portal/end_portal.png"))
                .withTexture("AuraTex", logos("textures/vfx/void_beam/aura.png"));
        return RenderType.create("logos_dimension_corruption_pillar_" + name, setup.sortOnUpload().createRenderSetup());
    }

    private static Identifier logos(String path) { return Identifier.fromNamespaceAndPath("logos", path); }
}
