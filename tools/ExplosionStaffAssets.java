import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * 爆裂魔杖（致敬《为美好的世界献上祝福》惠惠的法杖）的模型与贴图，全部用代码画。
 *
 * <p>单文件 Java，不依赖项目：
 * {@code java tools/ExplosionStaffAssets.java src/main/resources [预览图.png]}
 *
 * <p>杖身是深褐色的细长木杖，中段缠着白色绷带，脚下一块小小的平底；杖头向左上弯成钩，绕过红色宝珠的上方，
 * 在右上角收成一片尖尖的尾翼，宝珠由两道金色箍扣在钩内。模型单位里手握在 y≈8 附近（和 Logos 的长杖一样），
 * 宝珠中心是 {@code wand_tip}：蓄力特效挂在这里。给了预览图路径时，另外画一张正面的正交预览。
 */
public class ExplosionStaffAssets {
    /** 贴图 64×64，模型里 UV 的 1 单位是 4 个像素。 */
    static final int TEX = 64;
    /** 宝珠：中心与半径（模型单位）。 */
    static final double ORB_X = 9.9, ORB_Y = 21.5, ORB_R = 2.15;
    /** 杖头钩的弧：圆心就是宝珠，半径、在面内的宽度、前后的厚度。 */
    static final double HOOK_R = 3.1, HOOK_W = 1.25, HOOK_D = 0.8;

    // —— 贴图里的区域（UV 单位，0–16）——
    record Region(double u0, double v0, double u1, double v1) {}
    static final Region WOOD = new Region(0, 0, 4, 16), WRAP = new Region(4, 0, 8, 8), GOLD = new Region(8, 0, 12, 4),
            ORB = new Region(8, 4, 16, 12), WOOD_END = new Region(4, 8, 6, 10);

    record Box(String name, double x0, double y0, double z0, double x1, double y1, double z1, Region region,
               double angle, double ox, double oy, double oz, int light) {}

    static final List<Box> BOXES = new ArrayList<>();

    static void box(String name, double x0, double y0, double z0, double x1, double y1, double z1, Region r) {
        BOXES.add(new Box(name, x0, y0, z0, x1, y1, z1, r, 0, 0, 0, 0, 0));
    }

    /** 在 XY 平面里从 (ax, ay) 到 (bx, by) 的一段，宽 w（垂直于这段）、厚 d（前后），绕 z 轴转到对的方向。 */
    static void segment(String name, double ax, double ay, double bx, double by, double w, double d, Region r, int light) {
        double mx = (ax + bx) / 2, my = (ay + by) / 2, len = Math.hypot(bx - ax, by - ay);
        double angle = Math.toDegrees(Math.atan2(by - ay, bx - ax));
        BOXES.add(new Box(name, mx - len / 2, my - w / 2, 8 - d / 2, mx + len / 2, my + w / 2, 8 + d / 2, r, angle, mx, my, 8, light));
    }

    static void build() {
        // 杖身：截面 1.2，脚在 y=-10，顶到钩的起点
        box("shaft_low", 7.4, -7, 7.4, 8.6, 4, 8.6, WOOD);
        box("wrap", 7.2, 4, 7.2, 8.8, 11.2, 8.8, WRAP);
        box("wrap_knot", 6.9, 10.4, 7.6, 7.25, 11.4, 8.4, WRAP);
        box("shaft_high", 7.4, 11.2, 7.4, 8.6, 17.4, 8.6, WOOD);
        // 脚：一块向左伸出、略微翘起的平底
        segment("foot", 4.9, -7.75, 8.5, -7.2, 0.8, 1.3, WOOD, 0);
        segment("toe", 4.5, -7.95, 5.4, -7.45, 0.5, 1.0, WOOD, 0);
        // 颈：杖身收进钩里，带一道金箍
        box("neck_ring", 7.15, 16.1, 7.15, 8.85, 16.8, 8.85, GOLD);
        double start = Math.toRadians(238);
        double sx = ORB_X + HOOK_R * Math.cos(start), sy = ORB_Y + HOOK_R * Math.sin(start);
        segment("neck", 8.0, 17.2, sx, sy, 1.25, HOOK_D + .15, WOOD, 0);
        // 钩：从左下沿左边往上，绕过宝珠顶上，到右边略高处
        double from = 238, to = 38;
        int n = 10;
        double px = sx, py = sy;
        for (int i = 1; i <= n; i++) {
            double a = Math.toRadians(from + (to - from) * i / n);
            double x = ORB_X + HOOK_R * Math.cos(a), y = ORB_Y + HOOK_R * Math.sin(a);
            // 段与段首尾多出一点，转弯处不露缝
            double ex = (x - px) * .12, ey = (y - py) * .12;
            segment("hook_" + i, px - ex, py - ey, x + ex, y + ey, HOOK_W, HOOK_D, WOOD, 0);
            px = x;
            py = y;
        }
        // 尾翼：从钩的右上端向右上甩出，根部宽、逐段收成尖，像一片小旗
        double[][] tail = {{px, py}, {13.5, 24.4}, {14.6, 25.9}, {15.4, 27.4}, {15.9, 28.8}};
        double[] widths = {1.7, 1.35, .95, .5};
        for (int i = 0; i + 1 < tail.length; i++) {
            double ex = (tail[i + 1][0] - tail[i][0]) * .12, ey = (tail[i + 1][1] - tail[i][1]) * .12;
            segment("tail_" + i, tail[i][0] - ex, tail[i][1] - ey, tail[i + 1][0] + ex, tail[i + 1][1] + ey, widths[i], HOOK_D * (1 - .1 * i), WOOD, 0);
        }
        // 宝珠：几块正交的板拼成的圆珠，微微自发光
        double r = ORB_R, s = r * .57, t = r * .82;
        orb("orb_x", r, s, s);
        orb("orb_y", s, r, s);
        orb("orb_z", s + .02, s + .02, r);
        orb("orb_xy", t, t, s * .7);
        // 两道金箍，从钩的左下方扣住宝珠
        for (double deg : new double[] {212, 168}) {
            double a = Math.toRadians(deg);
            double ax = ORB_X + (ORB_R - .45) * Math.cos(a), ay = ORB_Y + (ORB_R - .45) * Math.sin(a);
            double bx = ORB_X + (HOOK_R + .1) * Math.cos(a), by = ORB_Y + (HOOK_R + .1) * Math.sin(a);
            segment("clasp_" + (int) deg, ax, ay, bx, by, .75, 1.5, GOLD, 0);
        }
    }

    static void orb(String name, double rx, double ry, double rz) {
        BOXES.add(new Box(name, ORB_X - rx, ORB_Y - ry, 8 - rz, ORB_X + rx, ORB_Y + ry, 8 + rz, ORB, 0, 0, 0, 0, 7));
    }

    // —— 模型 JSON ——

    static String num(double v) {
        String s = String.format(Locale.ROOT, "%.4f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    static String uv(Region r, double fu0, double fv0, double fu1, double fv1) {
        double w = r.u1 - r.u0, h = r.v1 - r.v0;
        return "[" + num(r.u0 + w * fu0) + ", " + num(r.v0 + h * fv0) + ", " + num(r.u0 + w * fu1) + ", " + num(r.v0 + h * fv1) + "]";
    }

    static String model() {
        StringBuilder b = new StringBuilder();
        b.append("{\n  \"credit\": \"Code-drawn by tools/ExplosionStaffAssets.java\",\n");
        b.append("  \"texture_size\": [64, 64],\n  \"gui_light\": \"side\",\n");
        b.append("  \"textures\": {\"0\": \"logos_dimension:item/explosion_staff\", \"particle\": \"logos_dimension:item/explosion_staff\"},\n");
        b.append("  \"elements\": [\n");
        for (int i = 0; i < BOXES.size(); i++) {
            Box x = BOXES.get(i);
            b.append("    {\"name\": \"").append(x.name).append("\", \"from\": [").append(num(x.x0)).append(", ").append(num(x.y0)).append(", ").append(num(x.z0))
                    .append("], \"to\": [").append(num(x.x1)).append(", ").append(num(x.y1)).append(", ").append(num(x.z1)).append("],");
            if (x.angle != 0) b.append(" \"rotation\": {\"angle\": ").append(num(x.angle)).append(", \"axis\": \"z\", \"origin\": [")
                    .append(num(x.ox)).append(", ").append(num(x.oy)).append(", ").append(num(x.oz)).append("]},");
            if (x.light > 0) b.append(" \"light_emission\": ").append(x.light).append(",");
            b.append("\n      \"faces\": {");
            // 长面沿长边取贴图的竖向，木纹顺着杖身
            boolean vertical = x.angle == 0 && (x.y1 - x.y0) >= (x.x1 - x.x0);
            String side = vertical ? uv(x.region, 0, 0, .35, 1) : uv(x.region, 0, 0, 1, .35);
            String sideRot = vertical ? "" : "";
            String cap = x.region == WOOD ? uv(WOOD_END, 0, 0, 1, 1) : uv(x.region, .3, .3, .7, .7);
            String[] names = {"north", "east", "south", "west", "up", "down"};
            for (int f = 0; f < 6; f++) {
                String u;
                if (x.region == ORB || x.region == GOLD) u = uv(x.region, 0, 0, 1, 1);
                else if (f < 4) u = vertical ? side : (f == 1 || f == 3 ? cap : uv(x.region, 0, 0, 1, .35));
                else u = vertical ? cap : uv(x.region, 0, 0, 1, .2);
                b.append("\"").append(names[f]).append("\": {\"uv\": ").append(u).append(", \"texture\": \"#0\"}");
                if (f < 5) b.append(", ");
            }
            b.append(sideRot).append("}}");
            b.append(i + 1 < BOXES.size() ? ",\n" : "\n");
        }
        b.append("  ],\n");
        b.append("""
                  "display": {
                    "thirdperson_righthand": {"rotation": [0, 0, -3], "translation": [0, 0.5, 1], "scale": [1, 1, 1]},
                    "thirdperson_lefthand": {"rotation": [0, 0, 3], "translation": [0, 0.5, 1], "scale": [1, 1, 1]},
                    "firstperson_righthand": {"rotation": [0, -15, 6], "translation": [0, -1.5, 0], "scale": [0.75, 0.75, 0.75]},
                    "firstperson_lefthand": {"rotation": [0, 15, -6], "translation": [0, -1.5, 0], "scale": [0.75, 0.75, 0.75]},
                    "gui": {"rotation": [12, -30, -40], "translation": [-0.6, -0.4, 0], "scale": [0.4, 0.4, 0.4]},
                    "ground": {"rotation": [0, 0, 90], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
                    "fixed": {"rotation": [0, 0, -40], "translation": [0, 0, 0], "scale": [0.45, 0.45, 0.45]},
                    "on_shelf": {"rotation": [0, 0, -40], "translation": [0, 0, 0], "scale": [0.45, 0.45, 0.45]}
                  },
                """);
        b.append("  \"groups\": [\n    {\"name\": \"staff\", \"origin\": [8, 8, 8], \"children\": [");
        for (int i = 0; i < BOXES.size(); i++) b.append(i).append(i + 1 < BOXES.size() ? ", " : "");
        b.append("]},\n    {\"name\": \"wand_tip\", \"origin\": [").append(num(ORB_X)).append(", ").append(num(ORB_Y)).append(", 8], \"children\": []}\n  ]\n}\n");
        return b.toString();
    }

    // —— 贴图 ——

    static double hash(int x, int y, int seed) {
        long h = x * 374761393L + y * 668265263L + seed * 2147483647L;
        h = (h ^ (h >>> 13)) * 1274126177L;
        return ((h ^ (h >>> 16)) & 0xFFFFFF) / (double) 0xFFFFFF;
    }

    static int rgb(double r, double g, double b) {
        return 0xFF000000 | clamp(r) << 16 | clamp(g) << 8 | clamp(b);
    }

    static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    static int mix(int a, int b, double k) {
        k = Math.max(0, Math.min(1, k));
        return rgb(((a >> 16) & 255) * (1 - k) + ((b >> 16) & 255) * k, ((a >> 8) & 255) * (1 - k) + ((b >> 8) & 255) * k,
                (a & 255) * (1 - k) + (b & 255) * k);
    }

    static BufferedImage texture() {
        BufferedImage img = new BufferedImage(TEX, TEX, BufferedImage.TYPE_INT_ARGB);
        // 深褐木纹：竖向的纹理，左亮右暗
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 16; x++) {
                double grain = .5 + .5 * Math.sin(x * 1.9 + Math.sin(y * .21 + x) * 1.3) * .6 + (hash(x, y / 3, 1) - .5) * .25;
                int base = mix(0x2A1D17, 0x46322A, grain);
                base = mix(base, 0x1A110D, Math.max(0, (x - 9) / 9.0));
                img.setRGB(x, y, base);
            }
        }
        // 木头端面：年轮
        for (int y = 32; y < 40; y++)
            for (int x = 16; x < 24; x++) {
                double d = Math.hypot(x - 19.5, y - 35.5);
                img.setRGB(x, y, mix(0x5A4031, 0x34241C, .5 + .5 * Math.sin(d * 2.2)));
            }
        // 白色绷带：斜着一圈圈缠上去，交界处有阴影
        for (int y = 0; y < 32; y++)
            for (int x = 16; x < 32; x++) {
                double band = ((x - 16) * .5 + y) % 6;
                int c = mix(0xF6F5F0, 0xE2E1DA, (hash(x, y, 2) - .3) * .5);
                if (band < 1.1) c = mix(c, 0xB8B6AE, .8 - band * .4);
                else if (band > 5.2) c = mix(c, 0xFFFFFF, .5);
                img.setRGB(x, y, c);
            }
        // 金色：上亮下暗，带一道高光
        for (int y = 0; y < 16; y++)
            for (int x = 32; x < 48; x++) {
                double k = y / 15.0;
                int c = mix(0xF7DD8E, 0x9C7130, k);
                if (y == 3 || y == 4) c = mix(c, 0xFFF6D2, .7);
                img.setRGB(x, y, mix(c, 0x6E4E1E, (hash(x, y, 3) - .5) * .2));
            }
        // 红宝珠：中间亮、边缘暗红，左上一点高光
        for (int y = 16; y < 48; y++)
            for (int x = 32; x < 64; x++) {
                double u = (x - 47.5) / 16.0, v = (y - 31.5) / 16.0;
                double d = Math.min(1, Math.hypot(u, v));
                int c = mix(0xFF5A42, 0xB0101A, Math.pow(d, 1.3));
                c = mix(c, 0x5C0610, Math.max(0, (d - .8) / .2));
                double spec = Math.hypot(u + .35, v + .4);
                c = mix(c, 0xFFE2D6, Math.max(0, 1 - spec / .22));
                double inner = Math.hypot(u - .18, v - .22);
                c = mix(c, 0xFF8A55, Math.max(0, .45 - inner) * .9);
                img.setRGB(x, y, c);
            }
        // 其余空白处填木色，免得采样到透明
        for (int y = 0; y < 64; y++)
            for (int x = 0; x < 64; x++)
                if ((img.getRGB(x, y) >>> 24) == 0) img.setRGB(x, y, 0xFF2A1D17);
        return img;
    }

    /** 实心圆：半径 0.86 以内不透明，到边缘平滑地变透明。 */
    static BufferedImage disc(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) {
                double d = Math.hypot((x + .5) / size * 2 - 1, (y + .5) / size * 2 - 1);
                double k = Math.max(0, Math.min(1, (1 - d) / .14));
                int a = (int) Math.round(255 * k * k * (3 - 2 * k));
                img.setRGB(x, y, a << 24 | 0xFFFFFF);
            }
        return img;
    }

    // —— 正面正交预览（只看轮廓与配色）——

    static BufferedImage preview(BufferedImage tex) {
        int scale = 24, w = 30 * scale, h = 44 * scale;
        double ox = -4, oy = -12;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, 0xF2F2F2);
        double[] depth = new double[w * h];
        java.util.Arrays.fill(depth, -1e9);
        List<Box> sorted = new ArrayList<>(BOXES);
        sorted.sort(Comparator.comparingDouble(b -> b.z1));
        for (Box b : sorted) {
            double cos = Math.cos(Math.toRadians(-b.angle)), sin = Math.sin(Math.toRadians(-b.angle));
            for (int py = 0; py < h; py++) {
                for (int px = 0; px < w; px++) {
                    double mx = px / (double) scale + ox, my = (h - py) / (double) scale + oy;
                    // 转回盒子自己的坐标
                    double lx = mx, ly = my;
                    if (b.angle != 0) {
                        double dx = mx - b.ox, dy = my - b.oy;
                        lx = b.ox + dx * cos - dy * sin;
                        ly = b.oy + dx * sin + dy * cos;
                    }
                    if (lx < b.x0 || lx > b.x1 || ly < b.y0 || ly > b.y1) continue;
                    if (b.z1 < depth[py * w + px]) continue;
                    depth[py * w + px] = b.z1;
                    double fu = (lx - b.x0) / (b.x1 - b.x0), fv = 1 - (ly - b.y0) / (b.y1 - b.y0);
                    Region r = b.region;
                    boolean vertical = b.angle == 0 && (b.y1 - b.y0) >= (b.x1 - b.x0);
                    double tu, tv;
                    if (r == ORB || r == GOLD) { tu = fu; tv = fv; }
                    else if (vertical) { tu = fu * .35; tv = fv; }
                    else { tu = fu; tv = fv * .35; }
                    int tx = (int) Math.min(TEX - 1, (r.u0 + (r.u1 - r.u0) * tu) * 4), ty = (int) Math.min(TEX - 1, (r.v0 + (r.v1 - r.v0) * tv) * 4);
                    img.setRGB(px, py, tex.getRGB(tx, ty) & 0xFFFFFF);
                }
            }
        }
        return img;
    }

    public static void main(String[] args) throws Exception {
        Path res = Path.of(args.length > 0 ? args[0] : "src/main/resources");
        build();
        Path models = res.resolve("assets/logos_dimension/models/item"), textures = res.resolve("assets/logos_dimension/textures/item"),
                items = res.resolve("assets/logos_dimension/items");
        Files.createDirectories(models);
        Files.createDirectories(textures);
        Files.createDirectories(items);
        Files.writeString(models.resolve("explosion_staff.json"), model(), StandardCharsets.UTF_8);
        BufferedImage tex = texture();
        ImageIO.write(tex, "png", textures.resolve("explosion_staff.png").toFile());
        Files.writeString(items.resolve("explosion_staff.json"), """
                {
                  "model": {
                    "type": "minecraft:model",
                    "model": "logos_dimension:item/explosion_staff"
                  }
                }
                """, StandardCharsets.UTF_8);
        if (args.length > 1) ImageIO.write(preview(tex), "png", new File(args[1]));
        // 法阵底下那层半透明深红用的圆盘：白色，透明度是一个边缘柔和的实心圆。Logos 现成的贴图里没有这种带透明度的实心圆
        Path vfx = res.resolve("assets/logos_dimension/textures/vfx/explosion");
        Files.createDirectories(vfx);
        ImageIO.write(disc(128), "png", vfx.resolve("disc.png").toFile());
        System.out.println(BOXES.size() + " elements");
    }
}
