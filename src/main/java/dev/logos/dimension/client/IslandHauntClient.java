package dev.logos.dimension.client;

import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.net.HauntPayload;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

/** Bounded visual apparitions: no collision, targeting, damage, drops or saved mobs. */
@EventBusSubscriber(modid=LogosDimension.ID, value=Dist.CLIENT)
public final class IslandHauntClient {
    private static final RandomSource RANDOM = RandomSource.create(271828);
    private static final List<Shade> SHADES = new ArrayList<>();
    private static HauntPayload region = HauntPayload.CLEAR;
    private static float density;
    private static int stale, nextShade = 24;
    private static final ContextKey<Frame> FRAME = new ContextKey<>(LogosDimension.id("haunt_frame"));
    private record Figure(Vec3 at, float alpha, float height) {}
    private record Frame(HauntPayload region, float angle, List<Figure> figures) {}
    private static final class Shade {
        final Vec3 at; final float height; int age;
        Shade(Vec3 at, float height) { this.at = at; this.height = height; }
        float alpha() { return .84f * Math.min(1, age / 12f) * Math.min(1, (88 - age) / 24f); }
    }
    private static RenderType material(String name) {
        return RenderType.create("crucible_" + name, RenderSetup.builder(RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
                .withLocation(LogosDimension.id("pipeline/" + name)).withVertexShader("core/position_tex_color").withFragmentShader("core/position_tex_color")
                .withSampler("Sampler0").withVertexFormat(DefaultVertexFormat.BLOCK, VertexFormat.Mode.QUADS)
                .withCull(false).withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).build())
                .withTexture("Sampler0", LogosDimension.id("textures/environment/" + name + ".png")).sortOnUpload().createRenderSetup());
    }
    private static final RenderType MIST = material("island_mist"), SHADOW = material("mist_shadow");

    @SubscribeEvent static void payloads(RegisterClientPayloadHandlersEvent event) {
        event.register(HauntPayload.TYPE, (p, context) -> {
            if (!region.veiled() && p.veiled()) nextShade = 24;
            region = p; stale = 0;
        });
    }
    @SubscribeEvent static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !mc.level.dimension().equals(LogosDimension.CRUCIBLE)) {
            region = HauntPayload.CLEAR; density = 0; SHADES.clear(); return;
        }
        if (++stale > 60) region = HauntPayload.CLEAR;
        density += (region.density() - density) * .12f;
        var eye = mc.player.getEyePosition();
        for (var shade : SHADES) {
            shade.age++;
            var to = shade.at.add(0, shade.height * .65, 0).subtract(eye);
            // Looking directly at it up close, or walking into it, makes it slip away.
            if (to.lengthSqr() < 16 || (to.lengthSqr() < 64 && to.normalize().dot(mc.player.getLookAngle()) > .965))
                shade.age = Math.max(shade.age, 78);
        }
        SHADES.removeIf(s -> s.age >= 88);
        if (region.density() < .1) { SHADES.clear(); nextShade = 24; return; }
        if (--nextShade <= 0 && SHADES.size() < 2) {
            double angle = Math.toRadians(mc.player.getYRot() + (RANDOM.nextBoolean() ? 1 : -1) * (15 + RANDOM.nextFloat() * 20));
            double distance = 8 + RANDOM.nextFloat() * 5;
            var p = mc.player.position().add(-Math.sin(angle) * distance, .15, Math.cos(angle) * distance);
            SHADES.add(new Shade(p, 2.6f + RANDOM.nextFloat() * .6f));
            nextShade = 130 + RANDOM.nextInt(110);
            mc.level.playLocalSound(p.x, p.y, p.z, SoundEvents.AMBIENT_CAVE.value(), SoundSource.AMBIENT, .22f, .65f, false);
        }
    }
    @SubscribeEvent static void fogColor(ViewportEvent.ComputeFogColor event) {
        float blend = density * .94f;
        event.setRed(event.getRed() * (1 - blend) + .075f * blend);
        event.setGreen(event.getGreen() * (1 - blend) + .082f * blend);
        event.setBlue(event.getBlue() * (1 - blend) + .095f * blend);
    }
    @SubscribeEvent static void fogDistance(ViewportEvent.RenderFog event) {
        if (density < .005) return;
        float blend = density;
        event.setNearPlaneDistance(event.getNearPlaneDistance() * (1 - blend) + 3 * blend);
        event.setFarPlaneDistance(event.getFarPlaneDistance() * (1 - blend) + 24 * blend);
    }
    @SubscribeEvent static void extract(ExtractLevelRenderStateEvent event) {
        if (!region.veiled() || !event.getLevel().dimension().equals(LogosDimension.CRUCIBLE)) return;
        event.getRenderState().setRenderData(FRAME, new Frame(region, event.getLevel().getGameTime() * .035f,
                SHADES.stream().map(s -> new Figure(s.at, s.alpha(), s.height)).toList()));
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) static void submit(SubmitCustomGeometryEvent event) {
        Frame frame = event.getLevelRenderState().getRenderData(FRAME); if (frame == null) return;
        var cam = event.getLevelRenderState().cameraRenderState;
        var pose = event.getPoseStack(); var out = LogosSkyOverlay.collector(event.getSubmitNodeCollector());
        var r = frame.region;
        for (int i = 0; i < 3; i++) {
            pose.pushPose(); pose.translate(r.x() - cam.pos.x, r.bottom() + (r.top() - r.bottom()) * (i + 1) / 4 - cam.pos.y, r.z() - cam.pos.z);
            pose.mulPose(Axis.YP.rotationDegrees(frame.angle * (i % 2 == 0 ? 1 : -1)));
            out.submitCustomGeometry(pose, MIST, (p, v) -> {
                float d = r.radius();
                quad(p, v, new float[][] {{-d,0,-d,0,0},{d,0,-d,1,0},{d,0,d,1,1},{-d,0,d,0,1}}, 0xCC8B9299);
            }); pose.popPose();
        }
        for (var figure : frame.figures) {
            pose.pushPose(); pose.translate(figure.at.x - cam.pos.x, figure.at.y - cam.pos.y, figure.at.z - cam.pos.z);
            pose.mulPose(cam.orientation);
            out.submitCustomGeometry(pose, SHADOW, (p, v) -> {
                float w = figure.height * .25f, h = figure.height;
                quad(p, v, new float[][] {{-w,0,0,0,1},{w,0,0,1,1},{w,h,0,1,0},{-w,h,0,0,0}},
                        ((int) (figure.alpha * 255) << 24) | 0xFFFFFF);
            }); pose.popPose();
        }
    }
    private static void quad(PoseStack.Pose p, VertexConsumer v, float[][] points, int color) {
        for (var a : points) v.addVertex(p,a[0],a[1],a[2]).setColor(color).setUv(a[3],a[4])
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(p,0,1,0);
    }
    static float density() { return density; }
    static int shades() { return SHADES.size(); }
    private IslandHauntClient() {}
}
