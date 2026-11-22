package dev.logos.dimension.client.companion;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.client.VfxParts;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.phys.Vec3;

/**
 * 琥珀的伞面（docs/KOHAKU_COMPANION.md 第 8.4 节）：下雨天她拄起长杖当伞柄，杖头上撑开一把魔力凝成的、缥缈的伞。
 * 模型贴图只能全透或不透，所以伞面画成特效，挂在她的右手（{@code RightHand} 骨骼）上，伞沿落在杖头（{@link #TIP}）：
 * 一个浅浅的穹顶，底下一层暗琥珀的薄膜（混合），上面叠一圈慢慢转的星盘和一圈边光（加亮）。克制：就这三层。
 * 大伞（把你也罩进去）要高过你的头，伞沿抬到 {@link #TIP_WIDE}，杖头到伞顶之间连一道淡淡的光当伞柄。
 *
 * <p>骨骼回调里的姿势以「模型像素 / 16」为单位（见 {@link VesperglassOnBone}）；她的模型 2 倍制作、缩放 0.5，32 模型像素是一格。
 */
public final class KohakuUmbrella {
    /**
     * 握的地方（同 RightHandLocator）、杖头离握处多高（进游戏对过）、伞沿离握处多高（她自己的落在杖头上；大的要高过你的头）、
     * 半径、穹顶多高（模型像素，32 是一格）。
     */
    static final Vec3 GRIP = new Vec3(0, -2.5, -1.26);
    static final float STAFF_TOP = 11, TIP = 16, TIP_WIDE = 30, RADIUS = 30, WIDE = 54, HEIGHT = 9, HEIGHT_WIDE = 12;
    /** 颜色：薄膜暗琥珀、星盘琥珀、边光浅金。 */
    static final int DEEP = 0xB8732A, MAIN = 0xE8A043, LIGHT = 0xFFE2A8;
    static final int RINGS = 5, SEGMENTS = 28;

    /** 测试用：画过几帧伞。 */
    private static int draws;

    public static int draws() {
        return draws;
    }

    private KohakuUmbrella() {}

    /** 画伞：{@code open} 0–1 撑开的程度（撑开、收起时缩放），{@code wide} 大伞，{@code clock} 转星盘用的时钟（刻）。 */
    static void submit(PoseStack pose, SubmitNodeCollector out, float open, boolean wide, float clock) {
        if (open <= 0) return;
        draws++;
        float k = VfxParts.smooth(0, 1, open);
        float r = (wide ? WIDE : RADIUS) * (.35f + .65f * k), h = (wide ? HEIGHT_WIDE : HEIGHT) * k, tip = wide ? TIP_WIDE : TIP;
        float spin = clock * .6f;
        pose.pushPose();
        pose.scale(1 / 16f, 1 / 16f, 1 / 16f);
        pose.translate(GRIP.x, GRIP.y, GRIP.z);
        out.submitCustomGeometry(pose, VfxParts.blend("heal/glow"), (p, buf) -> dome(p, buf, r, h, tip, 0, VfxParts.argb(.32f * k, DEEP)));
        out.submitCustomGeometry(pose, VfxParts.add("charge/circle_17"), (p, buf) -> dome(p, buf, r, h, tip, spin, VfxParts.argb(.42f * k, MAIN)));
        out.submitCustomGeometry(pose, VfxParts.add("heal/ring"), (p, buf) -> dome(p, buf, r * 1.02f, h, tip, 0, VfxParts.argb(.38f * k, LIGHT)));
        if (wide) {
            // 杖头到伞顶：两片交叉的光，淡淡的
            float top = tip + h, len = (top - STAFF_TOP) / 2;
            Vec3 mid = new Vec3(0, 0, -(STAFF_TOP + len)), along = new Vec3(0, 0, len);
            out.submitCustomGeometry(pose, VfxParts.add("heal/glow"), (p, buf) -> {
                VfxParts.quad(p, buf, mid, new Vec3(1.6, 0, 0), along, VfxParts.argb(.3f * k, LIGHT));
                VfxParts.quad(p, buf, mid, new Vec3(0, 1.6, 0), along, VfxParts.argb(.3f * k, LIGHT));
            });
        }
        pose.popPose();
    }

    /**
     * 穹顶：边缘一圈在握处往上 {@code tip}、顶点再高 {@code h}、半径 {@code r}；贴图从上往下平铺（星盘、光圈正好落在伞面上），
     * 转 {@code turn} 度。手的 -Z 是长杖朝的方向（撑伞时朝上）。
     */
    private static void dome(PoseStack.Pose p, VertexConsumer buf, float r, float h, float tip, float turn, int color) {
        double t = Math.toRadians(turn);
        for (int i = 0; i < RINGS; i++) {
            float s0 = (float) i / RINGS, s1 = (float) (i + 1) / RINGS;
            for (int j = 0; j < SEGMENTS; j++) {
                double a0 = Math.PI * 2 * j / SEGMENTS, a1 = Math.PI * 2 * (j + 1) / SEGMENTS;
                vertex(p, buf, r, h, tip, s0, a0, t, color);
                vertex(p, buf, r, h, tip, s1, a0, t, color);
                vertex(p, buf, r, h, tip, s1, a1, t, color);
                vertex(p, buf, r, h, tip, s0, a1, t, color);
            }
        }
    }

    private static void vertex(PoseStack.Pose p, VertexConsumer buf, float r, float h, float tip, float s, double a, double turn, int color) {
        double x = r * s * Math.cos(a), y = r * s * Math.sin(a), z = -(tip + h * (1 - s * s));
        float u = (float) (.5 + .5 * s * Math.cos(a + turn)), v = (float) (.5 + .5 * s * Math.sin(a + turn));
        VfxParts.vertex(p, buf, new Vec3(x, y, z), u, v, color);
    }

    /** 伞撑开了多少：撑伞那一段 0.5 秒里撑开，撑着是 1，收伞那 0.4 秒里收起来；别的时候 0。 */
    static float openness(String move, float seconds) {
        return switch (move) {
            case "umbrella_open" -> Math.min(1, seconds / .5f);
            case "umbrella" -> 1;
            case "umbrella_close" -> Math.max(0, 1 - seconds / .4f);
            default -> 0;
        };
    }
}
