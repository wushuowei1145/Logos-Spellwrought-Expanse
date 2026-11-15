package dev.logos.dimension.client.vfx;

import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.photon.client.fx.*;
import com.lowdragmc.photon.client.gameobject.emitter.data.EmissionSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.*;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.Sphere;
import com.lowdragmc.photon.client.gameobject.emitter.particle.*;
import com.mojang.blaze3d.platform.*;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.client.*;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.elven.blight.QueenSpellPayload;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import org.joml.Vector3f;

/** 生命魔法的叶片、花瓣、光晕改为猩红；贴图直接引用 Logos，粒子用 Photon 原生材质。 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class QueenCrimsonFx {
    private record Live(QueenSpellPayload cue, long start) {}
    private record Executor(Level level) implements IEffectExecutor { @Override public Level getLevel() { return level; } }
    private static final List<Live> LIVE = new ArrayList<>();
    private static final RenderType PETAL = VfxParts.add("life_impact/petal"), HALO = VfxParts.add("life_impact/halo"), GLOW = VfxParts.add("life_impact/glow"), RUNE = VfxParts.add("charge/circle_11"), STREAK = VfxParts.add("force/streak");
    private static final FX TRAIL = particles(false), BURST = particles(true);
    private static Level shown;
    private static int emittedThisTick;
    private static long received, sigils;
    private QueenCrimsonFx() {}
    public static long received() { return received; }
    public static long sigils() { return sigils; }
    @SubscribeEvent static void packets(RegisterClientPayloadHandlersEvent e) { e.register(QueenSpellPayload.TYPE, (p, ctx) -> receive(p)); }
    public static void receive(QueenSpellPayload cue) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        if (shown != level) { LIVE.clear(); shown = level; }
        received++;
        if (cue.kind() == QueenSpellPayload.SIGIL) sigils++;
        if (cue.kind() != QueenSpellPayload.SIGIL && emittedThisTick++ < 32) {
            var runtime = (cue.kind() == QueenSpellPayload.TRAIL ? TRAIL : BURST).createRuntime();
            runtime.getRoot().updatePos(new Vector3f((float) cue.at().x, (float) cue.at().y, (float) cue.at().z));
            float scale = Math.clamp(cue.scale(), .45f, 1.5f);
            runtime.getRoot().updateScale(new Vector3f(scale, scale, scale)); runtime.emit(new Executor(level));
        }
        if (cue.kind() != QueenSpellPayload.TRAIL) {
            if (LIVE.size() >= 64) LIVE.removeFirst();
            LIVE.add(new Live(cue, level.getGameTime()));
        }
    }
    private static FX particles(boolean impact) {
        FX fx = new FX();
        for (int layer = 0; layer < 2; layer++) {
            var emitter = new ParticleEmitter(); var config = emitter.config;
            config.setDuration(28); config.setLooping(false); config.setMaxParticles(12); config.lights.setEnable(true);
            config.setSimulationSpace(ParticleConfig.Space.World);
            config.setStartColor(NumberFunction.color(0xFFFFB6AC));
            config.setStartLifetime(new RandomConstant(12, 24)); config.setStartSpeed(new RandomConstant(.03f, .1f));
            float size = layer == 0 ? .16f : impact ? .7f : .12f;
            config.setStartSize(new NumberFunction3(size, size, size));
            config.setStartRotation(new NumberFunction3(NumberFunction.constant(0), NumberFunction.constant(0), new RandomConstant(0, 360)));
            config.rotationOverLifetime.setEnable(true); config.rotationOverLifetime.setRoll(new RandomConstant(-120, 120));
            config.colorOverLifetime.setEnable(true); config.colorOverLifetime.setColor(new Gradient(new GradientColor(0xFFFFC5B6, 0xFFCF324D, 0x806E1736, 0x006E1736)));
            Sphere shape = new Sphere(); shape.setRadius(impact ? .45f : .12f); config.shape.setShape(shape);
            config.physics.setEnable(true); config.physics.setGravity(NumberFunction.constant(.006f));
            config.emission.setEmissionRate(NumberFunction.constant(0));
            var burst = new EmissionSetting.Burst(); burst.time = 0; burst.setCount(NumberFunction.constant(impact ? layer == 0 ? 6 : 1 : 2)); config.emission.getBursts().add(burst);
            for (var material : config.renderer.getMaterials()) {
                var image = new TextureMaterial(Identifier.fromNamespaceAndPath("logos", "textures/vfx/life_impact/" + (layer == 0 ? "leaf" : "petal") + ".png"));
                image.setDiscardThreshold(.005f); material.setMaterial(image);
                material.getBlendMode().setSrcColorFactor(SourceFactor.SRC_ALPHA); material.getBlendMode().setDstColorFactor(layer == 0 ? DestFactor.ONE_MINUS_SRC_ALPHA : DestFactor.ONE);
            }
            fx.getFxData().objects().add(emitter);
        }
        return fx;
    }
    @SubscribeEvent static void tick(ClientTickEvent.Post e) { emittedThisTick = 0; if (Minecraft.getInstance().level != shown) { LIVE.clear(); shown = null; } }
    @SubscribeEvent(priority = EventPriority.LOWEST) static void draw(SubmitCustomGeometryEvent e) {
        var mc = Minecraft.getInstance(); if (mc.level == null || LIVE.isEmpty()) return;
        long now = mc.level.getGameTime();
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        LIVE.removeIf(l -> now - l.start > l.cue.ticks());
        var camera = e.getLevelRenderState().cameraRenderState; var view = VfxParts.View.of(camera);
        var out = ShaderPackOverlay.collector(e.getSubmitNodeCollector()); PoseStack pose = e.getPoseStack();
        pose.pushPose(); pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        for (Live live : LIVE) {
            var cue = live.cue; float t = now - live.start + partial, alpha = Math.min(1, t / 6) * Math.min(1, (cue.ticks() - t) / 10);
            if (cue.kind() == QueenSpellPayload.SIGIL) {
                // 三层反转的生命花冠与符文，位于真实的剑雨释放平面，不贴镜头。
                out.submitCustomGeometry(pose, PETAL, (p, b) -> VfxParts.ground(p, b, cue.at(), cue.scale(), t * .009f, VfxParts.argb(alpha * .65f, 0xD22D4A)));
                out.submitCustomGeometry(pose, RUNE, (p, b) -> VfxParts.ground(p, b, cue.at().add(0, .06, 0), cue.scale() * .85f, -t * .012f, VfxParts.argb(alpha * .85f, 0xFF9C86)));
                out.submitCustomGeometry(pose, HALO, (p, b) -> VfxParts.ground(p, b, cue.at().add(0, .12, 0), cue.scale() * 1.1f, t * .004f, VfxParts.argb(alpha * .45f, 0xC31D41)));
            } else if (cue.kind() == QueenSpellPayload.RAY) {
                out.submitCustomGeometry(pose, STREAK, (p, b) -> VfxParts.streak(p, b, view, cue.at(), cue.aux(), .35f, VfxParts.argb(alpha, 0xFF8E92)));
                out.submitCustomGeometry(pose, PETAL, (p, b) -> {
                    for (int i = 0; i < 7; i++) VfxParts.sprite(p, b, view, cue.at().lerp(cue.aux(), i / 6.0), .3f, t * .06f + i, VfxParts.argb(alpha * .7f, 0xD93657));
                });
            } else {
                out.submitCustomGeometry(pose, GLOW, (p, b) -> VfxParts.sprite(p, b, view, cue.at(), cue.scale() * .7f, 0, VfxParts.argb(alpha * .45f, 0xE63B53)));
                if (cue.kind() == QueenSpellPayload.CAST) out.submitCustomGeometry(pose, PETAL, (p, b) -> VfxParts.ground(p, b, cue.at(), cue.scale(), t * .025f, VfxParts.argb(alpha * .6f, 0xEE5262)));
            }
        }
        pose.popPose();
    }
}
