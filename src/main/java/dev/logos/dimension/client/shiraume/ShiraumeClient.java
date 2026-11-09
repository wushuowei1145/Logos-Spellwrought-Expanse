package dev.logos.dimension.client.shiraume;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.client.ShaderPackOverlay;
import dev.logos.client.VfxParts;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.dissolve.Dissolves;
import dev.logos.dimension.client.vfx.DawnvowShake;
import dev.logos.dimension.shiraume.ShiraumeCue;
import dev.logos.dimension.shiraume.ShiraumeEntities;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import org.jspecify.annotations.Nullable;

/**
 * 白梅战的画面（docs/SHIRAUME_BOSS.md 第 9 节）：收服务端的提示（{@link ShiraumeCue}），记下色散、红光、标题、光柱、
 * 双眼红光、火花与落点，按时间画出来。全屏的部分在 {@link ShiraumeScreen}，光源红晕在 {@link ShiraumeHalos}，标题在 {@link ShiraumeTitle}。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class ShiraumeClient {
    /** 色散一次多久（纳秒）：0.06 秒冲到最强，之后衰减。 */
    static final long ABERRATION_NANOS = 450_000_000L, ABERRATION_ATTACK = 60_000_000L;
    /** 红光：从起点往外蔓延的速度（格/刻）、淡入淡出的刻数、多久没收到续期就关掉。 */
    static final float MOOD_SPREAD = .6f;
    static final int MOOD_FADE = 40, MOOD_EXPIRE = 320;
    /** 双眼红光多久（刻）。 */
    static final float EYE_TICKS = 7;
    /** 光柱：冲起、维持、散去（刻，从冲起算）。 */
    static final float PILLAR_RISE = 8, PILLAR_FADE_AT = 96, PILLAR_GONE = 120;
    private static final RenderType GLOW = VfxParts.add("heal/glow");
    private static final RenderType PILLAR = VfxParts.add("charge/pillar");
    private static final RenderType FLASH = VfxParts.add("fire_impact/flash");

    private record Pulse(long start, float strength) {}
    private static final List<Pulse> PULSES = new ArrayList<>();
    private record Mood(AABB box, Vec3 from, long start, boolean on, long changed, long refreshed) {}
    private static @Nullable Mood mood;
    private static long titleAt = Long.MIN_VALUE;
    private record Pillar(Vec3 center, long start, float[] seals) {}
    private static @Nullable Pillar pillar;
    /** 每个实体最近一次双眼红光的刻。 */
    static final Map<Integer, Long> EYES = new HashMap<>();
    private record Spark(Vec3 at, long start, boolean big) {}
    private static final List<Spark> SPARKS = new ArrayList<>();
    private record Mark(Vec3 at, float radius, long start, int ticks) {}
    private static final List<Mark> MARKS = new ArrayList<>();
    static final SoftGlow GLOW_OUT = new SoftGlow();
    /** 客户端场景用：每种提示收到过几次。 */
    private static final int[] RECEIVED = new int[16];

    private ShiraumeClient() {}

    @SubscribeEvent
    static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ShiraumeEntities.SHIRAUME.get(), ShiraumeRenderer::new);
        event.registerEntityRenderer(ShiraumeEntities.BEAST.get(), ShiraumeBeastRenderer::new);
        event.registerEntityRenderer(ShiraumeEntities.FOXFIRE.get(), FoxfireRenderer::new);
    }

    @SubscribeEvent
    static void packets(RegisterClientPayloadHandlersEvent event) {
        event.register(ShiraumeCue.TYPE, (cue, context) -> receive(cue));
    }

    @SubscribeEvent
    static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    public static void clear() {
        PULSES.clear();
        mood = null;
        titleAt = Long.MIN_VALUE;
        pillar = null;
        EYES.clear();
        SPARKS.clear();
        MARKS.clear();
    }

    /** 收到一条提示。客户端场景也直接调它。 */
    public static void receive(ShiraumeCue cue) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        long now = level.getGameTime();
        float[] v = cue.values();
        if (cue.kind() >= 0 && cue.kind() < RECEIVED.length) RECEIVED[cue.kind()]++;
        switch (cue.kind()) {
            case ShiraumeCue.ABERRATION -> PULSES.add(new Pulse(Util.getNanos(), v.length > 0 ? v[0] : 1));
            case ShiraumeCue.SHAKE -> DawnvowShake.at(cue.pos(), v.length > 0 ? v[0] : 1);
            case ShiraumeCue.MOOD -> {
                boolean on = v[0] > .5f;
                AABB box = new AABB(v[1], v[2], v[3], v[4], v[5], v[6]);
                Mood old = mood;
                boolean changed = old == null || old.on != on;
                mood = new Mood(box, cue.pos(), cue.time(), on, changed ? now : old.changed, now);
            }
            case ShiraumeCue.TITLE -> titleAt = cue.time();
            case ShiraumeCue.PILLAR -> pillar = new Pillar(cue.pos(), cue.time(), v);
            case ShiraumeCue.EYES -> EYES.put(cue.entity(), now);
            case ShiraumeCue.SPARK -> {
                SPARKS.add(new Spark(cue.pos(), now, v.length > 0 && v[0] > .5f));
                for (int i = 0; i < 10; i++)
                    level.addParticle(ParticleTypes.ELECTRIC_SPARK, cue.pos().x, cue.pos().y, cue.pos().z,
                            (level.getRandom().nextDouble() - .5) * .8, level.getRandom().nextDouble() * .5, (level.getRandom().nextDouble() - .5) * .8);
                if (v.length > 0 && v[0] > .5f) PULSES.add(new Pulse(Util.getNanos(), .25f));
            }
            case ShiraumeCue.MARK -> MARKS.add(new Mark(cue.pos(), v[0], now, (int) v[1]));
            default -> { }
        }
    }

    /** 客户端场景用：这种提示收到过几次。 */
    public static int received(int kind) {
        return kind >= 0 && kind < RECEIVED.length ? RECEIVED[kind] : 0;
    }

    // ---------------------------------------------------------------- 给别的部分读

    /** 现在的色散强度（0–1），几次叠起来取最大。按原版「屏幕效果」滑条缩放。 */
    public static float aberration() {
        long now = Util.getNanos();
        float best = 0;
        PULSES.removeIf(p -> now - p.start > ABERRATION_NANOS || now < p.start);
        for (Pulse p : PULSES) {
            long age = now - p.start;
            float k = age < ABERRATION_ATTACK ? age / (float) ABERRATION_ATTACK : 1 - (age - ABERRATION_ATTACK) / (float) (ABERRATION_NANOS - ABERRATION_ATTACK);
            best = Math.max(best, p.strength * k * k);
        }
        return best * Minecraft.getInstance().options.screenEffectScale().get().floatValue();
    }

    /** 红光的强度（0–1）：镜头在封印之间里才有，开关时淡入淡出，续期断了也慢慢退掉。 */
    public static float mood(Vec3 camera, float partial) {
        Mood m = mood;
        ClientLevel level = Minecraft.getInstance().level;
        if (m == null || level == null) return 0;
        float now = level.getGameTime() + partial;
        if (now - m.refreshed > MOOD_EXPIRE) {
            mood = null;
            return 0;
        }
        if (!m.box.inflate(6).contains(camera)) return 0;
        float since = (now - m.changed) / MOOD_FADE;
        return m.on ? Mth.clamp(since, 0, 1) : 1 - Mth.clamp(since, 0, 1);
    }

    /** 红光蔓延到 {@code at} 了没有（光源红晕从杀生石往外一圈圈变红）：0–1。 */
    public static float spread(Vec3 at, float partial) {
        Mood m = mood;
        ClientLevel level = Minecraft.getInstance().level;
        if (m == null || level == null) return 0;
        float radius = (level.getGameTime() + partial - m.start) * MOOD_SPREAD;
        return Mth.clamp((radius - (float) at.distanceTo(m.from)) / 6f, 0, 1);
    }

    static @Nullable AABB moodBox() {
        return mood == null ? null : mood.box;
    }

    /** 标题出现了多少刻（没出现时为负）。 */
    static float titleAge(float partial) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || titleAt == Long.MIN_VALUE) return -1;
        return level.getGameTime() + partial - titleAt;
    }

    /** 这个实体的双眼红光到了哪（0–1，没在闪为 -1）。 */
    static float eyes(int entity, float partial) {
        Long at = EYES.get(entity);
        ClientLevel level = Minecraft.getInstance().level;
        if (at == null || level == null) return -1;
        float age = (level.getGameTime() + partial - at) / EYE_TICKS;
        if (age > 1) {
            EYES.remove(entity);
            return -1;
        }
        return age;
    }

    // ---------------------------------------------------------------- 世界里的光（光柱、火花、落点、光源红晕）

    /** 在 Logos 之后提交（Logos 在这个事件里先 begin ShaderPackOverlay）。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void submit(SubmitCustomGeometryEvent event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        boolean any = pillar != null || !SPARKS.isEmpty() || !MARKS.isEmpty() || mood != null;
        if (!any) return;
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float now = level.getGameTime() + pt;
        var camera = event.getLevelRenderState().cameraRenderState;
        VfxParts.View view = VfxParts.View.of(camera);
        PoseStack pose = event.getPoseStack();
        SubmitNodeCollector raw = event.getSubmitNodeCollector(), out = ShaderPackOverlay.collector(raw);
        boolean shadow = Dissolves.shadowPass();
        SoftGlow glow = GLOW_OUT.begin(view);
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        ShiraumeHalos.submit(level, view, pt, glow);
        if (pillar != null) {
            Pillar p = pillar;
            float age = now - p.start;
            if (age > PILLAR_GONE) pillar = null;
            else if (age >= 0) out.submitCustomGeometry(pose, GLOW, (pp, buf) -> pillar(pp, buf, view, p, age, glow));
        }
        SPARKS.removeIf(s -> now - s.start > 8);
        for (Spark s : SPARKS) {
            float k = 1 - (now - s.start) / 8f;
            float r = (s.big ? 1.6f : .8f) * (1.2f - k * .4f);
            out.submitCustomGeometry(pose, FLASH, (pp, buf) -> VfxParts.sprite(pp, buf, view, s.at, r, s.start * .37f, VfxParts.argb(k, 0xFFE8F0)));
            glow.disc(s.at, r * .8f, VfxParts.argb(k * .9f, s.big ? 0xFF6070 : 0xFFD0DA));
        }
        MARKS.removeIf(m -> now - m.start > m.ticks + 6);
        for (Mark m : MARKS) {
            float age = now - m.start, k = Mth.clamp(age / m.ticks, 0, 1);
            float a = (age > m.ticks ? 1 - (age - m.ticks) / 6f : .35f + .65f * k);
            glow.ring(m.at.add(0, .08, 0), new Vec3(0, 1, 0), m.radius * (1.15f - .15f * k), .18f, 40, VfxParts.argb(a, 0xE8303A));
            glow.ring(m.at.add(0, .08, 0), new Vec3(0, 1, 0), m.radius * .35f * k, .12f, 24, VfxParts.argb(a * .8f, 0xFF7080));
            out.submitCustomGeometry(pose, GLOW, (pp, buf) -> VfxParts.ground(pp, buf, m.at.add(0, .06, 0), m.radius * 1.1f, age * .02f,
                    VfxParts.argb(a * .45f, 0xE8303A)));
        }
        pose.popPose();
        if (!shadow) glow.flush();
    }

    /** 变身的光柱：正中一道粗的罩住她，原来九根封印杭的位置各冲起一道细的；冲起、维持、散去。 */
    private static void pillar(PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer buf, VfxParts.View view, Pillar p, float age, SoftGlow glow) {
        float rise = VfxParts.smooth(0, PILLAR_RISE, age), fade = 1 - VfxParts.smooth(PILLAR_FADE_AT, PILLAR_GONE, age);
        float show = rise * fade;
        if (show <= .003f) return;
        float height = 34 * rise;
        Vec3 base = p.center.add(0, -.5, 0), top = p.center.add(0, height, 0);
        float swell = 1 + .25f * Mth.sin(age * .35f);
        VfxParts.streak(pose, buf, view, base, top, 6.5f * swell, VfxParts.argb(show * .5f, 0xE8303A));
        VfxParts.streak(pose, buf, view, base, top, 3.8f * swell, VfxParts.argb(show * .75f, 0xF7B9DC));
        VfxParts.streak(pose, buf, view, base, top, 1.9f * swell, VfxParts.argb(show * .95f, 0xFFFFFF));
        for (int i = 0; i < 6; i++) {
            Vec3 at = base.add(top.subtract(base).scale(i / 5.0));
            glow.disc(at, 3.2f * swell, VfxParts.argb(show * .7f, 0xFFD6E6));
        }
        glow.ring(p.center.add(0, .1, 0), new Vec3(0, 1, 0), 2 + age * .45f, .5f, 48, VfxParts.argb(show * (1 - VfxParts.smooth(0, 40, age)), 0xFF8090));
        float thin = VfxParts.smooth(2, 10, age) * (1 - VfxParts.smooth(40, 70, age));
        if (thin <= .003f) return;
        for (int i = 0; i + 1 < p.seals.length; i += 2) {
            Vec3 b = new Vec3(p.seals[i], p.center.y - .5, p.seals[i + 1]);
            Vec3 t = b.add(0, 26 * thin, 0);
            VfxParts.streak(pose, buf, view, b, t, 1.2f, VfxParts.argb(thin * .7f, 0xE8303A));
            VfxParts.streak(pose, buf, view, b, t, .55f, VfxParts.argb(thin * .9f, 0xFFE0EA));
            glow.band(b, t, .5f, VfxParts.argb(thin * .6f, 0xFF6070));
        }
    }
}
