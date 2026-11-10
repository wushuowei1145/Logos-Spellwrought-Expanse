package dev.logos.dimension.client.jinghong;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.client.ShaderPackOverlay;
import dev.logos.client.VfxParts;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.dissolve.Dissolves;
import dev.logos.dimension.client.longsword.LongswordClient;
import dev.logos.dimension.client.longsword.LongswordRig;
import dev.logos.dimension.item.JinghongSwordItem;
import dev.logos.dimension.jinghong.SwordArrayPayload;
import dev.logos.dimension.jinghong.SwordFlight;
import dev.logos.dimension.longsword.LongswordPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 惊鸿剑阵的画面（docs/JINGHONG.md）：身后扇形的五把剑、追击与掷剑的飞剑、激活的剑发光、手里剑身上的激活刻度、万剑归宗。
 * 服务端只同步“哪把剑什么时候出发打谁”（{@link SwordArrayPayload}），轨迹按 {@link SwordFlight} 的时间轴在这里算；
 * 目标在飞剑途中走动时，剑跟着它当前的位置走。
 *
 * <p>剑的网格用惊鸿自己的第三人称骨架（{@link LongswordRig#submitLoose}），在世界的自定义几何阶段画；发光、拖影、法阵
 * 是叠加的自定义几何，经 Logos 的光影兼容通道提交。第一人称时自己身后那一扇不画（镜头在剑阵里面），飞出去的照画。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class SwordArrayClient {
    /** 整把剑发光（身后的剑激活、虚影剑）与手里剑身上的刻度（亮几段 = 激活了几把）。 */
    private static final Identifier GLOW = LogosDimension.id("textures/item/jinghong_sword_glow.png");
    private static final Identifier[] GAUGE = new Identifier[SwordFlight.FAN + 1];
    static {
        for (int i = 1; i <= SwordFlight.FAN; i++) GAUGE[i] = LogosDimension.id("textures/item/jinghong_sword_gauge_" + i + ".png");
    }
    /** 物理元素的光：亮处近白，外圈钢蓝。 */
    public static final int LIGHT = 0xDCEBFF, RIM = 0x8FB0E8;
    private static final RenderType HALO = VfxParts.add("physical/glow"), STAR = VfxParts.add("physical/star"),
            STREAK = VfxParts.add("force/streak"), CIRCLE = VfxParts.add("physical_field/circle_27"), RING = VfxParts.add("physical_field/circle_07");
    /** 身后剑的大小（每个网格单位多少格）：手里的剑第三人称是 0.8/16，身后的略小；掷出去的就是手里那把。 */
    private static final float SCALE = 0.8f / 16 * 0.85f, HAND_SCALE = 0.8f / 16;
    /** 剑的中点在网格里离握柄原点的高度：飞剑按这一点穿过目标。 */
    private static final float CENTER = 9.5f;
    /** 万剑归宗时剑环上的五把剑放大到的倍数（离得远也看得清），虚影剑的倍数。 */
    private static final float RING_GROW = 2.4f, PHANTOM_GROW = 1.6f;
    /** 出击点离目标、穿过后冲过头的距离（格）。 */
    private static final double APPROACH = 3.0, OVERSHOOT = 1.4;
    /** 升起的高度（格）。 */
    private static final double LIFT = 1.2;
    private static final Vec3 UP = new Vec3(0, 1, 0);

    private record Strike(UUID player, int sword, int target, long start, Vec3[] dirs, boolean lit, boolean[] fx) {
        int passes() { return dirs.length; }
        double end() { return start + SwordFlight.duration(passes()); }
    }
    private record Thrown(UUID player, int target, long start, Vec3 miss, boolean[] fx) {}
    private record Finale(UUID player, int target, long start, int seed, boolean[] fx) {
        double end() { return start + SwordFlight.FINALE_HOME; }
    }
    /** 一把剑这一刻的样子：剑的中点、剑尖方向、剑面法线、缩放、发光强弱（0 不亮）、要不要拖影。 */
    private record Pose(Vec3 center, Vec3 dir, Vec3 normal, float scale, float glow, boolean streak) {}

    private static final Map<UUID, long[]> ACTIVE = new HashMap<>();
    private static final List<Strike> STRIKES = new ArrayList<>();
    private static final List<Thrown> THROWS = new ArrayList<>();
    private static final List<Finale> FINALES = new ArrayList<>();
    /** 目标最后一次看到时的中心：目标没了（死了、走出视距）时剑飞到这里。 */
    private static final Map<Integer, Vec3> LAST_SEEN = new HashMap<>();
    /** 场景检查用：上一帧画了几把身后的剑、几把飞剑、几把虚影剑。 */
    private static int drawnFan, drawnFlying, drawnPhantoms;

    private SwordArrayClient() {}

    @SubscribeEvent static void packets(RegisterClientPayloadHandlersEvent event) {
        event.register(SwordArrayPayload.Active.TYPE, (p, ctx) -> ACTIVE.put(p.player(), p.until()));
        event.register(SwordArrayPayload.Strike.TYPE, (p, ctx) -> {
            var dirs = new Vec3[p.passes()];
            for (int i = 0; i < dirs.length; i++) dirs[i] = new Vec3(p.dirs()[3 * i], p.dirs()[3 * i + 1], p.dirs()[3 * i + 2]).normalize();
            STRIKES.add(new Strike(p.player(), p.sword(), p.target(), p.start(), dirs, p.lit(), new boolean[dirs.length + 1]));
        });
        event.register(SwordArrayPayload.Throw.TYPE, (p, ctx) ->
                THROWS.add(new Thrown(p.player(), p.target(), p.start(), new Vec3(p.x(), p.y(), p.z()), new boolean[2])));
        event.register(SwordArrayPayload.Finale.TYPE, (p, ctx) ->
                FINALES.add(new Finale(p.player(), p.target(), p.start(), p.seed(), new boolean[SwordFlight.PHANTOMS + 8])));
    }
    @SubscribeEvent static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        ACTIVE.clear(); STRIKES.clear(); THROWS.clear(); FINALES.clear(); LAST_SEEN.clear();
    }

    // ---------------------------------------------------------------- 查询

    /** 这名玩家身后激活着几把剑。 */
    public static int lit(UUID player, double now) {
        long[] until = ACTIVE.get(player);
        if (until == null) return 0;
        int n = 0;
        for (long u : until) if (u > now) n++;
        return n;
    }
    private static boolean lit(UUID player, int sword, double now) {
        long[] until = ACTIVE.get(player);
        return until != null && until[sword] > now;
    }
    /** 场景检查用。 */
    public static int drawnFan() { return drawnFan; }
    public static int drawnFlying() { return drawnFlying; }
    public static int drawnPhantoms() { return drawnPhantoms; }
    /** 场景检查用：这名玩家现在有几把剑飞在外面（追击、掷剑、万剑归宗）。 */
    public static int flying(UUID player, double now) {
        int n = 0;
        for (var s : STRIKES) if (s.player.equals(player) && now >= s.start && now < s.end()) n++;
        for (var t : THROWS) if (t.player.equals(player) && now >= t.start && now < t.start + SwordFlight.THROW_HOME) n++;
        for (var f : FINALES) if (f.player.equals(player) && now >= f.start && now < f.end()) n += SwordFlight.FAN;
        return n;
    }
    /** 场景检查用：正在放万剑归宗。 */
    public static boolean finale(UUID player, double now) { return finaleAge(player, now) >= 0; }
    /** 场景检查用：万剑归宗放了多少刻；没在放时为 -1。 */
    public static double finaleAge(UUID player, double now) {
        for (var f : FINALES) if (f.player.equals(player) && now >= f.start && now < f.end()) return now - f.start;
        return -1;
    }

    // ---------------------------------------------------------------- 手里的剑：激活刻度

    /** 剑脊上五段刻度的中点（网格单位，从剑根往剑尖），与 tools/jinghong_sword.py 的 GAUGE 对应。 */
    private static final float[] GAUGE_AT = {5.07f, 8.45f, 11.84f, 15.22f, 18.61f};

    /**
     * 手里那把剑的剑脊上亮几段刻度，就是身后激活了几把（第一人称看不到身后）；五把都亮时整条剑脊连同剑尖一起亮，并缓缓呼吸。
     * 第一人称多半只看得到剑的侧棱，所以每段亮着的刻度上再各点一颗朝着镜头的星芒，任何角度都数得清。
     * 由 {@code LongswordClient} 在画手里的剑之后调用（第一人称、第三人称各一次）。
     */
    public static void gauge(LongswordRig rig, LongswordPayload p, LongswordRig.Sample sample, PoseStack pose, SubmitNodeCollector collector,
                             boolean firstPerson, double now, boolean shown) {
        if (!shown || rig == null || p == null || p.variant() != JinghongSwordItem.WEAPON.id()) return;
        int n = Math.min(SwordFlight.FAN, lit(p.player(), now));
        if (n <= 0) return;
        boolean full = n == SwordFlight.FAN;
        float strength = full ? .8f + .2f * (float) Math.sin(now * .25) : .9f;
        rig.submitGlow(sample, pose, collector, firstPerson, strength, LIGHT, GAUGE[n]);
        if (!firstPerson) return;
        // 星芒和手里的剑在同一个坐标里画（骨架坐标，像素）；方片的两条边取镜头坐标的 X、Y 换回骨架坐标，
        // 所以不管剑怎么转，星芒都正对镜头。
        var back = new Matrix4f(pose.last().pose()).invert();
        var out = ShaderPackOverlay.item(collector, true);
        for (int k = 0; k < n; k++) {
            var at = LongswordRig.onWeapon(sample, new Vector3f(0, GAUGE_AT[k], 0));
            // 大小按骨架坐标（像素）给，只取镜头坐标的方向：手部渲染的根坐标带不带缩放都不影响
            float size = (full ? 1.0f : .85f) * (1 + .12f * (float) Math.sin(now * .3 + k * 1.7));
            var x = back.transformDirection(new Vector3f(1, 0, 0)).normalize(size);
            var y = back.transformDirection(new Vector3f(0, 1, 0)).normalize(size);
            int color = VfxParts.argb(full ? 1 : .9f, LIGHT);
            var c = new Vec3(at.x, at.y, at.z);
            var dx = new Vec3(x.x, x.y, x.z);
            var dy = new Vec3(y.x, y.y, y.z);
            // 两面都画：手部渲染的坐标朝向说不准，单面的方片可能正好被剔掉
            out.submitCustomGeometry(pose, STAR, (pp, buffer) -> {
                flatQuad(pp, buffer, c, dx, dy, color);
                flatQuad(pp, buffer, c, dy, dx, color);
            });
        }
    }

    // ---------------------------------------------------------------- 画

    @SubscribeEvent(priority = EventPriority.LOWEST) static void submit(SubmitCustomGeometryEvent event) {
        var mc = Minecraft.getInstance();
        var level = mc.level;
        if (level == null) return;
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        double now = level.getGameTime() + partial;
        STRIKES.removeIf(s -> now >= s.end() + 1);
        THROWS.removeIf(t -> now >= t.start + SwordFlight.THROW_HOME + 1);
        FINALES.removeIf(f -> now >= f.end() + 1);
        var rig = LongswordRig.get(JinghongSwordItem.WEAPON.id(), false);
        if (rig == null) return;
        boolean shadow = Dissolves.shadowPass();
        var camera = event.getLevelRenderState().cameraRenderState;
        var view = VfxParts.View.of(camera);
        PoseStack pose = event.getPoseStack();
        SubmitNodeCollector raw = event.getSubmitNodeCollector(), overlay = ShaderPackOverlay.collector(raw);
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        int fan = 0, flying = 0, phantoms = 0;
        // 身后一扇：持惊鸿、在持剑动作里的玩家；正飞在外面的那几把由飞剑画。
        for (var player : level.players()) {
            var p = LongswordClient.pose(player.getUUID());
            if (p == null || p.variant() != JinghongSwordItem.WEAPON.id() || player.isInvisible() || player.isSpectator()) continue;
            if (self(player) && mc.options.getCameraType().isFirstPerson()) continue;
            float unfold = unfold(p, now);
            if (unfold <= .01f) continue;
            for (int i = 0; i < SwordFlight.FAN; i++) {
                if (away(player.getUUID(), i, now)) continue;
                var slot = slot(player, i, partial, now, unfold);
                float glow = lit(player.getUUID(), i, now) ? .75f + .25f * (float) Math.sin(now * .2 + i) : 0;
                draw(rig, pose, raw, overlay, view, level, new Pose(slot.center, slot.dir, slot.normal, slot.scale, glow, false), shadow);
                fan++;
            }
        }
        for (var s : STRIKES) {
            var owner = level.getPlayerByUUID(s.player);
            if (owner == null || now < s.start) continue;
            var at = strike(s, owner, level, partial, now);
            if (at != null) { draw(rig, pose, raw, overlay, view, level, at, shadow); flying++; }
        }
        for (var t : THROWS) {
            var owner = level.getPlayerByUUID(t.player);
            if (owner == null || now < t.start) continue;
            var at = thrown(t, owner, level, partial, now);
            if (at != null) { draw(rig, pose, raw, overlay, view, level, at, shadow); flying++; }
        }
        for (var f : FINALES) {
            var owner = level.getPlayerByUUID(f.player);
            if (owner == null || now < f.start) continue;
            flying += finale(f, owner, level, partial, now, rig, pose, raw, overlay, view, shadow);
            phantoms += phantoms(f, owner, level, partial, now, rig, pose, overlay, view, shadow);
        }
        pose.popPose();
        if (!shadow) { drawnFan = fan; drawnFlying = flying; drawnPhantoms = phantoms; }
    }

    private static boolean self(Player player) {
        var mc = Minecraft.getInstance();
        return mc.getCameraEntity() == player;
    }

    /** 拔剑时剑阵在身后展开，收剑时合拢：0 合拢不见，1 完全展开。 */
    private static float unfold(LongswordPayload p, double now) {
        double age = now - p.started();
        if ("draw".equals(p.move())) return smooth((float) ((age - 4) / 12));
        if (p.sheathing()) return 1 - smooth((float) (age / 12));
        return 1;
    }

    /** 这把剑正飞在外面（追击、万剑归宗），身后那一格空着。 */
    private static boolean away(UUID player, int sword, double now) {
        for (var s : STRIKES) if (s.player.equals(player) && s.sword == sword && now >= s.start && now < s.end()) return true;
        for (var f : FINALES) if (f.player.equals(player) && now >= f.start && now < f.end()) return true;
        return false;
    }

    /**
     * 身后的第 {@code i} 把剑：五把剑柄沿背后一道半径 0.5 格的圆弧排开（圆心在腰后），剑尖朝外、左右各隔 32° 一把，像孔雀开屏，略向后仰，剑面朝后；
     * 各自缓缓上下浮动。{@code unfold} 小于 1 时张角和大小一起收拢。
     */
    private static Pose slot(Player player, int i, float partial, double now, float unfold) {
        double yaw = Math.toRadians(Mth.rotLerp(partial, player.yBodyRotO, player.yBodyRot));
        var forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        var right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        var back = forward.scale(-1);
        double height = player.isCrouching() ? .62 : .88;
        var anchor = player.getPosition(partial).add(0, height, 0).add(back.scale(.42));
        double a = Math.toRadians((i - 2) * 32 * unfold);
        var dir = UP.scale(Math.cos(a)).add(right.scale(Math.sin(a))).add(back.scale(.28)).normalize();
        double bob = .035 * Math.sin(now * .09 + i * 1.3);
        float scale = SCALE * unfold;
        // 剑柄彼此隔开约 0.28 格，不挤成一团（试玩反馈）
        var grip = anchor.add(dir.scale(.5)).add(0, bob, 0);
        return new Pose(grip.add(dir.scale(CENTER * scale)), dir, back, scale, 0, false);
    }

    /** 目标此刻的中心；不在了就用最后一次看到的位置；从没看到过（还没加载）时为 null。 */
    private static Vec3 target(Level level, int id, float partial) {
        Entity e = id < 0 ? null : level.getEntity(id);
        if (e != null && !e.isRemoved()) {
            var c = e.getPosition(partial).add(0, e.getBbHeight() / 2, 0);
            LAST_SEEN.put(id, c);
            if (LAST_SEEN.size() > 64) LAST_SEEN.clear();
            return c;
        }
        return LAST_SEEN.get(id);
    }

    /** 掷剑、回手时手的位置：第一人称是画面右下方，第三人称是右手前方。 */
    private static Vec3 hand(Player owner, float partial) {
        var eye = owner.getEyePosition(partial);
        var look = owner.getViewVector(partial);
        var right = look.cross(UP);
        right = right.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : right.normalize();
        if (self(owner) && Minecraft.getInstance().options.getCameraType().isFirstPerson())
            return eye.add(look.scale(.55)).add(right.scale(.28)).add(0, -.24, 0);
        return eye.add(look.scale(.5)).add(right.scale(.34)).add(0, -.42, 0);
    }

    /** 闪回后在玩家头顶出现的位置（五把剑左右错开）。 */
    private static Vec3 above(Player owner, int sword, float partial) {
        double yaw = Math.toRadians(Mth.rotLerp(partial, owner.yBodyRotO, owner.yBodyRot));
        var right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        return owner.getEyePosition(partial).add(0, 1.5, 0).add(right.scale((sword - 2) * .32));
    }

    /** 一次追击此刻的样子；还没出发、已经回位或正好闪回的那一刻为 null。 */
    private static Pose strike(Strike s, Player owner, Level level, float partial, double now) {
        double t = now - s.start;
        var slot = slot(owner, s.sword, partial, now, 1);
        var target = target(level, s.target, partial);
        if (target == null) target = owner.getEyePosition(partial).add(owner.getViewVector(partial).scale(4));
        boolean glow = s.lit || lit(s.player, s.sword, now);
        float g = glow ? 1 : 0;
        var top = slot.center.add(UP.scale(LIFT));
        if (t < SwordFlight.RISE) {
            float u = easeOut((float) (t / SwordFlight.RISE));
            return new Pose(lerp(slot.center, top, u), slerp(slot.dir, UP, u), slot.normal, SCALE, g, false);
        }
        int n = s.passes();
        for (int k = 0; k < n; k++) {
            var d = s.dirs[k];
            double hit = SwordFlight.hit(k);
            var start = target.subtract(d.scale(APPROACH));
            double t0 = k == 0 ? SwordFlight.RISE : SwordFlight.hit(k - 1) + 1;
            var from = k == 0 ? top : target.add(s.dirs[k - 1].scale(OVERSHOOT));
            var v0 = k == 0 ? UP : s.dirs[k - 1];
            if (t < hit - 1) {
                // 赶到这一段的出击点：曲线进来，末端顺着穿刺方向
                double u = (t - t0) / (hit - 1 - t0);
                double len = from.distanceTo(start);
                Vec3 p1 = from.add(v0.scale(len * .4)), p2 = start.subtract(d.scale(len * .4));
                return new Pose(bezier(from, p1, p2, start, u), bezierTangent(from, p1, p2, start, u), side(d), SCALE, g, true);
            }
            if (t < hit) return new Pose(lerp(start, target, (float) (t - (hit - 1))), d, side(d), SCALE, g, true);
            if (t < hit + 1) {
                if (!s.fx[k]) { s.fx[k] = true; spark(level, target, glow); }
                return new Pose(target.add(d.scale(OVERSHOOT * easeOut((float) (t - hit)))), d, side(d), SCALE, g, true);
            }
        }
        var last = s.dirs[n - 1];
        var exit = target.add(last.scale(OVERSHOOT));
        double blink = SwordFlight.blink(n);
        if (t < blink) {
            float f = (float) ((blink - t) / (blink - SwordFlight.hit(n - 1) - 1));
            return new Pose(exit, last, side(last), SCALE * Math.max(.05f, f), g, false);
        }
        if (!s.fx[n]) { s.fx[n] = true; puff(level, exit); puff(level, above(owner, s.sword, partial)); }
        if (t < s.end() - s.start) {
            float u = smooth((float) ((t - blink) / SwordFlight.HOME));
            var from = above(owner, s.sword, partial);
            float pop = Math.min(1, (float) (t - blink) * 2);
            return new Pose(lerp(from, slot.center, u), slerp(UP, slot.dir, u), slot.normal, SCALE * pop, g, false);
        }
        return null;
    }

    /** 掷出去的那把：笔直飞向目标、穿过、在那里掉头，剑尖仍朝外倒着飞回手里。 */
    private static Pose thrown(Thrown t, Player owner, Level level, float partial, double now) {
        double age = now - t.start;
        var hand = hand(owner, partial);
        var target = t.target >= 0 ? target(level, t.target, partial) : t.miss;
        if (target == null) target = t.miss;
        var out = target.subtract(hand);
        var dir = out.lengthSqr() < 1e-6 ? owner.getViewVector(partial) : out.normalize();
        if (age < SwordFlight.THROW_HIT)
            return new Pose(lerp(hand, target, (float) (age / SwordFlight.THROW_HIT)), dir, side(dir), HAND_SCALE, 0, true);
        var turn = target.add(dir.scale(.9));
        if (age < SwordFlight.THROW_TURN) {
            if (!t.fx[0] && t.target >= 0) { t.fx[0] = true; spark(level, target, false); }
            return new Pose(lerp(target, turn, easeOut((float) (age - SwordFlight.THROW_HIT))), dir, side(dir), HAND_SCALE, 0, false);
        }
        if (age < SwordFlight.THROW_HOME) {
            float u = smooth((float) ((age - SwordFlight.THROW_TURN) / (SwordFlight.THROW_HOME - SwordFlight.THROW_TURN)));
            return new Pose(lerp(turn, hand, u), dir, side(dir), HAND_SCALE, 0, true);
        }
        return null;
    }

    /** 万剑归宗里的五把真剑：升起 → 在目标头顶结成剑环、剑尖朝下转 → 一齐贯下 → 停在目标身上 → 闪回、落回原位。画了几把。 */
    private static int finale(Finale f, Player owner, Level level, float partial, double now, LongswordRig rig, PoseStack pose,
                              SubmitNodeCollector raw, SubmitNodeCollector overlay, VfxParts.View view, boolean shadow) {
        double t = now - f.start;
        var target = target(level, f.target, partial);
        if (target == null) return 0;
        var center = target.add(0, SwordFlight.RING_HEIGHT, 0);
        int drawn = 0;
        for (int j = 0; j < SwordFlight.FAN; j++) {
            var slot = slot(owner, j, partial, now, 1);
            var top = slot.center.add(UP.scale(LIFT));
            double theta = Math.toRadians(j * 72 + t * 6);
            var ring = center.add(Math.cos(theta) * SwordFlight.RING_RADIUS, 0, Math.sin(theta) * SwordFlight.RING_RADIUS);
            var down = target.subtract(ring).normalize();
            Pose at;
            if (t < SwordFlight.RISE) {
                float u = easeOut((float) (t / SwordFlight.RISE));
                at = new Pose(lerp(slot.center, top, u), slerp(slot.dir, UP, u), slot.normal, SCALE, 1, false);
            } else if (t < SwordFlight.FINALE_GATHER) {
                double u = (t - SwordFlight.RISE) / (SwordFlight.FINALE_GATHER - SwordFlight.RISE);
                double len = top.distanceTo(ring);
                Vec3 p1 = top.add(UP.scale(len * .5)), p2 = ring.subtract(down.scale(len * .3));
                float grow = 1 + (RING_GROW - 1) * smooth((float) u);
                at = new Pose(bezier(top, p1, p2, ring, smooth((float) u)), slerp(UP, down, smooth((float) u)), side(down), SCALE * grow, 1, true);
            } else if (t < SwordFlight.PLUNGE) {
                at = new Pose(ring.add(0, .06 * Math.sin(t * .5 + j), 0), down, side(down), SCALE * RING_GROW, 1, false);
            } else if (t < SwordFlight.PLUNGE_HIT) {
                float u = (float) ((t - SwordFlight.PLUNGE) / (SwordFlight.PLUNGE_HIT - SwordFlight.PLUNGE));
                at = new Pose(lerp(ring, target, u * u), down, side(down), SCALE * RING_GROW, 1, true);
            } else if (t < SwordFlight.FINALE_BLINK) {
                if (!f.fx[SwordFlight.PHANTOMS]) { f.fx[SwordFlight.PHANTOMS] = true; impact(level, target); }
                // 放大的剑贯在目标身上：剑尖穿出目标下方一点
                at = new Pose(target.add(down.scale(-CENTER * SCALE * RING_GROW * .6)), down, side(down), SCALE * RING_GROW, 1, false);
            } else if (t < SwordFlight.FINALE_HOME) {
                if (!f.fx[SwordFlight.PHANTOMS + 1 + j]) { f.fx[SwordFlight.PHANTOMS + 1 + j] = true; puff(level, target); puff(level, above(owner, j, partial)); }
                float u = smooth((float) ((t - SwordFlight.FINALE_BLINK) / SwordFlight.HOME));
                float pop = Math.min(1, (float) (t - SwordFlight.FINALE_BLINK) * 2);
                at = new Pose(lerp(above(owner, j, partial), slot.center, u), slerp(UP, slot.dir, u), slot.normal, SCALE * pop, 1, false);
            } else continue;
            draw(rig, pose, raw, overlay, view, level, at, shadow);
            drawn++;
        }
        if (!shadow) circle(f, center, target, t, pose, overlay, view);
        return drawn;
    }

    /** 剑环下的法阵、剑落地处的冲击光环。 */
    private static void circle(Finale f, Vec3 center, Vec3 target, double t, PoseStack pose, SubmitNodeCollector overlay, VfxParts.View view) {
        float in = smooth((float) ((t - SwordFlight.RISE) / 5)), out = 1 - smooth((float) ((t - SwordFlight.PLUNGE_HIT) / 4));
        float a = in * out;
        if (a > .01f) {
            double spin = Math.toRadians(t * 4);
            float r = 3.1f;
            var x = new Vec3(Math.cos(spin) * r, 0, Math.sin(spin) * r);
            var z = new Vec3(-Math.sin(spin) * r, 0, Math.cos(spin) * r);
            var c = center.add(0, -.15, 0);
            overlay.submitCustomGeometry(pose, CIRCLE, (p, buffer) -> flatQuad(p, buffer, c, x, z, VfxParts.argb(.85f * a, LIGHT)));
            overlay.submitCustomGeometry(pose, HALO, (p, buffer) -> VfxParts.sprite(p, buffer, view, c, 4.5f, 0, VfxParts.argb(.25f * a, RIM)));
        }
        double since = t - SwordFlight.PLUNGE_HIT;
        if (since >= 0 && since < 10) {
            float u = (float) (since / 10);
            float r = .8f + 4.2f * easeOut(u);
            var c = target.add(0, -.6, 0);
            Vec3 x = new Vec3(r, 0, 0), z = new Vec3(0, 0, r);
            overlay.submitCustomGeometry(pose, RING, (p, buffer) -> flatQuad(p, buffer, c, x, z, VfxParts.argb(.9f * (1 - u), LIGHT)));
            overlay.submitCustomGeometry(pose, STAR, (p, buffer) -> VfxParts.sprite(p, buffer, view, target, 5.5f * (1 - u * .5f), 0,
                    VfxParts.argb(1 - u, LIGHT)));
        }
    }

    /** 虚影剑：从目标四周的半球面上依次显现，剑尖朝着目标，随即落下贯入。只画发光层，像剑气凝成的剑。画了几把。 */
    private static int phantoms(Finale f, Player owner, Level level, float partial, double now, LongswordRig rig, PoseStack pose,
                                SubmitNodeCollector overlay, VfxParts.View view, boolean shadow) {
        if (shadow) return 0;
        double t = now - f.start;
        var target = target(level, f.target, partial);
        if (target == null) return 0;
        double offset = (f.seed & 0xFFFF) / 65536.0;
        int drawn = 0;
        for (int j = 0; j < SwordFlight.PHANTOMS; j++) {
            double born = SwordFlight.phantomBorn(j), shown = SwordFlight.PHANTOM_SHOW;
            double age = t - born;
            if (age < 0 || age > shown + SwordFlight.PHANTOM_FALL) continue;
            double az = offset * Math.PI * 2 + j * 2.399963;
            double el = Math.toRadians(22 + 55 * frac(j * .618034 + offset));
            var from = target.add(new Vec3(Math.cos(el) * Math.cos(az), Math.sin(el), Math.cos(el) * Math.sin(az)).scale(7.5));
            var dir = target.subtract(from).normalize();
            float alpha; Vec3 at;
            if (age < shown) { alpha = smooth((float) (age / shown)); at = from; }
            else {
                float u = (float) ((age - shown) / SwordFlight.PHANTOM_FALL);
                alpha = 1; at = lerp(from, target, u * u);
                if (u > .92f && !f.fx[j]) { f.fx[j] = true; level.addParticle(ParticleTypes.END_ROD, target.x, target.y, target.z, 0, .02, 0); }
            }
            var mesh = mesh(at, dir, side(dir), SCALE * PHANTOM_GROW);
            rig.submitGlowLoose(pose, overlay, mesh, .85f * alpha, LIGHT, GLOW);
            float a = alpha;
            var c = at;
            overlay.submitCustomGeometry(pose, HALO, (p, buffer) -> VfxParts.sprite(p, buffer, view, c, .5f, 0, VfxParts.argb(.18f * a, RIM)));
            if (age >= shown) {
                var tail = at.subtract(dir.scale(1.6));
                overlay.submitCustomGeometry(pose, STREAK, (p, buffer) -> VfxParts.streak(p, buffer, view, tail, c, .22f, VfxParts.argb(.6f, LIGHT)));
            }
            drawn++;
        }
        return drawn;
    }

    /** 画一把剑：网格（受世界光照，发光时全亮），发光层与柔光，飞得快时身后拖一道光。 */
    private static void draw(LongswordRig rig, PoseStack pose, SubmitNodeCollector raw, SubmitNodeCollector overlay, VfxParts.View view,
                             Level level, Pose at, boolean shadow) {
        if (at.scale <= 1e-4f) return;
        var mesh = mesh(at.center, at.dir, at.normal, at.scale);
        int light = at.glow > 0 ? LightCoordsUtil.FULL_BRIGHT : LevelRenderer.getLightCoords(level, BlockPos.containing(at.center));
        rig.submitLoose(pose, raw, light, mesh, Map.of());
        if (shadow) return;
        if (at.glow > 0) {
            rig.submitGlowLoose(pose, overlay, mesh, .9f * at.glow, LIGHT, GLOW);
            var c = at.center;
            float a = at.glow;
            overlay.submitCustomGeometry(pose, HALO, (p, buffer) -> VfxParts.sprite(p, buffer, view, c, .55f, 0, VfxParts.argb(.2f * a, RIM)));
        }
        if (at.streak) {
            var tip = at.center;
            var tail = at.center.subtract(at.dir.scale(2.2));
            int color = at.glow > 0 ? VfxParts.argb(.75f, LIGHT) : VfxParts.argb(.5f, 0x9FE8FF);
            overlay.submitCustomGeometry(pose, STREAK, (p, buffer) -> VfxParts.streak(p, buffer, view, tail, tip, .3f, color));
        }
    }

    /** 网格坐标 → 世界：剑的中点在 {@code center}，剑尖朝 {@code dir}，剑面法线朝 {@code normal}（与 dir 正交化）。 */
    private static Matrix4f mesh(Vec3 center, Vec3 dir, Vec3 normal, float scale) {
        var y = new Vector3f((float) dir.x, (float) dir.y, (float) dir.z).normalize();
        var z = new Vector3f((float) normal.x, (float) normal.y, (float) normal.z);
        z.sub(new Vector3f(y).mul(z.dot(y)));
        if (z.lengthSquared() < 1e-6f) z.set(Math.abs(y.y) < .9f ? new Vector3f(0, 1, 0) : new Vector3f(1, 0, 0)).sub(new Vector3f(y).mul(y.y));
        z.normalize();
        var x = new Vector3f(y).cross(z).normalize();
        return new Matrix4f().translation((float) center.x, (float) center.y, (float) center.z)
                .mul(new Matrix4f(x.x, x.y, x.z, 0, y.x, y.y, y.z, 0, z.x, z.y, z.z, 0, 0, 0, 0, 1))
                .scale(scale).translate(0, -CENTER, 0);
    }

    /** 飞剑的剑面朝向：剑面竖着（法线水平），看上去像劈开空气。 */
    private static Vec3 side(Vec3 dir) {
        var n = dir.cross(UP);
        return n.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : n.normalize();
    }

    private static void flatQuad(PoseStack.Pose p, com.mojang.blaze3d.vertex.VertexConsumer buffer, Vec3 c, Vec3 x, Vec3 z, int color) {
        VfxParts.vertex(p, buffer, c.subtract(x).subtract(z), 0, 0, color);
        VfxParts.vertex(p, buffer, c.add(x).subtract(z), 1, 0, color);
        VfxParts.vertex(p, buffer, c.add(x).add(z), 1, 1, color);
        VfxParts.vertex(p, buffer, c.subtract(x).add(z), 0, 1, color);
    }

    // ---------------------------------------------------------------- 粒子

    /** 穿过目标时迸出的几点火星；激活的剑是附魔色的。 */
    private static void spark(Level level, Vec3 at, boolean lit) {
        for (int i = 0; i < 3; i++) {
            double a = level.getRandom().nextDouble() * Math.PI * 2, b = (level.getRandom().nextDouble() - .5) * .3;
            level.addParticle(lit ? ParticleTypes.ENCHANTED_HIT : ParticleTypes.CRIT, at.x, at.y, at.z, Math.cos(a) * .3, b, Math.sin(a) * .3);
        }
    }
    /** 闪回：消失、出现的地方各一点微光。 */
    private static void puff(Level level, Vec3 at) {
        for (int i = 0; i < 2; i++) {
            double a = level.getRandom().nextDouble() * Math.PI * 2;
            level.addParticle(ParticleTypes.END_ROD, at.x, at.y, at.z, Math.cos(a) * .03, .01, Math.sin(a) * .03);
        }
    }
    /** 万剑归宗五剑贯下：贴地一圈。 */
    private static void impact(Level level, Vec3 at) {
        for (int i = 0; i < 16; i++) {
            double a = Math.PI * 2 * i / 16;
            level.addParticle(ParticleTypes.END_ROD, at.x, at.y - .4, at.z, Math.cos(a) * .3, .04, Math.sin(a) * .3);
        }
    }

    // ---------------------------------------------------------------- 数学

    private static float smooth(float u) { u = Mth.clamp(u, 0, 1); return u * u * (3 - 2 * u); }
    private static float easeOut(float u) { u = Mth.clamp(u, 0, 1); return 1 - (1 - u) * (1 - u); }
    private static double frac(double v) { return v - Math.floor(v); }
    private static Vec3 lerp(Vec3 a, Vec3 b, float u) { return a.add(b.subtract(a).scale(u)); }
    private static Vec3 slerp(Vec3 a, Vec3 b, float u) {
        var v = a.scale(1 - u).add(b.scale(u));
        return v.lengthSqr() < 1e-6 ? b : v.normalize();
    }
    private static Vec3 bezier(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double u) {
        u = Mth.clamp(u, 0, 1);
        double v = 1 - u;
        return p0.scale(v * v * v).add(p1.scale(3 * v * v * u)).add(p2.scale(3 * v * u * u)).add(p3.scale(u * u * u));
    }
    private static Vec3 bezierTangent(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double u) {
        u = Mth.clamp(u, 0, 1);
        double v = 1 - u;
        var d = p1.subtract(p0).scale(3 * v * v).add(p2.subtract(p1).scale(6 * v * u)).add(p3.subtract(p2).scale(3 * u * u));
        return d.lengthSqr() < 1e-8 ? p3.subtract(p0).normalize() : d.normalize();
    }
}
