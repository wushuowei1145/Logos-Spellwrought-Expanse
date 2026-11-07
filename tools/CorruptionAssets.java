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
