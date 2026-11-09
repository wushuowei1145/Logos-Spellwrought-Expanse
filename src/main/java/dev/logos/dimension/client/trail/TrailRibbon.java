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
        if (!open) { segment++; open = true; points.add(new Point(time, base, tip, segment, 0)); return; }
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
    }

    /**
     * 画一层。坐标先减去 {@code origin}（世界坐标时是镜头位置，手部坐标时是原点）。
     * {@code fade} 是整道刀光的淡出（刚断开时为 1），{@code seed} 让同一样式的两道刀光纹理不同。
     */
    void build(PoseStack.Pose pose, VertexConsumer buf, TrailStyle.Layer layer, double now, float life, Vec3 origin, int seed, float fade) {
        for (int i = 1; i < points.size(); i++) {
            Point a = points.get(i - 1), b = points.get(i);
            if (a.segment != b.segment) continue;
            float sa = (float) Math.min(1, (now - a.time) / life), sb = (float) Math.min(1, (now - b.time) / life);
            if (sa >= 1 && sb >= 1) continue;
            int light = (seed + a.segment * 7919) & 0x7FFF;
            corner(pose, buf, a, layer.inner(), origin, sa, 0, light, fade);
            corner(pose, buf, a, layer.outer(), origin, sa, 1, light, fade);
            corner(pose, buf, b, layer.outer(), origin, sb, 1, light, fade);
            corner(pose, buf, b, layer.inner(), origin, sb, 0, light, fade);
        }
    }

    private static void corner(PoseStack.Pose pose, VertexConsumer buf, Point p, float across, Vec3 origin, float age, float v,
                               int seed, float fade) {
        double x = p.base.x + (p.tip.x - p.base.x) * across - origin.x;
        double y = p.base.y + (p.tip.y - p.base.y) * across - origin.y;
        double z = p.base.z + (p.tip.z - p.base.z) * across - origin.z;
        int arc = Math.floorMod(Math.round(p.arc * 64), 32768);
        int alpha = Math.round(Math.clamp(fade, 0, 1) * 255);
        buf.addVertex(pose, (float) x, (float) y, (float) z).setColor(255, 255, 255, alpha).setUv(age, v)
                .setLight(arc | seed << 16);
    }

    /** 最近一段里刀尖走过的点（新到旧），给泛光与粒子用。 */
    List<Point> points() { return points; }
}
