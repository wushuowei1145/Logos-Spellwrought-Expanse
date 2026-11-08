package dev.logos.dimension.client.dissolve;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.client.ShaderPackOverlay;
import dev.logos.dimension.dissolve.DissolveShape;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/**
 * 动作武器（战斗骨架画的自定义几何，见 {@code LongswordRig}）的凝聚与消散。原版物品走 {@link DissolveCollector}，
 * 骨架画的武器不经过物品渲染，所以在这里按同样的做法切：每个面切成约一格贴图像素一片，按沿刀身的位置（掺一点噪波）排先后，
 * 阈值以下的片不画，刚好在阈值上的一窄条发光、放粒子（{@link Dissolves#weapon}）。
 * <p>{@code appear} 为真时反过来：片从柄往尖长出来（凝聚）；为假时从尖往柄散掉。只有标了 {@code fading} 的片参与时，其余的片照常画
 * （流火魔力耗尽时只散刀身，刀柄留着）。
 */
public final class WeaponDissolve {
    /** 波前被噪波打乱的程度：0 是一条整齐的线。 */
    private static final float ROUGH = .3f;
    /** 粒子要知道的消散形状：这里只用来记发光条，顺序由 {@link Cells#along} 给。 */
    private static final DissolveShape SHAPE = DissolveShape.sweep(0, 1, 0);

    private WeaponDissolve() {}

    /** 切好的片，都在骨骼空间（乘骨骼矩阵之前）。 */
    public static final class Cells {
        final float[] corners, uv, centers, along, noise;
        final boolean[] fading;
        final int count;

        private Cells(List<float[]> corners, List<float[]> uv, List<float[]> centers, List<Float> along, List<Boolean> fading) {
            count = corners.size();
            this.corners = flat(corners, 12);
            this.uv = flat(uv, 8);
            this.centers = flat(centers, 6);
            this.along = new float[count];
            this.fading = new boolean[count];
            float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
            for (float a : along) { lo = Math.min(lo, a); hi = Math.max(hi, a); }
            float[] raw = new float[count];
            for (int i = 0; i < count; i++) {
                this.along[i] = hi > lo ? (along.get(i) - lo) / (hi - lo) : 0;
                this.fading[i] = fading.get(i);
                raw[i] = DissolveNoise.at(this.centers[i * 6] / 16, this.centers[i * 6 + 1] / 16, this.centers[i * 6 + 2] / 16) + i * 1e-6f;
            }
            noise = DissolveNoise.ranks(raw);
        }

        public int count() { return count; }

        private static float[] flat(List<float[]> list, int stride) {
            float[] out = new float[list.size() * stride];
            for (int i = 0; i < list.size(); i++) System.arraycopy(list.get(i), 0, out, i * stride, stride);
            return out;
        }
    }

    /**
     * 把一组面切成片。{@code quads} 每个面四个角（与 {@code uvs} 的四角顺序相同：0 取 (u1,v1)，1 取 (u0,v1)，2 取 (u0,v0)，3 取 (u1,v0)），
     * {@code uvs} 是 0–1 的 [u0,v0,u1,v1]；{@code texWidth}、{@code texHeight} 是贴图像素，用来定每个面切几片。
     * {@code axis} 是沿刀身的方向（柄到尖），{@code fading} 标出哪些面在只散刀身时参与。
     */
    public static Cells cut(List<Vector3f[]> quads, List<float[]> uvs, List<Boolean> fading, int texWidth, int texHeight, Vector3f axis) {
        List<float[]> corners = new ArrayList<>(), uv = new ArrayList<>(), centers = new ArrayList<>();
        List<Float> along = new ArrayList<>();
        List<Boolean> fades = new ArrayList<>();
        Vector3f dir = new Vector3f(axis).normalize();
        for (int q = 0; q < quads.size(); q++) {
            Vector3f[] p = quads.get(q);
            float[] t = uvs.get(q);
            // 角 0→1 是 u 从 u1 到 u0，角 0→3 是 v 从 v1 到 v0
            Vector3f e1 = new Vector3f(p[1]).sub(p[0]), e3 = new Vector3f(p[3]).sub(p[0]);
            Vector3f n = new Vector3f(e1).cross(e3);
            if (n.lengthSquared() < 1e-12f) continue;
            n.normalize();
            int ns = Mth.clamp(Math.round(Math.abs(t[2] - t[0]) * texWidth), 1, 48);
            int nt = Mth.clamp(Math.round(Math.abs(t[3] - t[1]) * texHeight), 1, 48);
            for (int i = 0; i < ns; i++) for (int j = 0; j < nt; j++) {
                float s0 = i / (float) ns, s1 = (i + 1f) / ns, r0 = j / (float) nt, r1 = (j + 1f) / nt;
                float[] c = new float[12];
                put(c, 0, p[0], e1, e3, s0, r0);
                put(c, 3, p[0], e1, e3, s1, r0);
                put(c, 6, p[0], e1, e3, s1, r1);
                put(c, 9, p[0], e1, e3, s0, r1);
                corners.add(c);
                // 与上面四个角对应的 UV
                float ua = Mth.lerp(s0, t[2], t[0]), ub = Mth.lerp(s1, t[2], t[0]), va = Mth.lerp(r0, t[3], t[1]), vb = Mth.lerp(r1, t[3], t[1]);
                uv.add(new float[] {ua, va, ub, va, ub, vb, ua, vb});
                float cx = (c[0] + c[3] + c[6] + c[9]) / 4, cy = (c[1] + c[4] + c[7] + c[10]) / 4, cz = (c[2] + c[5] + c[8] + c[11]) / 4;
                centers.add(new float[] {cx, cy, cz, n.x, n.y, n.z});
                along.add(cx * dir.x + cy * dir.y + cz * dir.z);
                fades.add(fading.get(q));
            }
        }
        return new Cells(corners, uv, centers, along, fades);
    }

    private static void put(float[] out, int at, Vector3f p0, Vector3f e1, Vector3f e3, float s, float t) {
        out[at] = p0.x + e1.x * s + e3.x * t;
        out[at + 1] = p0.y + e1.y * s + e3.y * t;
        out[at + 2] = p0.z + e1.z * s + e3.z * t;
    }

    /** 这一片什么时候轮到，0 到 1：凝聚从柄往尖，消散从尖往柄。 */
    private static float value(Cells cells, int i, boolean appear) {
        float a = appear ? cells.along[i] : 1 - cells.along[i];
        return a * (1 - ROUGH) + cells.noise[i] * ROUGH;
    }

    /**
     * 画到 {@code progress}（0 到 1）为止的样子。{@code bone} 把片从骨骼空间换到 {@code pose} 的空间；
     * {@code bladeOnly} 时只有标了 fading 的片参与，其余照常画。{@code key} 区分放粒子的是谁（同一人第一、第三人称分开）。
     */
    public static void submit(Object key, Cells cells, Matrix4fc bone, PoseStack pose, SubmitNodeCollector collector, RenderType type, int light,
            int alpha, float progress, boolean appear, boolean bladeOnly, int color, boolean firstPerson) {
        progress = Mth.clamp(progress, 0, 1);
        // 凝聚：阈值从比所有值都低到比所有值都高一个发光条；值低于阈值的片已经长出来
        float threshold = appear ? Mth.lerp(progress, 0, 1 + Dissolve.WIDTH) : Mth.lerp(progress, -Dissolve.WIDTH, 1);
        Dissolve edge = Dissolves.weapon(key, SHAPE, color);
        Dissolve.Glow glow = new Dissolve.Glow();
        Matrix4f toWorld = firstPerson ? Dissolve.firstPersonToWorld(Minecraft.getInstance().gameRenderer.getMainCamera().rotation()) : null;
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        long frame = Dissolves.frame();
        int tick = Dissolves.tick();
        PoseStack.Pose last = pose.last();
        Matrix4f full = new Matrix4f(last.pose()).mul(bone);
        List<float[]> kept = new ArrayList<>(cells.count);
        Vector3f a = new Vector3f(), b = new Vector3f(), c = new Vector3f(), d = new Vector3f(), n = new Vector3f(), m = new Vector3f();
        boolean particles = !Dissolves.shadowPass();
        for (int i = 0; i < cells.count; i++) {
            float[] k = cells.corners;
            int o = i * 12;
            bone.transformPosition(k[o], k[o + 1], k[o + 2], a);
            bone.transformPosition(k[o + 3], k[o + 4], k[o + 5], b);
            bone.transformPosition(k[o + 6], k[o + 7], k[o + 8], c);
            bone.transformPosition(k[o + 9], k[o + 10], k[o + 11], d);
            bone.transformDirection(cells.centers[i * 6 + 3], cells.centers[i * 6 + 4], cells.centers[i * 6 + 5], n).normalize();
            if (bladeOnly && !cells.fading[i]) {
                kept.add(vertexData(a, b, c, d, n, cells.uv, i));
                continue;
            }
            float v = value(cells, i, appear);
            boolean shown = appear ? v < threshold : v > threshold;
            if (!shown) continue;
            kept.add(vertexData(a, b, c, d, n, cells.uv, i));
            float gap = appear ? threshold - v : v - threshold;
            if (gap >= Dissolve.WIDTH) continue;
            float heat = 1 - gap / Dissolve.WIDTH;
            // 发光条的面在 pose 的空间里给出（Glow 自己不带姿势）
            Vector3f pa = last.pose().transformPosition(a, new Vector3f()), pb = last.pose().transformPosition(b, new Vector3f());
            Vector3f pc = last.pose().transformPosition(c, new Vector3f()), pd = last.pose().transformPosition(d, new Vector3f());
            Vector3f pn = last.transformNormal(n.x, n.y, n.z, new Vector3f());
            glow.corners(pa.x, pa.y, pa.z, pb.x, pb.y, pb.z, pc.x, pc.y, pc.z, pd.x, pd.y, pd.z, pn.x, pn.y, pn.z, heat, edge.color, edge.petals);
            if (particles) {
                full.transformPosition(cells.centers[i * 6], cells.centers[i * 6 + 1], cells.centers[i * 6 + 2], m);
                Vector3f wn = new Vector3f(pn);
                if (toWorld != null) {
                    toWorld.transformPosition(m);
                    toWorld.transformDirection(wn);
                }
                edge.sample(m.x, m.y, m.z, wn.x, wn.y, wn.z, camera, frame, tick);
            }
        }
        if (!kept.isEmpty()) collector.order(-1).submitCustomGeometry(pose, type, (p, buffer) -> {
            for (float[] q : kept) for (int j = 0; j < 4; j++) {
                buffer.addVertex(p, q[j * 3], q[j * 3 + 1], q[j * 3 + 2]).setColor(255, 255, 255, alpha).setUv(q[12 + j * 2], q[13 + j * 2])
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, q[20], q[21], q[22]);
            }
        });
        if (!glow.empty()) {
            PoseStack identity = new PoseStack();
            glow.submit(identity, ShaderPackOverlay.item(collector, firstPerson));
            if (particles) glow.bloom(firstPerson);
        }
    }

    /** 一片的四个角（骨骼矩阵之后）、四角 UV 和法线，按提交顺序摆好。 */
    private static float[] vertexData(Vector3f a, Vector3f b, Vector3f c, Vector3f d, Vector3f n, float[] uv, int i) {
        float[] q = new float[23];
        Vector3f[] v = {a, b, c, d};
        for (int j = 0; j < 4; j++) {
            q[j * 3] = v[j].x; q[j * 3 + 1] = v[j].y; q[j * 3 + 2] = v[j].z;
        }
        System.arraycopy(uv, i * 8, q, 12, 8);
        q[20] = n.x; q[21] = n.y; q[22] = n.z;
        return q;
    }
}
