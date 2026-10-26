package dev.logos.dimension.client;

import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.*;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.cloudsea.CloudseaBlocks;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.*;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.*;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import org.joml.Vector3f;

/**
 * 长风草原的草浪：把附近草尖的四边形一次提交，交给顶点着色器按风摆动。
 *
 * <p>为什么这么做：
 * <ul>
 *   <li>26.1 的区块只有三个固定渲染层，没办法只给"草"换地形管线；</li>
 *   <li>所以草浪走自定义几何（与 {@link LogosSkyOverlay} 共用收集器，光影包能看见），
 *       只在玩家附近生成草尖片，远处不提交；</li>
 *   <li>CPU 每帧只做位置采样与顶点装填，位移、阵风、波浪相位全在 GPU 完成。</li>
 * </ul>
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class WindGrass {
    /** 附近多少格内提交草尖。 */
    private static final int RADIUS = 20;
    /** 每帧最多提交多少片草尖（每片 2 个交叉四边形）。 */
    /** 上限按"草片"算（每片 2 段），而不是按草丛记录算，避免实际片数失控。 */
    private static final int MAX_SHEETS = 7000;
    private static final ContextKey<List<Grass>> GRASS = new ContextKey<>(LogosDimension.id("wind_grass_state"));
    private static final Identifier BLADE_TEXTURE = LogosDimension.id("textures/block/wind_blade.png");

    private record Grass(double x, double y, double z, int light, double distance, boolean tall) {}

    private static RenderType bladeType, atlasType;
    /** 上一帧实际提交的草片数（调试用）。 */
    public static volatile int lastSheets;

    private WindGrass() {}

    private static RenderType type(boolean atlas) {
        if (bladeType == null) {
            var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(LogosDimension.id("pipeline/wind_grass"))
                .withVertexShader(LogosDimension.id("core/wind_grass"))
                .withFragmentShader(LogosDimension.id("core/wind_grass"))
                .withCull(false)
                .withSampler("Sampler0")
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
                .build();
            bladeType = RenderType.create("logos_dimension_wind_grass",
                RenderSetup.builder(pipeline).withTexture("Sampler0", BLADE_TEXTURE).sortOnUpload().createRenderSetup());
            var tallPipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(LogosDimension.id("pipeline/wind_grass_tall"))
                .withVertexShader(LogosDimension.id("core/wind_grass"))
                .withFragmentShader(LogosDimension.id("core/wind_grass_tall"))
                .withCull(false)
                .withSampler("Sampler0")
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
                .build();
            atlasType = RenderType.create("logos_dimension_wind_grass_atlas",
                RenderSetup.builder(tallPipeline).withTexture("Sampler0", TextureAtlas.LOCATION_BLOCKS).sortOnUpload().createRenderSetup());
        }
        return atlas ? atlasType : bladeType;
    }

    @SubscribeEvent
    static void extract(ExtractLevelRenderStateEvent event) {
        var level = event.getLevel();
        if (!level.dimension().equals(LogosDimension.CLOUDSEA)) return;
        var cam = event.getCamera().position();
        var center = BlockPos.containing(cam);
        var grass = new ArrayList<Grass>();
        var longGrass = CloudseaBlocks.MEADOW_GRASS.get();
        for (var pos : BlockPos.betweenClosed(center.offset(-RADIUS, -3, -RADIUS), center.offset(RADIUS, 3, RADIUS))) {
            BlockState state = level.getBlockState(pos);
            boolean tall = state.is(net.minecraft.world.level.block.Blocks.TALL_GRASS)
                && state.getValue(net.minecraft.world.level.block.DoublePlantBlock.HALF)
                    == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER;
            if (!tall && !state.is(longGrass)) continue;
            int sky = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos);
            int blockLight = level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos);
            float day = 1f - Math.min(11, level.getSkyDarken()) / 11f * 0.72f;
            float bright = Math.max(blockLight / 15f, sky / 15f * day);
            bright = 0.20f + 0.80f * Math.min(1f, bright);
            grass.add(new Grass(pos.getX() + .5, pos.getY(), pos.getZ() + .5,
                Math.round(bright * 255), cam.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5), tall));
            if (grass.size() >= MAX_SHEETS) break;
        }
        // 近处优先：远的草先被裁掉，视觉上"近密远疏"，开销也有上限。
        grass.sort(java.util.Comparator.comparingDouble(Grass::distance));
        if (grass.size() > MAX_SHEETS) grass.subList(MAX_SHEETS, grass.size()).clear();
        event.getRenderState().setRenderData(GRASS, grass);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    static void submit(SubmitCustomGeometryEvent event) {
        var frame = event.getLevelRenderState().getRenderData(GRASS);
        if (frame == null || frame.isEmpty()) return;
        var cam = event.getLevelRenderState().cameraRenderState;
        var pose = event.getPoseStack();
        var out = LogosSkyOverlay.collector(event.getSubmitNodeCollector());
        lastSheets = 0;
        // 专用草叶贴图，整张映射到叶片四边形上。
        float u0 = 0f, u1 = 1f, v0 = 0f, v1 = 1f;
        TextureAtlasSprite tallTop = null, tallBottom = null;
        try {
            var atlas = Minecraft.getInstance().getAtlasManager()
                .getAtlasOrThrow(Identifier.withDefaultNamespace("blocks"));
            tallBottom = atlas.getSprite(Identifier.withDefaultNamespace("block/tall_grass_bottom"));
            tallTop = atlas.getSprite(Identifier.withDefaultNamespace("block/tall_grass_top"));
        } catch (RuntimeException ignored) {
            // 图集还没准备好时就不画高草丛的摆动层
        }
        pose.pushPose();
        // 高草丛：整株（下半段 + 上半段）都用摆动的交叉四边形画，比原版略宽一点，
        // 这样近处看到的是"整株在动"，而不是"底下不动、上面一层绿在动"。
        if (tallTop != null && tallBottom != null) {
            float[] top = {tallTop.getU0(), tallTop.getU1(), tallTop.getV0(), tallTop.getV1()};
            float[] bottom = {tallBottom.getU0(), tallBottom.getU1(), tallBottom.getV0(), tallBottom.getV1()};
            out.submitCustomGeometry(pose, type(true), (p, v) -> {
                for (var g : frame) {
                    if (!g.tall()) continue;
                    int wx = (int) Math.round(g.x()), wz = (int) Math.round(g.z());
                    for (int k = 0; k < 2; k++)
                        tallGrass(p, v, g.x() - cam.pos.x, g.y() - cam.pos.y, g.z() - cam.pos.z,
                            wx, wz, g.light(), bottom, top, k);
                }
            });
        }
        out.submitCustomGeometry(pose, type(false), (p, v) -> {
            for (var g : frame) {
                if (g.tall()) continue;
                int light = g.light();
                // 世界 xz 塞进 UV2，顶点着色器用它算波浪相位（整数格）。
                int wx = (int) Math.round(g.x()), wz = (int) Math.round(g.z());
                int planes = g.distance() < 25 ? 9 : g.distance() < 64 ? 5 : 3;
                planes = Math.min(planes, Math.max(0, (MAX_SHEETS - lastSheets) / 2));
                for (int k = 0; k < planes; k++)
                    blade(p, v, g.x() - cam.pos.x, g.y() - cam.pos.y, g.z() - cam.pos.z, wx, wz, light,
                        u0, u1, v0, v1, k);
                lastSheets += planes * 2;
            }
        });
        pose.popPose();
    }

    /** 把亮度乘上草色并夹到 0..255，拼成 ARGB 顶点色。 */
    private static int channel(int alpha, int level, float tr, float tg, float tb) {
        int r = Math.min(255, Math.max(0, Math.round(level * tr)));
        int g = Math.min(255, Math.max(0, Math.round(level * tg)));
        int b = Math.min(255, Math.max(0, Math.round(level * tb)));
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }

    /** 整株高草丛：根→腰→顶两段，根不动、腰略弯、顶摆幅最大；宽一点以盖住原版静态模型。 */
    private static void tallGrass(PoseStack.Pose p, VertexConsumer v,
                                  double bx, double by, double bz, int wx, int wz, int bright,
                                  float[] bottom, float[] top, int plane) {
        float half = .60f;
        float waver = (plane == 0 ? .16f : .10f);
        float cos = plane == 0 ? 1f : 0f, sin = plane == 0 ? 0f : 1f;
        // 原版贴图是灰的、靠群系草色染色；我们不吃 biome tint，这里乘长风草原草色并略微提亮。
        float tr = 2.05f, tg = 2.35f, tb = 1.45f;
        int rootLevel = Math.max(26, (int) (bright * .74f));
        int root = channel(0, rootLevel, tr, tg, tb);
        int mid = channel(128, bright, tr, tg, tb);
        int tip = channel(255, bright, tr, tg, tb);
        float ground = (float) by + .02f, waist = (float) by + 1.02f, topY = (float) by + 1.98f;
        float wobble = waver * (float) Math.sin((wx * 0.7 + wz * 1.3));
        float midOff = wobble * .25f, tipOff = wobble;
        float rgb0 = cos * half, rb0 = sin * half;
        float rbm = cos * half * .82f, rbmz = sin * half * .82f;
        float rbt = cos * half * .55f, rbtz = sin * half * .55f;
        // 下半段：根 → 腰（用 tall_grass_bottom 贴图）
        v.addVertex(p, (float) bx - rgb0, ground, (float) bz - rb0).setColor(root)
            .setUv(bottom[0], bottom[3]).setOverlay(OverlayTexture.NO_OVERLAY) .setUv2(wx, wz).setNormal(p, -sin, 0, cos);
        v.addVertex(p, (float) bx + rgb0, ground, (float) bz + rb0).setColor(root)
            .setUv(bottom[1], bottom[3]).setOverlay(OverlayTexture.NO_OVERLAY) .setUv2(wx, wz).setNormal(p, -sin, 0, cos);
        v.addVertex(p, (float) bx + rbm + cos * midOff, waist, (float) bz + rbmz + sin * midOff).setColor(mid)
            .setUv(bottom[1], bottom[2]).setOverlay(OverlayTexture.NO_OVERLAY) .setUv2(wx, wz).setNormal(p, -sin, 0, cos);
        v.addVertex(p, (float) bx - rbm + cos * midOff, waist, (float) bz - rbmz + sin * midOff).setColor(mid)
            .setUv(bottom[0], bottom[2]).setOverlay(OverlayTexture.NO_OVERLAY) .setUv2(wx, wz).setNormal(p, -sin, 0, cos);
        // 上半段：腰 → 顶（用 tall_grass_top 贴图），顶端随阵风左右摆
        v.addVertex(p, (float) bx - rbm, waist, (float) bz - rbmz).setColor(mid)
            .setUv(top[0], top[3]).setOverlay(OverlayTexture.NO_OVERLAY) .setUv2(wx, wz).setNormal(p, -sin, 0, cos);
        v.addVertex(p, (float) bx + rbm, waist, (float) bz + rbmz).setColor(mid)
            .setUv(top[1], top[3]).setOverlay(OverlayTexture.NO_OVERLAY) .setUv2(wx, wz).setNormal(p, -sin, 0, cos);
        v.addVertex(p, (float) bx + rbt + cos * tipOff, topY, (float) bz + rbtz + sin * tipOff).setColor(tip)
            .setUv(top[1], top[2]).setOverlay(OverlayTexture.NO_OVERLAY) .setUv2(wx, wz).setNormal(p, -sin, 0, cos);
        v.addVertex(p, (float) bx - rbt + cos * tipOff, topY, (float) bz - rbtz + sin * tipOff).setColor(tip)
            .setUv(top[0], top[2]).setOverlay(OverlayTexture.NO_OVERLAY) .setUv2(wx, wz).setNormal(p, -sin, 0, cos);
    }

    /** 一片草：两段折线，根部立、叶尖向外弯；亮度来自方块所在位置的天空光/方块光。 */
    private static void blade(PoseStack.Pose p, VertexConsumer v,
                              double bx, double by, double bz, int wx, int wz, int bright,
                              float u0, float u1, float v0, float v1,
                              int plane) {
        int hash = (wx * 73856093) ^ (wz * 19349663) ^ (plane * 0x2C1B3C6D);
        float r = ((hash >>> 3) & 1023) / 1023f;
        // 叶宽、叶高、落点、朝向都随机：草地上是一蓬一蓬的草，不是一排韭菜。
        float half = .10f + .07f * r;
        float baseOffset = .05f + .22f * r;
        float height = .42f + .40f * r;
        // 在方块内随机挪落脚点（±0.34 格），避免所有叶子都从方块正中长出来。
        float jx = (((hash >>> 11) & 255) / 255f - .5f) * .68f;
        float jz = (((hash >>> 19) & 255) / 255f - .5f) * .68f;
        double ang = Math.toRadians(((hash >>> 6) & 255) / 255.0 * 180.0);
        float cos = (float) Math.cos(ang), sin = (float) Math.sin(ang);

        // 根部亮一点、叶尖亮一点（叶尖受光），中段靠片元的渐变再拉开。
        // 顶点色是 ARGB：alpha 必须写在高 8 位，着色器用 Color.a 当"离地高度比例"。
        // 写成 0x00RRGGBB 会让所有顶点的高度权重都是 0，草就完全不动（上一版就是这个 bug）。
        int rootLevel = Math.max(18, (int) (bright * 0.52f));
        int root = (0 << 24) | (rootLevel << 16) | (rootLevel << 8) | rootLevel;
        int tip = (255 << 24) | (bright << 16) | (bright << 8) | bright;

        float baseY = (float) by + baseOffset;
        float midY = baseY + height * 0.55f;
        float tipY = baseY + height;
        float midHalf = half * 0.62f;
        float tipHalf = half * 0.22f;
        // 中段先往外弯一点，叶尖再弯得更多，整体像被风压住。
        float midBend = height * 0.18f, tipBend = height * 0.52f;
        float bx1 = (float) bx + jx, bz1 = (float) bz + jz;
        float dxb = cos * half, dzb = sin * half;
        float dxm = cos * midHalf, dzm = sin * midHalf;
        float dxt = cos * tipHalf, dzt = sin * tipHalf;
        float bendX = cos * 0f, bendZ = sin * 0f;

        // 下段：根 → 中
        v.addVertex(p, bx1 - dxb, baseY, bz1 - dzb).setColor(root).setUv(u0, v1)
            .setOverlay(OverlayTexture.NO_OVERLAY).setUv2(wx, wz).setNormal(p, -sin, 0, cos);
        v.addVertex(p, bx1 + dxb, baseY, bz1 + dzb).setColor(root).setUv(u1, v1)
            .setOverlay(OverlayTexture.NO_OVERLAY).setUv2(wx, wz).setNormal(p, -sin, 0, cos);
        v.addVertex(p, bx1 + dxm + bendX, midY, bz1 + dzm + bendZ).setColor(tip).setUv(u1, (v0 + v1) * .5f)
            .setOverlay(OverlayTexture.NO_OVERLAY).setUv2(wx, wz).setNormal(p, -sin, 0, cos);
        v.addVertex(p, bx1 - dxm + bendX, midY, bz1 - dzm + bendZ).setColor(tip).setUv(u0, (v0 + v1) * .5f)
            .setOverlay(OverlayTexture.NO_OVERLAY).setUv2(wx, wz).setNormal(p, -sin, 0, cos);
        // 上段：中 → 尖
        v.addVertex(p, bx1 - dxm, midY, bz1 - dzm).setColor(tip).setUv(u0, (v0 + v1) * .5f)
            .setOverlay(OverlayTexture.NO_OVERLAY).setUv2(wx, wz).setNormal(p, -sin, 0, cos);
        v.addVertex(p, bx1 + dxm, midY, bz1 + dzm).setColor(tip).setUv(u1, (v0 + v1) * .5f)
            .setOverlay(OverlayTexture.NO_OVERLAY).setUv2(wx, wz).setNormal(p, -sin, 0, cos);
        v.addVertex(p, bx1 + dxt + cos * tipBend, tipY, bz1 + dzt + sin * tipBend).setColor(tip).setUv(u1, v0)
            .setOverlay(OverlayTexture.NO_OVERLAY).setUv2(wx, wz).setNormal(p, -sin, 0, cos);
        v.addVertex(p, bx1 - dxt + cos * tipBend, tipY, bz1 - dzt + sin * tipBend).setColor(tip).setUv(u0, v0)
            .setOverlay(OverlayTexture.NO_OVERLAY).setUv2(wx, wz).setNormal(p, -sin, 0, cos);
    }
}
