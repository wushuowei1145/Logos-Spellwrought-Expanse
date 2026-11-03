package dev.logos.dimension.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.client.VfxParts;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/**
 * 曦誓特效共用的长条：沿一串点、转向镜头的带子（拖尾、光芯、光路）。每个点只算一次朝向镜头的侧向，
 * 相邻两段共用这条边，带子不会在段与段之间错开。贴图的 V 方向是横截面（{@code fire_beam/laser}、{@code glow} 的中间一列）。
 */
final class GoldStrips {
    private GoldStrips() {}

    /** 一条带子：第 i 个点宽 {@code width.at(i)}、颜色 {@code color.at(i)}；U 沿长度从 {@code u0} 每格走 {@code uPerBlock}。 */
    static void strip(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, List<Vec3> points, IntFloat width, IntColor color,
                      float u0, float uPerBlock) {
        int n = points.size();
        if (n < 2) return;
        Vec3 prevA = null, prevB = null;
        float prevU = u0, u = u0;
        int prevC = 0;
        for (int i = 0; i < n; i++) {
            Vec3 p = points.get(i);
            Vec3 along = points.get(Math.min(n - 1, i + 1)).subtract(points.get(Math.max(0, i - 1)));
            Vec3 side = along.cross(p.subtract(view.cam()));
            if (side.lengthSqr() < 1e-12) continue;
            side = side.normalize().scale(width.at(i) * .5);
            if (i > 0) u += (float) p.distanceTo(points.get(i - 1)) * uPerBlock;
            Vec3 a = p.add(side), b = p.subtract(side);
            int c = color.at(i);
            if (prevA != null) {
                VfxParts.vertex(pose, buf, prevA, prevU, 0, prevC);
                VfxParts.vertex(pose, buf, prevB, prevU, 1, prevC);
                VfxParts.vertex(pose, buf, b, u, 1, c);
                VfxParts.vertex(pose, buf, a, u, 0, c);
            }
            prevA = a;
            prevB = b;
            prevU = u;
            prevC = c;
        }
    }

    /** 一条直线段上的带子：从 {@code a} 到 {@code b}，宽度与颜色按长度插值，分 {@code segments} 段。 */
    static void line(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, Vec3 a, Vec3 b, int segments,
                     float w0, float w1, int c0, int c1) {
        List<Vec3> points = new java.util.ArrayList<>(segments + 1);
        for (int i = 0; i <= segments; i++) points.add(a.lerp(b, i / (double) segments));
        strip(pose, buf, view, points, i -> w0 + (w1 - w0) * i / segments, i -> lerpArgb(c0, c1, i / (float) segments), .5f, 0);
    }

    /** 两个 ARGB 颜色之间插值（含透明度）。 */
    static int lerpArgb(int a, int b, float k) {
        int alpha = (int) ((a >>> 24) + ((b >>> 24) - (a >>> 24)) * k);
        return alpha << 24 | VfxParts.lerpColor(a & 0xFFFFFF, b & 0xFFFFFF, k) & 0xFFFFFF;
    }

    @FunctionalInterface interface IntFloat { float at(int i); }
    @FunctionalInterface interface IntColor { int at(int i); }
}
