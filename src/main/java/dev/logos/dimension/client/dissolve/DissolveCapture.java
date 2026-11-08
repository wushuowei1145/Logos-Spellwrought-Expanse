package dev.logos.dimension.client.dissolve;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 代替真正的缓冲接收模型写出的四边形，再切成小格。用于逐顶点写的东西：生物模型、GeckoLib 模型、三叉戟和盾牌。
 * 不管什么模型，面都从这里过。噪波按贴图位置取，模型动起来洞也粘在皮肤上，和原版用网格 UV 取噪波一样。
 */
final class DissolveCapture implements VertexConsumer {
    /** 每个顶点：位置、颜色、UV、overlay、光照、法线。 */
    private static final int STRIDE = 11;
    /** 沿贴图像素的一边切几格，也就是每个像素四格。 */
    private static final int PER_TEXEL = 2;
    /** 不知道贴图时按每格多少小格切，正常大小下是半个模型像素。 */
    private static final int CELLS_PER_BLOCK = 32, MAX_CELLS = 64;
    /** 读不到贴图时当它是这个尺寸；只影响噪波的疏密。 */
    private static final int ASSUMED_TEXTURE = 64;

    private float[] in = new float[STRIDE * 4 * 64];
    private int vertices;

    @Override public VertexConsumer addVertex(float x, float y, float z) {
        if ((vertices + 1) * STRIDE > in.length) in = Arrays.copyOf(in, in.length * 2);
        int o = vertices++ * STRIDE;
        in[o] = x;
        in[o + 1] = y;
        in[o + 2] = z;
        in[o + 3] = Float.intBitsToFloat(-1);
        for (int i = 4; i < STRIDE; i++) in[o + i] = 0;
        in[o + 9] = 1;
        return this;
    }

    private int last() { return (vertices - 1) * STRIDE; }

    @Override public VertexConsumer setColor(int r, int g, int b, int a) { return setColor(ARGB.color(a, r, g, b)); }

    @Override public VertexConsumer setColor(int color) { in[last() + 3] = Float.intBitsToFloat(color); return this; }

    @Override public VertexConsumer setUv(float u, float v) { in[last() + 4] = u; in[last() + 5] = v; return this; }

    @Override public VertexConsumer setUv1(int u, int v) { in[last() + 6] = Float.intBitsToFloat(u & 0xFFFF | v << 16); return this; }

    @Override public VertexConsumer setUv2(int u, int v) { in[last() + 7] = Float.intBitsToFloat(u & 0xFFFF | v << 16); return this; }

    @Override public VertexConsumer setNormal(float x, float y, float z) {
        in[last() + 8] = x; in[last() + 9] = y; in[last() + 10] = z;
        return this;
    }

    @Override public VertexConsumer setLineWidth(float width) { return this; }

    /** 接到的四边形还留下的部分，等 {@link Left#draw} 画；消失边缘的发光记进 {@code glow}。 */
    Left cut(Dissolve dissolve, @Nullable DissolveSkin skin, Matrix4fc toPattern, @Nullable Matrix4fc toWorld, float now,
            Dissolve.Glow glow, boolean particles, Vec3 camera, long frame, int tick) {
        Left left = new Left();
        float threshold = dissolve.threshold(now);
        Vector3f p = new Vector3f(), n = new Vector3f();
        int width = skin != null ? skin.width : ASSUMED_TEXTURE, height = skin != null ? skin.height : ASSUMED_TEXTURE;
        float[] v = in;
        for (int q = 0; q + 3 < vertices; q += 4) {
            int a = q * STRIDE, b = a + STRIDE, d = a + 3 * STRIDE;
            float ex = v[b] - v[a], ey = v[b + 1] - v[a + 1], ez = v[b + 2] - v[a + 2];
            float fx = v[d] - v[a], fy = v[d + 1] - v[a + 1], fz = v[d + 2] - v[a + 2];
            float du1 = v[b + 4] - v[a + 4], dv1 = v[b + 5] - v[a + 5], du3 = v[d + 4] - v[a + 4], dv3 = v[d + 5] - v[a + 5];
            int ns = cells(Mth.sqrt(ex * ex + ey * ey + ez * ez), du1, dv1, skin, particles);
            int nt = cells(Mth.sqrt(fx * fx + fy * fy + fz * fz), du3, dv3, skin, particles);
            float nx = v[a + 8], ny = v[a + 9], nz = v[a + 10];
            for (int i = 0; i < ns; i++) for (int j = 0; j < nt; j++) {
                float s0 = i / (float) ns, s1 = (i + 1f) / ns, t0 = j / (float) nt, t1 = (j + 1f) / nt, sc = (s0 + s1) / 2, tc = (t0 + t1) / 2;
                float uc = v[a + 4] + sc * du1 + tc * du3, vc = v[a + 5] + sc * dv1 + tc * dv3;
                if (skin != null && !skin.solid(uc, vc)) continue;
                float cx = v[a] + sc * ex + tc * fx, cy = v[a + 1] + sc * ey + tc * fy, cz = v[a + 2] + sc * ez + tc * fz;
                toPattern.transformPosition(cx, cy, cz, p);
                dissolve.include(p.x, p.y, p.z);
                float value = dissolve.value(p.x, p.y, p.z, DissolveNoise.uv(uc, vc, width, height));
                if (value <= threshold) continue;
                left.corner(v, a, ex, ey, ez, fx, fy, fz, du1, dv1, du3, dv3, s0, t0);
                left.corner(v, a, ex, ey, ez, fx, fy, fz, du1, dv1, du3, dv3, s1, t0);
                left.corner(v, a, ex, ey, ez, fx, fy, fz, du1, dv1, du3, dv3, s1, t1);
                left.corner(v, a, ex, ey, ez, fx, fy, fz, du1, dv1, du3, dv3, s0, t1);
                if (value >= threshold + Dissolve.WIDTH) continue;
                float heat = 1 - (value - threshold) / Dissolve.WIDTH;
                glow.corners(v[a] + s0 * ex + t0 * fx, v[a + 1] + s0 * ey + t0 * fy, v[a + 2] + s0 * ez + t0 * fz,
                        v[a] + s1 * ex + t0 * fx, v[a + 1] + s1 * ey + t0 * fy, v[a + 2] + s1 * ez + t0 * fz,
                        v[a] + s1 * ex + t1 * fx, v[a + 1] + s1 * ey + t1 * fy, v[a + 2] + s1 * ez + t1 * fz,
                        v[a] + s0 * ex + t1 * fx, v[a + 1] + s0 * ey + t1 * fy, v[a + 2] + s0 * ez + t1 * fz,
                        nx, ny, nz, heat, dissolve.color, dissolve.petals);
                if (particles) {
                    p.set(cx, cy, cz);
                    n.set(nx, ny, nz);
                    if (toWorld != null) {
                        toWorld.transformPosition(p);
                        toWorld.transformDirection(n);
                    }
                    dissolve.sample(p.x, p.y, p.z, n.x, n.y, n.z, camera, frame, tick);
                }
            }
        }
        vertices = 0;
        left.release();
        return left;
    }

    /** 光影包的阴影通道（{@code fine} 为假）看不出这么细的差别，切粗一档，省下大部分开销。 */
    private static int cells(float length, float du, float dv, @Nullable DissolveSkin skin, boolean fine) {
        float n = skin != null ? Math.max(Math.abs(du) * skin.width, Math.abs(dv) * skin.height) * PER_TEXEL : length * CELLS_PER_BLOCK;
        return Mth.clamp(Math.round(fine ? n : n / PER_TEXEL), 1, MAX_CELLS);
    }

    /** 这一帧在用的 {@link Left} 数组和已经空出来的数组。几个生物每秒就有几百兆顶点数据，所以反复用，不留给垃圾回收。都在同一帧内画完。 */
    private static final List<float[]> USED = new ArrayList<>(), FREE = new ArrayList<>();

    /** 新的一帧：上一帧画过的都空出来了。 */
    static void nextFrame() {
        FREE.addAll(USED);
        USED.clear();
    }

    /** 还留着的格子的顶点。 */
    static final class Left {
        private float[] data = FREE.isEmpty() ? new float[STRIDE * 4 * 256] : FREE.removeLast();
        private int size;

        /** 写完了：数组等这一帧结束后回收。 */
        private void release() { USED.add(data); }

        boolean empty() { return size == 0; }

        private void corner(float[] v, int a, float ex, float ey, float ez, float fx, float fy, float fz, float du1, float dv1, float du3, float dv3, float s, float t) {
            if (size + STRIDE > data.length) data = Arrays.copyOf(data, data.length * 2);
            data[size++] = v[a] + s * ex + t * fx;
            data[size++] = v[a + 1] + s * ey + t * fy;
            data[size++] = v[a + 2] + s * ez + t * fz;
            data[size++] = v[a + 3];
            data[size++] = v[a + 4] + s * du1 + t * du3;
            data[size++] = v[a + 5] + s * dv1 + t * dv3;
            System.arraycopy(v, a + 6, data, size, 5);
            size += 5;
        }

        void draw(VertexConsumer buf) {
            for (int i = 0; i < size; i += STRIDE) {
                buf.addVertex(data[i], data[i + 1], data[i + 2], Float.floatToRawIntBits(data[i + 3]), data[i + 4], data[i + 5],
                        Float.floatToRawIntBits(data[i + 6]), Float.floatToRawIntBits(data[i + 7]), data[i + 8], data[i + 9], data[i + 10]);
            }
        }
    }
}
