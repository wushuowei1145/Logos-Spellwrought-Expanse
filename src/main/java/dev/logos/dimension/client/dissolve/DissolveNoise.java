package dev.logos.dimension.client.dissolve;

import java.util.Arrays;
import net.minecraft.util.Mth;

/**
 * 消散用的噪波（相当于虚幻的 T_Noise01）。{@link #uv} 读一张可平铺、数值摊匀的表，所以消散过程里每一刻没掉的面积差不多；
 * {@link #at} 是同样的云，只是在空间里，所以洞能从物品前面穿到后面，也能跨方块连续。
 */
final class DissolveNoise {
    private DissolveNoise() {}

    private static final int SIZE = 256, MASK = SIZE - 1;
    /** 一个贴图像素对应几个表格：一团噪波约四个贴图像素。 */
    private static final int PER_TEXEL = 4;
    private static final float[] TABLE = table();

    /** 贴图上一点的值：模型怎么动都粘在皮肤上。{@code width}、{@code height} 是贴图尺寸。 */
    static float uv(float u, float v, int width, int height) {
        return TABLE[(Mth.floor(v * height * PER_TEXEL) & MASK) << 8 | Mth.floor(u * width * PER_TEXEL) & MASK];
    }

    /** 空间里一点的值，单位是格；0 到 1，很少到两头。 */
    static float at(float x, float y, float z) {
        float n = lattice(x * 3.5f, y * 3.5f, z * 3.5f) * .6f + lattice(x * 8 + 11, y * 8 + 5, z * 8 + 3) * .3f + lattice(x * 16 + 7, y * 16 + 13, z * 16 + 1) * .1f;
        return Mth.clamp((n - .5f) * 2.2f + .5f, 0, 1);
    }

    private static float lattice(float x, float y, float z) {
        int ix = Mth.floor(x), iy = Mth.floor(y), iz = Mth.floor(z);
        float fx = fade(x - ix), fy = fade(y - iy), fz = fade(z - iz);
        return Mth.lerp(fz,
                Mth.lerp(fy, Mth.lerp(fx, hash(ix, iy, iz), hash(ix + 1, iy, iz)), Mth.lerp(fx, hash(ix, iy + 1, iz), hash(ix + 1, iy + 1, iz))),
                Mth.lerp(fy, Mth.lerp(fx, hash(ix, iy, iz + 1), hash(ix + 1, iy, iz + 1)), Mth.lerp(fx, hash(ix, iy + 1, iz + 1), hash(ix + 1, iy + 1, iz + 1))));
    }

    private static float fade(float t) { return t * t * (3 - 2 * t); }

    private static float hash(int x, int y, int z) {
        int h = x * 73856093 ^ y * 19349663 ^ z * 83492791;
        h = (h ^ h >>> 13) * 1274126177;
        return ((h ^ h >>> 16) & 0xFFFF) / 65535f;
    }

    private static float tiled(int x, int y, int cells) {
        float cx = x * cells / (float) SIZE, cy = y * cells / (float) SIZE;
        int ix = Mth.floor(cx), iy = Mth.floor(cy);
        float fx = fade(cx - ix), fy = fade(cy - iy);
        int nx = (ix + 1) % cells, ny = (iy + 1) % cells;
        return Mth.lerp(fy, Mth.lerp(fx, hash(ix, iy, cells), hash(nx, iy, cells)), Mth.lerp(fx, hash(ix, ny, cells), hash(nx, ny, cells)));
    }

    /** 三层可平铺的噪声，再把每个值换成它的排名，于是数值在 0 到 1 上摊匀。 */
    private static float[] table() {
        float[] raw = new float[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            raw[y * SIZE + x] = tiled(x, y, 16) * .6f + tiled(x, y, 32) * .3f + tiled(x, y, 64) * .1f;
        }
        return ranks(raw);
    }

    /** 每个值在全部值里的排名，0 到 1。 */
    static float[] ranks(float[] values) {
        float[] sorted = values.clone();
        Arrays.sort(sorted);
        float[] out = new float[values.length];
        float last = Math.max(1, values.length - 1);
        for (int i = 0; i < values.length; i++) out[i] = Arrays.binarySearch(sorted, values[i]) / last;
        return out;
    }
}
