package dev.logos.dimension.client.corruption;

/**
 * 腐败核心的体素造型（维护者选的 A 肉瘤心脏，草图在 art/corruption-core）：一团起伏的肉瘤，表面有发光的脉，
 * 底下的根贴着地面爬出去；3 级正面（模型的 +Z）横着裂开一道眼形的口子，眼珠单独做，好让它转。
 * 体素是 1/16 格，和游戏里的像素一样大。造型由固定种子生成，同一级的核心长得一样（渲染时各自转一个角度）。
 *
 * <p>颜色是调色板序号，对应 {@code textures/entity/corruption_core.png} 第一行的像素（由 tools/CorruptionAssets.java 画出）。
 */
final class CoreShape {
    static final byte EMPTY = 0, FLESH = 1, GLOW = 6, SCLERA = 11, IRIS = 12, IRIS_DARK = 13, PUPIL = 14;
    /** 每级肉瘤的直径（格）。 */
    static final float[] SIZES = {.5f, 1, 2, 3};

    final int n, h;
    final byte[] cells;
    /** 3 级的眼珠中心（体素坐标），没有眼珠时是 null。 */
    float[] eye;
    float eyeRadius;

    CoreShape(int n, int h) {
        this.n = n;
        this.h = h;
        cells = new byte[n * h * n];
    }

    static boolean glows(byte c) {
        return c >= GLOW && c < GLOW + 5 || c == IRIS || c == IRIS_DARK;
    }

    int i(int x, int y, int z) { return (y * n + z) * n + x; }
    boolean in(int x, int y, int z) { return x >= 0 && y >= 0 && z >= 0 && x < n && y < h && z < n; }
    byte get(int x, int y, int z) { return in(x, y, z) ? cells[i(x, y, z)] : EMPTY; }
    void set(int x, int y, int z, int c) { if (in(x, y, z)) cells[i(x, y, z)] = (byte) c; }

    // ---- 噪波（和草图同一套，换了种子造型也不变） ----------------------------------------------------

    static double hash(int x, int y, int z, int seed) {
        long v = x * 374761393L + y * 668265263L + z * 2147483647L + seed * 144665L;
        v = (v ^ (v >> 13)) * 1274126177L;
        return ((v ^ (v >> 16)) & 0xFFFFFF) / (double) 0xFFFFFF;
    }

    private static double smooth(double t) { return t * t * (3 - 2 * t); }

    static double noise(double x, double y, double z, double cell, int seed) {
        x /= cell; y /= cell; z /= cell;
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y), z0 = (int) Math.floor(z);
        double tx = smooth(x - x0), ty = smooth(y - y0), tz = smooth(z - z0), v = 0;
        for (int dx = 0; dx < 2; dx++) for (int dy = 0; dy < 2; dy++) for (int dz = 0; dz < 2; dz++) {
            double w = (dx == 0 ? 1 - tx : tx) * (dy == 0 ? 1 - ty : ty) * (dz == 0 ? 1 - tz : tz);
            v += w * hash(x0 + dx, y0 + dy, z0 + dz, seed);
        }
        return v;
    }

    // ---- 肉瘤 ---------------------------------------------------------------------------------

    /** 根的条数和长度（体素）：比草图长一倍多，维护者要求“下面的触须再长一些”。 */
    private static int roots(int tier) { return 4 + tier; }
    private static int rootLength(int tier) { return 7 + tier * 10; }

    static CoreShape flesh(int tier) {
        int body = Math.round(SIZES[tier] * 16);
        double r = body / 2.0 * .95, cy = r * .85;
        int start = (int) Math.round(r * .6), reach = start + rootLength(tier) + 3;
        int n = Math.max(body + 4, 2 * reach + 2), h = body + 3;
        CoreShape s = new CoreShape(n, h);
        double c = n / 2.0;
        // 只在肉瘤的包围盒里算噪波
        int lo = (int) Math.floor(c - r * 1.3), hi = (int) Math.ceil(c + r * 1.3);
        for (int x = lo; x <= hi; x++) for (int y = 0; y < h; y++) for (int z = lo; z <= hi; z++) {
            double dx = x + .5 - c, dy = (y + .5 - cy) * 1.1, dz = z + .5 - c;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double lump = (noise(x, y, z, Math.max(2, r / 2), 1) - .5) * r * .5;
            if (d > r + lump) continue;
            double vein = Math.abs(noise(x, y, z, Math.max(2, r / 3), 2) - .5);
            boolean glow = vein < .035 && d > r * .6;
            int shade = (int) Math.min(4, Math.max(0, noise(x, y, z, 3, 3) * 5));
            s.set(x, y, z, glow ? GLOW + 2 + (vein < .015 ? 1 : 0) : FLESH + shade);
        }
        s.roots(c, start, roots(tier), rootLength(tier), tier, 4);
        if (tier == 3) s.slit(c, cy, r);
        return s;
    }

    /** 贴着地面向外爬的根：靠近肉瘤的一段粗、两层高，越往外越细，末端往下垂一点（垂进地面的部分看不见）。 */
    private void roots(double c, int start, int count, int length, int tier, int seed) {
        for (int k = 0; k < count; k++) {
            double a = k * Math.PI * 2 / count + hash(k, 0, 0, seed) * .8;
            double x = c + Math.cos(a) * start, z = c + Math.sin(a) * start;
            for (int i = 0; i < length; i++) {
                a += (hash(k, i, 1, seed) - .5) * .5;
                x += Math.cos(a);
                z += Math.sin(a);
                int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
                double t = i / (double) length;
                boolean glow = i % 4 == 0;
                set(bx, 0, bz, glow ? GLOW + 2 : FLESH + 3);
                if (t < .55) set(bx, 1, bz, glow ? GLOW + 1 : FLESH + 2);
                // 大部分两格宽，2 级以上靠近肉瘤的一段三格宽
                int ox = (int) Math.round(-Math.sin(a)), oz = (int) Math.round(Math.cos(a));
                if (tier >= 1 && t < .7) set(bx + ox, 0, bz + oz, FLESH + 2);
                if (tier >= 2 && t < .4) set(bx - ox, 0, bz - oz, FLESH + 3);
            }
        }
    }

    /** 正面（+Z）横着裂开一道眼形的口子，边缘发光；眼珠记下位置另做。 */
    private void slit(double c, double cy, double r) {
        double ex = c, ey = cy + r * .08, ez = c + r * .35;
        int lo = (int) Math.floor(c - r * 1.3), hi = (int) Math.ceil(c + r * 1.3);
        for (int x = lo; x <= hi; x++) for (int y = 0; y < h; y++) for (int z = lo; z <= hi; z++) {
            double dx = x + .5 - ex, dy = y + .5 - ey, dz = z + .5 - ez;
            if (dz < -r * .2) continue;
            double side = Math.abs(dx), vert = Math.abs(dy);
            if (side < r * .55 && vert < r * .32 * (1 - side / (r * .55))) set(x, y, z, EMPTY);
        }
        for (int x = lo; x <= hi; x++) for (int y = 0; y < h; y++) for (int z = lo; z <= hi; z++) {
            byte v = get(x, y, z);
            if (v == EMPTY || glows(v)) continue;
            double dx = x + .5 - ex, dy = y + .5 - ey, dz = z + .5 - ez;
            if (dx * dx + dy * dy + dz * dz > r * r * .25 || hash(x, y, z, 21) >= .6) continue;
            if (get(x + 1, y, z) == EMPTY || get(x - 1, y, z) == EMPTY || get(x, y + 1, z) == EMPTY || get(x, y, z + 1) == EMPTY) set(x, y, z, GLOW + 3);
        }
        eye = new float[] {(float) c, (float) cy, (float) (c + r * .15)};
        eyeRadius = (float) (r * .55);
    }

    /** 眼珠：以中心为原点的一个球，瞳孔朝 +Z，竖的一条缝；眼白上有几条血丝。 */
    static CoreShape eyeball(float radius) {
        int m = (int) Math.ceil(radius) * 2 + 2;
        CoreShape s = new CoreShape(m, m);
        double c = m / 2.0;
        for (int x = 0; x < m; x++) for (int y = 0; y < m; y++) for (int z = 0; z < m; z++) {
            double dx = x + .5 - c, dy = y + .5 - c, dz = z + .5 - c;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d > radius) continue;
            double facing = dz / Math.max(d, 1e-6), sx = dx / radius, sy = dy / radius;
            int col = SCLERA;
            if (facing > .55) {
                col = facing > .8 ? IRIS : IRIS_DARK;
                if (Math.abs(sx) < .09 && Math.abs(sy) < .42) col = PUPIL;
            } else if (facing > .45) {
                col = IRIS_DARK;
            } else if (hash(x, y, z, 9) < .08) {
                col = GLOW + 1;
            }
            s.set(x, y, z, col);
        }
        return s;
    }

    /** 粗一档的造型（远处用）：每 2×2×2 个体素并成一个，取里面第一个不空的颜色。 */
    CoreShape half() {
        CoreShape s = new CoreShape((n + 1) / 2, (h + 1) / 2);
        for (int x = 0; x < s.n; x++) for (int y = 0; y < s.h; y++) for (int z = 0; z < s.n; z++) {
            byte pick = EMPTY;
            for (int k = 0; k < 8 && pick == EMPTY; k++) pick = get(2 * x + (k & 1), 2 * y + (k >> 1 & 1), 2 * z + (k >> 2 & 1));
            s.set(x, y, z, pick);
        }
        return s;
    }
}
