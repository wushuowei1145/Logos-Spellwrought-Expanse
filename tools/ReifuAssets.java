import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * 霊符与符札书案的素材：16 个霊符图标、书案的材质贴图、书案与案上霊符的方块模型、方块状态、物品模型、
 * 配方和掉落表。案上的霊符用同一段代码画出的方块贴图（物品图标和方块不在同一张图集里）。
 *
 * <p>单文件 Java，不依赖项目：{@code java tools/ReifuAssets.java src/main/resources}
 *
 * <p>品级顺序、配色对照 Logos 的术式载页：基础、晶辉、回响、炼成、白垩、天穹、贤者、理。
 * 霊符的样子是剣先形（顶端尖）的御札：尖头涂成一道色带，写好后中间竖排两字墨迹，下方一方朱印。
 */
public class ReifuAssets {
    static final String NS = "logos_dimension";

    /** 一个品级的配色：纸面两色、边、顶带、墨、印，以及纸上的点缀色（没有填 0）。 */
    record Tier(String id, String logosBlank, String logosInscribed, int paper, int paperDark, int border, int band, int ink, int seal, int fleck) {}

    static final List<Tier> TIERS = List.of(
            new Tier("basic", "spell_page", "inscribed_page", 0xefdcb5, 0xe2cb9e, 0x7a5230, 0xc8402e, 0x2b1d14, 0xc8402e, 0),
            new Tier("crystal", "crystal_spell_page", "crystal_inscribed_page", 0xf4e4ef, 0xe8d0e2, 0x7b3fa0, 0xa95fd6, 0x5b2a80, 0xb46ee8, 0),
            new Tier("echo", "echo_spell_page", "echo_inscribed_page", 0x1f5a63, 0x184c54, 0x0b2a30, 0x3fd3e0, 0x7ff2f6, 0x3fd3e0, 0),
            new Tier("transmuted", "transmuted_spell_page", "transmuted_inscribed_page", 0x3c2629, 0x301e21, 0x160c0e, 0xc4502a, 0xe2743c, 0xc4502a, 0),
            new Tier("chalk", "chalk_spell_page", "chalk_inscribed_page", 0xf5f7f9, 0xe4e9ef, 0x8a95a5, 0x9fc3ea, 0x46566c, 0x8fb6e8, 0),
            new Tier("firmament", "firmament_spell_page", "firmament_inscribed_page", 0xf0bb3c, 0xe2a62c, 0x8a5a10, 0x3b6fd0, 0x6a3e08, 0x3b6fd0, 0xbfe0ff),
            new Tier("sage", "sage_spell_page", "sage_inscribed_page", 0xc8382c, 0xb22d23, 0x561009, 0xf2c040, 0xf7d36a, 0xffe08a, 0),
            new Tier("principle", "formless_principle", "supreme_principle", 0xf9eed4, 0xefdcb2, 0xb07a1a, 0xe8b840, 0x8a5a10, 0xe8b840, 0xfff6d8));

    /** 霊符在 16×16 图标里占 x 4..10、y 0..15；案上模型按这块裁切。 */
    static final int SX = 4, SW = 7;

    static Path res;

    public static void main(String[] args) throws IOException {
        res = Path.of(args.length > 0 ? args[0] : "src/main/resources");
        for (Tier t : TIERS) {
            for (boolean written : new boolean[]{false, true}) {
                String id = t.id + (written ? "_inscribed_reifu" : "_reifu");
                png("assets/" + NS + "/textures/item/" + id + ".png", reifu(t, written));
                png("assets/" + NS + "/textures/block/fuda_desk_sheet_" + id + ".png", reifu(t, written));
                json("assets/" + NS + "/items/" + id + ".json",
                        "{\n  \"model\": {\n    \"type\": \"minecraft:model\",\n    \"model\": \"" + NS + ":item/" + id + "\"\n  }\n}\n");
                json("assets/" + NS + "/models/item/" + id + ".json",
                        "{\n  \"parent\": \"minecraft:item/generated\",\n  \"textures\": {\n    \"layer0\": \"" + NS + ":item/" + id + "\"\n  }\n}\n");
                json("assets/" + NS + "/models/block/fuda_desk_" + t.id + (written ? "_inscribed" : "_blank") + ".json", sheet(id));
            }
        }
        png("assets/" + NS + "/textures/block/fuda_desk_wood.png", wood());
        png("assets/" + NS + "/textures/block/fuda_desk_inkstone.png", speckle(0x3b3e44, 0x2e3035, 0x4a4e55, 11));
        png("assets/" + NS + "/textures/block/fuda_desk_ink.png", speckle(0x141418, 0x0c0c10, 0x24242c, 12));
        png("assets/" + NS + "/textures/block/fuda_desk_felt.png", speckle(0x2a2f4c, 0x242840, 0x323858, 13));
        png("assets/" + NS + "/textures/block/fuda_desk_paper.png", paper());
        png("assets/" + NS + "/textures/block/fuda_desk_bamboo.png", bamboo());
        png("assets/" + NS + "/textures/block/fuda_desk_porcelain.png", porcelain());
        png("assets/" + NS + "/textures/block/fuda_desk_bronze.png", speckle(0x5c4a2e, 0x4a3b24, 0x74603c, 14));

        json("assets/" + NS + "/models/block/fuda_desk.json", desk());
        json("assets/" + NS + "/blockstates/fuda_desk.json", blockstate());
        json("assets/" + NS + "/items/fuda_desk.json",
                "{\n  \"model\": {\n    \"type\": \"minecraft:model\",\n    \"model\": \"" + NS + ":block/fuda_desk\"\n  }\n}\n");
        json("data/" + NS + "/loot_table/blocks/fuda_desk.json", loot());
        recipes();
    }

    // —— 霊符图标 ——

    static BufferedImage reifu(Tier t, boolean written) {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        long seed = t.id.hashCode();
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < SW; x++) {
                int c = cell(t, x, y, seed);
                if (c != 0) set(img, SX + x, y, c);
            }
        }
        if (written) {
            // 两个字竖排在中线上：上「工」下「土」
            int[][] strokes = {{2, 6}, {3, 6}, {4, 6}, {3, 7}, {2, 8}, {3, 8}, {4, 8}, {3, 10}, {2, 11}, {3, 11}, {4, 11}};
            for (int[] s : strokes) set(img, SX + s[0], s[1], t.ink);
            // 朱印：字下方三格宽、两格高
            for (int y = 13; y <= 14; y++) for (int x = 2; x <= 4; x++) set(img, SX + x, y, t.seal);
        }
        return img;
    }

    /** 剣先形外形：0 透明，其余返回颜色。 */
    static int cell(Tier t, int x, int y, long seed) {
        int edge = Math.max(0, 3 - y);
        if (x < edge || x > SW - 1 - edge) return 0;
        boolean border = y == 15 || x == edge || x == SW - 1 - edge;
        if (border) return t.border;
        if (y <= 4) return y == 4 ? shade(t.band, 0.85) : t.band;
        if (t.fleck != 0 && ((x == 1 && y == 6) || (x == 5 && y == 9) || (x == 2 && y == 13) || (x == 4 && y == 4))) return t.fleck;
        return noise(seed, x, y) < 0.35 ? t.paperDark : t.paper;
    }

    // —— 书案贴图（16×16，模型面不写 uv，按元件坐标自动取样） ——

    static BufferedImage wood() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        int base = 0x6a4128, dark = 0x55331f, light = 0x7c4f32;
        for (int y = 0; y < 16; y++) {
            // 横向木纹：每行一个底色，个别格子加深或提亮
            int row = (y % 5 == 2) ? dark : (y % 7 == 4) ? light : base;
            for (int x = 0; x < 16; x++) {
                double n = noise(21, x, y);
                set(img, x, y, n < 0.12 ? dark : n > 0.9 ? light : row);
            }
        }
        return img;
    }

    static BufferedImage paper() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                // 纸叠的侧面每隔一行一条细缝，顶面是带纤维的和纸
                double n = noise(31, x, y);
                int c = y % 2 == 1 ? 0xd9ccb0 : n < 0.25 ? 0xe6dcc4 : 0xf1e9d5;
                set(img, x, y, c);
            }
        }
        return img;
    }

    static BufferedImage bamboo() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int c = x % 6 == 0 ? 0x8e6a30 : noise(41, x, y) < 0.3 ? 0xb88e48 : 0xcda65c;
                set(img, x, y, c);
            }
        }
        return img;
    }

    static BufferedImage porcelain() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) set(img, x, y, (y == 12 || y == 13) ? 0x3a5fa8 : noise(51, x, y) < 0.3 ? 0xdde6ec : 0xeef3f6);
        }
        return img;
    }

    static BufferedImage speckle(int base, int dark, int light, long seed) {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                double n = noise(seed, x, y);
                set(img, x, y, n < 0.25 ? dark : n > 0.85 ? light : base);
            }
        }
        return img;
    }

    // —— 模型 ——

    /** 书案元件：名字、材质、起点、终点。朝前（玩家一侧）是 +Z，方块状态按朝向转。 */
    record Box(String name, String tex, double[] from, double[] to) {}

    static Box box(String name, String tex, double x1, double y1, double z1, double x2, double y2, double z2) {
        return new Box(name, tex, new double[]{x1, y1, z1}, new double[]{x2, y2, z2});
    }

    static String desk() {
        List<Box> boxes = List.of(
                // 文机：一块案面、两端翘起的筆返し、板状案腿和落地的横档，前面一只抽屉
                box("top", "wood", 0, 11, 1.5, 16, 12.5, 14.5),
                box("lip_left", "wood", 0, 12.5, 1.5, 0.8, 13.3, 14.5),
                box("lip_right", "wood", 15.2, 12.5, 1.5, 16, 13.3, 14.5),
                box("leg_left", "wood", 1.2, 1, 3, 2.7, 11, 13),
                box("leg_right", "wood", 13.3, 1, 3, 14.8, 11, 13),
                box("foot_left", "wood", 0.6, 0, 2, 3.3, 1, 14),
                box("foot_right", "wood", 12.7, 0, 2, 15.4, 1, 14),
                box("apron_back", "wood", 2.7, 9.5, 2.2, 13.3, 11, 3.2),
                box("drawer", "wood", 2.7, 8.5, 12.8, 13.3, 11, 13.8),
                box("drawer_pull", "bronze", 7.2, 9.3, 13.8, 8.8, 10.1, 14.2),
                // 纸：左侧一叠和纸
                box("paper_stack", "paper", 1.4, 12.5, 3, 4.6, 13.2, 10.5),
                // 下敷き：中间的毡垫，霊符放在上面；上端压一根文鎮
                box("felt", "felt", 5, 12.5, 2.6, 11, 12.6, 13.4),
                box("paperweight", "bronze", 5.6, 12.6, 2.9, 10.4, 13.2, 3.8),
                // 砚：右后方一方石砚，远端是墨池；墨条斜靠在砚面上
                box("inkstone", "inkstone", 11.8, 12.5, 2.8, 14.8, 13.5, 8.6),
                box("ink_pool", "ink", 12.3, 13.4, 3.3, 14.3, 13.52, 4.9),
                box("ink_stick", "ink", 12.4, 13.5, 6.2, 14.2, 14.1, 7.1),
                // 笔：右前方横搁在白瓷筆置き上，笔头朝右
                box("brush_rest", "porcelain", 12.2, 12.5, 10.4, 13.4, 13.0, 12.4),
                box("brush_shaft", "bamboo", 10.2, 13.0, 11.05, 14.6, 13.6, 11.65),
                box("brush_tip", "ink", 14.6, 13.05, 11.1, 15.6, 13.55, 11.6));
        StringBuilder sb = new StringBuilder("{\n  \"parent\": \"minecraft:block/block\",\n  \"textures\": {\n");
        sb.append("    \"particle\": \"" + NS + ":block/fuda_desk_wood\",\n");
        String[] texs = {"wood", "paper", "felt", "bronze", "inkstone", "ink", "porcelain", "bamboo"};
        for (int i = 0; i < texs.length; i++) {
            sb.append("    \"" + texs[i] + "\": \"" + NS + ":block/fuda_desk_" + texs[i] + "\"").append(i + 1 < texs.length ? ",\n" : "\n");
        }
        sb.append("  },\n  \"elements\": [\n");
        for (int i = 0; i < boxes.size(); i++) {
            Box b = boxes.get(i);
            sb.append("    {\n      \"name\": \"" + b.name + "\",\n");
            sb.append("      \"from\": " + vec(b.from) + ",\n      \"to\": " + vec(b.to) + ",\n      \"faces\": {\n");
            String[] faces = {"north", "east", "south", "west", "up", "down"};
            for (int f = 0; f < faces.length; f++) {
                String cull = faces[f].equals("down") && b.from[1] == 0 ? ", \"cullface\": \"down\"" : "";
                sb.append("        \"" + faces[f] + "\": {\"texture\": \"#" + b.tex + "\"" + cull + "}").append(f + 1 < faces.length ? ",\n" : "\n");
            }
            sb.append("      }\n    }").append(i + 1 < boxes.size() ? ",\n" : "\n");
        }
        sb.append("  ],\n  \"display\": {\n");
        sb.append("    \"gui\": {\"rotation\": [30, 225, 0], \"translation\": [0, 1, 0], \"scale\": [0.625, 0.625, 0.625]}\n");
        sb.append("  }\n}\n");
        return sb.toString();
    }

    /** 毡垫上的霊符：3.5×8，顶带朝里（北），贴图与图标同一画法，取霊符那一块。 */
    static String sheet(String id) {
        return "{\n  \"textures\": {\n    \"sheet\": \"" + NS + ":block/fuda_desk_sheet_" + id + "\",\n    \"particle\": \"" + NS + ":block/fuda_desk_wood\"\n  },\n"
                + "  \"elements\": [\n    {\n      \"from\": [6.25, 12.6, 4.4],\n      \"to\": [9.75, 12.65, 12.4],\n      \"faces\": {\n"
                + "        \"up\": {\"uv\": [" + SX + ", 0, " + (SX + SW) + ", 16], \"texture\": \"#sheet\"}\n      }\n    }\n  ]\n}\n";
    }

    static String blockstate() {
        String[][] facings = {{"south", "0"}, {"west", "90"}, {"north", "180"}, {"east", "270"}};
        List<String> parts = new ArrayList<>();
        for (String[] f : facings) {
            String rot = f[1].equals("0") ? "" : ", \"y\": " + f[1];
            parts.add("    {\"when\": {\"facing\": \"" + f[0] + "\"}, \"apply\": {\"model\": \"" + NS + ":block/fuda_desk\"" + rot + "}}");
            for (Tier t : TIERS) {
                for (String page : new String[]{"blank", "inscribed"}) {
                    parts.add("    {\"when\": {\"facing\": \"" + f[0] + "\", \"page\": \"" + page + "\", \"page_tier\": \"" + t.id + "\"}, \"apply\": {\"model\": \""
                            + NS + ":block/fuda_desk_" + t.id + "_" + page + "\"" + rot + "}}");
                }
            }
        }
        return "{\n  \"multipart\": [\n" + String.join(",\n", parts) + "\n  ]\n}\n";
    }

    static String loot() {
        return "{\n  \"type\": \"minecraft:block\",\n  \"pools\": [\n    {\n      \"bonus_rolls\": 0.0,\n"
                + "      \"conditions\": [\n        {\n          \"condition\": \"minecraft:survives_explosion\"\n        }\n      ],\n"
                + "      \"entries\": [\n        {\n          \"type\": \"minecraft:item\",\n          \"name\": \"" + NS + ":fuda_desk\"\n        }\n      ],\n"
                + "      \"rolls\": 1.0\n    }\n  ],\n  \"random_sequence\": \"" + NS + ":blocks/fuda_desk\"\n}\n";
    }

    // —— 配方 ——

    /** Logos 载页升级用的材料，按品级顺序（第一档没有升级）。 */
    static final String[] UPGRADE = {null, "minecraft:amethyst_shard", "logos:echo_tincture", "logos:raven_head", "logos:chalk",
            "logos:firmament_stone", "logos:philosophers_stone", null};

    static void recipes() throws IOException {
        for (int i = 0; i < TIERS.size(); i++) {
            Tier t = TIERS.get(i);
            for (boolean written : new boolean[]{false, true}) {
                String reifu = NS + ":" + t.id + (written ? "_inscribed_reifu" : "_reifu");
                String page = "logos:" + (written ? t.logosInscribed : t.logosBlank);
                String group = NS + "_" + t.id + "_reifu";
                // 载页加红色染料染成霊符，霊符加白色染料褪回载页；写着的术式和名字都跟着走
                recipe(t.id + (written ? "_inscribed_reifu" : "_reifu") + "_from_page", group, reifu, page, "minecraft:red_dye");
                recipe((written ? t.logosInscribed : t.logosBlank) + "_from_reifu", group, page, reifu, "minecraft:white_dye");
                // 霊符之间按 Logos 的材料升级，和载页一样
                if (UPGRADE[i] != null) {
                    String prev = NS + ":" + TIERS.get(i - 1).id + (written ? "_inscribed_reifu" : "_reifu");
                    recipe(t.id + (written ? "_inscribed_reifu" : "_reifu") + "_from_upgrade", group + "_upgrade", reifu, prev, UPGRADE[i], "logos:adhesive");
                }
            }
        }
        json("data/" + NS + "/recipe/fuda_desk.json", "{\n  \"type\": \"minecraft:crafting_shapeless\",\n  \"category\": \"misc\",\n"
                + "  \"ingredients\": [\n    \"logos:inscription_table\",\n    \"minecraft:bamboo\",\n    \"minecraft:ink_sac\"\n  ],\n"
                + "  \"result\": {\n    \"id\": \"" + NS + ":fuda_desk\"\n  }\n}\n");
        json("data/" + NS + "/recipe/inscription_table_from_fuda_desk.json", "{\n  \"type\": \"minecraft:crafting_shapeless\",\n  \"category\": \"misc\",\n"
                + "  \"ingredients\": [\n    \"" + NS + ":fuda_desk\"\n  ],\n"
                + "  \"result\": {\n    \"id\": \"logos:inscription_table\"\n  }\n}\n");
    }

    /** 保留术式的无序配方（Logos 的 page_upgrade：成品带上原载页的组件）。 */
    static void recipe(String name, String group, String result, String... ingredients) throws IOException {
        StringBuilder sb = new StringBuilder("{\n  \"type\": \"logos:page_upgrade\",\n  \"category\": \"misc\",\n  \"group\": \"" + group + "\",\n  \"ingredients\": [\n");
        for (int i = 0; i < ingredients.length; i++) sb.append("    \"" + ingredients[i] + "\"").append(i + 1 < ingredients.length ? ",\n" : "\n");
        sb.append("  ],\n  \"result\": {\n    \"id\": \"" + result + "\"\n  }\n}\n");
        json("data/" + NS + "/recipe/" + name + ".json", sb.toString());
    }

    // —— 工具 ——

    static String vec(double[] v) {
        return "[" + num(v[0]) + ", " + num(v[1]) + ", " + num(v[2]) + "]";
    }

    static String num(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(Locale.ROOT, "%s", (float) d);
    }

    static int shade(int rgb, double k) {
        int r = (int) (((rgb >> 16) & 255) * k), g = (int) (((rgb >> 8) & 255) * k), b = (int) ((rgb & 255) * k);
        return (r << 16) | (g << 8) | b;
    }

    /** 固定种子的格子噪声，每次生成结果相同。 */
    static double noise(long seed, int x, int y) {
        long h = seed * 0x9E3779B97F4A7C15L + x * 0xC2B2AE3D27D4EB4FL + y * 0x165667B19E3779F9L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (h >>> 11) / (double) (1L << 53);
    }

    static void set(BufferedImage img, int x, int y, int rgb) {
        img.setRGB(x, y, 0xff000000 | rgb);
    }

    static void png(String rel, BufferedImage img) throws IOException {
        Path p = res.resolve(rel);
        Files.createDirectories(p.getParent());
        ImageIO.write(img, "png", p.toFile());
    }

    static void json(String rel, String text) throws IOException {
        Path p = res.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text, StandardCharsets.UTF_8);
    }
}
