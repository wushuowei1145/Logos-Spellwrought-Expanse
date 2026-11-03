package dev.logos.dimension.client.vfx;

import static dev.logos.dimension.client.vfx.RailgunDraw.*;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.client.VfxParts;
import java.util.function.IntFunction;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 发射。射线照 Logos 的物理射线做（一根瞬间打穿的亮线、出口的十字闪光、音爆锥、沿线推开的气浪环、命中处的气浪、
 * 之后断续闪烁的余线），换成橙色、放大约两倍半，另外补上：
 * <ul>
 * <li>雷电：发射瞬间三股电弧螺旋缠着射线往前，之后余线上残留的电弧明灭；</li>
 * <li>出口一圈很大的白色冲击环套住手臂（参考动画第三张），前面再跟一圈小的；</li>
 * <li>射线四周向前飞的橙色熔流；出口横向的淡紫镜头光；</li>
 * <li>角色身上的气浪：从胸口往后掠的音爆锥和压力环、往后吹过身体的风线、脚下贴地扩散的尘环与扬尘。</li>
 * </ul>
 * 第一人称看自己开炮时，出口的闪光与环都推远、收小，不糊住画面。
 */
final class RailgunShot {
    /** 射线尖端穿过全长的刻数；亮线保持到 HOT，到 AFTER 收成余线，余线在 GONE 前闪完。 */
    private static final float SHOOT = 1.5f, HOT = 9, AFTER = 16, GONE = 64, LIFE = 66;
    private static final float COIL_LIFE = 14, RING_LIFE = 13, RING_SPACING = 3.5f;
    private static final int SHEATH = 0xFF6A12, CORE = 0xFFC870, WHITE = 0xFFFBEA, EMBER = 0xFF8A3A, STREAK = 0xFF9A40;
    private static final int SHOCK = 0xF6F8FF, VAPOUR = 0xF2E6DA, AIR_GREY = 0xC8CED6, WIND = 0xE8F0FF, LENS = 0xE6A8FF;

    private final Vec3 muzzle, to, axis, right, up, feet, chest;
    private final float length;
    private final boolean struck, own;
    /** 脚下地面的高度，离脚太远（在空中）时为 NaN。 */
    private final double ground;
    private final int tint;
    private final long born;
    private final int seed, shooter;
    private final boolean offhand;
    /** 脚下的亮度（0.25~1）：灰白的气浪夜里跟着暗下去，不然像自己发光。 */
    private final float lit;

    RailgunShot(Level level, Entity shooter, boolean offhand, Vec3 eye, Vec3 to, boolean struck) {
        RailgunClient.Grip g = shooter == null ? null : RailgunClient.grip(shooter, offhand, 1);
        this.own = g != null && g.own();
        this.muzzle = g == null ? eye : g.fist().add(g.look().scale(own ? .25 : .42));
        this.to = to;
        Vec3 delta = to.subtract(muzzle);
        this.length = (float) delta.length();
        this.axis = length < 1e-4 ? (g == null ? new Vec3(0, 0, 1) : g.look()) : delta.scale(1 / length);
        this.right = perpendicular(axis, new Vec3(0, 1, 0));
        this.up = axis.cross(right);
        double scale = shooter instanceof LivingEntity l ? l.getScale() : 1;
        this.feet = shooter == null ? eye.subtract(0, 1.62, 0) : shooter.position();
        this.chest = feet.add(0, 1.1 * scale, 0);
        this.ground = groundBelow(level, feet);
        this.tint = VfxParts.groundTint(level, feet);
        this.struck = struck;
        this.born = level.getGameTime();
        this.seed = level.getRandom().nextInt();
        this.shooter = shooter == null ? -1 : shooter.getId();
        this.offhand = offhand;
        this.lit = Math.max(.25f, level.getMaxLocalRawBrightness(BlockPos.containing(chest)) / 15f);
        sounds(level);
        particles(level);
    }

    Vec3 muzzle() { return muzzle; }

    int shooter() { return shooter; }

    boolean offhand() { return offhand; }

    float age(Level level, float pt) { return level.getGameTime() - born + pt; }

    boolean tick(Level level) { return level.getGameTime() - born < LIFE; }

    // ---- 绘制 ------------------------------------------------------------------------------------

    void submit(Level level, VfxParts.View view, float pt, PoseStack pose, IntFunction<OrderedSubmitNodeCollector> layers) {
        if (length < .05f) return;
        float t = Math.max(0, level.getGameTime() - born + pt);
        if (t >= GONE) return;
        Vec3 eye = view.cam();
        // 镜头离出口越近（第一人称自己开炮）越是 0：出口的装饰往前推、收小
        float near = (float) Math.min(1, Math.max(0, (eye.distanceTo(muzzle) - .8) / 3));
        layers.apply(1).submitCustomGeometry(pose, AIR, (p, buf) -> air(p, buf, t, near));
        layers.apply(2).submitCustomGeometry(pose, LINE, (p, buf) -> {
            beam(p, buf, t, eye);
            shockEdges(p, buf, t, near, eye);
        });
        layers.apply(2).submitCustomGeometry(pose, BOLT, (p, buf) -> lightning(p, buf, t, eye));
        layers.apply(2).submitCustomGeometry(pose, GLOW, (p, buf) -> glows(p, buf, view, t, near));
        layers.apply(2).submitCustomGeometry(pose, FLASH, (p, buf) -> {
            float flash = flash(t, 9);
            if (flash > 0) {
                Vec3 c = muzzleDressing(near, 1.4);
                float r = Math.min((1.6f + 2.6f * near) * flash, .4f * (float) eye.distanceTo(c));
                flare(p, buf, view, c, r, r, .05f + t * .01f, VfxParts.argb(.95f * (.65f + .35f * near), 0xFFE6C0));
            }
            float hit = struck ? flash(t - SHOOT, 8) : 0;
            if (hit > 0) flare(p, buf, view, impact(), 3.2f * hit, 3.2f * hit, -.12f, VfxParts.argb(.95f, 0xFFE0B0));
        });
    }

    /** 亮线：橙色的鞘、黄白的芯、最中间一根白线；边缘每刻抖一抖像被撕开；之后收细成断续闪烁的橙色余线。 */
    private void beam(PoseStack.Pose p, VertexConsumer buf, float t, Vec3 eye) {
        if (t >= AFTER) {
            afterline(p, buf, eye, t);
            return;
        }
        float reach = Math.min(1, t / SHOOT);
        float tip = length * (1 - (1 - reach) * (1 - reach));
        float k = VfxParts.smooth(HOT, AFTER, t);
        int frame = (int) t;
        float step = 2;
        for (float a = 0; a < tip; a += step) {
            float b = Math.min(tip, a + step);
            int j = (int) (a / step);
            float rag = 1 + .36f * (hash(seed + frame * 7, j, 3) - .5f);
            // 出口那一段从细张开，免得第一人称一上来就是一大片
            float open = Math.min(1, .35f + a / 4);
            Vec3 pa = muzzle.add(axis.scale(a)), pb = muzzle.add(axis.scale(b));
            band(p, buf, pa, pb, eye, (3.2f - 2.9f * k) * rag * open, VfxParts.argb(.7f * (1 - .5f * k), SHEATH));
            band(p, buf, pa, pb, eye, (1.5f - 1.25f * k) * open, VfxParts.argb(.95f, VfxParts.lerpColor(CORE, EMBER, k)));
            band(p, buf, pa, pb, eye, .5f * open, VfxParts.argb(1 - k, WHITE));
        }
    }

    /** 出口两圈冲击环的白边：和 {@link #air} 里的环同心同大，亮而细。 */
    private void shockEdges(PoseStack.Pose p, VertexConsumer buf, float t, float near, Vec3 eye) {
        Vec3 ringAt = muzzleDressing(near, 1.6).subtract(axis.scale(.3 * near));
        if (t < RING_LIFE) {
            float q = t / RING_LIFE, e = easeOut(q);
            lineRing(p, buf, ringAt, axis, .4f + 3.6f * e, .3f, eye, seed + 3, 72, VfxParts.argb((float) Math.pow(1 - q, 1.6) * .9f, SHOCK));
        }
        if (t >= 1 && t < 11) {
            float q = (t - 1) / 10, e = easeOut(q);
            lineRing(p, buf, ringAt.add(axis.scale(1.4 + 1.2 * e)), axis, .3f + 1.6f * e, .22f, eye, seed + 5, 56,
                    VfxParts.argb((1 - q) * (1 - q) * .8f, SHOCK));
        }
    }

    /** 余线：一根细细的橙线断成几截，一截截闪灭。 */
    private void afterline(PoseStack.Pose p, VertexConsumer buf, Vec3 eye, float t) {
        float life = (t - AFTER) / (GONE - AFTER), fade = 1 - life * life;
        int frame = (int) (t / 4.5f);
        float keep = .9f - .75f * life;
        double at = 0;
        for (int i = 0; at < length && i < 160; i++) {
            double dash = .6 + 1.4 * hash(seed, i, 17), gap = .15 + .5 * hash(seed, i, 19) * (.4 + life);
            double end = Math.min(length, at + dash);
            if (hash(seed + frame * 31, i, 23) < keep) {
                float flicker = .55f + .45f * hash(seed + frame * 7, i, 29);
                band(p, buf, muzzle.add(axis.scale(at)), muzzle.add(axis.scale(end)), eye, .09f, VfxParts.argb(fade * flicker, EMBER));
            }
            at = end + gap;
        }
    }

    /** 雷电：发射时三股螺旋电弧缠着射线往前；之后余线上零星的电弧明灭；命中处电弧乱爬。 */
    private void lightning(PoseStack.Pose p, VertexConsumer buf, float t, Vec3 eye) {
        int frame = (int) (t / 2);
        if (t < COIL_LIFE) {
            float a = (float) Math.pow(1 - t / COIL_LIFE, 1.2);
            float reach = Math.min(1, t / SHOOT);
            float coil = Math.min(length, 40) * (1 - (1 - reach) * (1 - reach));
            // 第一人称：螺旋从五格外开始、淡一些，迎面看过去不缠成一团
            float start = own ? Math.min(coil, 5) : 0, dim = own ? .35f : 1;
            for (int strand = 0; strand < 3; strand++) {
                Vec3[] pts = helix(strand, start, coil, 1f + .35f * t / COIL_LIFE, seed + frame * 97 + strand * 13);
                ribbon(p, buf, pts, eye, .06f, VfxParts.argb(a * dim, ARC));
                ribbon(p, buf, pts, eye, .024f, VfxParts.argb(a * dim, ARC_HOT));
            }
            // 从射线上岔出去的几道
            if (t < 8) for (int i = 0; i < 5; i++) {
                int s = seed + frame * 61 + i;
                float along = Math.min(coil, 1 + hash(s, i, 3) * Math.min(coil, 24));
                Vec3 from = muzzle.add(axis.scale(along));
                Vec3 out = from.add(randomAcross(s, i).scale(1 + 1.6 * hash(s, i, 5))).add(axis.scale(hash(s, i, 7) * 1.2));
                Vec3[] arc = bolt(from, out, s, .18, .2);
                ribbon(p, buf, arc, eye, .03f, VfxParts.argb(a * .9f, ARC));
                ribbon(p, buf, arc, eye, .012f, VfxParts.argb(a, ARC_HOT));
            }
        }
        if (t >= 6 && t < GONE * .85f) {
            float life = (t - 6) / (GONE * .85f - 6);
            int count = (int) (6 * (1 - life)) + 1;
            for (int i = 0; i < count; i++) {
                int s = seed + (int) (t / 3) * 113 + i * 7;
                if (hash(s, i, 3) < .45f) continue;
                float along = hash(s, i, 5) * length, span = 1.2f + 2 * hash(s, i, 7);
                Vec3 a0 = muzzle.add(axis.scale(along)), a1 = muzzle.add(axis.scale(Math.min(length, along + span)))
                        .add(randomAcross(s, i).scale(.3));
                Vec3[] arc = bolt(a0, a1, s, .14, .15);
                ribbon(p, buf, arc, eye, .025f, VfxParts.argb(.8f * (1 - life), ARC));
                ribbon(p, buf, arc, eye, .01f, VfxParts.argb(1 - life, ARC_HOT));
            }
        }
        if (struck && t > SHOOT && t < SHOOT + 16) {
            float a = 1 - (t - SHOOT) / 16;
            Vec3 at = impact();
            for (int i = 0; i < 4; i++) {
                int s = seed + frame * 41 + i * 3;
                Vec3[] arc = bolt(at, at.add(randomAcross(s, i).scale(.8 + 1.4 * hash(s, i, 9))).subtract(axis.scale(hash(s, i, 11))), s, .2, .18);
                ribbon(p, buf, arc, eye, .035f, VfxParts.argb(a, ARC));
                ribbon(p, buf, arc, eye, .014f, VfxParts.argb(a, ARC_HOT));
            }
        }
    }

    /** 缠着射线的一股螺旋，从 {@code from} 到 {@code len} 格：每圈约 3 格，半径 {@code radius}，折点乱跳得像电弧而不是弹簧。 */
    private Vec3[] helix(int strand, float from, float len, float radius, int s) {
        int n = Math.max(2, (int) ((len - from) / .3f));
        Vec3[] pts = new Vec3[n + 1];
        double phase = strand * Math.PI * 2 / 3 + hash(seed, strand, 71) * 6.3;
        double pitch = 2.6 + .8 * hash(seed, strand, 73);
        for (int i = 0; i <= n; i++) {
            double d = from + (len - from) * i / n, a = phase + d / pitch * Math.PI * 2 + (hash(s, i, 7) - .5) * .8;
            double r = radius * (.65 + .7 * hash(s, i, 3)) * Math.min(1, .3 + d / 2);
            pts[i] = muzzle.add(axis.scale(d + (hash(s, i, 5) - .5) * .35)).add(right.scale(Math.cos(a) * r)).add(up.scale(Math.sin(a) * r));
        }
        return pts;
    }

    /** 软光：出口和命中处的辉光、横向的镜头光、往前飞的橙色熔流、往后吹过身体的风线。 */
    private void glows(PoseStack.Pose p, VertexConsumer buf, VfxParts.View view, float t, float near) {
        Vec3 eye = view.cam();
        float flash = flash(t, 12);
        if (flash > 0) {
            Vec3 c = muzzleDressing(near, 1.4);
            float cap = .5f * (float) eye.distanceTo(c);
            float r = Math.min((2.2f + 3f * near) * flash, cap);
            flare(p, buf, view, c, r, r, 0, VfxParts.argb(.55f * flash, SHEATH));
            // 横向拉长的淡紫镜头光（参考动画第四张）
            flare(p, buf, view, c, Math.min(9 * flash, cap * 4), .22f * flash, 0, VfxParts.argb((own ? .3f : .6f) * flash, LENS));
        }
        float hit = struck ? flash(t - SHOOT, 12) : 0;
        if (hit > 0) flare(p, buf, view, impact(), 4.5f * hit, 4.5f * hit, 0, VfxParts.argb(.6f * hit, SHEATH));
        // 熔流：射线四周一道道往前飞的橙色细流
        for (int i = 0; i < 30; i++) {
            float b = hash(seed, i, 101) * 4, life = 6 + 6 * hash(seed, i, 103), age = t - b;
            if (age < 0 || age > life) continue;
            float speed = 3 + 4 * hash(seed, i, 107), start = 1 + 8 * hash(seed, i, 109);
            float head = Math.min(length, start + speed * age);
            float span = (2 + 3 * hash(seed, i, 113)) * (.5f + .5f * Math.min(1, age / 2));
            float radial = (.9f + 2.8f * (float) Math.pow(hash(seed, i, 127), .7)) * (1 + .15f * age / life);
            double angle = hash(seed, i, 131) * Math.PI * 2;
            Vec3 off = right.scale(Math.cos(angle) * radial).add(up.scale(Math.sin(angle) * radial));
            Vec3 h = muzzle.add(axis.scale(head)).add(off), tl = muzzle.add(axis.scale(Math.max(0, head - span))).add(off);
            float a = (float) Math.pow(1 - age / life, 1.3) * .9f * VfxParts.fade(eye, h, 1, 3);
            VfxParts.streak(p, buf, view, tl, h, .16f + .12f * hash(seed, i, 137), VfxParts.argb(a, STREAK));
        }
        if (own) return;
        // 风线：气浪从身边往后吹
        for (int i = 0; i < 16; i++) {
            float b = hash(seed, i, 151) * 5, life = 8 + 4 * hash(seed, i, 153), age = t - b;
            if (age < 0 || age > life) continue;
            double angle = hash(seed, i, 157) * Math.PI * 2, radial = .45 + .9 * hash(seed, i, 159);
            Vec3 start = chest.add(right.scale(Math.cos(angle) * radial)).add(up.scale(Math.sin(angle) * radial * 1.1));
            Vec3 h = start.subtract(axis.scale(.2 + 1.6 * age)), tl = h.add(axis.scale(1 + 1.2 * hash(seed, i, 161)));
            VfxParts.streak(p, buf, view, tl, h, .05f, VfxParts.argb(.5f * (1 - age / life), WIND));
        }
    }

    /** 气浪：出口的大冲击环、沿线的气浪环、音爆锥；身上往后掠的锥和压力环；脚下贴地扩散的尘环；命中处的气浪。 */
    private void air(PoseStack.Pose p, VertexConsumer buf, float t, float near) {
        float own = .5f + .5f * near;
        // 出口：一圈套住手臂的大白环，前面跟一圈小的
        Vec3 ringAt = muzzleDressing(near, 1.6).subtract(axis.scale(.3 * near));
        if (t < RING_LIFE) {
            float e = easeOut(t / RING_LIFE);
            ring(p, buf, ringAt, axis, .4f + 3.6f * e, .08f + .16f * e, (float) Math.pow(1 - t / RING_LIFE, 1.2) * .55f, .15f * e,
                    seed + 3, 72, SHOCK);
        }
        if (t >= 1 && t < 11) {
            float q = (t - 1) / 10, e = easeOut(q);
            ring(p, buf, ringAt.add(axis.scale(1.4 + 1.2 * e)), axis, .3f + 1.6f * e, .06f + .12f * e, (1 - q) * .5f, .1f * e,
                    seed + 5, 56, SHOCK);
        }
        Vec3 base = muzzle.add(axis.scale(.3 + 1.6 * (1 - near)));
        boom(p, buf, base, axis, t, 11, 2.2f * own, own * .8f, seed, shade(AIR_GREY));
        boom(p, buf, base, axis, t - 2, 10, 1.4f * own, .6f * own, seed + 7, shade(AIR_GREY));
        // 沿线被推开的气浪环，只在出口往前三十格内，越远越小
        int rings = Math.min(8, (int) ((Math.min(length, 30) - 2) / RING_SPACING));
        for (int i = 1; i <= rings; i++) {
            float along = Math.min(length - 1, i * RING_SPACING + .8f * hash(seed, i, 3));
            float age = t - SHOOT * (along / length) - .4f * hash(seed, i, 5);
            if (age < 0 || age > RING_LIFE) continue;
            float q = age / RING_LIFE, e = easeOut(q);
            float tiltX = (hash(seed, i, 7) - .5f) * .4f, tiltY = (hash(seed, i, 11) - .5f) * .4f;
            Vec3 n = axis.add(right.scale(tiltX)).add(up.scale(tiltY)).normalize();
            float size = 1 - .5f * i / Math.max(1, rings);
            ring(p, buf, muzzle.add(axis.scale(along + .8 * e)), n, (.2f + 1.3f * e) * size, (.06f + .18f * e) * size,
                    (float) Math.pow(1 - q, 1.5) * .7f, .25f * e * size, seed + i * 13, 48, shade(VAPOUR));
        }
        if (!this.own) {
            // 身上：往后掠的音爆锥，两圈压力环从胸口往后推
            boom(p, buf, chest, axis.scale(-1), t, 12, 1.2f, .4f, seed + 11, shade(AIR_GREY));
            for (int i = 0; i < 2; i++) {
                float age = t - i * 3;
                if (age < 0 || age >= 14) continue;
                float q = age / 14, e = easeOut(q);
                ring(p, buf, chest.subtract(axis.scale(.3 + 1.2 * e)), axis, .4f + 1.6f * e, .08f + .18f * e, (1 - q) * .35f, .15f * e,
                        seed + 17 + i, 48, shade(AIR_GREY));
            }
        }
        // 脚下：贴地扩散的尘环
        if (!Double.isNaN(ground)) {
            Vec3 c = new Vec3(feet.x, ground + .08, feet.z);
            Vec3 n = new Vec3(0, 1, 0);
            if (t < 18) {
                float q = t / 18, e = easeOut(q);
                ring(p, buf, c, n, .5f + 6.5f * e, .25f + .7f * e, (float) Math.pow(1 - q, 1.3) * .5f, .25f * e, seed + 23, 72, shade(tint));
            }
            if (t >= 4 && t < 20) {
                float q = (t - 4) / 16, e = easeOut(q);
                ring(p, buf, c.add(0, .05, 0), n, .3f + 4 * e, .2f + .45f * e, (1 - q) * .35f, .2f * e, seed + 29, 56, shade(tint));
            }
        }
        if (struck) {
            Vec3 at = impact(), back = axis.scale(-1);
            float h = t - SHOOT;
            if (h >= 0 && h < 16) {
                float q = h / 16, e = easeOut(q);
                ring(p, buf, at, back, .3f + 5.4f * e, .3f + .7f * e, (1 - q) * .95f, .5f * e, seed + 101, 72, shade(VAPOUR));
            }
            if (h >= 2 && h < 16) {
                float q = (h - 2) / 14, e = 1 - (1 - q) * (1 - q);
                ring(p, buf, at.add(back.scale(.15)), back, .2f + 3.3f * e, .2f + .45f * e, (1 - q) * .7f, .3f * e, seed + 211, 64, shade(VAPOUR));
            }
            boom(p, buf, at.add(back.scale(.1)), back, h, 12, 4.2f, .9f, seed + 307, shade(AIR_GREY));
        }
    }

    /** 灰白的空气按脚下的亮度压暗。 */
    private int shade(int rgb) { return VfxParts.lerpColor(0, rgb, lit); }

    /** 出口装饰放在哪：从外面看就在拳前，第一人称往射线前方推出 {@code push} 格。 */
    private Vec3 muzzleDressing(float near, double push) {
        return muzzle.add(axis.scale(Math.min(length * .5, push * (1 - near))));
    }

    /** 命中点往回退一点，免得气浪埋在方块里。 */
    private Vec3 impact() {
        return to.subtract(axis.scale(Math.min(.12, length * .5)));
    }

    private Vec3 randomAcross(int s, int i) {
        double a = hash(s, i, 89) * Math.PI * 2;
        return right.scale(Math.cos(a)).add(up.scale(Math.sin(a)));
    }

    // ---- 声音与粒子 ------------------------------------------------------------------------------

    private void sounds(Level level) {
        level.playLocalSound(muzzle.x, muzzle.y, muzzle.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1.2f, 1.45f, false);
        level.playLocalSound(muzzle.x, muzzle.y, muzzle.z, SoundEvents.TRIDENT_THUNDER.value(), SoundSource.PLAYERS, .8f, 1.7f, false);
        level.playLocalSound(muzzle.x, muzzle.y, muzzle.z, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, .6f, .5f, false);
        if (struck) level.playLocalSound(to.x, to.y, to.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1f, .9f, false);
    }

    private void particles(Level level) {
        RandomSource r = level.getRandom();
        // 脚下被掀起的碎块，贴地往四周推开（尘环在 air 里画）
        if (!Double.isNaN(ground)) {
            BlockState floor = level.getBlockState(BlockPos.containing(feet.x, ground - .5, feet.z));
            if (!floor.isAir()) for (int i = 0; i < 14; i++) {
                double a = r.nextDouble() * Math.PI * 2, v = .2 + r.nextDouble() * .25;
                level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, floor), feet.x + Math.cos(a) * .6, ground + .05,
                        feet.z + Math.sin(a) * .6, Math.cos(a) * v, .15 + r.nextDouble() * .2, Math.sin(a) * v);
            }
        }
        // 沿线：电火花，贴近地面时掀起地皮
        int samples = (int) Math.min(48, length / 2);
        for (int i = 1; i <= samples; i++) {
            Vec3 at = muzzle.add(axis.scale(i * 2));
            Vec3 out = randomAcross(seed + i, i);
            level.addParticle(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, out.x * .6, out.y * .6, out.z * .6);
            kickGround(level, at, r);
        }
        if (struck) {
            Vec3 at = impact();
            for (int i = 0; i < 3; i++) level.addParticle(ParticleTypes.EXPLOSION, at.x + r.nextGaussian() * .6,
                    at.y + r.nextGaussian() * .6, at.z + r.nextGaussian() * .6, 0, 0, 0);
            BlockState state = level.getBlockState(BlockPos.containing(to.add(axis.scale(.05))));
            if (!state.isAir()) for (int i = 0; i < 20; i++) level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, state),
                    at.x, at.y, at.z, -axis.x * .4 + (r.nextDouble() - .5) * .7, .15 + r.nextDouble() * .35,
                    -axis.z * .4 + (r.nextDouble() - .5) * .7);
        }
    }

    /** 射线贴着地面掠过时，气压掀起一点地皮。 */
    private static void kickGround(Level level, Vec3 at, RandomSource r) {
        for (int depth = 0; depth <= 3; depth++) {
            BlockPos pos = BlockPos.containing(at).below(depth);
            BlockState state = level.getBlockState(pos);
            var shape = state.getCollisionShape(level, pos);
            if (shape.isEmpty()) continue;
            double top = pos.getY() + shape.max(Direction.Axis.Y);
            if (at.y - top > 2.5 || at.y < top) return;
            for (int k = 0; k < 3; k++) level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, state),
                    at.x + (r.nextDouble() - .5) * 1.2, top + .05, at.z + (r.nextDouble() - .5) * 1.2,
                    (r.nextDouble() - .5) * .4, .2 + r.nextDouble() * .3, (r.nextDouble() - .5) * .4);
            return;
        }
    }

    /** 脚下地面的高度：往下找三格内第一个有碰撞的方块顶面。 */
    private static double groundBelow(Level level, Vec3 feet) {
        for (int depth = 0; depth <= 3; depth++) {
            BlockPos pos = BlockPos.containing(feet.x, feet.y - .01, feet.z).below(depth);
            var shape = level.getBlockState(pos).getCollisionShape(level, pos);
            if (!shape.isEmpty()) return pos.getY() + shape.max(Direction.Axis.Y);
        }
        return Double.NaN;
    }
}
