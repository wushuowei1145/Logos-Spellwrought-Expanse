package dev.logos.dimension.client.dissolve;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.client.VfxParts;
import dev.logos.dimension.client.longsword.SwordCastClient;
import dev.logos.dimension.client.screenfx.GlowBloom;
import dev.logos.dimension.dissolve.DissolveShape;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 一个正在消散的东西（参考虚幻的 NiagaraDissolveEdge）。和它的材质、粒子脚本一样：目标的每一格有一个值，阈值随时间上升，
 * 值低于阈值的格子消失，刚好高于阈值的一窄条（{@link #WIDTH}）发光并放粒子。
 * <p>值在目标自己的空间（图案空间）里算：生物以脚底为原点，物品用模型空间，方块用整组的空间。
 * 这个空间的范围把形状的波前换算成 0 到 1，范围随画出来的东西扩大。
 */
final class Dissolve {
    /** 发光的那一窄条占 0 到 1 的多少。 */
    static final float WIDTH = .05f;
    private static final int MAX_SAMPLES = 1024;
    static final RenderType GLOW = VfxParts.add("fire_impact/glow");

    final DissolveShape shape;
    final float start;
    final int ticks, color;
    /** 放樱花瓣而不是火星（颜色带 {@link dev.logos.dimension.dissolve.Dissolving#PETALS}）。 */
    final boolean petals;
    private final Vector3f min = new Vector3f(Float.MAX_VALUE), max = new Vector3f(-Float.MAX_VALUE);
    private float originX, originY, originZ, reach = 1, from, span = 1;
    private boolean dirty = true;

    /** 上一帧画到的发光格子，世界坐标：位置和法线。 */
    final float[] samples = new float[MAX_SAMPLES * 6];
    int sampled;
    private int seen;
    private long frame = -1;

    Dissolve(DissolveShape shape, float start, int ticks, int color) {
        this.shape = shape;
        this.start = start;
        this.ticks = ticks;
        this.color = color & 0xFFFFFF;
        this.petals = (color & dev.logos.dimension.dissolve.Dissolving.PETALS) != 0;
    }

    float progress(float now) { return (now - start) / ticks; }

    boolean gone(float now) { return progress(now) >= 1; }

    /** 阈值：从比所有值都低一点（还没有发光条）到比所有值都高。 */
    float threshold(float now) { return Mth.lerp(Mth.clamp(progress(now), 0, 1), -WIDTH, 1); }

    void include(float x, float y, float z) {
        if (x < min.x || y < min.y || z < min.z || x > max.x || y > max.y || z > max.z) {
            min.min(new Vector3f(x, y, z));
            max.max(new Vector3f(x, y, z));
            dirty = true;
        }
    }

    private void settle() {
        dirty = false;
        float dx = max.x - min.x, dy = max.y - min.y, dz = max.z - min.z;
        switch (shape.kind()) {
            case SPHERE, POINT -> {
                boolean unit = shape.kind() == DissolveShape.Kind.SPHERE;
                originX = unit ? min.x + shape.x() * dx : shape.x();
                originY = unit ? min.y + shape.y() * dy : shape.y();
                originZ = unit ? min.z + shape.z() * dz : shape.z();
                float rx = Math.max(Math.abs(min.x - originX), Math.abs(max.x - originX));
                float ry = Math.max(Math.abs(min.y - originY), Math.abs(max.y - originY));
                float rz = Math.max(Math.abs(min.z - originZ), Math.abs(max.z - originZ));
                reach = Math.max(1e-3f, Mth.sqrt(rx * rx + ry * ry + rz * rz));
            }
            case SWEEP -> {
                float len = Math.max(1e-4f, Mth.sqrt(shape.x() * shape.x() + shape.y() * shape.y() + shape.z() * shape.z()));
                originX = shape.x() / len;
                originY = shape.y() / len;
                originZ = shape.z() / len;
                from = Math.min(min.x * originX, max.x * originX) + Math.min(min.y * originY, max.y * originY) + Math.min(min.z * originZ, max.z * originZ);
                span = Math.max(1e-3f, Math.abs(dx * originX) + Math.abs(dy * originY) + Math.abs(dz * originZ));
            }
            case NOISE -> {}
        }
    }

    /** 图案空间里这一点的格子什么时候消失，0 到 1。 */
    float value(float x, float y, float z, float noise) {
        if (shape.kind() == DissolveShape.Kind.NOISE) return shape.inward() ? 1 - noise : noise;
        if (dirty) settle();
        float along;
        if (shape.kind() == DissolveShape.Kind.SWEEP) {
            along = (x * originX + y * originY + z * originZ - from) / span;
        } else {
            float dx = x - originX, dy = y - originY, dz = z - originZ;
            along = Mth.sqrt(dx * dx + dy * dy + dz * dz) / reach;
        }
        along = Mth.clamp(along, 0, 1);
        if (shape.inward()) along = 1 - along;
        return along * (1 - shape.rough()) + noise * shape.rough();
    }

    /** 记下一个发光格子给粒子用：{@code x y z} 相对镜头，世界坐标轴。 */
    void sample(float x, float y, float z, float nx, float ny, float nz, Vec3 camera, long frameNow, int tick) {
        if (frame != frameNow) {
            frame = frameNow;
            sampled = 0;
        }
        seen = tick;
        // 记满之后新的覆盖旧的：粒子只需要大致知道发光条在哪
        int at = sampled < MAX_SAMPLES ? sampled++ : (int) (Math.abs(x * 7919 + y * 104729 + z * 1299709) % MAX_SAMPLES);
        samples[at * 6] = (float) (camera.x + x);
        samples[at * 6 + 1] = (float) (camera.y + y);
        samples[at * 6 + 2] = (float) (camera.z + z);
        samples[at * 6 + 3] = nx;
        samples[at * 6 + 4] = ny;
        samples[at * 6 + 5] = nz;
    }

    /** 最近画到过发光条没有（看不见的目标不放粒子）。 */
    boolean visible(int tick) { return sampled > 0 && tick - seen <= 2; }

    /** 动作武器的消散：好几刻没再画到发光条，可以丢掉了。 */
    boolean stale(int tick) { return tick - seen > 10 && tick - start > 10; }

    /**
     * {@code mesh} 还留下的格子，发光条记进 {@code glow}。{@code pose} 是格子的姿势，{@code toPattern} 把模型空间换到图案空间，
     * {@code toWorld} 把姿势之后的空间换到相对镜头的世界坐标轴（已经是的话为 null）；{@code noise} 用模型自己的，或者按方块位置算的。
     */
    List<BakedQuad> left(DissolveMesh mesh, float[] noise, PoseStack.Pose pose, Matrix4fc toPattern, @Nullable Matrix4fc toWorld, float now,
            Glow glow, boolean particles, Vec3 camera, long frameNow, int tick) {
        float threshold = threshold(now);
        List<BakedQuad> left = new ArrayList<>(mesh.quads.length);
        Vector3f p = new Vector3f(), n = new Vector3f();
        float[] cells = mesh.cells;
        for (int i = 0; i < mesh.quads.length; i++) {
            toPattern.transformPosition(cells[i * 6], cells[i * 6 + 1], cells[i * 6 + 2], p);
            include(p.x, p.y, p.z);
            float v = value(p.x, p.y, p.z, noise[i]);
            if (v <= threshold) continue;
            BakedQuad quad = mesh.quads[i];
            left.add(quad);
            if (v >= threshold + WIDTH) continue;
            float heat = 1 - (v - threshold) / WIDTH;
            pose.pose().transformPosition(cells[i * 6], cells[i * 6 + 1], cells[i * 6 + 2], p);
            pose.transformNormal(cells[i * 6 + 3], cells[i * 6 + 4], cells[i * 6 + 5], n);
            glow.quad(pose, quad, n, heat, color, petals);
            if (particles) {
                if (toWorld != null) {
                    toWorld.transformPosition(p);
                    toWorld.transformDirection(n);
                }
                sample(p.x, p.y, p.z, n.x, n.y, n.z, camera, frameNow, tick);
            }
        }
        return left;
    }

    /** 一次提交里发光的面，以加法叠在它们贴着的格子上。 */
    static final class Glow {
        private static final float GROW = 1.15f, LIFT = .004f;
        /** 发光条最热处的颜色：火偏金，花瓣偏白。 */
        private static final int HOT = 0xFFD9A0, HOT_PETAL = 0xFFF4FA;

        private float[] data = new float[8 * 4 * 32];
        private int size;

        boolean empty() { return size == 0; }

        void quad(PoseStack.Pose pose, BakedQuad q, Vector3f normal, float heat, int color, boolean petals) {
            Vector3f a = pose.pose().transformPosition(q.position0(), new Vector3f()), b = pose.pose().transformPosition(q.position1(), new Vector3f());
            Vector3f c = pose.pose().transformPosition(q.position2(), new Vector3f()), d = pose.pose().transformPosition(q.position3(), new Vector3f());
            corners(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z, d.x, d.y, d.z, normal.x, normal.y, normal.z, heat, color, petals);
        }

        /** 从中心稍微放大，让发光条连成一片，再抬离表面一点。 */
        void corners(float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz, float dx, float dy, float dz,
                float nx, float ny, float nz, float heat, int color, boolean petals) {
            float mx = (ax + bx + cx + dx) / 4, my = (ay + by + cy + dy) / 4, mz = (az + bz + cz + dz) / 4;
            // 背对镜头的面在模型上不画（剔除了），透过洞看过去它的光会悬在半空
            if (mx * nx + my * ny + mz * nz > 0) return;
            if (size + 16 > data.length) data = Arrays.copyOf(data, data.length * 2);
            int argb = VfxParts.argb(.15f + .6f * heat * heat, VfxParts.lerpColor(color, petals ? HOT_PETAL : HOT, heat * heat * heat));
            float[][] v = {{ax, ay, az}, {bx, by, bz}, {cx, cy, cz}, {dx, dy, dz}};
            for (float[] p : v) {
                data[size++] = mx + (p[0] - mx) * GROW + nx * LIFT;
                data[size++] = my + (p[1] - my) * GROW + ny * LIFT;
                data[size++] = mz + (p[2] - mz) * GROW + nz * LIFT;
                data[size++] = Float.intBitsToFloat(argb);
            }
        }

        /** 同一份发光也交给真正的泛光。 */
        void bloom(boolean firstPerson) { GlowBloom.quads(data, size, firstPerson); }

        void draw(VertexConsumer buf) {
            for (int i = 0; i < size; i += 4) {
                buf.addVertex(data[i], data[i + 1], data[i + 2]).setUv(.5f, .5f).setColor(Float.floatToRawIntBits(data[i + 3]))
                        .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(0, 1, 0);
            }
        }

        /** 提交发光；几何里已经带着姿势了。 */
        void submit(PoseStack poseStack, OrderedSubmitNodeCollector out) {
            if (empty()) return;
            out.submitCustomGeometry(poseStack, GLOW, (pose, buf) -> draw(buf));
        }
    }

    /** 第一人称时，从姿势之后的空间到相对镜头的世界坐标轴。 */
    static Matrix4f firstPersonToWorld(Quaternionfc cameraRotation) {
        return new Matrix4f().rotation(cameraRotation).mul(SwordCastClient.inView(new Matrix4f()));
    }
}
