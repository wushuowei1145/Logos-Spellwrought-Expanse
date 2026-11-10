package dev.logos.dimension.client.trail;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.client.ShaderPackOverlay;
import dev.logos.client.VfxParts;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.dissolve.Dissolves;
import dev.logos.dimension.client.shiraume.SoftGlow;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import org.jspecify.annotations.Nullable;

/**
 * 刀光的总入口（docs/WEAPON_TRAILS.md）。谁拿着会挥的武器，谁就每帧把刀身（柄端、尖端两点）连同此刻的样式送进来，
 * 这里记成一条扫过的面，按样式画成几层带子，再从刀尖甩出粒子、沿刀尖铺一层柔光。
 *
 * <p>两种坐标：{@link Space#WORLD} 是世界坐标，在世界的自定义几何阶段画（第三人称、NPC）；{@link Space#HAND} 是
 * 第一人称手部渲染的坐标，由调用方在画手时调 {@link #submitHand} 画，刀光跟着镜头走，和手里的刀一样。
 *
 * <p>送点的两种方式：{@link #feed} 给能在任意时刻取样刀身的（玩家骨架按动作取样，帧与帧之间补满点）；
 * {@link #feedSmoothed} 给只能逐帧取到的（GeckoLib 骨骼），这里用样条在帧间补成圆弧。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class WeaponTrails {
    public enum Space { WORLD, HAND }

    /** 粒子总数上限。 */
    private static final int SPARK_CAP = 600;
    /** 这么多秒没送点、刀光也散尽了，就忘掉这个发射者。 */
    private static final double STALE = 2;
    /** 隔了这么多秒才又送点，就断开当前这一道。 */
    private static final double QUIET = .25;

    private static final Map<Object, Emitter> EMITTERS = new HashMap<>();
    private static final List<Spark> SPARKS = new ArrayList<>();
    private static final RandomSource RANDOM = RandomSource.create();
    private static final SoftGlow BLOOM = new SoftGlow();

    private WeaponTrails() {}

    private static final class Emitter {
        final Space space;
        final TrailRibbon ribbon = new TrailRibbon();
        final int seed = RANDOM.nextInt(32768);
        TrailStyle style;
        double seen;
        float sparkDebt;
        Emitter(Space space, TrailStyle style) { this.space = space; this.style = style; }
    }

    private static final class Spark {
        final Object key;
        final Space space;
        final TrailStyle.Sparks def;
        Vec3 pos, vel;
        final double born;
        final float life, size, spin, roll;
        final int frame;
        Spark(Object key, Space space, TrailStyle.Sparks def, Vec3 pos, Vec3 vel, double born) {
            this.key = key; this.space = space; this.def = def; this.pos = pos; this.vel = vel; this.born = born;
            life = def.life() * (.6f + RANDOM.nextFloat() * .8f);
            size = def.size() * (.6f + RANDOM.nextFloat() * .8f);
            spin = (RANDOM.nextFloat() * 2 - 1) * def.spin();
            roll = RANDOM.nextFloat() * Mth.TWO_PI;
            frame = def.frames() <= 1 ? 0 : RANDOM.nextInt(def.frames());
        }
    }

    private static double clock;

    /**
     * 当前时刻（秒）：客户端游戏时间加帧内插值，只进不退。服务端的时间包偶尔会把客户端时间往回拨一点，
     * 往回走会让刚记的点被当成重复丢掉、年龄变成负的，所以回拨时停在原处等它追上。换世界时从头算。
     */
    public static double now() {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return clock = 0;
        double t = (mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false)) / 20.0;
        if (t > clock || clock - t > 5) clock = t;
        return clock;
    }

    /**
     * 送一次刀身。{@code time} 秒（{@link #now} 的时基，可以早于现在：帧间补的点），{@code base}/{@code tip} 是
     * {@code space} 里的坐标。{@code emit} 为假时不记点，只断开当前这一道（已有的照常散去）。
     * 光影包的阴影通道里画的身体姿势是太阳视角的，这时送来的点直接丢掉。
     */
    public static void feed(Object key, Space space, double time, Vec3 base, Vec3 tip, boolean emit, TrailStyle style) {
        if (Dissolves.shadowPass()) return;
        Emitter e = emitter(key, space, style, emit);
        if (e == null) return;
        TrailRibbon.Point last = e.ribbon.last();
        boolean was = e.ribbon.emitting();
        e.ribbon.add(time, base, tip, emit);
        if (emit && was && last != null && time > last.time()) sparks(key, e, last, base, tip, time);
    }

    /** 同 {@link #feed}，但只能逐帧取样：帧与帧之间用样条补成圆弧，刀尖每走 {@code step} 格补一个点。 */
    public static void feedSmoothed(Object key, Space space, double time, Vec3 base, Vec3 tip, boolean emit, TrailStyle style, float step) {
        if (Dissolves.shadowPass()) return;
        Emitter e = emitter(key, space, style, emit);
        if (e == null) return;
        TrailRibbon.Point last = e.ribbon.last();
        boolean was = e.ribbon.emitting();
        e.ribbon.addSmoothed(time, base, tip, emit, step);
        if (emit && was && last != null && time > last.time()) sparks(key, e, last, base, tip, time);
    }

    private static @Nullable Emitter emitter(Object key, Space space, TrailStyle style, boolean emit) {
        Emitter e = EMITTERS.get(key);
        if (e == null) {
            if (!emit) return null;
            e = new Emitter(space, style);
            EMITTERS.put(key, e);
        }
        double now = now();
        // 停了一阵才又送点（切了视角、被剔除后又回来）：断开，不和停之前那一道连起来
        if (now - e.seen > QUIET) e.ribbon.add(now, Vec3.ZERO, Vec3.ZERO, false);
        // 换了样式（比如中途缠上了魔力）：从下一刀起用新的；正在散的这一道保持原样
        if (emit && !e.ribbon.emitting()) e.style = style;
        e.seen = now;
        return e;
    }

    /** 刀尖从上一点走到这一点，按路程甩出粒子。 */
    private static void sparks(Object key, Emitter e, TrailRibbon.Point last, Vec3 base, Vec3 tip, double time) {
        TrailStyle.Sparks def = e.style.sparks();
        if (def == null || def.perBlock() <= 0) return;
        double dist = last.tip().distanceTo(tip), dt = Math.max(1e-3, time - last.time());
        e.sparkDebt += (float) (dist * def.perBlock());
        Vec3 velocity = tip.subtract(last.tip()).scale(def.inherit() / dt);
        while (e.sparkDebt >= 1 && SPARKS.size() < SPARK_CAP) {
            e.sparkDebt -= 1;
            double k = RANDOM.nextDouble(), inward = RANDOM.nextDouble() * RANDOM.nextDouble() * .35;
            Vec3 at = last.tip().lerp(tip, k);
            at = at.lerp(last.base().lerp(base, k), inward);
            Vec3 jitter = new Vec3(RANDOM.nextGaussian(), RANDOM.nextGaussian(), RANDOM.nextGaussian()).scale(def.spread() * .5);
            SPARKS.add(new Spark(key, e.space, def, at, velocity.add(jitter), time - (1 - k) * dt));
        }
        if (e.sparkDebt > 4) e.sparkDebt = 4;
    }

    // ---- 画 ----------------------------------------------------------------------------------

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void submit(SubmitCustomGeometryEvent event) {
        if (EMITTERS.isEmpty() && SPARKS.isEmpty()) return;
        double now = now();
        step(now);
        if (Dissolves.shadowPass()) return;
        var camera = event.getLevelRenderState().cameraRenderState;
        VfxParts.View view = VfxParts.View.of(camera);
        SubmitNodeCollector raw = event.getSubmitNodeCollector(), out = ShaderPackOverlay.collector(raw);
        var layered = VfxParts.layered(raw, out);
        PoseStack pose = event.getPoseStack();
        SoftGlow glow = BLOOM.begin(view);
        for (Emitter e : EMITTERS.values()) {
            if (e.space != Space.WORLD || e.ribbon.isEmpty()) continue;
            draw(e, pose, layered, now, view.cam());
            bloom(e, glow, now);
        }
        drawSparks(Space.WORLD, null, pose, out, now, new VfxParts.View(Vec3.ZERO, view.right(), view.up()), view.cam());
        glow.flush();
    }

    /**
     * 第一人称：在手部渲染里画 {@code key} 的刀光与粒子。{@code pose} 要和送点时用的是同一个坐标（送点时
     * 已经把骨架里的刀身乘到这个坐标里）。
     */
    public static void submitHand(Object key, PoseStack pose, SubmitNodeCollector out) {
        Emitter e = EMITTERS.get(key);
        double now = now();
        // 开着光影时 Iris 用它自己的投影画第一人称的手：交给手部的叠加收集器，合成后按那套投影补画（同剑身发光、魔力缠绕）
        SubmitNodeCollector collector = ShaderPackOverlay.item(out, true);
        if (e != null && e.space == Space.HAND && !e.ribbon.isEmpty()) draw(e, pose, VfxParts.layered(out, collector), now, Vec3.ZERO);
        // 手部坐标与镜头同向：右是 +X，上是 +Y
        drawSparks(Space.HAND, key, pose, collector, now,
                new VfxParts.View(Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(0, 1, 0)), Vec3.ZERO);
    }

    private static void draw(Emitter e, PoseStack pose, java.util.function.IntFunction<? extends net.minecraft.client.renderer.OrderedSubmitNodeCollector> layered,
                             double now, Vec3 origin) {
        TrailStyle style = e.style;
        float life = style.life();
        var points = e.ribbon;
        for (int i = 0; i < style.layers().size(); i++) {
            TrailStyle.Layer layer = style.layers().get(i);
            RenderType type = TrailMaterials.of(layer);
            layered.apply(i).submitCustomGeometry(pose, type, (p, buf) -> points.build(p, buf, layer, now, life, style.expand(), origin, e.seed));
        }
    }

    /** 沿最近扫过的刀尖铺一层柔光，越新越亮。 */
    private static void bloom(Emitter e, SoftGlow glow, double now) {
        TrailStyle s = e.style;
        if (s.glow() <= 0) return;
        List<TrailRibbon.Point> pts = e.ribbon.points();
        float life = s.life() * .7f;
        for (int i = Math.max(1, pts.size() - 48); i < pts.size(); i++) {
            TrailRibbon.Point a = pts.get(i - 1), b = pts.get(i);
            if (a.segment() != b.segment()) continue;
            float age = (float) ((now - b.time()) / life);
            if (age >= 1) continue;
            float k = (1 - age) * (1 - age);
            Vec3 ma = a.base().lerp(a.tip(), .82), mb = b.base().lerp(b.tip(), .82);
            glow.band(ma, mb, s.glowWidth() * (.6f + .4f * k), VfxParts.argb(s.glow() * k, s.glowColor()));
        }
    }

    private static void drawSparks(Space space, @Nullable Object key, PoseStack pose, SubmitNodeCollector out, double now,
                                   VfxParts.View view, Vec3 origin) {
        for (Spark s : SPARKS) {
            if (s.space != space || (key != null && !key.equals(s.key))) continue;
            float age = (float) ((now - s.born) / s.life);
            if (age < 0 || age >= 1) continue;
            TrailStyle.Sparks d = s.def;
            int color = d.colors().length == 1 ? d.colors()[0] : gradient(d.colors(), age);
            float alpha = age < .1f ? age / .1f : 1 - (age - .1f) / .9f;
            float size = s.size * (1 - age * .6f);
            float u0 = s.frame / (float) Math.max(1, d.frames()), u1 = (s.frame + 1) / (float) Math.max(1, d.frames());
            Vec3 at = s.pos.subtract(origin);
            float roll = s.roll + s.spin * (float) (now - s.born);
            RenderType type = d.additive() ? VfxParts.add(d.texture()) : VfxParts.blend(d.texture());
            out.submitCustomGeometry(pose, type, (p, buf) -> VfxParts.sprite(p, buf, view, at, size, size, roll, u0, 0, u1, 1,
                    VfxParts.argb(alpha, color)));
        }
    }

    private static int gradient(int[] colors, float k) {
        float x = Mth.clamp(k, 0, 1) * (colors.length - 1);
        int i = Math.min(colors.length - 2, (int) x);
        return VfxParts.lerpColor(colors[i], colors[i + 1], x - i);
    }

    /** 推进粒子、去掉散尽的点与久未送点的发射者；每帧一次（在世界阶段）。 */
    private static double stepped;
    private static void step(double now) {
        double dt = Math.clamp(now - stepped, 0, .1);
        stepped = now;
        Iterator<Spark> it = SPARKS.iterator();
        while (it.hasNext()) {
            Spark s = it.next();
            if (now - s.born >= s.life) { it.remove(); continue; }
            if (dt <= 0) continue;
            s.vel = s.vel.scale(Math.exp(-s.def.drag() * dt)).add(0, -s.def.gravity() * dt, 0);
            s.pos = s.pos.add(s.vel.scale(dt));
        }
        Iterator<Map.Entry<Object, Emitter>> emitters = EMITTERS.entrySet().iterator();
        while (emitters.hasNext()) {
            Emitter e = emitters.next().getValue();
            e.ribbon.prune(now, e.style.life());
            if (e.ribbon.isEmpty() && now - e.seen > STALE) emitters.remove();
        }
    }

    /** 资源重载、退出世界时清空。 */
    public static void clear() {
        EMITTERS.clear();
        SPARKS.clear();
        TrailMaterials.clear();
        TrailStyles.clear();
        BoneTrails.clear();
    }

    @SubscribeEvent
    static void logout(ClientPlayerNetworkEvent.LoggingOut event) { EMITTERS.clear(); SPARKS.clear(); }

    /** 场景检查用：正在画的刀光条数与粒子数。 */
    public static int ribbons() {
        int n = 0;
        for (Emitter e : EMITTERS.values()) if (!e.ribbon.isEmpty()) n++;
        return n;
    }

    public static int sparks() { return SPARKS.size(); }

    /** 场景检查用：{@code key} 这道刀光现在的样式；没有时为 null。 */
    public static @Nullable TrailStyle styleOf(Object key) {
        Emitter e = EMITTERS.get(key);
        return e == null ? null : e.style;
    }
}
