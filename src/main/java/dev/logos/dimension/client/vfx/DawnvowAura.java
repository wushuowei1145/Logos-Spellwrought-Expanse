package dev.logos.dimension.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.client.VfxParts;
import dev.logos.dimension.dawnvow.DawnvowCharge;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 一位玩家的大型蓄力在客户端的样子，从进入大型蓄力（7 秒）起：
 * <ul>
 *   <li>四周 {@link #FIELD} 格内的地上不断升起金色光点，各自随机游走着上升，身后拖一条细细的光痕（参考萤火与升腾的火星）；
 *       越接近蓄满越密；</li>
 *   <li>身上的金色蓄力光，照 Logos 虚空巨型蓄力的光环做法（没有法阵）：脚下一片柔光，两层缠着身体往上流的光，
 *       从脚边往上散开、闪烁的细光线，绕身体盘旋上升的火星，从四周飞进身体的带拖尾光点；</li>
 *   <li>蓄满的一刻：胸前一团爆闪、放射的光芒，脚下一圈光向外扩开；之后身上的光更盛。</li>
 * </ul>
 * 结束（放出或中断）后不再生出新的光点，已有的走完；身上的光一秒内淡出。
 * 颜色都是半透明的金色，贴图复用 Logos：{@code fire_beam/dot}、{@code laser}、{@code glow}，{@code surface/trail}，
 * {@code force/streak}，{@code fire_impact/flash}，{@code lightning_beam/rays}。
 */
final class DawnvowAura {
    static final RenderType DOT = VfxParts.add("fire_beam/dot"), LASER = VfxParts.add("fire_beam/laser"), GLOW = VfxParts.add("fire_beam/glow");
    private static final RenderType WISP = VfxParts.add("surface/trail"), STREAK = VfxParts.add("force/streak");
    private static final RenderType FLASH = VfxParts.add("fire_impact/flash"), RAYS = VfxParts.add("lightning_beam/rays");
    static final int GOLD = 0xFFC24A, PALE = 0xFFE7A6, HOT = 0xFFF8E2;
    /** 光点头上的颜色：比光痕亮、仍带金色。 */
    private static final int MOTE = 0xFFDB8C;
    /** 光点从多远的地上升起。 */
    static final double FIELD = 32;
    private static final int MAX_MOTES = 320, MAX_SPARKS = 60, MAX_INFLOW = 14;
    /** 结束后身上的光淡出的刻数。 */
    private static final int FADE = 20;

    /** 一个带拖尾的光点：位置、速度、年龄与寿命、大小；拖尾记最近几刻的位置。 */
    private static final class Mote {
        Vec3 pos, vel;
        int age;
        final int life, keep;
        final float size, phase;
        final ArrayDeque<Vec3> trail = new ArrayDeque<>();

        Mote(Vec3 pos, Vec3 vel, int life, float size, float phase, int keep) {
            this.pos = pos; this.vel = vel; this.life = life; this.size = size; this.phase = phase; this.keep = keep;
        }

        void move(Vec3 to) {
            trail.addFirst(pos);
            while (trail.size() > keep) trail.removeLast();
            pos = to;
            age++;
        }

        float alpha(float pt) {
            float a = age + pt;
            return VfxParts.smooth(0, 8, a) * (1 - VfxParts.smooth(life - 20, life, a));
        }
    }

    /** 从四周飞进身体的光点：起点相对胸口，{@link #INFLOW_TICKS} 刻飞到。 */
    private record Inflow(long born, Vec3 start, float swirl, float size) {}
    private static final int INFLOW_TICKS = 16;

    final int entity;
    final long start;
    long full = -1, ended = -1;
    private final List<Mote> motes = new ArrayList<>(), sparks = new ArrayList<>();
    private final List<Inflow> inflow = new ArrayList<>();
    private float owedMotes, owedSparks, owedInflow;

    DawnvowAura(int entity, long start) {
        this.entity = entity;
        this.start = start;
    }

    /** 这次蓄力按了多少刻（含帧间）。 */
    double held(double now) { return now - start; }

    /** 剑身金光的强弱（0–1），12 秒起一秒半亮到满，结束后淡出。 */
    float blade(double now) {
        return VfxParts.smooth(DawnvowCharge.BLADE_TICKS, DawnvowCharge.BLADE_TICKS + 30, (float) held(now)) * fading(now);
    }

    /** 结束后从 1 淡到 0。 */
    float fading(double now) {
        return ended < 0 ? 1 : 1 - VfxParts.smooth(0, FADE, (float) (now - ended));
    }

    /** 身上光的强弱：进入大型蓄力三秒内渐强，蓄满后更盛，结束后淡出。 */
    float strength(double now) {
        float grow = VfxParts.smooth(DawnvowCharge.GRAND_TICKS, DawnvowCharge.GRAND_TICKS + 60, (float) held(now));
        float more = .35f * VfxParts.smooth(DawnvowCharge.GRAND_TICKS, DawnvowCharge.FULL_TICKS, (float) held(now));
        float full = this.full >= 0 && now >= this.full ? .35f : 0;
        return (grow * .65f + more + full) * fading(now);
    }

    /** 一个客户端刻。返回 false 时这次蓄力的效果已经全部消失，可以丢掉。 */
    boolean tick(Level level, Entity body, long now) {
        RandomSource r = level.getRandom();
        Vec3 feet = body.position();
        boolean live = ended < 0;
        float k = (float) Mth.clamp((held(now) - DawnvowCharge.GRAND_TICKS) / (DawnvowCharge.FULL_TICKS - DawnvowCharge.GRAND_TICKS), 0, 1);
        boolean isFull = full >= 0 && now >= full;
        if (live) {
            for (owedMotes += 2 + 10 * k + (isFull ? 6 : 0); owedMotes >= 1; owedMotes--) if (motes.size() < MAX_MOTES) groundMote(level, r, feet);
            for (owedSparks += isFull ? 2.5f : 1.2f; owedSparks >= 1; owedSparks--) if (sparks.size() < MAX_SPARKS) spark(r, feet);
            for (owedInflow += .35f + .4f * k; owedInflow >= 1; owedInflow--) if (inflow.size() < MAX_INFLOW) inflow(r, now);
        }
        for (Iterator<Mote> it = motes.iterator(); it.hasNext(); ) {
            Mote m = it.next();
            // 随机游走着上升：水平速度每刻抖一下再衰减，竖直方向慢慢加速
            m.vel = new Vec3(m.vel.x * .93 + r.nextGaussian() * .012, Math.min(.16, m.vel.y + .0015), m.vel.z * .93 + r.nextGaussian() * .012);
            m.move(m.pos.add(m.vel));
            if (m.age >= m.life) it.remove();
        }
        for (Iterator<Mote> it = sparks.iterator(); it.hasNext(); ) {
            Mote m = it.next();
            // 绕着身体盘旋上升
            Vec3 rel = m.pos.subtract(feet);
            double a = Math.atan2(rel.z, rel.x) + .22, rad = Math.hypot(rel.x, rel.z) * 1.006;
            m.move(new Vec3(feet.x + Math.cos(a) * rad, m.pos.y + m.vel.y, feet.z + Math.sin(a) * rad));
            if (m.age >= m.life) it.remove();
        }
        inflow.removeIf(f -> now - f.born() > INFLOW_TICKS + 6);
        return live || !motes.isEmpty() || !sparks.isEmpty() || !inflow.isEmpty() || now - ended < FADE;
    }

    /** 在四周 {@link #FIELD} 格内找一处地面放一个光点；那一列附近找不到地面就算了。 */
    private void groundMote(Level level, RandomSource r, Vec3 feet) {
        double a = r.nextDouble() * Mth.TWO_PI, d = 1.5 + (FIELD - 1.5) * Math.sqrt(r.nextDouble());
        int x = Mth.floor(feet.x + Math.cos(a) * d), z = Mth.floor(feet.z + Math.sin(a) * d);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, Mth.floor(feet.y) + 8, z);
        for (int i = 0; i < 20; i++, pos.move(0, -1, 0)) {
            if (!level.getBlockState(pos).isAir() || level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty()) continue;
            Vec3 at = new Vec3(x + r.nextDouble(), pos.getY() + .05, z + r.nextDouble());
            Vec3 vel = new Vec3(r.nextGaussian() * .01, .025 + .04 * r.nextDouble(), r.nextGaussian() * .01);
            motes.add(new Mote(at, vel, 70 + r.nextInt(80), .07f + .08f * r.nextFloat(), r.nextFloat() * 10, 10));
            return;
        }
    }

    private void spark(RandomSource r, Vec3 feet) {
        double a = r.nextDouble() * Mth.TWO_PI, d = .45 + .5 * r.nextDouble();
        Vec3 at = feet.add(Math.cos(a) * d, .05 + r.nextDouble() * .3, Math.sin(a) * d);
        sparks.add(new Mote(at, new Vec3(0, .09 + .06 * r.nextDouble(), 0), 20 + r.nextInt(14), .05f + .05f * r.nextFloat(), 0, 6));
    }

    private void inflow(RandomSource r, long now) {
        Vec3 d = new Vec3(r.nextGaussian(), r.nextGaussian() * .6, r.nextGaussian()).normalize().scale(2.2 + 1.4 * r.nextDouble());
        inflow.add(new Inflow(now, new Vec3(d.x, Math.max(-.8, d.y), d.z), (r.nextFloat() - .5f) * 2.4f, .05f + .04f * r.nextFloat()));
    }

    /** 每帧。{@code feet} 是这一帧插值后的脚下，{@code near} 是贴近镜头时的淡出（第一人称看自己时身上的层几乎不画）。 */
    void submit(PoseStack pose, SubmitNodeCollector out, VfxParts.View view, Vec3 feet, float height, double now, float pt, float near) {
        float s = strength(now);
        Vec3 chest = feet.add(0, height * .55, 0);
        if (!motes.isEmpty()) {
            out.submitCustomGeometry(pose, LASER, (p, buf) -> {
                for (Mote m : motes) trail(p, buf, view, m, pt, .6f * VfxParts.fade(view.cam(), m.pos, .8, 3), GOLD);
            });
            out.submitCustomGeometry(pose, DOT, (p, buf) -> {
                for (Mote m : motes) {
                    Vec3 at = lerp(m, pt);
                    float tw = .75f + .25f * Mth.sin((float) now * .3f + m.phase * 6);
                    VfxParts.sprite(p, buf, view, at, m.size, 0, VfxParts.argb(.85f * m.alpha(pt) * tw * VfxParts.fade(view.cam(), at, .8, 3), MOTE));
                }
            });
        }
        if (s <= 0.002f && sparks.isEmpty() && inflow.isEmpty()) return;
        // 脚下的柔光
        out.submitCustomGeometry(pose, GLOW, (p, buf) -> {
            VfxParts.ground(p, buf, feet.add(0, .03, 0), 2.2f + .8f * s, 0, VfxParts.argb(.32f * s, GOLD));
            VfxParts.sprite(p, buf, view, chest, 1.4f * s, 0, VfxParts.argb(.12f * s * near, PALE));
        });
        // 两层缠着身体往上流的光
        if (near > 0.01f) out.submitCustomGeometry(pose, WISP, (p, buf) -> {
            shell(p, buf, view, feet, .7f + .15f * s, height * 1.3f, (float) now * .035f, .34f * s * near);
            shell(p, buf, view, feet, .95f + .2f * s, height * 1.55f, (float) now * -.022f + .5f, .22f * s * near);
        });
        // 从脚边往上散开、闪烁的细光线
        out.submitCustomGeometry(pose, STREAK, (p, buf) -> {
            for (int i = 0; i < 8; i++) {
                double a = i * Mth.TWO_PI / 8 + now * .012 + (i % 2) * .4;
                float tilt = .25f + .15f * Mth.sin((float) now * .05f + i);
                Vec3 out2 = new Vec3(Math.cos(a) * tilt, 1, Math.sin(a) * tilt).normalize();
                float len = height * (1.3f + .5f * Mth.sin((float) now * .21f + i * 1.7f)) * (.8f + .4f * s);
                float flicker = .5f + .5f * Mth.sin((float) now * .37f + i * 2.3f);
                Vec3 base = feet.add(Math.cos(a) * .35, .1, Math.sin(a) * .35);
                VfxParts.streak(p, buf, view, base, base.add(out2.scale(len)), .22f, VfxParts.argb(.28f * s * flicker * (.4f + .6f * near), MOTE));
            }
        });
        if (!sparks.isEmpty()) out.submitCustomGeometry(pose, LASER, (p, buf) -> { for (Mote m : sparks) trail(p, buf, view, m, pt, .9f * near, MOTE); });
        if (!inflow.isEmpty()) out.submitCustomGeometry(pose, DOT, (p, buf) -> {
            for (Inflow f : inflow) {
                double age = now - f.born();
                if (age < 0 || age > INFLOW_TICKS) continue;
                Vec3 at = chest.add(inflowAt(f, age));
                float a = VfxParts.smooth(0, 4, (float) age) * (1 - VfxParts.smooth(INFLOW_TICKS - 4, INFLOW_TICKS, (float) age));
                VfxParts.sprite(p, buf, view, at, f.size(), 0, VfxParts.argb(.75f * a * VfxParts.fade(view.cam(), at, .6, 2), MOTE));
                for (int k = 1; k <= 5; k++) {
                    Vec3 back = chest.add(inflowAt(f, Math.max(0, age - k * .9)));
                    VfxParts.sprite(p, buf, view, back, f.size() * (1 - k * .15f), 0, VfxParts.argb(.45f * a * (1 - k / 6f), GOLD));
                }
            }
        });
        if (full >= 0 && now >= full && now - full < 24) burst(pose, out, view, feet, chest, (float) (now - full), near);
    }

    /** 从四周飞进胸口的路线：起步慢、越飞越快，前半程绕着转一点。 */
    private static Vec3 inflowAt(Inflow f, double age) {
        float k = Mth.clamp((float) (age / INFLOW_TICKS), 0, 1);
        float in = k * k * (2.2f - 1.2f * k);
        double turn = f.swirl() * Math.sin(k * Math.PI) * .9;
        Vec3 s = f.start().scale(1 - in);
        double c = Math.cos(turn), sn = Math.sin(turn);
        return new Vec3(s.x * c - s.z * sn, s.y, s.x * sn + s.z * c);
    }

    /** 蓄满的一刻：胸前爆闪与放射光芒，脚下一圈光向外扩开。 */
    private static void burst(PoseStack pose, SubmitNodeCollector out, VfxParts.View view, Vec3 feet, Vec3 chest, float age, float near) {
        float flash = 1 - VfxParts.smooth(0, 10, age), ring = VfxParts.smooth(0, 14, age);
        out.submitCustomGeometry(pose, FLASH, (p, buf) -> VfxParts.sprite(p, buf, view, chest, 3.2f * (.4f + .6f * VfxParts.smooth(0, 3, age)), age * .05f,
                VfxParts.argb(.9f * flash * (.3f + .7f * near), HOT)));
        out.submitCustomGeometry(pose, RAYS, (p, buf) -> VfxParts.sprite(p, buf, view, chest, 4.5f * (.6f + .4f * ring), -age * .03f,
                VfxParts.argb(.55f * (1 - VfxParts.smooth(4, 24, age)), PALE)));
        out.submitCustomGeometry(pose, GLOW, (p, buf) -> VfxParts.ground(p, buf, feet.add(0, .05, 0), 1 + 8 * ring, 0,
                VfxParts.argb(.5f * (1 - ring), GOLD)));
    }

    /**
     * 一层往上流的光：开口圆柱，贴图的 U 沿高度往上滚，V 绕一圈两遍；底下亮、顶上透明。
     * 侧着对镜头的那部分（圆柱的轮廓）按视角淡掉，看上去是一团光而不是一圈硬边的玻璃板。
     */
    private static void shell(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, Vec3 feet, float radius, float height, float scroll, float alpha) {
        int segments = 24;
        int top = VfxParts.argb(0, GOLD);
        for (int i = 0; i < segments; i++) {
            double a0 = Mth.TWO_PI * i / segments, a1 = Mth.TWO_PI * (i + 1) / segments;
            Vec3 p0 = feet.add(Math.cos(a0) * radius, 0, Math.sin(a0) * radius), p1 = feet.add(Math.cos(a1) * radius, 0, Math.sin(a1) * radius);
            Vec3 q0 = feet.add(Math.cos(a0) * radius * .7, height, Math.sin(a0) * radius * .7), q1 = feet.add(Math.cos(a1) * radius * .7, height, Math.sin(a1) * radius * .7);
            float v0 = 2f * i / segments, v1 = 2f * (i + 1) / segments;
            int b0 = VfxParts.argb(alpha * facing(view, feet, a0), GOLD), b1 = VfxParts.argb(alpha * facing(view, feet, a1), GOLD);
            VfxParts.vertex(pose, buf, p0, -scroll, v0, b0);
            VfxParts.vertex(pose, buf, q0, 1 - scroll, v0, top);
            VfxParts.vertex(pose, buf, q1, 1 - scroll, v1, top);
            VfxParts.vertex(pose, buf, p1, -scroll, v1, b1);
        }
    }

    /** 圆柱上朝 {@code a} 方向的那条边有多正对镜头（0 侧对、1 正对），平方后让轮廓更快淡掉。 */
    private static float facing(VfxParts.View view, Vec3 feet, double a) {
        Vec3 to = new Vec3(view.cam().x - feet.x, 0, view.cam().z - feet.z);
        if (to.lengthSqr() < 1e-6) return 1;
        float d = (float) (Math.abs(Math.cos(a) * to.x + Math.sin(a) * to.z) / to.length());
        return d * d;
    }

    private static Vec3 lerp(Mote m, float pt) {
        Vec3 last = m.trail.isEmpty() ? m.pos : m.trail.peekFirst();
        return last.lerp(m.pos, pt);
    }

    /** 光点身后的细光痕：从此刻的位置沿走过的路往后，越往后越细越淡。 */
    private static void trail(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, Mote m, float pt, float alpha, int rgb) {
        if (m.trail.size() < 2) return;
        List<Vec3> points = new ArrayList<>(m.trail.size() + 1);
        points.add(lerp(m, pt));
        points.addAll(m.trail);
        int n = points.size();
        float a = m.alpha(pt) * alpha;
        GoldStrips.strip(pose, buf, view, points, i -> m.size * .7f * (1 - i / (float) n), i -> VfxParts.argb(a * (1 - i / (float) n), rgb), .5f, 0);
    }
}
