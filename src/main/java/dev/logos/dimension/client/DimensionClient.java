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
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.RegisterFluidModelsEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/** Client render data is captured once, so the rotating sky never reads mutable world state during submission. */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class DimensionClient {
    private record Sky(ArcaneArrayMotion.Frame motion, int color) {}
    private static final ContextKey<Sky> SKY = new ContextKey<>(LogosDimension.id("sky"));
    // Sky marks must survive distance fog, while still being occluded by terrain/clouds in front.
    private static RenderType engraving(String layer) {
        return RenderType.create("crucible_astrolabe_" + layer, RenderSetup.builder(
            RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
                    .withLocation(LogosDimension.id("pipeline/astrolabe_" + layer))
                    .withVertexShader("core/position_tex_color").withFragmentShader("core/position_tex_color")
                    .withSampler("Sampler0").withVertexFormat(DefaultVertexFormat.BLOCK, VertexFormat.Mode.QUADS)
                    .withCull(false).withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                    .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING)).build())
            .withTexture("Sampler0", LogosDimension.id("textures/environment/" +
                    (layer.equals("energy") ? "cloudsea_white" : "astrolabe_" + layer) + ".png"))
            .sortOnUpload().createRenderSetup());
    }
    private static final RenderType OUTER = engraving("outer");
    private static final RenderType ORBITS = engraving("orbits");
    private static final RenderType SEAL = engraving("seal");
    private static final RenderType ENERGY = engraving("energy");

    @SubscribeEvent static void entityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(dev.logos.dimension.npc.NpcEntities.FOREST_ELF.get(), ctx -> new NpcRenderer<>(ctx, "forest_elf"));
    }

    @SubscribeEvent static void fluids(RegisterFluidModelsEvent event) {
        event.register(new FluidModel.Unbaked(new Material(LogosDimension.id("block/mercury_still")),
                new Material(LogosDimension.id("block/mercury_flow")), null,
                (net.neoforged.neoforge.client.fluid.FluidTintSource) null), DimensionFluids.MERCURY, DimensionFluids.FLOWING_MERCURY);
        event.register(new FluidModel.Unbaked(new Material(LogosDimension.id("block/molten_glass_still")),
                new Material(LogosDimension.id("block/molten_glass_flow")), null,
                (net.neoforged.neoforge.client.fluid.FluidTintSource) null), DimensionFluids.MOLTEN_GLASS, DimensionFluids.FLOWING_GLASS);
    }
    @SubscribeEvent static void extract(ExtractLevelRenderStateEvent event) {
        if (!LogosDimension.isCrucible(event.getLevel().dimension())) return;
        long ticks = OpusPhase.ticks(event.getLevel());
        int color = switch (OpusPhase.at(ticks)) {
            case NIGREDO -> 0xD9CBDDE9; case ALBEDO -> 0xEBF2F5EA;
            case CITRINITAS -> 0xEBFFE9BA; case RUBEDO -> 0xEBDDBDB4;
        };
        double time = ticks + event.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        event.getRenderState().setRenderData(SKY, new Sky(ArcaneArrayMotion.sample(time), color));
    }
    // Logos starts its shared shader overlay earlier in this event. Add our sky after that reset.
    @SubscribeEvent(priority = EventPriority.LOWEST) static void submit(SubmitCustomGeometryEvent event) {
        Sky sky = event.getLevelRenderState().getRenderData(SKY);
        if (sky == null) return;
        PoseStack pose = event.getPoseStack();
        var motion = sky.motion;
        // Enlarge around the array itself; scaling around the camera also scales its distance.
        pose.pushPose();
        pose.translate(0, GlassArcaneArray.HEIGHT, 0);
        pose.scale(1.15f, 1.15f, 1.15f);
        pose.translate(0, -GlassArcaneArray.HEIGHT, 0);
        GlassArcaneArray.submit(event,motion);
        pose.pushPose();
        pose.translate(0, GlassArcaneArray.HEIGHT-.9f, 0);
        pose.mulPose(Axis.YP.rotationDegrees(motion.outerAngle()));
        pose.scale(motion.unfold(), 1, motion.unfold());
        LogosSkyOverlay.collector(event.getSubmitNodeCollector()).submitCustomGeometry(pose, OUTER,
                (p, v) -> fragmentedRing(p, v, motion, sky.color));
        pose.popPose();
        layer(event, pose, ORBITS, motion.orbitAngle(), GlassArcaneArray.HEIGHT-9.9f, sky.color,
                .88f + motion.opening() * .16f, (float) Math.sin(motion.time() * .009) * 11);
        int alpha = (int) ((sky.color >>> 24) * motion.glow());
        layer(event, pose, SEAL, motion.sealAngle(), GlassArcaneArray.HEIGHT-25.9f + motion.opening() * 20,
                (alpha << 24) | (sky.color & 0xFFFFFF), .76f + motion.opening() * .28f,
                (float) Math.cos(motion.time() * .007) * 7);
        pose.pushPose();
        pose.translate(0, GlassArcaneArray.HEIGHT-16, 0);
        LogosSkyOverlay.collector(event.getSubmitNodeCollector()).submitCustomGeometry(pose, ENERGY,
                (p, v) -> energy(p, v, motion));
        pose.popPose();
        pose.popPose();
    }
    private static void layer(SubmitCustomGeometryEvent event, PoseStack pose, RenderType material,
                              float angle, float height, int color, float scale, float tilt) {
        pose.pushPose();
        pose.translate(0, height, 0);
        pose.mulPose(Axis.YP.rotationDegrees(angle));
        pose.mulPose(Axis.XP.rotationDegrees(tilt));
        pose.scale(scale, scale, scale);
        LogosSkyOverlay.collector(event.getSubmitNodeCollector()).submitCustomGeometry(pose, material,
                (p, vertices) -> disc(p, vertices, color));
        pose.popPose();
    }
    private static void fragmentedRing(PoseStack.Pose pose, VertexConsumer vertices,
                                       ArcaneArrayMotion.Frame frame, int color) {
        for (int sector = 0; sector < 12; sector++) {
            double mid = (sector + .5) * Math.PI / 6;
            float displacement = frame.fracture() * (18 + (sector % 3) * 9);
            float dx = (float) Math.cos(mid) * displacement, dz = (float) Math.sin(mid) * displacement;
            float dy = frame.fracture() * (float) Math.sin(sector * 1.8 + frame.time() * .012) * 14;
            for (int step = 0; step < 4; step++) {
                double a = (sector + step / 4.0) * Math.PI / 6, b = a + Math.PI / 24;
                ringVertex(pose, vertices, 185, a, dx, dy, dz, color);
                ringVertex(pose, vertices, 245, a, dx, dy, dz, color);
                ringVertex(pose, vertices, 245, b, dx, dy, dz, color);
                ringVertex(pose, vertices, 185, b, dx, dy, dz, color);
            }
        }
    }
    private static void ringVertex(PoseStack.Pose pose, VertexConsumer out, float r, double a,
                                    float dx, float dy, float dz, int color) {
        float x = (float) Math.cos(a) * r, z = (float) Math.sin(a) * r;
        out.addVertex(pose, x + dx, dy, z + dz).setColor(color).setUv(.5f + x / 490, .5f + z / 490)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, -1, 0);
    }
    private static void energy(PoseStack.Pose pose, VertexConsumer out, ArcaneArrayMotion.Frame frame) {
        // Filaments move between orbiting nodes and become a different star polygon as the seal opens.
        float radius = 116 + frame.opening() * 30;
        float blend=ArcaneArrayMotion.starBlend(frame);
        star(pose,out,frame,radius,2,1-blend);
        star(pose,out,frame,radius,3,blend);
        // Three counter-rotating arc sweeps trace the spell's energy circulation.
        for (int sweep = 0; sweep < 3; sweep++) for (int step = 0; step < 18; step++) {
            double a = frame.time() * .012 + sweep * Math.PI * 2 / 3 + step * .035;
            double b = a + .035; float r = 179 + (float) Math.sin(frame.time() * .01) * 5;
            int alpha = 35 + step * 10;
            filament(pose, out, (float)Math.cos(a)*r, (float)Math.sin(a)*r,
                    (float)Math.cos(b)*r, (float)Math.sin(b)*r, .70f, (alpha<<24)|0xE7FAFF);
        }
        for(int pulse=0;pulse<2;pulse++){
            float progress=ArcaneArrayMotion.progress(frame.time(),160,pulse*.5);float r=36+progress*138;
            int color=((int)(ArcaneArrayMotion.waveOpacity(progress)*90)<<24)|0xABEAFF;
            for(int i=0;i<64;i++){
                double a=i*Math.PI/32,b=(i+1)*Math.PI/32;
                filament(pose,out,(float)Math.cos(a)*r,(float)Math.sin(a)*r,(float)Math.cos(b)*r,(float)Math.sin(b)*r,.45f,color);
            }
        }
    }
    private static void star(PoseStack.Pose pose,VertexConsumer out,ArcaneArrayMotion.Frame frame,float radius,int stride,float opacity){
        if(opacity<=0)return;
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4 + frame.time() * .003;
            double b = (i + stride) * Math.PI / 4 + frame.time() * .003;
            float x1 = (float) Math.cos(a) * radius, z1 = (float) Math.sin(a) * radius;
            float x2 = (float) Math.cos(b) * radius, z2 = (float) Math.sin(b) * radius;
            filament(pose, out, x1, z1, x2, z2, 1.15f, fade(0x298ECAD8,opacity));
            filament(pose, out, x1, z1, x2, z2, .30f, fade(0xB8DCFAFF,opacity));
            float progress=ArcaneArrayMotion.progress(frame.time(),85,i/8.0);
            int glint=fade(0xEFFFF1C9,opacity*ArcaneArrayMotion.travelOpacity(progress));
            float x = x1 + (x2 - x1) * progress, z = z1 + (z2 - z1) * progress;
            filament(pose, out, x - 2, z, x + 2, z, .8f, glint);
            filament(pose, out, x, z - 2, x, z + 2, .8f, glint);
            float orb = 2.5f + frame.glow() * 1.6f;
            filament(pose, out, x1-orb, z1, x1+orb, z1, orb*.55f, fade(0xC9FFE5A5,opacity));
        }
    }
    private static int fade(int color,float opacity){return ((int)((color>>>24)*opacity)<<24)|(color&0xFFFFFF);}
    private static void filament(PoseStack.Pose pose, VertexConsumer out, float x1, float z1,
                                  float x2, float z2, float width, int color) {
        if((color>>>24)==0)return;
        float dx=x2-x1, dz=z2-z1, length=(float)Math.sqrt(dx*dx+dz*dz);
        if(length<.001)return;
        float nx=-dz/length*width, nz=dx/length*width;
        vertex(pose,out,x1+nx,z1+nz,0,0,color);vertex(pose,out,x2+nx,z2+nz,1,0,color);
        vertex(pose,out,x2-nx,z2-nz,1,1,color);vertex(pose,out,x1-nx,z1-nz,0,1,color);
    }
    private static void disc(PoseStack.Pose pose, VertexConsumer vertices, int color) {
        vertex(pose,vertices,-245,-245,0,0,color);vertex(pose,vertices,245,-245,1,0,color);
        vertex(pose,vertices,245,245,1,1,color);vertex(pose,vertices,-245,245,0,1,color);
    }
    private static void vertex(PoseStack.Pose pose, VertexConsumer vertices, float x,float z,float u,float v,int color) {
        vertices.addVertex(pose,x,0,z).setColor(color).setUv(u,v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, -1, 0);
    }
    private DimensionClient() {}
}
