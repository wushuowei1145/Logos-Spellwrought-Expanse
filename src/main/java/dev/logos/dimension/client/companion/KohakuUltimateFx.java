package dev.logos.dimension.client.companion;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.client.ShaderPackOverlay;
import dev.logos.client.VfxParts;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.companion.KohakuSeal;
import dev.logos.dimension.companion.KohakuUltimate;
import dev.logos.dimension.ultimate.UltimatePayloads;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import org.jspecify.annotations.Nullable;

/**
 * 琥珀奥义在世界里的那一半（docs/KOHAKU_COMPANION.md 第 20 节）：看得见她的玩家都看得到，不只是下令的那一个。
 * <ul>
 *   <li>她脚下的炼成阵（吟唱时转开，出手后收起）；</li>
 *   <li>目标脚下半径 7.5 的三层大炼成阵（凝时那一刻转开，凝时结束才收）——用 {@link KohakuCircle}；</li>
 *   <li>掌中的琥珀晶石（下令的玩家看到的是全屏后期里画的那一颗，盖在上面）；</li>
 *   <li>出手那一拍阵心开出的立体星辰花：六片大花瓣、六片小花瓣、一圈冲击环、往外飞的琥珀碎晶；</li>
 *   <li>连携里（交出炼金回流时）一条从阵心流回主人的金色缎带；</li>
 *   <li>被凝时的每一只身上的琥珀晶壳（服务端 {@link KohakuSeal.Payload} 告诉封了多久），放开时碎掉。</li>
 * </ul>
 * 素材全用 Logos 现成的贴图，颜色由顶点给成琥珀色。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class KohakuUltimateFx {
    static final int AMBER = 0xE8A043, LIGHT = 0xFFD98A, WHITE = 0xFFF3D6, DEEP = 0x9A5418;
    /** 她脚下那一阵多大；掌中晶石在世界里多高（半高，格）。 */
    static final float OWN_CIRCLE = 2.2f, GEM = .4f;
    private static final RenderType ROMB = VfxParts.add("charge/romb"), ROMB_UNDER = VfxParts.blend("charge/romb"), FLASH = VfxParts.add("arcane_impact/flash"),
            WAVE = VfxParts.add("charge/shockwave"), STAR = VfxParts.add("arcane_impact/star"), STARRY = VfxParts.add("arcane_charge/circle_04"),
            STREAK = VfxParts.add("force/streak"), GLOW = VfxParts.add("heal/glow"), VEIL = VfxParts.add("arcane_field/veil");

    private record Live(int performer, int owner, Vec3 aim, KohakuUltimate.Beats beats, long start, long seed, boolean relay) {
        /** 这一段在世界里的东西都画完了没有（凝时的大阵比过场长）。 */
        float end() {
            return Math.max(beats.length() + 18, beats.impact() + KohakuUltimate.SEAL_TICKS + 14);
        }
    }

    /** 一只被封住的：从哪一刻开始、到哪一刻（{@code Long.MAX_VALUE} 是被馈赠按住了）、放开的那一刻（没放开是 -1）。 */
    private static final class Shell {
        long start, until, broke = -1;
    }

    private static final List<Live> LIVE = new ArrayList<>();
    private static final Map<Integer, Shell> SHELLS = new HashMap<>();
    private static @Nullable Level shown;

    private KohakuUltimateFx() {}

    @SubscribeEvent
    static void packets(RegisterClientPayloadHandlersEvent event) {
        event.register(KohakuSeal.Payload.TYPE, (p, context) -> context.enqueueWork(() -> seal(p)));
    }

    /** 开演：记下这一段（所有看得见她的客户端都画）。 */
    static void start(UltimatePayloads.Cast cast, KohakuUltimate.Beats beats) {
        Level level = level();
        if (level == null) return;
        LIVE.removeIf(l -> l.performer == cast.performer());
        LIVE.add(new Live(cast.performer(), cast.owner(), cast.aim(), beats, level.getGameTime(), cast.seed(), cast.members() >= 2));
    }

    /** 被打断：她的东西立刻收起来。 */
    static void stop(int performer) {
        LIVE.removeIf(l -> l.performer == performer);
    }

    /** 正在画几段（客户端场景检查）。 */
    public static int live() {
        return LIVE.size();
    }

    /** 身上画着晶壳的有几只（客户端场景检查）。 */
    public static int shells() {
        return SHELLS.size();
    }

    private static void seal(KohakuSeal.Payload p) {
        Level level = level();
        if (level == null) return;
        long now = level.getGameTime();
        Shell s = SHELLS.get(p.entity());
        if (p.ticks() == 0) {
            if (s != null && s.broke < 0) s.broke = now;
            return;
        }
        if (s == null || s.broke >= 0) {
            s = new Shell();
            s.start = now;
            SHELLS.put(p.entity(), s);
        }
        s.until = p.ticks() == KohakuSeal.HELD ? Long.MAX_VALUE : now + p.ticks();
    }

    private static @Nullable Level level() {
        Level level = Minecraft.getInstance().level;
        if (level != shown) {
            LIVE.clear();
            SHELLS.clear();
            shown = level;
        }
        return level;
    }

    /**
     * 掌中晶石在世界里的位置：她左手边、胸前偏上（她这时是 {@code cutin} 那个样子，左手抬到下巴前竖起一指，晶石悬在指尖前上方）。
     */
    static Vec3 gem(Vec3 feet, Vec3 forward, Vec3 right, float height) {
        return feet.add(forward.scale(.5)).subtract(right.scale(.2)).add(0, height * .98, 0);
    }

    /** {@code e} 的掌中晶石：她朝着 {@code aim}（客户端的身体朝向转得慢，不按它）。 */
    static Vec3 gem(Entity e, Vec3 aim, float partial) {
        Vec3 feet = e.getPosition(partial), f = aim.subtract(feet).multiply(1, 0, 1);
        if (f.lengthSqr() < 1e-4) {
            float yaw = e instanceof LivingEntity l ? Mth.rotLerp(partial, l.yBodyRotO, l.yBodyRot) : e.getViewYRot(partial);
            double r = Math.toRadians(yaw);
            f = new Vec3(-Math.sin(r), 0, Math.cos(r));
        }
        f = f.normalize();
        return gem(feet, f, new Vec3(-f.z, 0, f.x), e.getBbHeight());
    }

    // ---------------------------------------------------------------- 画

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void submit(SubmitCustomGeometryEvent event) {
        Minecraft mc = Minecraft.getInstance();
        Level level = level();
        if (level == null || LIVE.isEmpty() && SHELLS.isEmpty()) return;
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float now = level.getGameTime() + pt;
        LIVE.removeIf(l -> now - l.start > l.end());
        CameraRenderState camera = event.getLevelRenderState().cameraRenderState;
        PoseStack pose = event.getPoseStack();
        SubmitNodeCollector out = ShaderPackOverlay.collector(event.getSubmitNodeCollector());
        VfxParts.View view = VfxParts.View.of(camera);
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        for (Live l : LIVE) live(pose, out, camera, view, level, l, now - l.start, now, pt);
        shells(pose, out, view, level, now, pt);
        pose.popPose();
    }

    private static void live(PoseStack pose, SubmitNodeCollector out, CameraRenderState camera, VfxParts.View view, Level level, Live l, float t,
                             float clock, float pt) {
        KohakuUltimate.Beats b = l.beats;
        Entity performer = level.getEntity(l.performer);
        // 她脚下：吟唱时转开，出手后收起
        int own = b.impact() + 10;
        if (performer != null && t < own) KohakuCircle.submit(pose, out, camera, performer.getPosition(pt), OWN_CIRCLE, t, own, false, clock);
        // 目标脚下的大阵：凝时那一刻转开，凝时结束才收
        if (t >= b.stop()) {
            int big = b.impact() - b.stop() + KohakuUltimate.SEAL_TICKS + 10;
            KohakuCircle.submit(pose, out, camera, l.aim, (float) KohakuUltimate.RADIUS, t - b.stop(), big, false, clock);
        }
        // 掌中晶石（下令的玩家看到的是全屏后期里那一颗，盖在上面）
        if (performer != null && t >= b.palm() && t < b.shatter()) crystal(pose, out, view, gem(performer, l.aim, pt), GEM, t - b.palm(), clock, -1);
        if (t >= b.impact() && t < b.impact() + 28) starflower(pose, out, view, l, t - b.impact());
        if (l.relay && t >= b.wipe() - 6) {
            Entity owner = level.getEntity(l.owner);
            if (owner instanceof Player p) ribbon(pose, out, view, l, p.getPosition(pt).add(0, p.getBbHeight() * .55, 0), t - (b.wipe() - 6));
        }
    }

    /** 立体星辰花：阵心冲起六片大花瓣、六片小花瓣，一团白光、一枚星芒，贴地一圈冲击环，琥珀碎晶往外飞。 */
    private static void starflower(PoseStack pose, SubmitNodeCollector out, VfxParts.View view, Live l, float e) {
        float R = (float) KohakuUltimate.RADIUS;
        float open = 1 - (float) Math.pow(1 - VfxParts.smooth(0, 5, e), 3), fade = 1 - VfxParts.smooth(10, 28, e);
        Vec3 c = l.aim.add(0, .05, 0);
        Vec3 up = new Vec3(0, 1, 0);
        out.submitCustomGeometry(pose, WAVE, (p, buf) -> VfxParts.ground(p, buf, c.add(0, .03, 0), 1 + 13 * VfxParts.smooth(0, 14, e), 0,
                VfxParts.argb(.9f * (1 - VfxParts.smooth(4, 14, e)), LIGHT)));
        out.submitCustomGeometry(pose, FLASH, (p, buf) -> VfxParts.ground(p, buf, c.add(0, .04, 0), R * 1.2f * open, 0,
                VfxParts.argb(.7f * (1 - VfxParts.smooth(0, 10, e)), WHITE)));
        out.submitCustomGeometry(pose, STARRY, (p, buf) -> VfxParts.ground(p, buf, c.add(0, .05, 0), R * (.6f + .4f * open), e * .04f,
                VfxParts.argb(.85f * fade, LIGHT)));
        // 花瓣先用半透明的深琥珀垫一遍（白天在浅色地面上，光靠加法只会冲成一片白），再叠发光的
        out.submitCustomGeometry(pose, ROMB_UNDER, (p, buf) -> petals(p, buf, c, up, R, open, e, fade, true));
        out.submitCustomGeometry(pose, ROMB, (p, buf) -> petals(p, buf, c, up, R, open, e, fade, false));
        out.submitCustomGeometry(pose, FLASH, (p, buf) -> VfxParts.sprite(p, buf, view, c.add(0, 1.4, 0), 1.5f + 5 * (1 - VfxParts.smooth(0, 8, e)), 0,
                VfxParts.argb(.9f * (1 - VfxParts.smooth(2, 12, e)), WHITE)));
        out.submitCustomGeometry(pose, STAR, (p, buf) -> VfxParts.sprite(p, buf, view, c.add(0, 1.6, 0), 3.5f * open * fade, e * .05f,
                VfxParts.argb(.9f * fade, LIGHT)));
        shards(pose, out, view, l, c, e);
    }

    /** 两层花瓣：六片大的、六片小的错开 30°，从阵心往外斜着张开。{@code under}：垫底的那一遍（深琥珀）。 */
    private static void petals(PoseStack.Pose p, VertexConsumer buf, Vec3 c, Vec3 up, float R, float open, float e, float fade, boolean under) {
        for (int ring = 0; ring < 2; ring++) {
            float len = R * (ring == 0 ? .42f : .26f) * open, wid = R * (ring == 0 ? .15f : .11f) * open;
            float tilt = (float) Math.toRadians(ring == 0 ? 38 : 62);
            for (int i = 0; i < 6; i++) {
                double a = Math.toRadians(i * 60 + ring * 30 + e * 1.5);
                Vec3 dir = new Vec3(Math.cos(a), 0, Math.sin(a));
                Vec3 axis = dir.scale(Math.cos(tilt)).add(up.scale(Math.sin(tilt)));
                Vec3 side = dir.cross(up);
                Vec3 mid = c.add(0, .2, 0).add(axis.scale(len));
                int color = under ? VfxParts.argb(.45f * fade, DEEP) : VfxParts.argb((ring == 0 ? .85f : .95f) * fade, ring == 0 ? LIGHT : WHITE);
                VfxParts.quad(p, buf, mid, axis.scale(len), side.scale(wid), color);
            }
        }
    }

    private static void shards(PoseStack pose, SubmitNodeCollector out, VfxParts.View view, Live l, Vec3 c, float e) {
        // 往外飞的琥珀碎晶：同一个种子，所有人看到的一样
        out.submitCustomGeometry(pose, ROMB, (p, buf) -> {
            for (int i = 0; i < 28; i++) {
                float h1 = rand(l.seed, i), h2 = rand(l.seed, i + 100), h3 = rand(l.seed, i + 200);
                double a = h1 * Math.PI * 2;
                float speed = .25f + .35f * h2, rise = .12f + .2f * h3;
                Vec3 at = c.add(Math.cos(a) * speed * e, 1 + rise * e - .012 * e * e, Math.sin(a) * speed * e);
                float k = 1 - VfxParts.smooth(12, 26, e);
                if (k > 0) VfxParts.sprite(p, buf, view, at, .22f + .18f * h3, e * (h2 - .5f) * .6f, VfxParts.argb(.95f * k, i % 4 == 0 ? WHITE : AMBER));
            }
        });
    }

    /** 回流的缎带：从阵心沿一道弧流回主人，头先过去，尾巴跟着收。 */
    private static void ribbon(PoseStack pose, SubmitNodeCollector out, VfxParts.View view, Live l, Vec3 to, float e) {
        float head = VfxParts.smooth(0, 12, e), tail = VfxParts.smooth(9, 24, e);
        if (head <= tail) return;
        Vec3 from = l.aim.add(0, 1.2, 0);
        Vec3 mid = from.lerp(to, .5).add(0, 3.2, 0);
        int n = 28;
        out.submitCustomGeometry(pose, GLOW, (p, buf) -> segments(p, buf, view, from, mid, to, n, tail, head, .7f, .35f, AMBER));
        out.submitCustomGeometry(pose, STREAK, (p, buf) -> segments(p, buf, view, from, mid, to, n, tail, head, .28f, .95f, LIGHT));
        out.submitCustomGeometry(pose, STAR, (p, buf) -> {
            for (int i = 0; i < 5; i++) {
                float u = Mth.lerp(i / 5f, tail, head);
                VfxParts.sprite(p, buf, view, bezier(from, mid, to, u), .25f, e * .2f + i, VfxParts.argb(.85f, WHITE));
            }
        });
    }

    private static void segments(PoseStack.Pose p, VertexConsumer buf, VfxParts.View view, Vec3 a, Vec3 m, Vec3 b, int n, float from, float to, float width,
                                 float alpha, int color) {
        for (int i = 0; i < n; i++) {
            float u0 = Mth.lerp(i / (float) n, from, to), u1 = Mth.lerp((i + 1) / (float) n, from, to);
            float k = (i + .5f) / n;
            float taper = Mth.sin(k * Mth.PI);
            VfxParts.streak(p, buf, view, bezier(a, m, b, u0), bezier(a, m, b, u1), width * (.35f + .65f * taper), VfxParts.argb(alpha * taper, color));
        }
    }

    private static Vec3 bezier(Vec3 a, Vec3 m, Vec3 b, float u) {
        return a.lerp(m, u).lerp(m.lerp(b, u), u);
    }

    // ---------------------------------------------------------------- 晶壳

    private static void shells(PoseStack pose, SubmitNodeCollector out, VfxParts.View view, Level level, float now, float pt) {
        for (Iterator<Map.Entry<Integer, Shell>> it = SHELLS.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            Shell s = entry.getValue();
            Entity e = level.getEntity(entry.getKey());
            boolean gone = e == null || !e.isAlive();
            // 放开的包丢了也要收：时间过了一秒还没收到就自己碎掉（被按着的没有期限）
            if (s.broke < 0 && (gone || s.until != Long.MAX_VALUE && now > s.until + 20)) s.broke = (long) now;
            if (s.broke >= 0 && now - s.broke > 8 || e == null) {
                it.remove();
                continue;
            }
            float h = e.getBbHeight();
            Vec3 c = e.getPosition(pt).add(0, h / 2, 0);
            float w = Math.max(e.getBbWidth(), .5f) * .85f;
            float age = now - s.start;
            float k = s.broke >= 0 ? (now - s.broke) / 8 : 0;
            float grow = (1 + .25f * (1 - VfxParts.smooth(0, 4, age))) * (1 + .3f * k);
            float alpha = VfxParts.smooth(0, 3, age) * (1 - k);
            crystal(pose, out, view, c, h * .62f * grow, age, now, w * grow, alpha);
        }
    }

    /**
     * 一颗竖着的六边双锥晶石：{@code center} 正中、半高 {@code half}；{@code width} 是腰围半径（小于 0 时按半高的 0.56）。
     * 晶面是一层发光的薄纱，棱是细光线。
     */
    private static void crystal(PoseStack pose, SubmitNodeCollector out, VfxParts.View view, Vec3 center, float half, float age, float clock,
                                float width) {
        crystal(pose, out, view, center, half, age, clock, width, Math.min(1, VfxParts.smooth(0, 3, age)));
    }

    private static void crystal(PoseStack pose, SubmitNodeCollector out, VfxParts.View view, Vec3 center, float half, float age, float clock,
                                float width, float alpha) {
        if (alpha <= .003f) return;
        float w = width < 0 ? half * .56f : width;
        float spin = clock * .02f;
        Vec3 top = center.add(0, half * 1.05, 0), bottom = center.add(0, -half, 0);
        Vec3[] ring = new Vec3[6];
        for (int i = 0; i < 6; i++) {
            double a = spin + i * Math.PI / 3;
            ring[i] = center.add(Math.cos(a) * w, (i % 2 == 0 ? .12 : -.12) * half, Math.sin(a) * w);
        }
        out.submitCustomGeometry(pose, VEIL, (p, buf) -> {
            for (int i = 0; i < 6; i++) {
                Vec3 a = ring[i], b = ring[(i + 1) % 6];
                int tone = VfxParts.argb(.32f * alpha * (i % 2 == 0 ? 1 : .75f), i % 2 == 0 ? AMBER : LIGHT);
                triangle(p, buf, top, a, b, tone);
                triangle(p, buf, bottom, b, a, VfxParts.argb(.26f * alpha, DEEP));
            }
        });
        out.submitCustomGeometry(pose, STREAK, (p, buf) -> {
            int edge = VfxParts.argb(.75f * alpha, LIGHT);
            float thin = Math.max(.02f, half * .05f);
            for (int i = 0; i < 6; i++) {
                VfxParts.streak(p, buf, view, top, ring[i], thin, edge);
                VfxParts.streak(p, buf, view, bottom, ring[i], thin, edge);
                VfxParts.streak(p, buf, view, ring[i], ring[(i + 1) % 6], thin, edge);
            }
        });
        out.submitCustomGeometry(pose, GLOW, (p, buf) -> VfxParts.sprite(p, buf, view, center, half * 2.4f, 0, VfxParts.argb(.22f * alpha, AMBER)));
    }

    private static void triangle(PoseStack.Pose p, VertexConsumer buf, Vec3 a, Vec3 b, Vec3 c, int color) {
        VfxParts.vertex(p, buf, a, .5f, 0, color);
        VfxParts.vertex(p, buf, b, 0, 1, color);
        VfxParts.vertex(p, buf, c, 1, 1, color);
        VfxParts.vertex(p, buf, c, 1, 1, color);
    }

    private static float rand(long seed, int i) {
        long h = seed * 0x9E3779B97F4A7C15L + i * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (h & 0xFFFF) / 65535f;
    }
}
