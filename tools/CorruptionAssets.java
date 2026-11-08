import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * 腐败的方块贴图：腐败污秽、腐败脉络、腐败核心（顶、侧、底，侧面和顶面带脉动动画）。
 *
 * <p>单文件 Java，不依赖项目：{@code java tools/CorruptionAssets.java src/main/resources}
 *
 * <p>配色：发黑的紫底，病红到粉的发光脉，和消散的颜色 {@code 0xB4286E} 同一族。固定种子，重跑结果不变。
 */
public class CorruptionAssets {
    static final int S = 16;
    // 底色从深到浅
    static final int[] FLESH = {0x140913, 0x1E0D1D, 0x2A1229, 0x381836, 0x472043};
    // 发光脉从暗到亮
    static final int[] GLOW = {0x5A1640, 0x8A1F5A, 0xB4286E, 0xDE4C8E, 0xFF8CC0};

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : "src/main/resources");
        Path dir = root.resolve("assets/logos_dimension/textures/block");
        Files.createDirectories(dir);
        write(dir, "corruption", filth(new Random(0xC0FFEE)));
        write(dir, "corruption_vein", vein(new Random(0xBADF00D)));
        int frames = 8;
        write(dir, "corruption_core_side", animate(frames, f -> coreSide(new Random(17), pulse(f, frames))));
        write(dir, "corruption_core_top", animate(frames, f -> coreTop(new Random(23), pulse(f, frames))));
        write(dir, "corruption_core_bottom", coreBottom(new Random(29)));
        mcmeta(dir, "corruption_core_side", 3);
        mcmeta(dir, "corruption_core_top", 3);
        Path entity = root.resolve("assets/logos_dimension/textures/entity");
        Files.createDirectories(entity);
        write(entity, "corruption_core", corePalette());
        write(entity, "corruption_overlay", overlay(new Random(0xF117)));
        write(entity, "corruption_overlay_veins", overlayVeins(new Random(0xF118)));
        Path item = root.resolve("assets/logos_dimension/textures/item");
        Files.createDirectories(item);
        // 刷怪蛋：深色的底，按原版那只怪大概的颜色，带腐败的斑点
        write(item, "filth_zombie_spawn_egg", egg(new Random(31), 0x2E4A3A, 0x8A1F5A));
        write(item, "filth_skeleton_spawn_egg", egg(new Random(32), 0x8E8694, 0x5A1640));
        write(item, "filth_silverfish_spawn_egg", egg(new Random(33), 0x5E5866, 0xB4286E));
        write(item, "filth_slime_spawn_egg", egg(new Random(34), 0x4A2A5A, 0xDE4C8E));
    }

    /**
     * 被腐败的生物身上那层斑驳：64×64，透明底上一片片发黑的紫斑，边缘半透明。按生物自己的贴图坐标铺上去，
     * 所以斑块落在皮肤的什么地方由它的贴图布局决定。
     */
    static BufferedImage overlay(Random r) {
        int n = 64;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        double[][] blot = new double[n][n];
        for (int k = 0; k < 4; k++) {
            int cell = 4 << k % 3;
            double[][] layer = noiseAt(r, n, cell);
            for (int x = 0; x < n; x++) for (int y = 0; y < n; y++) blot[x][y] += layer[x][y] / 4;
        }
        for (int x = 0; x < n; x++) for (int y = 0; y < n; y++) {
            double v = blot[x][y];
            if (v < .46) continue;
            int alpha = v > .54 ? 215 : 140;
            int shade = v > .63 ? FLESH[0] : v > .56 ? FLESH[2] : FLESH[3];
            img.setRGB(x, y, alpha << 24 | shade);
        }
        return img;
    }

    /** 发光的脉：64×64，透明（全黑）底上几条弯弯曲曲的粉红细线和几个脓疱，叠加画、全亮。 */
    static BufferedImage overlayVeins(Random r) {
        int n = 64;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        for (int k = 0; k < 24; k++) {
            int x = r.nextInt(n), y = r.nextInt(n), dx = r.nextBoolean() ? 1 : -1, dy = 0;
            int length = 8 + r.nextInt(13);
            for (int i = 0; i < length; i++) {
                int c = i % 6 == 0 ? GLOW[4] : GLOW[2 + r.nextInt(2)];
                if (x >= 0 && y >= 0 && x < n && y < n) img.setRGB(x, y, argb(c));
                if (r.nextInt(3) == 0) {
                    int t = dx;
                    dx = r.nextBoolean() ? dy : -dy;
                    dy = r.nextBoolean() ? t : -t;
                    if (dx == 0 && dy == 0) dx = 1;
                }
                x += dx;
                y += dy;
            }
        }
        for (int k = 0; k < 10; k++) img.setRGB(r.nextInt(n), r.nextInt(n), argb(GLOW[4]));
        return img;
    }

    /** 能在边上接上的值噪声，任意边长。 */
    static double[][] noiseAt(Random r, int n, int cell) {
        int g = Math.max(1, n / cell);
        double[][] grid = new double[g][g];
        for (int i = 0; i < g; i++) for (int j = 0; j < g; j++) grid[i][j] = r.nextDouble();
        double[][] out = new double[n][n];
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            double gx = (double) x / cell, gy = (double) y / cell;
            int x0 = (int) gx, y0 = (int) gy;
            double tx = smooth(gx - x0), ty = smooth(gy - y0);
            double a = grid[x0 % g][y0 % g], b = grid[(x0 + 1) % g][y0 % g];
            double c = grid[x0 % g][(y0 + 1) % g], d = grid[(x0 + 1) % g][(y0 + 1) % g];
            out[x][y] = lerp(lerp(a, b, tx), lerp(c, d, tx), ty);
        }
        return out;
    }

    /** 16×16 的蛋：上窄下宽，深色描边，{@code base} 底色上点几块 {@code spot}。 */
    static BufferedImage egg(Random r, int base, int spot) {
        BufferedImage img = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        boolean[][] in = new boolean[S][S];
        for (int y = 1; y < 15; y++) {
            double t = (y - 8.6) / 6.6;
            double half = 5.4 * Math.sqrt(Math.max(0, 1 - t * t)) * (y < 8 ? .82 + .18 * y / 8.0 : 1);
            for (int x = 0; x < S; x++) in[x][y] = Math.abs(x + .5 - 8) <= half;
        }
        int outline = mix(base, 0x000000, .55);
        for (int x = 0; x < S; x++) for (int y = 0; y < S; y++) {
            if (!in[x][y]) continue;
            boolean edge = x == 0 || y == 0 || x == S - 1 || y == S - 1 || !in[x - 1][y] || !in[x + 1][y] || !in[x][y - 1] || !in[x][y + 1];
            int c = edge ? outline : mix(base, 0xFFFFFF, x < 7 && y < 8 ? .12 : 0);
            img.setRGB(x, y, argb(c));
        }
        for (int k = 0; k < 6; k++) {
            int x = 4 + r.nextInt(8), y = 4 + r.nextInt(9);
            if (!in[x][y] || !in[x - 1][y] || !in[x + 1][y]) continue;
            img.setRGB(x, y, argb(spot));
            if (r.nextBoolean() && in[x + 1][y + 1]) img.setRGB(x + 1, y, argb(spot));
        }
        return img;
    }

    /**
     * 放在世界里的核心（体素肉瘤）用的调色板：第一行第 i 个像素是颜色 i，序号和 {@code client/corruption/CoreShape} 一致：
     * 1–5 肉（深到浅），6–10 发光（暗到亮），11 眼白，12 虹膜，13 虹膜暗边，14 瞳孔。
     */
    static BufferedImage corePalette() {
        BufferedImage img = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[16];
        for (int i = 0; i < 5; i++) {
            row[1 + i] = FLESH[i];
            row[6 + i] = GLOW[i];
        }
        row[11] = 0xE6DCC4;
        row[12] = 0xD8323C;
        row[13] = 0x7A1020;
        row[14] = 0x0A0408;
        for (int x = 1; x < 15; x++) for (int y = 0; y < S; y++) img.setRGB(x, y, argb(row[x]));
        return img;
    }

    /** 第 f 帧的亮度 0..1：慢起快落，像心跳。 */
    static double pulse(int f, int frames) {
        double t = (double) f / frames;
        return t < .35 ? t / .35 : Math.pow(1 - (t - .35) / .65, 1.6);
    }

    // ---- 腐败污秽 ---------------------------------------------------------------------------------

    static BufferedImage filth(Random r) {
        BufferedImage img = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        double[][] n = noise(r, 4);
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                int shade = clamp((int) (n[x][y] * FLESH.length), 0, FLESH.length - 1);
                img.setRGB(x, y, argb(FLESH[shade]));
            }
        }
        // 每个象限一条蜿蜒的发光脉，能在贴图边上接上（走出去从对边回来），铺开来看不出一块一块的
        for (int q = 0; q < 4; q++) worm(img, r, (q % 2) * 8 + r.nextInt(8), (q / 2) * 8 + r.nextInt(8), 6 + r.nextInt(5), true);
        // 零星的亮点：脓疱
        for (int i = 0; i < 5; i++) {
            int x = r.nextInt(S), y = r.nextInt(S);
            img.setRGB(x, y, argb(GLOW[3 + r.nextInt(2)]));
        }
        return img;
    }

    /** 一条随机游走的脉：中间亮、两侧暗一档。 */
    static void worm(BufferedImage img, Random r, int x, int y, int length, boolean wrap) {
        int dx = r.nextBoolean() ? 1 : -1, dy = 0;
        for (int i = 0; i < length; i++) {
            int core = r.nextInt(6) == 0 ? 3 : 1 + r.nextInt(2);
            put(img, x, y, GLOW[core], wrap);
            // 脉的阴影
            put(img, x + dy, y + dx, darker(img, x + dy, y + dx, GLOW[0]), wrap);
            if (r.nextInt(3) == 0) {
                int t = dx;
                dx = r.nextBoolean() ? dy : -dy;
                dy = r.nextBoolean() ? t : -t;
                if (dx == 0 && dy == 0) dx = 1;
            }
            x += dx;
            y += dy;
            if (!wrap && (x < 0 || y < 0 || x >= S || y >= S)) return;
        }
    }

    // ---- 腐败脉络 ---------------------------------------------------------------------------------

    static BufferedImage vein(Random r) {
        BufferedImage img = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        // 从四条边各两处长进来、往对边去的分叉细脉，透明底，贴满整面
        int[][] starts = {{0, 3}, {0, 11}, {15, 5}, {15, 13}, {4, 0}, {12, 0}, {2, 15}, {10, 15}};
        for (int[] s : starts) branch(img, r, s[0], s[1], 6 + r.nextInt(6), 0);
        // 交汇处鼓起的结节
        for (int i = 0; i < 3; i++) {
            int x = 4 + r.nextInt(8), y = 4 + r.nextInt(8);
            if ((img.getRGB(x, y) >>> 24) == 0) continue;
            img.setRGB(x, y, argb(GLOW[4]));
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = x + d[0], ny = y + d[1];
                if (inside(nx, ny)) img.setRGB(nx, ny, argb(GLOW[2]));
            }
        }
        return img;
    }

    static void branch(BufferedImage img, Random r, int x, int y, int length, int depth) {
        // 朝对边走，带一点随机的偏角
        double angle = (x == 0 ? 0 : x == S - 1 ? Math.PI : y == 0 ? Math.PI / 2 : y == S - 1 ? -Math.PI / 2 : r.nextDouble() * 6.28)
                + (r.nextDouble() - .5) * 1.2;
        double fx = x, fy = y;
        for (int i = 0; i < length; i++) {
            int px = (int) Math.round(fx), py = (int) Math.round(fy);
            if (!inside(px, py)) return;
            int tone = depth == 0 ? (i < 2 ? 1 : 2 + (r.nextInt(4) == 0 ? 1 : 0)) : 1 + r.nextInt(2);
            img.setRGB(px, py, argb(GLOW[tone]));
            angle += (r.nextDouble() - .5) * .9;
            fx += Math.cos(angle);
            fy += Math.sin(angle);
            if (depth < 2 && i > 2 && r.nextInt(4) == 0) {
                double side = angle + (r.nextBoolean() ? 1.1 : -1.1);
                branch(img, r, px + (int) Math.round(Math.cos(side)), py + (int) Math.round(Math.sin(side)), length / 2, depth + 1);
            }
        }
    }

    // ---- 腐败核心 ---------------------------------------------------------------------------------

    /** 侧面：深色外壳裂开，中间一颗竖瞳一样的发光核，脉从它往四角爬。 */
    static BufferedImage coreSide(Random r, double beat) {
        BufferedImage img = shell(r);
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                double dx = (x - 7.5) / 3.2, dy = (y - 7.5) / 5.0;
                double d = Math.sqrt(dx * dx + dy * dy);
                if (d < 1) {
                    double heat = (1 - d) * (.55 + .45 * beat);
                    img.setRGB(x, y, argb(GLOW[clamp((int) (heat * 5 + .4), 0, 4)]));
                }
            }
        }
        // 竖瞳
        for (int y = 5; y <= 10; y++) img.setRGB(7 + (y % 2), y, argb(mix(0x1A0010, GLOW[4], .15 * beat)));
        cracks(img, r, beat);
        return img;
    }

    /** 顶面：同心的发光环，环里一圈圈暗下去。 */
    static BufferedImage coreTop(Random r, double beat) {
        BufferedImage img = shell(r);
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                double d = Math.hypot(x - 7.5, y - 7.5);
                if (d < 2.2) img.setRGB(x, y, argb(GLOW[clamp((int) (2 + 2.5 * beat), 0, 4)]));
                else if (d > 3.6 && d < 4.6) img.setRGB(x, y, argb(GLOW[clamp((int) (1 + 2 * beat), 0, 4)]));
            }
        }
        cracks(img, r, beat);
        return img;
    }

    static BufferedImage coreBottom(Random r) {
        BufferedImage img = shell(r);
        // 底面不发光，只有几粒暗红的斑
        for (int i = 0; i < 6; i++) img.setRGB(2 + r.nextInt(12), 2 + r.nextInt(12), argb(GLOW[r.nextInt(2)]));
        return img;
    }

    /** 核心的外壳：比污秽更暗、更硬的块面，有一圈深色描边。 */
    static BufferedImage shell(Random r) {
        BufferedImage img = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        double[][] n = noise(r, 3);
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                boolean edge = x == 0 || y == 0 || x == S - 1 || y == S - 1;
                int shade = edge ? 0 : clamp((int) (n[x][y] * 3) + 1, 1, 3);
                img.setRGB(x, y, argb(FLESH[shade]));
            }
        }
        return img;
    }

    /** 从中心往四角的发光裂纹，亮度跟着心跳。 */
    static void cracks(BufferedImage img, Random r, double beat) {
        int[][] corners = {{1, 1}, {14, 1}, {1, 14}, {14, 14}};
        for (int[] c : corners) {
            double x = 7.5, y = 7.5;
            for (int i = 0; i < 9; i++) {
                x += (c[0] - x) * .22 + (r.nextDouble() - .5) * .9;
                y += (c[1] - y) * .22 + (r.nextDouble() - .5) * .9;
                int px = (int) Math.round(x), py = (int) Math.round(y);
                if (!inside(px, py)) break;
                double dist = Math.hypot(px - 7.5, py - 7.5);
                if (dist < 3) continue;
                int tone = clamp((int) ((1 - dist / 9) * 3 + beat * 1.5), 0, 4);
                img.setRGB(px, py, argb(GLOW[tone]));
            }
        }
    }

    // ---- 工具 -------------------------------------------------------------------------------------

    /** 能在边上接上的值噪声，cell 越小越碎。 */
    static double[][] noise(Random r, int cell) {
        int g = S / cell;
        double[][] grid = new double[g][g];
        for (int i = 0; i < g; i++) for (int j = 0; j < g; j++) grid[i][j] = r.nextDouble();
        double[][] out = new double[S][S];
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                double gx = (double) x / cell, gy = (double) y / cell;
                int x0 = (int) gx, y0 = (int) gy;
                double tx = smooth(gx - x0), ty = smooth(gy - y0);
                double a = grid[x0 % g][y0 % g], b = grid[(x0 + 1) % g][y0 % g];
                double c = grid[x0 % g][(y0 + 1) % g], d = grid[(x0 + 1) % g][(y0 + 1) % g];
                double v = lerp(lerp(a, b, tx), lerp(c, d, tx), ty);
                out[x][y] = clamp01(v * .8 + r.nextDouble() * .2);
            }
        }
        return out;
    }

    interface Frame { BufferedImage draw(int frame); }

    static BufferedImage animate(int frames, Frame frame) {
        BufferedImage strip = new BufferedImage(S, S * frames, BufferedImage.TYPE_INT_ARGB);
        for (int f = 0; f < frames; f++) strip.getGraphics().drawImage(frame.draw(f), 0, f * S, null);
        return strip;
    }

    static void mcmeta(Path dir, String name, int frametime) throws IOException {
        Files.writeString(dir.resolve(name + ".png.mcmeta"), "{\n  \"animation\": {\n    \"frametime\": " + frametime + ",\n    \"interpolate\": true\n  }\n}\n");
    }

    static void write(Path dir, String name, BufferedImage img) throws IOException {
        ImageIO.write(img, "png", new File(dir.resolve(name + ".png").toString()));
    }

    static void put(BufferedImage img, int x, int y, int rgb, boolean wrap) {
        if (wrap) {
            x = Math.floorMod(x, S);
            y = Math.floorMod(y, S);
        } else if (!inside(x, y)) return;
        img.setRGB(x, y, argb(rgb));
    }

    /** 已经比 {@code rgb} 亮的格子（别的脉）不压暗。 */
    static int darker(BufferedImage img, int x, int y, int rgb) {
        int now = img.getRGB(Math.floorMod(x, S), Math.floorMod(y, S)) & 0xFFFFFF;
        return luma(now) > luma(rgb) ? now : rgb;
    }

    static int luma(int rgb) { return ((rgb >> 16) & 255) * 3 + ((rgb >> 8) & 255) * 6 + (rgb & 255); }
    static boolean inside(int x, int y) { return x >= 0 && y >= 0 && x < S && y < S; }
    static int argb(int rgb) { return 0xFF000000 | rgb; }
    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }
    static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    static double smooth(double t) { return t * t * (3 - 2 * t); }

    static int mix(int a, int b, double t) {
        int r = (int) lerp((a >> 16) & 255, (b >> 16) & 255, t);
        int g = (int) lerp((a >> 8) & 255, (b >> 8) & 255, t);
        int bl = (int) lerp(a & 255, b & 255, t);
        return r << 16 | g << 8 | bl;
    }
}
