package dev.logos.dimension.client.vfx;

import static dev.logos.dimension.client.vfx.RailgunDraw.*;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.client.VfxParts;
import dev.logos.dimension.economy.Railgun;
import java.util.function.IntFunction;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 蓄力：拇指把硬币弹上去，硬币翻转着升到一格半高再落回拳前，第 {@link Railgun#FIRE_TICK} 刻正好停在拳头前面。
 * 同时雷电往身上和手上聚：身上爬着细电弧，拳头和前臂绕着电流环（参考动画里拳头外那一圈电），
 * 四周的电火花往拳头收拢，拳头的蓝光越来越亮；硬币落到拳前时，拳头和硬币之间连上电弧。
 * 松手作罢时硬币继续落下、电光散掉。
 */
final class RailgunCharge {
    private static final float F = Railgun.FIRE_TICK;
    /** 硬币升起的最大高度（格）、翻转速度（弧度每刻）、半径。 */
    private static final float TOSS = 1.5f, SPIN = 1.15f, COIN_RADIUS = .15f;
    private static final int GLOW_BLUE = 0x5AA8FF, SPARK = 0x9FD8FF;

    private final int entity;
    private final boolean offhand;
    private final long born;
    private final int seed;
    private int idle;
    /** 作罢时的年龄（刻），没作罢时为 -1。 */
    private float cancelled = -1;

    RailgunCharge(Level level, Entity e, boolean offhand) {
        this.entity = e.getId();
        this.offhand = offhand;
        this.born = level.getGameTime();
        this.seed = level.getRandom().nextInt();
        Vec3 at = e.getEyePosition();
        level.playLocalSound(at.x, at.y, at.z, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, .55f, 2f, false);
        level.playLocalSound(at.x, at.y, at.z, SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, .35f, 2f, false);
    }

    void cancel(Level level) {
        if (cancelled < 0) cancelled = level.getGameTime() - born;
    }

    /** 每刻；返回 false 时丢掉。 */
    boolean tick(Level level) {
        Entity e = level.getEntity(entity);
        long age = level.getGameTime() - born;
        if (e == null || !e.isAlive()) return false;
        if (cancelled >= 0) return age - cancelled < 24;
        boolean using = e instanceof LivingEntity l && l.isUsingItem() && Railgun.coin(l.getUseItem());
        idle = using ? 0 : idle + 1;
        // 换了手里的东西、死了或没等到发射包：当作罢
        if (age > 4 && idle > 3 || age > F + 12) cancel(level);
        if (age % 4 == 0 && level.getRandom().nextFloat() < .4f + .5f * age / F) {
            Vec3 at = e.getEyePosition();
            level.playLocalSound(at.x, at.y, at.z, SoundEvents.FIRE_AMBIENT, SoundSource.PLAYERS, .45f + .4f * age / F,
                    1.8f + level.getRandom().nextFloat() * .2f, false);
        }
        return true;
    }

    void submit(Level level, VfxParts.View view, float pt, PoseStack pose, IntFunction<OrderedSubmitNodeCollector> layers) {
        Entity e = level.getEntity(entity);
        if (e == null) return;
        float t = level.getGameTime() - born + pt;
        float fade = cancelled < 0 ? 1 : 1 - Math.min(1, (t - cancelled) / 8);
        float charge = VfxParts.smooth(0, F, t);
        RailgunClient.Grip g = RailgunClient.grip(e, offhand, pt);
        Vec3 eye = view.cam();
        Vec3 coin = coin(g, t);
        int frame = (int) (t / 2);

        // 硬币本身（不透明，先画）
        float angle = t * SPIN;
        // 翻转轴不完全横着：从侧面看也能看到硬币的正面，而不是一直侧着成一条线
        Vec3 spin = g.right().add(g.up().scale(.45)).add(g.look().scale(.3)).normalize();
        Vec3 n0 = perpendicular(spin, g.right().cross(g.up()));
        Vec3 normal = n0.scale(Math.cos(angle)).add(spin.cross(n0).scale(Math.sin(angle)));
        Vec3 across = normal.cross(spin);
        boolean coinShown = cancelled < 0 || t - cancelled < 22;
        if (coinShown) layers.apply(0).submitCustomGeometry(pose, COIN, (p, buf) ->
                VfxParts.quad(p, buf, coin, spin.scale(COIN_RADIUS), across.scale(COIN_RADIUS), 0xFFFFFFFF));

        layers.apply(2).submitCustomGeometry(pose, GLOW, (p, buf) -> {
            // 硬币翻到正面对着镜头时闪一下
            if (coinShown) {
                float face = (float) Math.abs(normal.dot(eye.subtract(coin).normalize()));
                float glint = (float) Math.pow(face, 10);
                if (glint > .02f) flare(p, buf, view, coin, .14f * glint + .04f, .14f * glint + .04f, 0, VfxParts.argb(.8f * glint, 0xFFFFFF));
                // 远处也认得出硬币：一点淡淡的白光，越到后面越蓝越亮
                flare(p, buf, view, coin, .12f + .1f * charge, .12f + .1f * charge, 0, VfxParts.argb((.25f + .4f * charge) * fade, VfxParts.lerpColor(0xFFFFFF, GLOW_BLUE, charge)));
            }
            // 拳头的蓝光，越蓄越亮；第一人称收小，不糊住画面
            float flicker = .85f + .15f * hash(seed, frame, 41);
            float r = (.1f + .26f * charge) * flicker * (g.own() ? .6f : 1);
            flare(p, buf, view, g.fist(), r, r, 0, VfxParts.argb(.85f * fade * (.35f + .65f * charge), GLOW_BLUE));
            flare(p, buf, view, g.fist(), r * .45f, r * .45f, 0, VfxParts.argb(.7f * fade * charge, 0xDDF2FF));
            if (!g.own()) {
                double scale = e instanceof LivingEntity l ? l.getScale() : 1;
                Vec3 body = e.getPosition(pt).add(0, .95 * scale, 0);
                flare(p, buf, view, body, (float) (.75 * scale), (float) (1.1 * scale), 0, VfxParts.argb(.16f * charge * fade * flicker, GLOW_BLUE));
            }
            // 往拳头收拢的电火花
            int sparks = (int) (3 + 7 * charge);
            float reachOut = g.own() ? .55f : 1.4f;
            for (int i = 0; i < sparks; i++) {
                float period = 6, phase = (t + hash(seed, i, 7) * period) / period;
                int cycle = (int) phase;
                float s = phase - cycle;
                Vec3 dir = randomDir(seed + cycle * 131, i);
                Vec3 head = g.fist().add(dir.scale(reachOut * (1 - s)));
                Vec3 tail = g.fist().add(dir.scale(reachOut * Math.min(1, 1 - s + .22f)));
                VfxParts.streak(p, buf, view, tail, head, .035f, VfxParts.argb(.7f * fade * (float) Math.sin(Math.PI * s), SPARK));
            }
        });

        float thin = g.own() ? .45f : 1;
        layers.apply(2).submitCustomGeometry(pose, BOLT, (p, buf) -> {
            float a = fade * (.45f + .55f * charge);
            // 拳头和前臂外绕着的电流环
            int loops = 1 + (int) (charge * 2.5f);
            for (int i = 0; i < loops; i++) {
                if (hash(seed + frame * 17, i, 3) < .2f) continue;
                Vec3[] loop = loop(g, seed + frame * 31 + i * 7, i);
                ribbon(p, buf, loop, eye, .022f * thin, VfxParts.argb(a, ARC));
                ribbon(p, buf, loop, eye, .009f * thin, VfxParts.argb(a, ARC_HOT));
            }
            // 身上爬的细电弧；第一人称看不到自己的身子，不画
            if (!g.own()) {
                int arcs = (int) (2 + 8 * charge);
                double scale = e instanceof LivingEntity l ? l.getScale() : 1;
                Vec3 feet = e.getPosition(pt);
                for (int i = 0; i < arcs; i++) {
                    int s = seed + (frame + i) / 2 * 53 + i * 977;
                    if (hash(s, i, 5) < .25f) continue;
                    double a0 = hash(s, i, 9) * Math.PI * 2, y0 = (.15 + 1.55 * hash(s, i, 11)) * scale;
                    double a1 = a0 + (hash(s, i, 13) - .5) * 2.2, y1 = y0 + (hash(s, i, 17) - .5) * 1.1 * scale;
                    double rad = .34 * scale;
                    Vec3 from = feet.add(Math.cos(a0) * rad, y0, Math.sin(a0) * rad), to = feet.add(Math.cos(a1) * rad, y1, Math.sin(a1) * rad);
                    Vec3[] arc = bolt(from, to, s, .09, .07);
                    ribbon(p, buf, arc, eye, .02f, VfxParts.argb(a * .9f, ARC));
                    ribbon(p, buf, arc, eye, .008f, VfxParts.argb(a, ARC_HOT));
                }
            }
            // 硬币落回来时，拳头和硬币之间连上电弧
            if (cancelled < 0 && t > F - 7) {
                float k = Math.min(1, (t - (F - 7)) / 5);
                for (int i = 0; i < 2; i++) {
                    Vec3[] arc = bolt(g.fist(), coin, seed + frame * 13 + i * 101, .06, .05);
                    ribbon(p, buf, arc, eye, .016f * thin, VfxParts.argb(k, ARC));
                    ribbon(p, buf, arc, eye, .007f * thin, VfxParts.argb(k, ARC_HOT));
                }
            }
        });
    }

    /** 硬币在第 {@code t} 刻的位置：从拇指弹起，抛物线升到 {@link #TOSS} 格，第 F 刻落到拳前；作罢后接着往下掉。 */
    private static Vec3 coin(RailgunClient.Grip g, float t) {
        float s = t / F;
        double height = 4 * TOSS * s * (1 - s) + .05;
        return g.fist().add(g.look().scale(.12 + .3 * Math.min(1, s))).add(0, height, 0);
    }

    /** 绕着前臂（拳头往回 0.1~0.35 格）的一圈不规则电流。 */
    private static Vec3[] loop(RailgunClient.Grip g, int s, int i) {
        Vec3 axis = g.look(), rr = g.right(), uu = g.up();
        Vec3 c = g.fist().subtract(axis.scale(.08 + .22 * hash(s, i, 19)));
        double radius = (.13 + .1 * hash(s, i, 23)) * (g.own() ? .4 : 1);
        double tiltA = (hash(s, i, 29) - .5) * 1.2, tiltB = (hash(s, i, 31) - .5) * 1.2;
        int n = 14;
        Vec3[] pts = new Vec3[n + 1];
        for (int k = 0; k <= n; k++) {
            double a = Math.PI * 2 * k / n + hash(s, i, 37) * 6.3;
            double r = radius * (.8 + .4 * hash(s, k % n, 43));
            double along = Math.cos(a) * tiltA * radius + Math.sin(a) * tiltB * radius + (hash(s, k % n, 47) - .5) * .06;
            pts[k] = c.add(rr.scale(Math.cos(a) * r)).add(uu.scale(Math.sin(a) * r)).add(axis.scale(along));
        }
        return pts;
    }

    private static Vec3 randomDir(int s, int i) {
        double z = hash(s, i, 53) * 2 - 1, a = hash(s, i, 59) * Math.PI * 2, r = Math.sqrt(1 - z * z);
        return new Vec3(Math.cos(a) * r, z, Math.sin(a) * r);
    }
}
