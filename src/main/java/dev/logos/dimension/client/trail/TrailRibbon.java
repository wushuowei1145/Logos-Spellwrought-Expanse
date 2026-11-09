package dev.logos.dimension.client.trail;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/**
 * 一道刀光扫过的面：按时间先后记下的刀身（柄端 base、尖端 tip），相邻两次之间连成四边形，就是刀身真正扫过的那片面，
 * 所以刀光一定顺着刀的运动方向。停止发光再开始时另起一段，两刀之间不会连起来。
 */
final class TrailRibbon {
    /** 一次取样。{@code arc}：从这一段开始起刀尖走过的路程（格）。 */
    record Point(double time, Vec3 base, Vec3 tip, int segment, float arc) {}

    private final List<Point> points = new ArrayList<>();
    private int segment;
    private boolean open;

    boolean isEmpty() { return points.isEmpty(); }
    boolean emitting() { return open; }
    Point last() { return points.isEmpty() ? null : points.getLast(); }

    /** 记一点；{@code emit} 为假时只断开当前这一段（已有的照常散去）。时间不晚于上一点的不记（同一帧画了两次）。 */
    void add(double time, Vec3 base, Vec3 tip, boolean emit) {
        if (!emit) { open = false; return; }
        Point last = last();
        if (last != null && time <= last.time) return;
        if (!open || last == null) { segment++; open = true; points.add(new Point(time, base, tip, segment, 0)); return; }
        float arc = last.segment == segment ? last.arc + (float) last.tip.distanceTo(tip) : 0;
        points.add(new Point(time, base, tip, segment, arc));
    }

    /**
     * 只能逐帧取到刀身的（NPC 的骨骼）：用 Catmull-Rom 在上一帧与这一帧之间补点，刀尖每走 {@code step} 格补一个，
     * 快速挥砍在低帧率下也是圆弧而不是折线。补的是倒数第二段（要用到前后各一点），所以最新的一小段先是直的，下一帧补圆。
     */
    void addSmoothed(double time, Vec3 base, Vec3 tip, boolean emit, float step) {
        if (!emit || !open) { add(time, base, tip, emit); return; }
        Point last = last();
        if (last == null || time <= last.time) return;
        int n = points.size();
        // p0 p1 p2 已在列表里（同一段），p3 是新来的；在 p1 与 p2 之间补点
        if (n >= 3 && points.get(n - 3).segment == segment) {
            Point p0 = points.get(n - 3), p1 = points.get(n - 2), p2 = points.get(n - 1);
            int count = Math.min(12, (int) (p1.tip.distanceTo(p2.tip) / step));
            if (count > 0) {
                List<Point> fill = new ArrayList<>(count);
                float arc = p1.arc;
                Vec3 prev = p1.tip;
                for (int i = 1; i <= count; i++) {
                    double k = i / (double) (count + 1);
                    Vec3 b = spline(p0.base, p1.base, p2.base, base, k), t = spline(p0.tip, p1.tip, p2.tip, tip, k);
                    arc += (float) prev.distanceTo(t);
                    prev = t;
                    fill.add(new Point(p1.time + (p2.time - p1.time) * k, b, t, segment, arc));
                }
                points.remove(n - 1);
                points.addAll(fill);
                points.add(new Point(p2.time, p2.base, p2.tip, segment, arc + (float) prev.distanceTo(p2.tip)));
            }
        }
        add(time, base, tip, true);
    }

    private static Vec3 spline(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double t) {
        double t2 = t * t, t3 = t2 * t;
        return p1.scale(2).add(p2.subtract(p0).scale(t)).add(p0.scale(2).subtract(p1.scale(5)).add(p2.scale(4)).subtract(p3).scale(t2))
                .add(p1.scale(3).subtract(p0).subtract(p2.scale(3)).add(p3).scale(t3)).scale(.5);
    }

    /** 去掉已经散尽的点。 */
    void prune(double now, float life) {
        int drop = 0;
        while (drop < points.size() && now - points.get(drop).time > life) drop++;
        // 下一点还在同一段时留下最老的这一点做尾巴，让散去是渐隐而不是一截截消失
        if (drop > 0 && drop < points.size() && points.get(drop).segment == points.get(drop - 1).segment) drop--;
        if (drop > 0) points.subList(0, drop).clear();
        // 送点的一方停了比 life 还久（被视锥剔除、F1 藏了手）：这一段已散尽，下次另起一段
        if (points.isEmpty()) open = false;
    }

    /** 某一段的淡出：还在发光的那一段为 1；已断开的一段从它最后一点起，用 life 的六成淡完，避免最后几点悬在空中。 */
    private float fade(int seg, double end, double now, float life) {
        if (open && seg == segment) return 1;
        return (float) Math.max(0, 1 - (now - end) / (life * .6));
    }

    /**
     * 画一层。坐标先减去 {@code origin}（世界坐标时是镜头位置，手部坐标时是原点）。
     * 每一段按自己的淡出画（见 {@link #fade}），{@code seed} 让同一样式的两道刀光纹理不同。
     * 越老的点柄那一侧收掉越多（{@link #inset}），离刀越远刀光越窄；{@code expand} 让刀光在散尽前顺着刀身往外飘出这么多格。
     */
    void build(PoseStack.Pose pose, VertexConsumer buf, TrailStyle.Layer layer, double now, float life, float expand, Vec3 origin, int seed) {
        float fade = 0;
        for (int i = 1; i < points.size(); i++) {
            Point a = points.get(i - 1), b = points.get(i);
            if (a.segment != b.segment) continue;
            // 进入新的一段时，找到这一段最后一点的时刻算淡出
            if (i == 1 || points.get(i - 2).segment != a.segment) {
                int j = i;
                while (j + 1 < points.size() && points.get(j + 1).segment == a.segment) j++;
                fade = fade(a.segment, points.get(j).time, now, life);
            }
            if (fade <= 0) continue;
            float sa = (float) Math.min(1, (now - a.time) / life), sb = (float) Math.min(1, (now - b.time) / life);
            if (sa >= 1 && sb >= 1) continue;
            int light = (seed + a.segment * 7919) & 0x7FFF;
            float va = inset(layer, sa), vb = inset(layer, sb), width = layer.outer() - layer.inner();
            corner(pose, buf, a, layer.inner() + width * va, origin, sa, va, light, fade, expand);
            corner(pose, buf, a, layer.outer(), origin, sa, 1, light, fade, expand);
            corner(pose, buf, b, layer.outer(), origin, sb, 1, light, fade, expand);
            corner(pose, buf, b, layer.inner() + width * vb, origin, sb, vb, light, fade, expand);
            if (layer.facing() > 0) facing(pose, buf, a, b, layer, origin, sa, sb, light, fade, expand);
        }
    }

    /**
     * 年龄 {@code age} 处柄那一侧收掉的比例（横跨这一层的 0–1）：着色器里收窄的软边从 narrow × age^pow 起，
     * 几何上少裁一点，留出软边。
     */
    static float inset(TrailStyle.Layer layer, float age) {
        return Math.clamp(layer.narrow() * (float) Math.pow(age, layer.narrowPow()) - .04f, 0, .97f);
    }

    /** 顺着刀身往外飘：这一点在年龄 {@code age} 时外移的量。 */
    private static Vec3 drift(Point p, float age, float expand) {
        if (expand == 0 || age <= 0) return Vec3.ZERO;
        Vec3 blade = p.tip.subtract(p.base);
        double len = blade.length();
        return len < 1e-6 ? Vec3.ZERO : blade.scale(expand * age / len);
    }

    /**
     * 沿刀尖路径朝向镜头的带子：中线是刃（纵向坐标 1，最亮），两侧到 0。扫过的面越是侧对镜头越显，
     * 正对镜头时淡成 0（那时扫过的面本身看得清）。镜头在 {@code origin}（减完以后就是原点）。
     */
    private static void facing(PoseStack.Pose pose, VertexConsumer buf, Point a, Point b, TrailStyle.Layer layer, Vec3 origin,
                               float sa, float sb, int seed, float fade, float expand) {
        Vec3 ta = a.base.lerp(a.tip, layer.outer()).add(drift(a, sa, expand)), tb = b.base.lerp(b.tip, layer.outer()).add(drift(b, sb, expand));
        Vec3 along = tb.subtract(ta);
        if (along.lengthSqr() < 1e-8) return;
        Vec3 mid = ta.add(tb).scale(.5).subtract(origin);
        Vec3 view = mid.lengthSqr() < 1e-8 ? new Vec3(0, 0, 1) : mid.normalize();
        Vec3 side = along.cross(view);
        if (side.lengthSqr() < 1e-10) return;
        side = side.normalize().scale(layer.facing());
        // 扫过的面的法线：刀身方向 × 刀尖走的方向；与视线越垂直（侧对）带子越亮
        Vec3 normal = a.tip.subtract(a.base).cross(along);
        float edgeOn = normal.lengthSqr() < 1e-10 ? 1 : 1 - (float) Math.abs(normal.normalize().dot(view));
        float f = fade * edgeOn * edgeOn;
        if (f < .02f) return;
        for (int sgn = -1; sgn <= 1; sgn += 2) {
            Vec3 off = side.scale(sgn);
            strip(pose, buf, ta, origin, sa, 1, seed, f, a.arc);
            strip(pose, buf, ta.add(off), origin, sa, 0, seed, f, a.arc);
            strip(pose, buf, tb.add(off), origin, sb, 0, seed, f, b.arc);
            strip(pose, buf, tb, origin, sb, 1, seed, f, b.arc);
        }
    }

    private static void strip(PoseStack.Pose pose, VertexConsumer buf, Vec3 p, Vec3 origin, float age, float v, int seed, float fade, float arc) {
        int units = Math.floorMod(Math.round(arc * 64), 32768);
        buf.addVertex(pose, (float) (p.x - origin.x), (float) (p.y - origin.y), (float) (p.z - origin.z))
                .setColor(255, 255, 255, Math.round(Math.clamp(fade, 0, 1) * 255)).setUv(age, v).setLight(units | seed << 16);
    }

    private static void corner(PoseStack.Pose pose, VertexConsumer buf, Point p, float across, Vec3 origin, float age, float v,
                               int seed, float fade, float expand) {
        Vec3 out = drift(p, age, expand);
        double x = p.base.x + (p.tip.x - p.base.x) * across + out.x - origin.x;
        double y = p.base.y + (p.tip.y - p.base.y) * across + out.y - origin.y;
        double z = p.base.z + (p.tip.z - p.base.z) * across + out.z - origin.z;
        int arc = Math.floorMod(Math.round(p.arc * 64), 32768);
        int alpha = Math.round(Math.clamp(fade, 0, 1) * 255);
        buf.addVertex(pose, (float) x, (float) y, (float) z).setColor(255, 255, 255, alpha).setUv(age, v)
                .setLight(arc | seed << 16);
    }

    /** 最近一段里刀尖走过的点（新到旧），给泛光与粒子用。 */
    List<Point> points() { return points; }
}
