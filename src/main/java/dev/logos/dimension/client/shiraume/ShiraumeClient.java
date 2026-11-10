package dev.logos.dimension.client.shiraume;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.client.ShaderPackOverlay;
import dev.logos.client.VfxParts;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.dissolve.Dissolves;
import dev.logos.dimension.client.vfx.DawnvowShake;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.dimension.shiraume.Shiraume;
import dev.logos.dimension.shiraume.ShiraumeBeast;
import dev.logos.dimension.shiraume.ShiraumeCue;
import dev.logos.dimension.shiraume.ShiraumeEntities;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Vector3fc;
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
    /** 狐火的火舌（同狐火缠身）；冲击那一圈另用混合的一层垫底，亮的地面上也看得出是粉的（只叠加的话白瓷地上一片白）。 */
    private static final RenderType TONGUE = VfxParts.add("fire_impact/glow"), TONGUE_BASE = VfxParts.blend("fire_impact/glow");
    /** 狐火冲击垫底那层的粉：比她的淡粉深一些。 */
    static final int RING_BASE = 0xE0609A;
    private static final Vec3 UP = new Vec3(0, 1, 0);

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
    /** 速度感（缩地、处决的冲刺、狐形的斩法扑击）：冲完之后几刻淡出。 */
    static final float SPEED_FADE = 5;
    private record Speed(int entity, long start, float strength, int ticks, Vec3 at) {}
    private static final List<Speed> SPEEDS = new ArrayList<>();
    /** 清除增益、破盾、斩法：在世界里闪一下（kind 同 {@link ShiraumeCue}），画多少刻。 */
    static final float BURST_TICKS = 12;
    private record Burst(int kind, int entity, Vec3 at, long start, int[] colours) {}
    private static final List<Burst> BURSTS = new ArrayList<>();
    /** 被盯上的术师（实体 → 最近一次收到的刻）：头顶一点淡粉的狐火；服务端每两秒重发，五秒没收到就不画。 */
    static final Map<Integer, Long> LOCKED = new HashMap<>();
    static final int LOCK_EXPIRE = 100;
    /** 狂暴大跳落地的狐火冲击：圆心、放出的刻（半径照服务端的 {@link ShiraumeBeast#ringRadius}）。 */
    private record Ring(Vec3 center, long start) {}
    private static final List<Ring> RINGS = new ArrayList<>();
    /** 一字斩的剑气：她、起点、方向、放出的刻（走法照 {@link Shiraume#WAVE_SPEED} 等）。 */
    private record Wave(int entity, Vec3 from, Vec3 dir, long start) {}
    private static final List<Wave> WAVES = new ArrayList<>();
    /** 剑气：竖着的光刃往前鼓多少格；身后拖几道残影、相隔几格。 */
    static final float WAVE_BOW = 1.1f, WAVE_TRAIL_STEP = .9f;
    static final int WAVE_TRAILS = 3;
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
        SPEEDS.clear();
        BURSTS.clear();
        LOCKED.clear();
        RINGS.clear();
        WAVES.clear();
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
            case ShiraumeCue.SPEED -> SPEEDS.add(new Speed(cue.entity(), now, v.length > 0 ? v[0] : 1, v.length > 1 ? (int) v[1] : 12, cue.pos()));
            case ShiraumeCue.PURGE -> {
                // 清掉的效果碎成它们自己颜色的光点，从身上往外迸
                int[] colours = new int[v.length];
                for (int i = 0; i < v.length; i++) colours[i] = (int) v[i];
                BURSTS.add(new Burst(cue.kind(), cue.entity(), cue.pos(), now, colours));
                Vec3 at = body(level.getEntity(cue.entity()), cue.pos(), 1);
                RandomSource r = level.getRandom();
                for (int c : colours.length > 0 ? colours : new int[] {0xFFFFFF})
                    for (int i = 0; i < 14; i++)
                        level.addParticle(ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, 0xFF000000 | c), at.x, at.y, at.z,
                                (r.nextDouble() - .5) * .9, r.nextDouble() * .5, (r.nextDouble() - .5) * .9);
                if (mc.player != null && cue.entity() == mc.player.getId()) PULSES.add(new Pulse(Util.getNanos(), .3f));
            }
            case ShiraumeCue.GUARD_BREAK -> {
                BURSTS.add(new Burst(cue.kind(), -1, cue.pos(), now, new int[0]));
                RandomSource r = level.getRandom();
                Vec3 at = cue.pos();
                for (int i = 0; i < 16; i++)
                    level.addParticle(ParticleTypes.CRIT, at.x, at.y, at.z, (r.nextDouble() - .5) * 1.2, r.nextDouble() * .6, (r.nextDouble() - .5) * 1.2);
                for (int i = 0; i < 8; i++)
                    level.addParticle(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, (r.nextDouble() - .5) * .9, r.nextDouble() * .5, (r.nextDouble() - .5) * .9);
                if (mc.player != null && mc.player.position().distanceToSqr(at) < 16) {
                    PULSES.add(new Pulse(Util.getNanos(), .35f));
                    DawnvowShake.at(at, .35f);
                }
            }
            case ShiraumeCue.RING -> RINGS.add(new Ring(cue.pos(), cue.time()));
            case ShiraumeCue.WAVE -> {
                if (v.length >= 2) WAVES.add(new Wave(cue.entity(), cue.pos(), new Vec3(v[0], 0, v[1]).normalize(), cue.time()));
            }
            case ShiraumeCue.LOCK -> {
                if (v.length > 0 && v[0] > .5f) LOCKED.put(cue.entity(), now);
                else LOCKED.remove(cue.entity());
            }
            case ShiraumeCue.COUNTER -> {
                // 满是狐火的一刀：一团淡粉的光炸开，狐火的碎屑四散；挨刀的人眼前一花
                BURSTS.add(new Burst(cue.kind(), cue.entity(), cue.pos(), now, new int[0]));
                RandomSource r = level.getRandom();
                Vec3 at = cue.pos();
                for (int i = 0; i < 36; i++)
                    level.addParticle(new DustParticleOptions(i % 3 == 0 ? FoxfireFlame.SHIRAUME.bright : FoxfireFlame.SHIRAUME.core, 1.4f),
                            at.x + (r.nextDouble() - .5) * 1.2, at.y + (r.nextDouble() - .5) * 1.4, at.z + (r.nextDouble() - .5) * 1.2, 0, 0, 0);
                if (mc.player != null && cue.entity() == mc.player.getId()) {
                    PULSES.add(new Pulse(Util.getNanos(), .7f));
                    DawnvowShake.at(at, .6f);
                }
            }
            default -> { }
        }
    }

    /** 实体身上（六成身高处）；实体不在时用 {@code fallback}。 */
    private static Vec3 body(@Nullable Entity e, Vec3 fallback, float partial) {
        return e == null ? fallback : e.getPosition(partial).add(0, e.getBbHeight() * .6, 0);
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

    /**
     * 速度感：{强度（0–1，已按「屏幕效果」滑条缩放）, 焦点 x, 焦点 y}，焦点是冲过来的她在画面上的位置（0–1，左下为原点，
     * 同后期处理的纹理坐标）。几次叠在一起取最强的那次；没有时强度为 0。
     */
    public static float[] speed(float partial) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        float[] out = {0, .5f, .5f};
        if (level == null || SPEEDS.isEmpty()) return out;
        float now = level.getGameTime() + partial;
        SPEEDS.removeIf(s -> now - s.start > s.ticks + SPEED_FADE || now < s.start - 1);
        Camera camera = mc.gameRenderer.getMainCamera();
        for (Speed s : SPEEDS) {
            float age = now - s.start;
            float k = s.strength * VfxParts.smooth(0, 2, age) * (1 - VfxParts.smooth(s.ticks, s.ticks + SPEED_FADE, age));
            if (k <= out[0]) continue;
            Entity e = level.getEntity(s.entity);
            float[] at = screen(camera, e != null ? e.getPosition(partial).add(0, e.getBbHeight() * .5, 0) : s.at);
            out[0] = k;
            out[1] = at[0];
            out[2] = at[1];
        }
        out[0] *= mc.options.screenEffectScale().get().floatValue();
        return out;
    }

    /** 世界里的一点投到画面上（0–1，左下为原点）；在镜头后面时推到画面外的那一侧。 */
    static float[] screen(Camera camera, Vec3 at) {
        Vec3 d = at.subtract(camera.position());
        Vector3fc f = camera.forwardVector(), u = camera.upVector(), l = camera.leftVector();
        double z = d.x * f.x() + d.y * f.y() + d.z * f.z();
        double x = -(d.x * l.x() + d.y * l.y() + d.z * l.z());
        double y = d.x * u.x() + d.y * u.y() + d.z * u.z();
        if (z < .2) {
            double len = Math.max(1e-4, Math.hypot(x, y));
            return new float[] {(float) (.5 + x / len * .9), (float) (.5 + y / len * .9)};
        }
        var window = Minecraft.getInstance().getWindow();
        double aspect = (double) window.getWidth() / Math.max(1, window.getHeight());
        double tan = Math.tan(Math.toRadians(camera.getFov()) / 2);
        return new float[] {(float) (.5 + x / (z * tan * aspect) * .5), (float) (.5 + y / (z * tan) * .5)};
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
        boolean any = pillar != null || !SPARKS.isEmpty() || !MARKS.isEmpty() || mood != null || !BURSTS.isEmpty() || !LOCKED.isEmpty()
                || !RINGS.isEmpty() || !WAVES.isEmpty();
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
        BURSTS.removeIf(b -> now - b.start > BURST_TICKS);
        for (Burst b : BURSTS) {
            float age = now - b.start, k = 1 - age / BURST_TICKS;
            Vec3 at = b.entity >= 0 ? body(level.getEntity(b.entity), b.at, pt) : b.at;
            switch (b.kind) {
                case ShiraumeCue.PURGE -> {
                    // 清掉的每个效果一圈它自己的颜色，从身上往外散开
                    for (int i = 0; i < Math.max(1, b.colours.length); i++) {
                        int c = b.colours.length > 0 ? b.colours[i] : 0xFFFFFF;
                        glow.ring(at, new Vec3(0, 1, 0), .45f + age * (.14f + i * .05f), .09f, 32, VfxParts.argb(k * .9f, c));
                    }
                    out.submitCustomGeometry(pose, FLASH, (pp, buf) -> VfxParts.sprite(pp, buf, view, at, .8f * (1.3f - k * .3f), b.start * .31f,
                            VfxParts.argb(k * .75f, 0xFFFFFF)));
                }
                case ShiraumeCue.GUARD_BREAK -> {
                    // 盾被砸开：一下金白的大闪光，一圈往外撞开
                    out.submitCustomGeometry(pose, FLASH, (pp, buf) -> VfxParts.sprite(pp, buf, view, at, 2.1f * (1.25f - k * .45f), b.start * .29f,
                            VfxParts.argb(k, 0xFFF2D0)));
                    glow.disc(at, 1.3f + age * .08f, VfxParts.argb(k * .75f, 0xFFC860));
                    glow.ring(at, view.cam().subtract(at).normalize(), .6f + age * .22f, .14f, 36, VfxParts.argb(k * .9f, 0xFFE6A0));
                }
                case ShiraumeCue.COUNTER -> {
                    // 斩法：一团淡粉的狐火炸开
                    FoxfireFlame f = FoxfireFlame.SHIRAUME;
                    out.submitCustomGeometry(pose, FLASH, (pp, buf) -> VfxParts.sprite(pp, buf, view, at, 3.0f * (1.3f - k * .3f), b.start * .23f,
                            VfxParts.argb(k, f.bright)));
                    glow.disc(at, 2.4f * (1.3f - k * .3f), VfxParts.argb(k * .9f, f.core));
                    glow.ring(at, new Vec3(0, 1, 0), .8f + age * .32f, .2f, 40, VfxParts.argb(k, f.core));
                }
                default -> { }
            }
        }
        // 狐火冲击：贴地一圈淡粉的狐火，火舌往上窜，起初很慢、越来越快地往外扩散
        RINGS.removeIf(g -> ShiraumeBeast.ringRadius(now - g.start) > ShiraumeBeast.RING_MAX + 1);
        for (Ring g : RINGS) {
            float r = (float) ShiraumeBeast.ringRadius(now - g.start);
            float fade = 1 - VfxParts.smooth((float) ShiraumeBeast.RING_MAX - 3, (float) ShiraumeBeast.RING_MAX + 1, r);
            Vec3 c = g.center.add(0, .06, 0);
            FoxfireFlame f = FoxfireFlame.SHIRAUME;
            glow.ring(c, UP, r, .55f, 72, VfxParts.argb(.55f * fade, f.core));
            glow.ring(c, UP, r, .2f, 72, VfxParts.argb(.5f * fade, f.core));
            int n = Mth.clamp((int) (r * Mth.TWO_PI * 1.6f), 16, 160);
            out.submitCustomGeometry(pose, TONGUE_BASE, (pp, buf) -> ringFlames(pp, buf, view, c, r, n, now, fade * .85f, RING_BASE, f.core));
            out.submitCustomGeometry(pose, TONGUE, (pp, buf) -> ringFlames(pp, buf, view, c, r, n, now, fade * .6f, f.bright, f.core));
            out.submitCustomGeometry(pose, GLOW, (pp, buf) -> VfxParts.ground(pp, buf, c, r + .4f, now * .01f, VfxParts.argb(.18f * fade, f.core)));
        }
        // 剑气：竖着的淡粉光刃贴地往前，身后拖几道残影；撞墙就停在墙前散掉
        WAVES.removeIf(w -> now - w.start > Shiraume.WAVE_RANGE / Shiraume.WAVE_SPEED + 8);
        for (Wave w : WAVES) {
            float age = now - w.start;
            double dist = Math.min(Shiraume.WAVE_RANGE, Shiraume.WAVE_SPEED * age);
            Vec3 a = w.from.add(0, 1, 0);
            HitResult block = level.clip(new ClipContext(a, a.add(w.dir.scale(dist)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
            float stopped = 0;
            if (block.getType() != HitResult.Type.MISS) {
                dist = block.getLocation().distanceTo(a);
                stopped = age - (float) (dist / Shiraume.WAVE_SPEED);
            }
            float fade = VfxParts.smooth(0, 1.5f, age) * (1 - VfxParts.smooth((float) Shiraume.WAVE_RANGE - 5, (float) Shiraume.WAVE_RANGE, (float) dist))
                    * (1 - VfxParts.smooth(0, 5, stopped));
            if (fade <= .003f) continue;
            double head = dist;
            out.submitCustomGeometry(pose, PILLAR, (pp, buf) -> waveBlade(pp, buf, view, w, head, fade));
            Vec3 base = w.from.add(w.dir.scale(dist));
            glow.band(base.add(w.dir.scale(WAVE_BOW * .5)).add(0, .2, 0), base.add(w.dir.scale(WAVE_BOW * .5)).add(0, Shiraume.WAVE_HEIGHT - .2, 0), .9f,
                    VfxParts.argb(.7f * fade, FoxfireFlame.SHIRAUME.core));
        }
        // 被盯上的术师头顶一点淡粉的狐火（第一人称时自己的就不画）
        LOCKED.values().removeIf(at -> now - at > LOCK_EXPIRE);
        for (var entry : LOCKED.entrySet()) {
            Entity e = level.getEntity(entry.getKey());
            if (e == null || e == mc.getCameraEntity() && mc.options.getCameraType().isFirstPerson()) continue;
            float age = now - entry.getValue();
            float pulse = .78f + .22f * Mth.sin(now * .35f);
            Vec3 top = e.getPosition(pt).add(0, e.getBbHeight() + .5 + .08 * Mth.sin(now * .2f), 0);
            glow.disc(top, .34f * pulse, VfxParts.argb(.8f, FoxfireFlame.SHIRAUME.core));
            out.submitCustomGeometry(pose, FLASH, (pp, buf) -> VfxParts.sprite(pp, buf, view, top, .2f * pulse, age * .05f,
                    VfxParts.argb(.9f, FoxfireFlame.SHIRAUME.bright)));
        }
        pose.popPose();
        if (!shadow) glow.flush();
    }

    /** 狐火冲击的一圈火舌：沿圆周，每格一两条，往上窜、渐渐变淡。 */
    private static void ringFlames(PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer buf, VfxParts.View view, Vec3 c, float r, int n,
                                   float now, float fade, int from, int to) {
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2 / n;
            float phase = Mth.frac(now * .09f + i * .618f);
            Vec3 p = c.add(Math.cos(a) * r, .1 + .85 * phase, Math.sin(a) * r);
            float alpha = Mth.sin(phase * Mth.PI) * fade;
            VfxParts.sprite(pose, buf, view, p, .6f * (1.2f - phase * .6f), i + now * .03f, VfxParts.argb(alpha, VfxParts.lerpColor(from, to, phase)));
        }
    }

    /** 剑气的光刃：从脚下到 {@link Shiraume#WAVE_HEIGHT} 往前鼓成一道弧，外层淡粉、里层白；身后几道残影越来越淡。 */
    private static void waveBlade(PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer buf, VfxParts.View view, Wave w, double head, float fade) {
        int segs = 8;
        for (int k = 0; k <= WAVE_TRAILS; k++) {
            double at = head - k * WAVE_TRAIL_STEP;
            if (at < 0) break;
            float a = fade * (k == 0 ? 1 : .45f / k);
            Vec3 base = w.from.add(w.dir.scale(at));
            Vec3 prev = null;
            for (int i = 0; i <= segs; i++) {
                float s = i / (float) segs;
                Vec3 pt = base.add(w.dir.scale(WAVE_BOW * Mth.sin(s * Mth.PI))).add(0, s * Shiraume.WAVE_HEIGHT, 0);
                if (prev != null) {
                    float mid = Mth.sin((s - .5f / segs) * Mth.PI);
                    VfxParts.streak(pose, buf, view, prev, pt, (.35f + .55f * mid) * (k == 0 ? 1 : .8f), VfxParts.argb(.75f * a, FoxfireFlame.SHIRAUME.core));
                    if (k == 0) VfxParts.streak(pose, buf, view, prev, pt, .12f + .2f * mid, VfxParts.argb(.95f * a, 0xFFFFFF));
                }
                prev = pt;
            }
        }
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
