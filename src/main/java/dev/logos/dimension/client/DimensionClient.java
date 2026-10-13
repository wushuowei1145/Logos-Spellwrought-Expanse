package dev.logos.dimension.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.math.Axis;
import dev.logos.dimension.DimensionFluids;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.OpusPhase;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.context.ContextKey;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.RegisterFluidModelsEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/** Client render data is captured once, so the rotating sky never reads mutable world state during submission. */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class DimensionClient {
    private record Sky(double angle, int color) {}
    private static final ContextKey<Sky> SKY = new ContextKey<>(LogosDimension.id("sky"));
    // Sky marks must survive distance fog, while still being occluded by terrain/clouds in front.
    private static final RenderType ASTROLABE = RenderType.create("crucible_astrolabe", RenderSetup.builder(
            RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
                    .withLocation(LogosDimension.id("pipeline/astrolabe"))
                    .withVertexShader("core/position_tex_color").withFragmentShader("core/position_tex_color")
                    .withSampler("Sampler0").withVertexFormat(DefaultVertexFormat.BLOCK, VertexFormat.Mode.QUADS)
                    .withCull(false).withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).build())
            .withTexture("Sampler0", LogosDimension.id("textures/environment/astrolabe.png"))
            .sortOnUpload().createRenderSetup());

    @SubscribeEvent static void fluids(RegisterFluidModelsEvent event) {
        event.register(new FluidModel.Unbaked(new Material(LogosDimension.id("block/mercury_still")),
                new Material(LogosDimension.id("block/mercury_flow")), null,
                (net.neoforged.neoforge.client.fluid.FluidTintSource) null), DimensionFluids.MERCURY, DimensionFluids.FLOWING_MERCURY);
        event.register(new FluidModel.Unbaked(new Material(LogosDimension.id("block/molten_glass_still")),
                new Material(LogosDimension.id("block/molten_glass_flow")), null,
                (net.neoforged.neoforge.client.fluid.FluidTintSource) null), DimensionFluids.MOLTEN_GLASS, DimensionFluids.FLOWING_GLASS);
    }
    @SubscribeEvent static void extract(ExtractLevelRenderStateEvent event) {
        if (!event.getLevel().dimension().equals(LogosDimension.CRUCIBLE)) return;
        long ticks = OpusPhase.ticks(event.getLevel());
        int color = switch (OpusPhase.at(ticks)) {
            case NIGREDO -> 0xAAADB2BB; case ALBEDO -> 0xAAE4E8DE;
            case CITRINITAS -> 0xAAE8D09A; case RUBEDO -> 0xAACFA096;
        };
        event.getRenderState().setRenderData(SKY, new Sky(Math.floorMod(ticks, OpusPhase.PERIOD_TICKS) * 360.0 / OpusPhase.PERIOD_TICKS, color));
    }
    // Logos starts its shared shader overlay earlier in this event. Add our sky after that reset.
    @SubscribeEvent(priority = EventPriority.LOWEST) static void submit(SubmitCustomGeometryEvent event) {
        Sky sky = event.getLevelRenderState().getRenderData(SKY);
        if (sky == null) return;
        PoseStack pose = event.getPoseStack();
        pose.pushPose(); pose.translate(0, 160, 0); pose.mulPose(Axis.YP.rotationDegrees((float) sky.angle));
        LogosSkyOverlay.collector(event.getSubmitNodeCollector()).submitCustomGeometry(pose,
                ASTROLABE,
                (p, vertices) -> disc(p, vertices, sky.color));
        pose.popPose();
    }
    private static void disc(PoseStack.Pose pose, VertexConsumer vertices, int color) {
        float[][] corners = {{-210, -210, 0, 0}, {210, -210, 1, 0}, {210, 210, 1, 1}, {-210, 210, 0, 1}};
        for (int i = 0; i < 4; i++) vertex(pose, vertices, corners[i], color);
        for (int i = 3; i >= 0; i--) vertex(pose, vertices, corners[i], color);
    }
    private static void vertex(PoseStack.Pose pose, VertexConsumer vertices, float[] c, int color) {
        vertices.addVertex(pose, c[0], 0, c[1]).setColor(color).setUv(c[2], c[3])
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, -1, 0);
    }
    private DimensionClient() {}
}
