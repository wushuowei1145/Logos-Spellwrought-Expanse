package dev.logos.dimension.client.corruption;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;

/**
 * 体素造型露在外面的面，同色、同朝向挨着的面贪心合并成大矩形，所以 3 级的肉瘤也只有几千个面。
 * 坐标是体素（1/16 格），原点在造型的 (0,0,0) 角；{@code scale} 是一个体素代表几个最细的体素（粗一档的是 2）。
 */
final class CoreMesh {
    private static final int[][] DIRS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /** 每个面 4 个角 × xyz。 */
    final float[] corners;
    final byte[] colors, dirs;
    final int quads;

    private CoreMesh(float[] corners, byte[] colors, byte[] dirs, int quads) {
        this.corners = corners;
        this.colors = colors;
        this.dirs = dirs;
        this.quads = quads;
    }

    static CoreMesh of(CoreShape s, float scale) {
        FloatList out = new FloatList();
        ByteList cols = new ByteList(), ds = new ByteList();
        int[] dim = {s.n, s.h, s.n};
        for (int d = 0; d < 6; d++) {
            int[] dir = DIRS[d];
            int axis = dir[0] != 0 ? 0 : dir[1] != 0 ? 1 : 2, u = (axis + 1) % 3, v = (axis + 2) % 3;
            int sign = dir[axis];
            byte[] mask = new byte[dim[u] * dim[v]];
            int[] p = new int[3];
            for (int k = 0; k < dim[axis]; k++) {
                // 这一层里朝 dir 露在外面的面
                for (int a = 0; a < dim[u]; a++) for (int b = 0; b < dim[v]; b++) {
                    p[axis] = k; p[u] = a; p[v] = b;
                    byte here = s.get(p[0], p[1], p[2]);
                    byte next = s.get(p[0] + dir[0], p[1] + dir[1], p[2] + dir[2]);
                    mask[a * dim[v] + b] = here != CoreShape.EMPTY && next == CoreShape.EMPTY ? here : CoreShape.EMPTY;
                }
                // 贪心合并
                for (int a = 0; a < dim[u]; a++) {
                    for (int b = 0; b < dim[v]; ) {
                        byte c = mask[a * dim[v] + b];
                        if (c == CoreShape.EMPTY) { b++; continue; }
                        int w = 1;
                        while (b + w < dim[v] && mask[a * dim[v] + b + w] == c) w++;
                        int len = 1;
                        grow:
                        while (a + len < dim[u]) {
                            for (int t = 0; t < w; t++) if (mask[(a + len) * dim[v] + b + t] != c) break grow;
                            len++;
                        }
                        for (int i = 0; i < len; i++) for (int t = 0; t < w; t++) mask[(a + i) * dim[v] + b + t] = CoreShape.EMPTY;
                        float plane = (k + (sign > 0 ? 1 : 0)) * scale;
                        float u0 = a * scale, u1 = (a + len) * scale, v0 = b * scale, v1 = (b + w) * scale;
                        float[][] q = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
                        float[] pts = new float[12];
                        for (int i = 0; i < 4; i++) {
                            pts[i * 3 + axis] = plane;
                            pts[i * 3 + u] = q[i][0];
                            pts[i * 3 + v] = q[i][1];
                        }
                        // 绕向对着法线（实体渲染剔除背面）
                        float ax = pts[3] - pts[0], ay = pts[4] - pts[1], az = pts[5] - pts[2];
                        float bx = pts[6] - pts[0], by = pts[7] - pts[1], bz = pts[8] - pts[2];
                        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
                        if (nx * dir[0] + ny * dir[1] + nz * dir[2] < 0) {
                            for (int i = 0; i < 3; i++) {
                                float t = pts[3 + i]; pts[3 + i] = pts[9 + i]; pts[9 + i] = t;
                            }
                        }
                        out.addAll(pts);
                        cols.add(c);
                        ds.add((byte) d);
                        b += w;
                    }
                }
            }
        }
        return new CoreMesh(out.toArray(), cols.toArray(), ds.toArray(), cols.size);
    }

    /**
     * 写进顶点：颜色来自调色板贴图（每个面采样一个像素的中心）。发光的面全亮并按 {@code glowBrightness} 调亮暗，
     * 别的面用 {@code light}。
     */
    void draw(PoseStack.Pose pose, VertexConsumer out, int light, float glowBrightness) {
        int g = Math.round(255 * Math.clamp(glowBrightness, 0, 1));
        int glowColor = 0xFF000000 | g << 16 | g << 8 | g;
        for (int q = 0; q < quads; q++) {
            byte c = colors[q];
            boolean glow = CoreShape.glows(c);
            float u = (c + .5f) / 16f, v = .5f / 16f;
            int[] n = DIRS[dirs[q]];
            int color = glow ? glowColor : 0xFFFFFFFF, lit = glow ? LightCoordsUtil.FULL_BRIGHT : light;
            for (int i = 0; i < 4; i++) {
                int o = q * 12 + i * 3;
                out.addVertex(pose, corners[o], corners[o + 1], corners[o + 2]).setColor(color).setUv(u, v)
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(lit).setNormal(pose, n[0], n[1], n[2]);
            }
        }
    }

    private static final class FloatList {
        float[] data = new float[1024];
        int size;
        void addAll(float[] v) {
            if (size + v.length > data.length) data = java.util.Arrays.copyOf(data, Math.max(data.length * 2, size + v.length));
            System.arraycopy(v, 0, data, size, v.length);
            size += v.length;
        }
        float[] toArray() { return java.util.Arrays.copyOf(data, size); }
    }

    private static final class ByteList {
        byte[] data = new byte[256];
        int size;
        void add(byte b) {
            if (size == data.length) data = java.util.Arrays.copyOf(data, size * 2);
            data[size++] = b;
        }
        byte[] toArray() { return java.util.Arrays.copyOf(data, size); }
    }
}
