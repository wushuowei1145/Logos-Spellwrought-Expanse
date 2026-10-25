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
            grass.add(new Grass(pos.getX() + .5, pos.getY(), pos.getZ() + .5, LightCoordsUtil.FULL_BRIGHT));
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
                blade(p, v, g.x() - cam.pos.x, g.y() - cam.pos.y, g.z() - cam.pos.z, wx, wz, light,
                    u0, u1, v0, v1, 0.0f);
                blade(p, v, g.x() - cam.pos.x, g.y() - cam.pos.y, g.z() - cam.pos.z, wx, wz, light,
                    u0, u1, v0, v1, 1.0f);
            }
        });
        pose.popPose();
    }

    /** 一片草：一个竖直四边形，底边两个顶点高度权重 0、顶边 1（存在颜色 alpha 里）。 */
    private static void blade(PoseStack.Pose p, VertexConsumer v,
                              double bx, double by, double bz, int wx, int wz, int light,
                              float u0, float u1, float v0, float v1,
                              float yaw) {
        float half = .15f, baseOffset = .22f, height = .62f;
        double dx = (yaw == 0 ? half : -half), dz = (yaw == 0 ? 0 : half);
        int top = (255 << 24) | 0xE8F2D0;
        int bottom = 0x00E8F2D0;
        float baseY = (float) by + baseOffset;
        v.addVertex(p, (float) (bx - dx), baseY, (float) (bz - dz))
            .setColor(bottom).setUv(u0, v1).setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
            .setUv2(wx, wz);
        v.addVertex(p, (float) (bx + dx), baseY, (float) (bz + dz))
            .setColor(bottom).setUv(u1, v1).setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
            .setUv2(wx, wz);
        v.addVertex(p, (float) (bx + dx), baseY + height, (float) (bz + dz))
            .setColor(top).setUv(u1, v0).setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
            .setUv2(wx, wz);
        v.addVertex(p, (float) (bx - dx), baseY + height, (float) (bz - dz))
            .setColor(top).setUv(u0, v0).setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
            .setUv2(wx, wz);
    }
}
