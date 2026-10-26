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
    private static final int RADIUS = 10;
    /** 每帧最多提交多少片草尖（每片 2 个交叉四边形）。 */
    private static final int MAX_BLADES = 600;
    private static final ContextKey<List<Grass>> GRASS = new ContextKey<>(LogosDimension.id("wind_grass_state"));
    private static final Identifier GRASS_TEXTURE = LogosDimension.id("block/journey_meadow_grass");

    private record Grass(double x, double y, double z, int light) {}

    private static RenderType type;

    private WindGrass() {}

    private static RenderType type() {
        if (type == null) {
            var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(LogosDimension.id("pipeline/wind_grass"))
                .withVertexShader(LogosDimension.id("core/wind_grass"))
                .withFragmentShader(LogosDimension.id("core/wind_grass"))
                .withCull(false)
                .withSampler("Sampler0")
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
                .build();
            type = RenderType.create("logos_dimension_wind_grass",
                RenderSetup.builder(pipeline)
                    .withTexture("Sampler0", TextureAtlas.LOCATION_BLOCKS)
                    .sortOnUpload()
                    .createRenderSetup());
        }
        return type;
    }

    @SubscribeEvent
    static void extract(ExtractLevelRenderStateEvent event) {
        var level = event.getLevel();
        if (!level.dimension().equals(LogosDimension.CLOUDSEA)) return;
        var cam = event.getCamera().position();
        var center = BlockPos.containing(cam);
        var grass = new ArrayList<Grass>();
        var longGrass = CloudseaBlocks.MEADOW_GRASS.get();
        var shortGrass = CloudseaBlocks.MEADOW_GRASS_SHORT.get();
        for (var pos : BlockPos.betweenClosed(center.offset(-RADIUS, -3, -RADIUS), center.offset(RADIUS, 3, RADIUS))) {
            BlockState state = level.getBlockState(pos);
            if (!state.is(longGrass) && !state.is(shortGrass)) continue;
            int sky = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos);
            int blockLight = level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos);
            float day = 1f - Math.min(11, level.getSkyDarken()) / 11f * 0.72f;
            float bright = Math.max(blockLight / 15f, sky / 15f * day);
            bright = 0.20f + 0.80f * Math.min(1f, bright);
            grass.add(new Grass(pos.getX() + .5, pos.getY(), pos.getZ() + .5,
                Math.round(bright * 255)));
            if (grass.size() >= MAX_BLADES) break;
        }
        event.getRenderState().setRenderData(GRASS, grass);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    static void submit(SubmitCustomGeometryEvent event) {
        var frame = event.getLevelRenderState().getRenderData(GRASS);
        if (frame == null || frame.isEmpty()) return;
        var cam = event.getLevelRenderState().cameraRenderState;
        var pose = event.getPoseStack();
        var out = LogosSkyOverlay.collector(event.getSubmitNodeCollector());
        // 26.1 的图集管理器按"图集 id"索引（minecraft:blocks），不是贴图路径。
        TextureAtlasSprite sprite;
        try {
            sprite = Minecraft.getInstance().getAtlasManager()
                .getAtlasOrThrow(Identifier.withDefaultNamespace("blocks")).getSprite(GRASS_TEXTURE);
        } catch (RuntimeException e) {
            return; // 图集还没准备好时安静跳过，不要因为装饰渲染崩掉整个画面
        }
        float su0 = sprite.getU0(), su1 = sprite.getU1(), v0 = sprite.getV0(), v1 = sprite.getV1();
        // 只取贴图中间的一条窄带：一片叶子，不是整丛草。
        float u0 = su0 + (su1 - su0) * 0.44f, u1 = su0 + (su1 - su0) * 0.56f;
        pose.pushPose();
        out.submitCustomGeometry(pose, type(), (p, v) -> {
            for (var g : frame) {
                int light = g.light();
                // 世界 xz 塞进 UV2，顶点着色器用它算波浪相位（整数格）。
                int wx = (int) Math.round(g.x()), wz = (int) Math.round(g.z());
                for (int k = 0; k < 3; k++)
                    blade(p, v, g.x() - cam.pos.x, g.y() - cam.pos.y, g.z() - cam.pos.z, wx, wz, light,
                        u0, u1, v0, v1, k);
            }
        });
        pose.popPose();
    }

    /** 一片草：两段折线，根部立、叶尖向外弯；亮度来自方块所在位置的天空光/方块光。 */
    private static void blade(PoseStack.Pose p, VertexConsumer v,
                              double bx, double by, double bz, int wx, int wz, int bright,
                              float u0, float u1, float v0, float v1,
                              int plane) {
        int hash = (wx * 73856093) ^ (wz * 19349663) ^ (plane * 0x2C1B3C6D);
        float r = ((hash >>> 3) & 1023) / 1023f;
        float half = .10f + .04f * r;
        float baseOffset = .08f + .18f * r;
        float height = .55f + .45f * r;
        double ang = Math.toRadians(plane * 45 + r * 18);
        float cos = (float) Math.cos(ang), sin = (float) Math.sin(ang);

        // 根部亮一点、叶尖亮一点（叶尖受光），中段靠片元的渐变再拉开。
        int root = (0) | (Math.max(20, (int) (bright * 0.72f)) << 16)
                 | (Math.max(20, (int) (bright * 0.72f)) << 8) | Math.max(20, (int) (bright * 0.72f));
        int tip = (255) | (bright << 16) | (bright << 8) | bright;

        float baseY = (float) by + baseOffset;
        float midY = baseY + height * 0.55f;
        float tipY = baseY + height;
        float midHalf = half * 0.62f;
        float tipHalf = half * 0.22f;
        // 中段先往外弯一点，叶尖再弯得更多，整体像被风压住。
        float midBend = height * 0.10f, tipBend = height * 0.26f;
        float bx1 = (float) bx, bz1 = (float) bz;
        float dxb = cos * half, dzb = sin * half;
        float dxm = cos * midHalf, dzm = sin * midHalf;
        float dxt = cos * tipHalf, dzt = sin * tipHalf;
        float bendX = cos * 0f, bendZ = sin * 0f;

        // 下段：根 → 中
        v.addVertex(p, bx1 - dxb, baseY, bz1 - dzb).setColor(root).setUv(u0, v1)
            .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setUv2(wx, wz);
        v.addVertex(p, bx1 + dxb, baseY, bz1 + dzb).setColor(root).setUv(u1, v1)
            .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setUv2(wx, wz);
        v.addVertex(p, bx1 + dxm + bendX, midY, bz1 + dzm + bendZ).setColor(tip).setUv(u1, (v0 + v1) * .5f)
            .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setUv2(wx, wz);
        v.addVertex(p, bx1 - dxm + bendX, midY, bz1 - dzm + bendZ).setColor(tip).setUv(u0, (v0 + v1) * .5f)
            .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setUv2(wx, wz);
        // 上段：中 → 尖
        v.addVertex(p, bx1 - dxm, midY, bz1 - dzm).setColor(tip).setUv(u0, (v0 + v1) * .5f)
            .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setUv2(wx, wz);
        v.addVertex(p, bx1 + dxm, midY, bz1 + dzm).setColor(tip).setUv(u1, (v0 + v1) * .5f)
            .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setUv2(wx, wz);
        v.addVertex(p, bx1 + dxt + cos * tipBend, tipY, bz1 + dzt + sin * tipBend).setColor(tip).setUv(u1, v0)
            .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setUv2(wx, wz);
        v.addVertex(p, bx1 - dxt + cos * tipBend, tipY, bz1 - dzt + sin * tipBend).setColor(tip).setUv(u0, v0)
            .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setUv2(wx, wz);
    }
}
