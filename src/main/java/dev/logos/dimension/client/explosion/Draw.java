package dev.logos.dimension.client.explosion;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.client.VfxParts;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/** 爆裂魔法特效共用的几何与配色：VfxParts 没有的飘带，和吟唱的深蓝、法阵的红金、十字闪的各色。 */
final class Draw {
    private Draw() {}

    // 吟唱：动画里那种深蓝，亮处偏青白
    static final int DEEP = 0x0A2696, MAIN = 0x2C6CFF, LIGHT = 0x9EDFFF, WHITE = 0xFFFFFF;
    // 法阵与火柱：暗红、橙红、金
    static final int RED_DEEP = 0x8A0606, RED = 0xFF3A12, GOLD = 0xFFC94A, PALE_GOLD = 0xFFF0C0;
    // 十字闪的颜色：黄、绿、粉、青、白、橙、紫
    static final int[] SPARKLE = {0xFFE14A, 0x5CFF7A, 0xFF6EC7, 0x5AD8FF, 0xFFFFFF, 0xFF9A3C, 0xB98CFF};
    /** 爆裂魔法用到的 Logos 贴图：进世界后先载一遍，第一次吟唱时不因为读贴图卡一下。 */
    static final String[] TEXTURES = {"arcane_impact/flash", "arcane_impact/star", "arcane_charge/circle_04", "charge/circle_03", "charge/circle_11",
            "charge/circle_15", "charge/circle_17", "charge/pillar", "charge/plasma", "charge/shockwave", "fire_beam/circle", "fire_charge/sun_circle",
            "fire_field/circle_05", "fire_impact/flash", "fire_ray/ring", "force/streak", "heal/glow", "heal/ring", "heal/swirl", "lightning_beam/rays",
            "physical_ray/wind", "pillar_burst/flame", "void_ray/ring", "void_ray/star", "void_beam/flow", "ward/noise", "potion/smoke"};

    /**
     * 一条面向镜头的飘带，穿过 {@code n} 个点：每个点自己的半宽、颜色和贴图的 U（V 横跨飘带）。
     */
    static void ribbon(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, Vec3[] pts, float[] half, int[] colors, float[] u, int n) {
        Vec3 prevA = null, prevB = null;
        for (int i = 0; i < n; i++) {
            Vec3 along = pts[Math.min(n - 1, i + 1)].subtract(pts[Math.max(0, i - 1)]);
            Vec3 side = along.cross(pts[i].subtract(view.cam()));
            double len = side.length();
            side = len < 1e-9 ? Vec3.ZERO : side.scale(half[i] / len);
            Vec3 a = pts[i].add(side), b = pts[i].subtract(side);
            if (prevA != null) {
                VfxParts.vertex(pose, buf, prevA, u[i - 1], 0, colors[i - 1]);
                VfxParts.vertex(pose, buf, prevB, u[i - 1], 1, colors[i - 1]);
                VfxParts.vertex(pose, buf, b, u[i], 1, colors[i]);
                VfxParts.vertex(pose, buf, a, u[i], 0, colors[i]);
            }
            prevA = a;
            prevB = b;
        }
    }

    /** 均匀分布在球面上的单位向量。 */
    static Vec3 unit(RandomSource r) {
        double z = r.nextDouble() * 2 - 1, a = r.nextDouble() * Math.PI * 2, s = Math.sqrt(1 - z * z);
        return new Vec3(s * Math.cos(a), z, s * Math.sin(a));
    }

    /** 垂直于 {@code n} 的两根单位轴。 */
    static Vec3[] basis(Vec3 n) {
        Vec3 up = Math.abs(n.y) < .95 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 a = n.cross(up).normalize();
        return new Vec3[] {a, n.cross(a).normalize()};
    }

    static float smooth(float lo, float hi, float x) {
        return VfxParts.smooth(lo, hi, x);
    }

    /** 0 → 1 → 0 的钟形：寿命里先亮后暗。 */
    static float bell(float k) {
        return k <= 0 || k >= 1 ? 0 : Mth.sin(k * Mth.PI);
    }

    static int argb(float alpha, int rgb) {
        return VfxParts.argb(alpha, rgb);
    }

    static int lerp(int a, int b, float k) {
        return VfxParts.lerpColor(a, b, k);
    }
}
