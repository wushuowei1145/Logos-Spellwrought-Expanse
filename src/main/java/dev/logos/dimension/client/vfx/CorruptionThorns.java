package dev.logos.dimension.client.vfx;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.client.LiveVein;
import dev.logos.client.VeinVisual;
import dev.logos.client.VfxParts;
import dev.logos.dimension.LogosDimension;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 腐败荆棘：Logos 生命游脉留下的带刺藤蔓（{@code LifeLingeringVeinVisual}）的腐败版，颜色是腐败的紫红，
 * 贴图（{@code logos:textures/vfx/life_ray/vines.png}，白色带透明）和着色器（{@code logos:core/vein_vine}）直接引用 Logos 的。
 * <ul>
 * <li>{@link Vein}：女王的「腐化荆棘」（{@code :linger} 的生命游脉）爬过的地方长出来的藤蔓。
 * <li>{@link #spike}：「荆棘穿刺」那种向上的射线，画成一簇从地里刺出来、很快又缩回去的荆棘。
 * </ul>
 * 藤蔓是植物不是光：按世界的光照着色，夜里是暗的。
 */
public final class CorruptionThorns {
    /** 出土处是近黑的暗紫，往上是腐败的紫红，刺尖亮一点。 */
    static final int ROOT = 0x260818, STEM = 0x8A1C55, TIP = 0xC23C7E, BARB = 0x9E2462;
    private static final RenderType VINES = material("life_ray/vines"), BARBS = material("life_impact/leaf");
    /** 长满、缩回去要几刻；离镜头多远不画。 */
    private static final float GROW = 7, SINK = 14, SINK_SHARE = .3f;
    private static final double FAR = 96, PER_BLOCK = 2.2;
    private static final int MAX = 120;

    private CorruptionThorns() {}

    /** 叶片位置换成刺：{@code at} 这一成的地方、{@code size} 长、绕茎转 {@code turn}。 */
    record Barb(float at, float size, float turn) {}

    /** 一根藤：沿 {@code points} 长，{@code along} 是到每一点的长度；{@code grow} 刻长满，{@code laid} 时出土、{@code until} 时完全缩回。 */
    record Vine(Vec3[] points, float[] along, Vec3 n, float width, float seed, double laid, double until, boolean hook, float grow, List<Barb> barbs) {
        float length() { return along[along.length - 1]; }

        float shown(double now) {
            double t = now - laid;
            if (t <= 0) return 0;
            float k = (float) Math.min(1, t / grow), grown = 1 - (1 - k) * (1 - k) * (1 - k);
            double sink = Math.min(SINK, (until - laid) * SINK_SHARE), left = until - now;
            return left >= sink ? grown : grown * VfxParts.smooth(0, 1, (float) (left / Math.max(sink, 1e-3)));
        }
    }

    // ---------------------------------------------------------------- 游脉

    /** 腐化荆棘的一个头（代替生命游脉的样子，见 {@code CorruptionVeinVisualsMixin}）。 */
    public static final class Vein implements VeinVisual {
        private final List<Vine> vines = new ArrayList<>();
        private double carried;

        @Override public boolean tick(Level level, LiveVein vein) {
            double now = level.getGameTime();
            if (!vein.ended()) lay(level, vein, level.getRandom(), now);
            vines.removeIf(v -> v.until() <= now);
            return !vines.isEmpty() || !vein.ended();
        }

        private void lay(Level level, LiveVein vein, RandomSource r, double now) {
            Vec3 from = vein.prev(), to = vein.pos(), n = vein.normal();
            double length = from.distanceTo(to);
            Vec3 dir = length > 1e-6 ? to.subtract(from).scale(1 / length) : vein.dir();
            Vec3 side = dir.cross(n);
            side = side.lengthSqr() < 1e-6 ? Vec3.ZERO : side.normalize();
            double until = now + Math.max(1, vein.linger());
            double every = 1 / PER_BLOCK;
            for (double s = every - carried; s <= length; s += every) {
                if (vines.size() >= MAX) break;
                Vec3 at = from.lerp(to, length < 1e-9 ? 1 : s / length).add(side.scale((r.nextDouble() - .5) * vein.width() * .8));
                vines.add(r.nextFloat() < .35f ? hook(r, at, n, vein.width(), vein.height(), until, now) : arch(r, at, dir, n, side, vein.width(), vein.height(), until, now));
                if (r.nextFloat() < .4f) level.addParticle(new DustParticleOptions(STEM, .8f), at.x, at.y + .1, at.z, 0, .02, 0);
            }
            carried = (carried + length) % every;
        }

        @Override public void submit(Level level, LiveVein vein, CameraRenderState camera, float partialTick, PoseStack pose, SubmitNodeCollector out) {
            draw(level, vines, level.getGameTime() + partialTick, camera, pose, out);
        }
    }

    /** 在地面 {@code at} 往外拱的一根：拱起来再扎回去，往一侧偏一点。 */
    static Vine arch(RandomSource r, Vec3 at, Vec3 dir, Vec3 n, Vec3 side, double width, double height, double until, double now) {
        double span = width * (.5 + .7 * r.nextDouble()), high = height * (.6 + .55 * r.nextDouble());
        Vec3 back = at.subtract(dir.scale(span)).add(side.scale((r.nextDouble() - .5) * width * .6));
        Vec3 bow = side.scale((r.nextDouble() - .5) * span * .8);
        Vec3 a = at.subtract(n.scale(.12)), b = back.subtract(n.scale(.12));
        Vec3 c1 = at.add(n.scale(high * 1.33)).add(bow), c2 = back.add(n.scale(high * 1.33)).add(bow);
        Vec3[] points = new Vec3[17];
        for (int i = 0; i < points.length; i++) points[i] = bezier(a, c1, c2, b, i / (points.length - 1.0));
        return vine(points, n, (float) (width * (.46f + .14f * r.nextFloat())), r, until, now, false, GROW);
    }

    /** 从 {@code at} 往上长、顶上卷过去的一根，尖是空着的。 */
    static Vine hook(RandomSource r, Vec3 at, Vec3 n, double width, double height, double until, double now) {
        double high = height * (.7 + .55 * r.nextDouble()), curl = high * (.18 + .1 * r.nextDouble());
        Vec3 u = perpendicular(n), w = n.cross(u);
        double turn = r.nextDouble() * Math.PI * 2;
        Vec3 lean = u.scale(Math.cos(turn)).add(w.scale(Math.sin(turn)));
        Vec3 top = at.add(n.scale(high - curl)).add(lean.scale(high * .25));
        Vec3 a = at.subtract(n.scale(.12));
        List<Vec3> points = new ArrayList<>();
        for (int i = 0; i <= 8; i++) points.add(bezier(a, at.add(n.scale(high * .5)), top.subtract(n.scale(high * .25)), top, i / 8.0));
        Vec3 centre = top.add(lean.scale(curl));
        double sweep = Math.PI * (1.4 + .4 * r.nextDouble());
        for (int i = 1; i <= 10; i++) {
            double t = sweep * i / 10, radius = curl * (1 - .5 * i / 10);
            points.add(centre.add(lean.scale(-Math.cos(t) * radius)).add(n.scale(Math.sin(t) * radius)));
        }
        return vine(points.toArray(Vec3[]::new), n, (float) (width * (.4f + .1f * r.nextFloat())), r, until, now, true, GROW);
    }

    private static Vine vine(Vec3[] points, Vec3 n, float width, RandomSource r, double until, double now, boolean hook, float grow) {
        float[] along = new float[points.length];
        for (int i = 1; i < points.length; i++) along[i] = along[i - 1] + (float) points[i].distanceTo(points[i - 1]);
        List<Barb> barbs = new ArrayList<>();
        int count = 2 + r.nextInt(3);
        for (int k = 0; k < count; k++) barbs.add(new Barb(.2f + .65f * r.nextFloat(), width * (.35f + .2f * r.nextFloat()), r.nextFloat() * 6.3f));
        double when = now + r.nextInt(3);
        return new Vine(points, along, n, width, r.nextFloat(), when, until + (when - now), hook, grow, barbs);
    }

    // ---------------------------------------------------------------- 向上刺出的荆棘

    private static final List<Vine> SPIKES = new ArrayList<>();
    private static Level spikesIn;

    /** {@code at} 处地里刺出一簇荆棘，最高 {@code height} 格：三四根几乎笔直地往上捅，一下长满，停一会儿缩回去。 */
    public static void spike(Level level, Vec3 at, float height) {
        if (spikesIn != level) { SPIKES.clear(); spikesIn = level; }
        RandomSource r = level.getRandom();
        double now = level.getGameTime();
        Vec3 up = new Vec3(0, 1, 0);
        int count = 3 + r.nextInt(2);
        for (int k = 0; k < count && SPIKES.size() < 240; k++) {
            double a = r.nextDouble() * Math.PI * 2, off = k == 0 ? 0 : .15 + .25 * r.nextDouble();
            Vec3 base = at.add(Math.cos(a) * off, -.15, Math.sin(a) * off);
            double h = height * (k == 0 ? 1 : .55 + .35 * r.nextDouble());
            // 往外斜一点，顶上带一点弯，像从地里捅出来的刺。
            Vec3 lean = new Vec3(Math.cos(a), 0, Math.sin(a)).scale(k == 0 ? .08 : .28 + .2 * r.nextDouble());
            Vec3 bend = new Vec3(r.nextDouble() - .5, 0, r.nextDouble() - .5).scale(.25);
            Vec3[] points = new Vec3[9];
            for (int i = 0; i < points.length; i++) {
                double t = i / (points.length - 1.0);
                points[i] = base.add(up.scale(h * t)).add(lean.scale(h * t)).add(bend.scale(t * t * h));
            }
            SPIKES.add(vine(points, up, (float) (k == 0 ? .42 : .26 + .1 * r.nextDouble()), r, now + 16, now, false, 2.5f));
        }
        for (int i = 0; i < 10; i++) level.addParticle(new DustParticleOptions(STEM, 1.1f), at.x + (r.nextDouble() - .5) * .8, at.y + .1, at.z + (r.nextDouble() - .5) * .8,
                0, .05 + r.nextDouble() * .08, 0);
    }

    /** 刺出来的荆棘每帧画（{@code CorruptionFx} 调）。 */
    static void drawSpikes(Level level, double now, CameraRenderState camera, PoseStack pose, SubmitNodeCollector out) {
        if (spikesIn != level) { SPIKES.clear(); spikesIn = level; return; }
        SPIKES.removeIf(v -> v.until() <= now);
        draw(level, SPIKES, now, camera, pose, out);
    }

    public static int spikes() { return SPIKES.size(); }

    // ---------------------------------------------------------------- 画

    static void draw(Level level, List<Vine> vines, double now, CameraRenderState camera, PoseStack pose, SubmitNodeCollector out) {
        if (vines.isEmpty()) return;
        Vec3 cam = camera.pos;
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        out.submitCustomGeometry(pose, VINES, (p, buf) -> {
            for (Vine v : vines) {
                if (v.points()[0].distanceTo(cam) > FAR) continue;
                float shown = v.shown(now);
                if (shown > .01f) stem(p, buf, cam, v, shown * v.length(), light(level, v));
            }
        });
        out.submitCustomGeometry(pose, BARBS, (p, buf) -> {
            for (Vine v : vines) {
                if (v.points()[0].distanceTo(cam) > FAR) continue;
                float shown = v.shown(now) * v.length();
                for (Barb barb : v.barbs()) barb(p, buf, v, barb, shown, light(level, v));
            }
        });
        pose.popPose();
    }

    private static int light(Level level, Vine v) {
        return LevelRenderer.getLightCoords(level, BlockPos.containing(v.points()[0].add(v.n().scale(.6))));
    }

    /** 藤到 {@code reach} 为止，一条朝着镜头的带子，长着的尖收成一点。 */
    private static void stem(PoseStack.Pose pose, VertexConsumer buf, Vec3 cam, Vine v, float reach, int light) {
        Vec3[] pts = v.points();
        float[] along = v.along();
        float repeat = v.width() * 2;
        Vec3 lastP = null, lastSide = null;
        float lastU = 0;
        int lastColor = 0;
        for (int i = 0; i < pts.length; i++) {
            float s = along[i];
            Vec3 p = pts[i];
            boolean end = false;
            if (s >= reach) {
                if (i == 0) return;
                float k = (reach - along[i - 1]) / Math.max(1e-4f, s - along[i - 1]);
                p = pts[i - 1].lerp(pts[i], k);
                s = reach;
                end = true;
            }
            Vec3 tangent = (i + 1 < pts.length && !end ? pts[i + 1] : p).subtract(i > 0 ? pts[i - 1] : p);
            Vec3 side = tangent.cross(cam.subtract(p));
            if (side.lengthSqr() < 1e-10) side = lastSide != null ? lastSide : perpendicular(tangent.lengthSqr() < 1e-10 ? v.n() : tangent);
            float taper = VfxParts.smooth(0, Math.min(.3f, v.width()), reach - s);
            if (v.hook()) taper *= 1 - .55f * s / v.length();
            Vec3 half = side.normalize().scale(v.width() * .5f * Math.max(taper, .05f));
            float u = v.seed() * 4 + s / repeat;
            float k = s / Math.max(.5f, v.length());
            int color = 0xFF000000 | (k < .35f ? VfxParts.lerpColor(ROOT, STEM, k / .35f) : VfxParts.lerpColor(STEM, TIP, (k - .35f) / .65f));
            if (lastP != null) {
                vertex(pose, buf, lastP.add(lastSide), lastU, 0, lastColor, light);
                vertex(pose, buf, lastP.subtract(lastSide), lastU, 1, lastColor, light);
                vertex(pose, buf, p.subtract(half), u, 1, color, light);
                vertex(pose, buf, p.add(half), u, 0, color, light);
            }
            lastP = p;
            lastSide = half;
            lastU = u;
            lastColor = color;
            if (end) return;
        }
    }

    /** 茎上的一根刺（借叶片的贴图，压窄、拉尖），藤长过它的位置时伸出来。 */
    private static void barb(PoseStack.Pose pose, VertexConsumer buf, Vine v, Barb barb, float reach, int light) {
        float at = barb.at() * v.length();
        float open = VfxParts.smooth(0, .25f, reach - at);
        if (open <= .02f) return;
        Vec3[] pts = v.points();
        float[] along = v.along();
        int i = 1;
        while (i < pts.length - 1 && along[i] < at) i++;
        float k = (at - along[i - 1]) / Math.max(1e-4f, along[i] - along[i - 1]);
        Vec3 base = pts[i - 1].lerp(pts[i], k), tangent = pts[i].subtract(pts[i - 1]).normalize();
        Vec3 a = perpendicular(tangent), b = tangent.cross(a);
        Vec3 outward = a.scale(Math.cos(barb.turn())).add(b.scale(Math.sin(barb.turn()))).add(tangent.scale(.9)).normalize();
        Vec3 across = tangent.cross(outward).normalize().scale(barb.size() * .14f * open);
        Vec3 tip = base.add(outward.scale(barb.size() * open));
        int color = 0xFF000000 | BARB;
        vertex(pose, buf, base.subtract(across), 0, 1, color, light);
        vertex(pose, buf, base.add(across), 1, 1, color, light);
        vertex(pose, buf, tip.add(across), 1, 0, color, light);
        vertex(pose, buf, tip.subtract(across), 0, 0, color, light);
    }

    /** 同 Logos 的藤蔓：原版粒子的顶点阶段（各顶点按世界光照）加 {@code logos:core/vein_vine}（按透明度裁切、着色），双面，写深度。 */
    private static RenderType material(String texture) {
        Identifier id = Identifier.fromNamespaceAndPath("logos", "textures/vfx/" + texture + ".png");
        String key = texture.replace('/', '_');
        var pipeline = RenderPipeline.builder(RenderPipelines.PARTICLE_SNIPPET)
                .withLocation(LogosDimension.id("pipeline/corruption_vine_" + key))
                .withFragmentShader(Identifier.fromNamespaceAndPath("logos", "core/vein_vine")).withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true)).build();
        return RenderType.create("logos_dimension_corruption_vine_" + key, RenderSetup.builder(pipeline).withTexture("Sampler0", id).useLightmap()
                .createRenderSetup());
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buf, Vec3 p, float u, float v, int color, int light) {
        buf.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(color).setUv(u, v).setLight(light).setNormal(pose, 0, 1, 0);
    }

    private static Vec3 bezier(Vec3 a, Vec3 b, Vec3 c, Vec3 d, double t) {
        double u = 1 - t;
        return a.scale(u * u * u).add(b.scale(3 * u * u * t)).add(c.scale(3 * u * t * t)).add(d.scale(t * t * t));
    }

    private static Vec3 perpendicular(Vec3 v) {
        Vec3 n = v.normalize();
        Vec3 other = Math.abs(n.y) < .9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        return n.cross(other).normalize();
    }

    static double now() {
        var level = Minecraft.getInstance().level;
        return level == null ? 0 : level.getGameTime() + Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
    }
}
