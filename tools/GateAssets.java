import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.imageio.ImageIO;

/**
 * 远行之门的素材：大号门模板（由维护者的投影蓝图转换）、小号门模板、启门石与传送面贴图。
 *
 * <p>单文件 Java，不依赖项目：
 * {@code java tools/GateAssets.java <logos维度传送门.litematic> src/main/resources}
 *
 * <p>大号门的转换规则：蓝色羊毛（占位）存成空气，保证开口不被地形堵住；地面中心那块泥土换成启门石；
 * 其余空气一律不存，门框周围的地形和植被不会被挖掉。
 */
public class GateAssets {
    static final int DATA_VERSION = 4790;

    // —— NBT 读 ——
    static Object read(DataInputStream in, int t) throws IOException {
        switch (t) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: { byte[] b = new byte[in.readInt()]; in.readFully(b); return b; }
            case 8: return in.readUTF();
            case 9: { int et = in.readByte(), n = in.readInt(); List<Object> l = new ArrayList<>(); for (int i = 0; i < n; i++) l.add(read(in, et)); return l; }
            case 10: { Map<String, Object> m = new LinkedHashMap<>(); while (true) { int et = in.readByte(); if (et == 0) return m; String k = in.readUTF(); m.put(k, read(in, et)); } }
            case 11: { int n = in.readInt(); int[] a = new int[n]; for (int i = 0; i < n; i++) a[i] = in.readInt(); return a; }
            case 12: { int n = in.readInt(); long[] a = new long[n]; for (int i = 0; i < n; i++) a[i] = in.readLong(); return a; }
            default: throw new IOException("未知标签 " + t);
        }
    }
    @SuppressWarnings("unchecked") static Map<String, Object> map(Object o) { return (Map<String, Object>) o; }
    @SuppressWarnings("unchecked") static List<Object> list(Object o) { return (List<Object>) o; }

    // —— NBT 写：只用到结构模板需要的几种 ——
    static void writeNamed(DataOutputStream out, String name, Object v) throws IOException {
        out.writeByte(tag(v));
        out.writeUTF(name);
        write(out, v);
    }
    static int tag(Object v) {
        if (v instanceof Integer) return 3;
        if (v instanceof String) return 8;
        if (v instanceof List) return 9;
        if (v instanceof Map) return 10;
        throw new IllegalArgumentException(String.valueOf(v));
    }
    static void write(DataOutputStream out, Object v) throws IOException {
        if (v instanceof Integer i) out.writeInt(i);
        else if (v instanceof String s) out.writeUTF(s);
        else if (v instanceof List<?> l) {
            out.writeByte(l.isEmpty() ? 0 : tag(l.get(0)));
            out.writeInt(l.size());
            for (Object e : l) write(out, e);
        } else if (v instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) writeNamed(out, (String) e.getKey(), e.getValue());
            out.writeByte(0);
        } else throw new IllegalArgumentException(String.valueOf(v));
    }

    /** 模板：坐标 → 方块状态（名字 + 属性）。没写的格子放置时不动世界里的方块。 */
    static final class Template {
        final int sx, sy, sz;
        final Map<List<Integer>, Map<String, Object>> blocks = new LinkedHashMap<>();
        Template(int sx, int sy, int sz) { this.sx = sx; this.sy = sy; this.sz = sz; }
        void put(int x, int y, int z, Map<String, Object> state) { blocks.put(List.of(x, y, z), state); }
        Map<String, Object> at(int x, int y, int z) { return blocks.get(List.of(x, y, z)); }

        void save(Path file) throws IOException {
            List<Object> palette = new ArrayList<>();
            Map<String, Integer> index = new HashMap<>();
            List<Object> list = new ArrayList<>();
            for (var e : blocks.entrySet()) {
                String key = new TreeMap<>(e.getValue()).toString();
                Integer id = index.get(key);
                if (id == null) { id = palette.size(); index.put(key, id); palette.add(e.getValue()); }
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("pos", new ArrayList<Object>(e.getKey()));
                b.put("state", id);
                list.add(b);
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("DataVersion", DATA_VERSION);
            root.put("size", List.of((Object) sx, sy, sz));
            root.put("palette", palette);
            root.put("blocks", list);
            root.put("entities", new ArrayList<>());
            Files.createDirectories(file.getParent());
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {
                writeNamed(out, "", root);
            }
        }
    }

    static Map<String, Object> state(String name, String... props) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Name", name);
        if (props.length > 0) {
            Map<String, Object> p = new LinkedHashMap<>();
            for (int i = 0; i < props.length; i += 2) p.put(props[i], props[i + 1]);
            m.put("Properties", p);
        }
        return m;
    }

    static final Map<String, Object> AIR = state("minecraft:air");
    static final Map<String, Object> STONE = state("logos_dimension:gate_stone", "charge", "0");

    /** 投影蓝图 → 大号门模板。 */
    static Template bigGate(Path litematic) throws IOException {
        Map<String, Object> root;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(litematic))))) {
            int t = in.readByte(); in.readUTF(); root = map(read(in, t));
        }
        Map<String, Object> reg = map(map(root.get("Regions")).values().iterator().next());
        Map<String, Object> size = map(reg.get("Size"));
        int ax = Math.abs((int) size.get("x")), ay = Math.abs((int) size.get("y")), az = Math.abs((int) size.get("z"));
        List<Object> palette = list(reg.get("BlockStatePalette"));
        int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
        long[] longs = (long[]) reg.get("BlockStates");
        long mask = (1L << bits) - 1;
        if ((int) root.get("MinecraftDataVersion") != DATA_VERSION) throw new IOException("蓝图数据版本不是 " + DATA_VERSION);
        Template out = new Template(ax, ay, az);
        int wool = 0;
        for (int y = 0; y < ay; y++) for (int z = 0; z < az; z++) for (int x = 0; x < ax; x++) {
            long bit = (long) (y * ax * az + z * ax + x) * bits;
            int w = (int) (bit >>> 6), off = (int) (bit & 63);
            long v = longs[w] >>> off;
            if (off + bits > 64) v |= longs[w + 1] << (64 - off);
            Map<String, Object> s = map(palette.get((int) (v & mask)));
            String name = (String) s.get("Name");
            if (name.equals("minecraft:air")) continue;
            if (name.equals("minecraft:blue_wool")) { out.put(x, y, z, AIR); wool++; continue; }
            out.put(x, y, z, s);
        }
        if (wool != 57) throw new IOException("开口应是 57 格，实际 " + wool);
        if (!"minecraft:dirt".equals(out.at(9, 0, 3).get("Name"))) throw new IOException("(9,0,3) 不是泥土，蓝图改过了？");
        out.put(9, 0, 3, STONE);
        return out;
    }

    /**
     * 小号门：另一侧有东西过来时自动生成。沿用大号门的语言（橡木门柱、云杉楼梯收圆角、杜鹃叶树冠、灯笼），
     * 开口 5×5 切掉四角，共 21 格。门的朝向是 X 轴，启门石在 (4,0,2)。
     */
    static Template smallGate() {
        Template t = new Template(9, 9, 5);
        Random r = new Random(20261105);
        var log = state("minecraft:oak_log", "axis", "y");
        var grass = state("minecraft:grass_block", "snowy", "false");
        var dirt = state("minecraft:dirt");
        // 地面
        for (int x = 0; x < 9; x++) for (int z = 0; z < 5; z++) t.put(x, 0, z, z == 2 && x >= 1 && x <= 7 ? dirt : grass);
        t.put(4, 0, 2, STONE);
        // 门洞前后的通道也清空，到达时不会被埋在山体或树里
        for (int z = 0; z < 5; z++) for (int y = 1; y <= 5; y++) for (int x = 2; x <= 6; x++) t.put(x, y, z, AIR);
        // 门柱和横梁
        for (int y = 0; y <= 6; y++) { t.put(1, y, 2, log); t.put(7, y, 2, log); }
        for (int x = 2; x <= 6; x++) t.put(x, 6, 2, log);
        // 四角的楼梯：朝向和大号门相同
        t.put(2, 1, 2, stairs("west", "bottom"));
        t.put(6, 1, 2, stairs("east", "bottom"));
        t.put(2, 5, 2, stairs("west", "top"));
        t.put(6, 5, 2, stairs("east", "top"));
        // 灯笼
        for (int x : new int[]{1, 7}) for (int z : new int[]{1, 3}) t.put(x, 1, z, state("minecraft:lantern", "hanging", "false", "waterlogged", "false"));
        // 门柱两侧的叶簇
        for (int x : new int[]{1, 7}) for (int z : new int[]{1, 3}) for (int y = 3; y <= 5; y++) if (y > 3 || r.nextBoolean()) t.put(x, y, z, leaf(r));
        for (int y = 2; y <= 5; y++) { if (y > 2 || r.nextBoolean()) t.put(0, y, 2, leaf(r)); if (y > 2 || r.nextBoolean()) t.put(8, y, 2, leaf(r)); }
        // 树冠
        for (int x = 0; x <= 8; x++) {
            if (x == 0 || x == 8) t.put(x, 6, 2, leaf(r));
            for (int z : new int[]{1, 3}) t.put(x, 6, z, leaf(r));
        }
        for (int x = 1; x <= 7; x++) for (int z = 1; z <= 3; z++) t.put(x, 7, z, leaf(r));
        for (int x = 2; x <= 6; x++) { if (r.nextInt(4) > 0) t.put(x, 7, 0, leaf(r)); if (r.nextInt(4) > 0) t.put(x, 7, 4, leaf(r)); }
        for (int x = 3; x <= 5; x++) for (int z = 1; z <= 3; z++) if (z == 2 || r.nextInt(3) > 0) t.put(x, 8, z, leaf(r));
        return t;
    }
    static Map<String, Object> stairs(String facing, String half) {
        return state("minecraft:spruce_stairs", "facing", facing, "half", half, "shape", "straight", "waterlogged", "false");
    }
    static Map<String, Object> leaf(Random r) {
        String name = r.nextInt(4) == 0 ? "minecraft:flowering_azalea_leaves" : "minecraft:azalea_leaves";
        return state(name, "distance", "7", "persistent", "true", "waterlogged", "false");
    }

    static Map<String, Object> mod(String name, String... props) { return state("logos_dimension:" + name, props); }
    static Map<String, Object> mc(String name, String... props) { return state("minecraft:" + name, props); }
    static void box(Template t, int x0, int y0, int z0, int x1, int y1, int z1, Map<String, Object> s) {
        for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) t.put(x, y, z, s);
    }
    static Map<String, Object> stair(String block, String facing, String half) {
        return mc(block, "facing", facing, "half", half, "shape", "straight", "waterlogged", "false");
    }
    static Map<String, Object> slab(String block, String type) { return mc(block, "type", type, "waterlogged", "false"); }
    static Map<String, Object> postWall(String block) {
        return mc(block, "east", "none", "north", "none", "south", "none", "west", "none", "up", "true", "waterlogged", "false");
    }

    /**
     * 神狐的小鸟居：朱漆柱、半格厚的贯、额束，朱漆岛木上一道出挑的灰瓦笠木，形制同聚落里的鸟居（fox_landmarks.py）。
     * 贯和岛木之间挂注连绳，参道铺石砖，两旁立石灯笼，地上落些樱花。开口 5×5，共 25 格。
     */
    static Template foxGate() {
        Template t = new Template(11, 10, 5);
        Random r = new Random(20261106);
        box(t, 0, 1, 0, 10, 9, 4, AIR);
        box(t, 0, 0, 0, 10, 0, 4, mc("grass_block", "snowy", "false"));
        box(t, 4, 0, 0, 6, 0, 4, mc("stone_bricks"));
        t.put(5, 0, 2, STONE);
        var pillar = mod("journey_lacquer_log", "axis", "y");
        for (int y = 0; y <= 7; y++) { t.put(2, y, 2, pillar); t.put(8, y, 2, pillar); }
        var nuki = mod("journey_lacquer_slab", "type", "top", "waterlogged", "false");
        for (int x = 1; x <= 9; x++) if (x != 2 && x != 8) t.put(x, 6, 2, nuki);
        t.put(5, 7, 2, mod("journey_lacquer_fence", "north", "false", "south", "false", "east", "false", "west", "false", "waterlogged", "false"));
        for (int x : new int[]{3, 4, 6, 7}) t.put(x, 7, 2, mod("journey_shimenawa", "facing", "north"));
        box(t, 1, 8, 2, 9, 8, 2, mod("journey_lacquer_planks"));
        box(t, 0, 9, 2, 10, 9, 2, mod("journey_kawara_slab", "type", "bottom", "waterlogged", "false"));
        for (int x : new int[]{1, 9}) for (int z : new int[]{0, 4}) t.put(x, 1, z, mod("journey_stone_lantern"));
        String[] facings = {"north", "east", "south", "west"};
        for (int i = 0; i < 9; i++) {
            int x = r.nextBoolean() ? r.nextInt(3) : 8 + r.nextInt(3), z = r.nextInt(5);
            if (t.at(x, 1, z) == AIR) t.put(x, 1, z, mc("pink_petals", "facing", facings[r.nextInt(4)], "flower_amount", String.valueOf(1 + r.nextInt(4))));
        }
        return t;
    }

    /**
     * 灵猫的英式门：红砖砌的小山墙，石砌尖拱，转角石块相间。山墙两端翘起一对猫耳，耳下两点暖黄的窗像猫眼。
     * 前后是常春藤、修剪过的树篱和铁灯柱，鹅卵石小径穿过门洞。开口 29 格。
     */
    static Template catGate() {
        Template t = new Template(11, 12, 5);
        Random r = new Random(20261107);
        box(t, 0, 1, 0, 10, 11, 4, AIR);
        box(t, 0, 0, 0, 10, 0, 4, mc("grass_block", "snowy", "false"));
        box(t, 3, 0, 0, 7, 0, 4, mc("cobblestone"));
        t.put(5, 0, 2, STONE);
        var brick = mc("bricks");
        var stone = mc("stone_bricks");
        for (int z = 1; z <= 3; z++) {
            // 墙身，转角石块和红砖相间
            box(t, 1, 1, z, 9, 8, z, brick);
            for (int y = 1; y <= 8; y += 2) { t.put(1, y, z, stone); t.put(9, y, z, stone); }
            // 门洞和拱
            box(t, 2, 1, z, 2, 6, z, stone);
            box(t, 8, 1, z, 8, 6, z, stone);
            box(t, 3, 1, z, 7, 5, z, AIR);
            box(t, 4, 6, z, 6, 6, z, AIR);
            t.put(5, 7, z, AIR);
            t.put(3, 6, z, stair("stone_brick_stairs", "west", "top"));
            t.put(7, 6, z, stair("stone_brick_stairs", "east", "top"));
            t.put(4, 7, z, stair("stone_brick_stairs", "west", "top"));
            t.put(6, 7, z, stair("stone_brick_stairs", "east", "top"));
            t.put(3, 7, z, stone);
            t.put(7, 7, z, stone);
            box(t, 4, 8, z, 6, 8, z, stone);
            t.put(5, 8, z, mc("chiseled_stone_bricks"));
            // 山墙和猫耳
            box(t, 2, 9, z, 8, 9, z, brick);
            box(t, 3, 10, z, 7, 10, z, brick);
            t.put(1, 9, z, stair("stone_brick_stairs", "east", "bottom"));
            t.put(9, 9, z, stair("stone_brick_stairs", "west", "bottom"));
            t.put(2, 10, z, stair("stone_brick_stairs", "east", "bottom"));
            t.put(8, 10, z, stair("stone_brick_stairs", "west", "bottom"));
            t.put(3, 11, z, stair("stone_brick_stairs", "west", "bottom"));
            t.put(7, 11, z, stair("stone_brick_stairs", "east", "bottom"));
            box(t, 4, 11, z, 6, 11, z, slab("stone_brick_slab", "bottom"));
        }
        // 猫眼：前后两面各两点
        for (int z : new int[]{1, 3}) { t.put(4, 10, z, mc("ochre_froglight", "axis", "y")); t.put(6, 10, z, mc("ochre_froglight", "axis", "y")); }
        // 树篱、灯柱
        var hedge = mc("spruce_leaves", "distance", "7", "persistent", "true", "waterlogged", "false");
        for (int x : new int[]{1, 2, 8, 9}) for (int z : new int[]{0, 4}) t.put(x, 1, z, hedge);
        for (int x : new int[]{0, 10}) for (int z : new int[]{0, 4}) {
            t.put(x, 1, z, postWall("stone_brick_wall"));
            t.put(x, 2, z, mc("iron_bars", "east", "false", "north", "false", "south", "false", "west", "false", "waterlogged", "false"));
            t.put(x, 3, z, mc("lantern", "hanging", "false", "waterlogged", "false"));
        }
        // 常春藤爬在墙面上
        for (int x : new int[]{1, 2, 8, 9}) for (int y = 2; y <= 8; y++) {
            if (r.nextInt(5) < 2) t.put(x, y, 0, vine("south"));
            if (r.nextInt(5) < 2) t.put(x, y, 4, vine("north"));
        }
        return t;
    }
    static Map<String, Object> vine(String side) {
        return mc("vine", "up", "false", "north", String.valueOf(side.equals("north")), "south", String.valueOf(side.equals("south")), "east", "false", "west", "false");
    }

    /**
     * 群狼的门：古罗马凯旋门。白石砌的宽圆拱，拱顶一块拱心石，两侧贴着白色壁柱，上面是铭文带、带线脚的檐口和一层阁楼。
     * 斯巴达的部分在装饰上：深红战旗、青铜盾饰和青铜铭文带，阁楼上立两杆青铜长枪，四角是青铜灯。开口 43 格。
     */
    static Template wolfGate() {
        Template t = new Template(13, 13, 5);
        box(t, 0, 1, 0, 12, 12, 4, AIR);
        box(t, 0, 0, 0, 12, 0, 4, mc("polished_diorite"));
        box(t, 3, 0, 0, 9, 0, 4, mc("smooth_stone"));
        t.put(6, 0, 2, STONE);
        var wall = mc("calcite");
        var marble = mc("smooth_quartz");
        var bronze = mc("waxed_chiseled_copper");
        for (int z = 1; z <= 3; z++) {
            box(t, 1, 1, z, 11, 8, z, wall);
            // 门洞两侧的石框和圆拱
            box(t, 2, 1, z, 2, 6, z, marble);
            box(t, 10, 1, z, 10, 6, z, marble);
            box(t, 3, 1, z, 9, 5, z, AIR);
            box(t, 4, 6, z, 8, 6, z, AIR);
            box(t, 5, 7, z, 7, 7, z, AIR);
            t.put(3, 6, z, stair("quartz_stairs", "west", "top"));
            t.put(9, 6, z, stair("quartz_stairs", "east", "top"));
            t.put(4, 7, z, stair("quartz_stairs", "west", "top"));
            t.put(8, 7, z, stair("quartz_stairs", "east", "top"));
            t.put(3, 7, z, marble);
            t.put(9, 7, z, marble);
            box(t, 4, 8, z, 8, 8, z, marble);
            t.put(6, 8, z, mc("chiseled_quartz_block"));
            // 铭文带
            box(t, 1, 9, z, 11, 9, z, marble);
            box(t, 2, 9, z, 10, 9, z, mc("waxed_cut_copper"));
            // 檐口
            box(t, 1, 10, z, 11, 10, z, marble);
            t.put(0, 10, z, stair("quartz_stairs", "east", "top"));
            t.put(12, 10, z, stair("quartz_stairs", "west", "top"));
            // 阁楼
            box(t, 2, 11, z, 10, 11, z, wall);
            box(t, 2, 12, z, 10, 12, z, slab("smooth_quartz_slab", "bottom"));
        }
        // 檐口前后两沿向外收的线脚
        box(t, 0, 10, 0, 12, 10, 0, stair("quartz_stairs", "south", "top"));
        box(t, 0, 10, 4, 12, 10, 4, stair("quartz_stairs", "north", "top"));
        // 盾饰：门洞两侧各一面，阁楼正中一面
        for (int z : new int[]{1, 3}) { t.put(2, 8, z, bronze); t.put(10, 8, z, bronze); t.put(6, 11, z, bronze); }
        // 白色壁柱，贴在墙面前后
        for (int z : new int[]{0, 4}) for (int x : new int[]{1, 11}) {
            t.put(x, 1, z, mc("chiseled_quartz_block"));
            box(t, x, 2, z, x, 8, z, mc("quartz_pillar", "axis", "y"));
            t.put(x, 9, z, mc("chiseled_quartz_block"));
        }
        // 深红战旗挂在门洞两侧
        for (int x : new int[]{2, 10}) {
            t.put(x, 7, 0, mc("red_wall_banner", "facing", "north"));
            t.put(x, 7, 4, mc("red_wall_banner", "facing", "south"));
        }
        // 阁楼上的长枪
        for (int x : new int[]{3, 9}) t.put(x, 12, 2, mc("waxed_lightning_rod", "facing", "up", "powered", "false", "waterlogged", "false"));
        // 四角的青铜灯
        for (int x : new int[]{0, 12}) for (int z : new int[]{0, 4}) {
            t.put(x, 1, z, mc("chiseled_quartz_block"));
            t.put(x, 2, z, mc("waxed_copper_bulb", "lit", "true", "powered", "false"));
        }
        return t;
    }

    /**
     * 人类帝国的门：中世纪城门楼。两座带垛口的灰石塔向前后凸出，夹着一段城墙和圆拱门洞，塔上开箭窗；
     * 石块里掺一些长苔和开裂的，像用了很久。帝国蓝的战旗挂在塔身，塔脚插火把，土路穿过门洞。开口 23 格。
     */
    static Template humanGate() {
        Template t = new Template(11, 12, 7);
        Random r = new Random(20261108);
        box(t, 0, 1, 0, 10, 11, 6, AIR);
        box(t, 0, 0, 0, 10, 0, 6, mc("grass_block", "snowy", "false"));
        box(t, 3, 0, 0, 7, 0, 6, mc("dirt_path"));
        t.put(5, 0, 3, STONE);
        // 两座塔
        for (int x : new int[]{0, 1, 2, 8, 9, 10}) for (int z = 1; z <= 5; z++) for (int y = 1; y <= 9; y++) t.put(x, y, z, worn(r));
        // 塔顶的垛口
        for (int x : new int[]{0, 2, 8, 10}) for (int z : new int[]{1, 3, 5}) t.put(x, 10, z, worn(r));
        // 中间的城墙和门洞
        for (int z = 2; z <= 4; z++) {
            for (int x = 3; x <= 7; x++) for (int y = 6; y <= 7; y++) t.put(x, y, z, worn(r));
            t.put(3, 5, z, stair("stone_brick_stairs", "west", "top"));
            t.put(7, 5, z, stair("stone_brick_stairs", "east", "top"));
            t.put(5, 6, z, mc("chiseled_stone_bricks"));
            for (int x = 3; x <= 7; x++) t.put(x, 8, z, slab("stone_brick_slab", "bottom"));
        }
        // 城墙的垛口：只立在前后两沿，中间是走道
        for (int x : new int[]{3, 5, 7}) for (int z : new int[]{2, 4}) t.put(x, 8, z, worn(r));
        // 箭窗
        var bars = mc("iron_bars", "east", "false", "north", "false", "south", "false", "west", "false", "waterlogged", "false");
        for (int x : new int[]{1, 9}) for (int z : new int[]{1, 5}) t.put(x, 7, z, bars);
        // 战旗和火把
        for (int x : new int[]{1, 9}) {
            t.put(x, 5, 0, mc("blue_wall_banner", "facing", "north"));
            t.put(x, 5, 6, mc("blue_wall_banner", "facing", "south"));
        }
        for (int x : new int[]{2, 8}) {
            t.put(x, 3, 0, mc("wall_torch", "facing", "north"));
            t.put(x, 3, 6, mc("wall_torch", "facing", "south"));
        }
        return t;
    }
    static Map<String, Object> worn(Random r) {
        int k = r.nextInt(100);
        return mc(k < 12 ? "mossy_stone_bricks" : k < 22 ? "cracked_stone_bricks" : "stone_bricks");
    }

    /** 模板开口自检：从启门石上方在门面内泛洪，必须正好是期望的格数。 */
    static void checkOpening(Template t, int sx, int sy, int sz, int expect) {
        Set<List<Integer>> seen = new HashSet<>();
        Deque<int[]> q = new ArrayDeque<>();
        q.add(new int[]{sx, sy, sz});
        seen.add(List.of(sx, sy, sz));
        while (!q.isEmpty()) {
            int[] c = q.poll();
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int x = c[0] + d[0], y = c[1] + d[1];
                if (x < 0 || y < 0 || x >= t.sx || y >= t.sy) throw new IllegalStateException("开口漏到模板外面了");
                var s = t.at(x, y, sz);
                if (s != null && !s.get("Name").equals("minecraft:air")) continue;
                if (seen.add(List.of(x, y, sz))) q.add(new int[]{x, y});
            }
        }
        if (seen.size() != expect) throw new IllegalStateException("开口应是 " + expect + " 格，实际 " + seen.size());
    }

    // —— 贴图 ——
    static int argb(int a, int r, int g, int b) { return a << 24 | r << 16 | g << 8 | b; }
    static int lerp(int a, int b, double k) { return (int) Math.round(a + (b - a) * k); }
    static double smooth(double lo, double hi, double x) { double t = Math.clamp((x - lo) / (hi - lo), 0, 1); return t * t * (3 - 2 * t); }

    /** 启门石：长青苔的旧石块，中间刻一圈符纹，充能越多符纹越亮（charge 0–4）。 */
    static void stoneTexture(Path file, int charge) throws IOException {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Random r = new Random(1746);
        double[][] grain = new double[16][16], moss = new double[16][16];
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) { grain[y][x] = r.nextDouble(); moss[y][x] = r.nextDouble(); }
        double glow = charge / 4.0;
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            double dx = x - 7.5, dy = y - 7.5, d = Math.sqrt(dx * dx + dy * dy);
            // 石面明暗 + 边缘一圈凿出来的暗线
            int shade = (int) (92 + 26 * grain[y][x]);
            if (x == 0 || y == 0 || x == 15 || y == 15) shade -= 26;
            int cr = shade, cg = shade + 2, cb = shade + 4;
            // 边角的苔藓
            double edge = Math.max(Math.abs(dx), Math.abs(dy));
            if (edge > 5.5 && moss[y][x] > 0.62) { cr = (int) (cr * .62); cg = (int) (cg * .86); cb = (int) (cb * .5); }
            // 符纹：一圈环 + 十字四点；没有魔力时只是凿痕，有魔力时泛蓝发亮
            boolean rune = (d > 3.6 && d < 5.0) || (Math.abs(dx) < 1 && Math.abs(dy) < 1)
                    || (Math.abs(dx) < .6 && Math.abs(dy) > 5.0 && Math.abs(dy) < 7) || (Math.abs(dy) < .6 && Math.abs(dx) > 5.0 && Math.abs(dx) < 7);
            if (rune) {
                cr = lerp((int) (cr * .55), 190, glow);
                cg = lerp((int) (cg * .55), 240, glow);
                cb = lerp((int) (cb * .60), 255, glow);
            } else if (d < 7.2 && glow > 0) {
                // 符纹周围的石面被照亮一点
                double halo = (1 - smooth(1, 7.2, Math.abs(d - 4.3))) * glow * .35;
                cr = lerp(cr, 90, halo); cg = lerp(cg, 190, halo); cb = lerp(cb, 240, halo);
            }
            img.setRGB(x, y, argb(255, Math.clamp(cr, 0, 255), Math.clamp(cg, 0, 255), Math.clamp(cb, 0, 255)));
        }
        Files.createDirectories(file.getParent());
        ImageIO.write(img, "png", file.toFile());
    }

    /** 传送面贴图横跨几格：每格只显示其中一块，整片门面的纹路是连续的，不会一格一格重复。 */
    static final int TILE_BLOCKS = 8;

    /** 在 x、y、t 三个方向都首尾相接的随机值噪声：x、y 每个周期 cells 格，t 每个周期 steps 格。 */
    static final class LoopNoise {
        final int cells, steps;
        final double[][][] table;
        LoopNoise(Random r, int cells, int steps) {
            this.cells = cells;
            this.steps = steps;
            table = new double[steps][cells][cells];
            for (double[][] plane : table) for (double[] row : plane) for (int i = 0; i < cells; i++) row[i] = r.nextDouble();
        }
        double at(double x, double y, double t) {
            double fx = x * cells, fy = y * cells, ft = t * steps;
            int ix = (int) Math.floor(fx), iy = (int) Math.floor(fy), it = (int) Math.floor(ft);
            double ax = smooth(0, 1, fx - ix), ay = smooth(0, 1, fy - iy), at = smooth(0, 1, ft - it);
            double out = 0;
            for (int dt = 0; dt <= 1; dt++) for (int dy = 0; dy <= 1; dy++) for (int dx = 0; dx <= 1; dx++) {
                double w = (dx == 0 ? 1 - ax : ax) * (dy == 0 ? 1 - ay : ay) * (dt == 0 ? 1 - at : at);
                out += w * table[Math.floorMod(it + dt, steps)][Math.floorMod(iy + dy, cells)][Math.floorMod(ix + dx, cells)];
            }
            return out;
        }
    }

    /** 几层越来越细的噪声叠在一起，结果大致在 0–1。 */
    static double fractal(LoopNoise[] layers, double x, double y, double t) {
        double sum = 0, total = 0, weight = 1;
        for (LoopNoise layer : layers) {
            sum += weight * layer.at(x, y, t);
            total += weight;
            weight *= .55;
        }
        return sum / total;
    }

    static LoopNoise[] layers(Random r, int cells, int steps) {
        LoopNoise[] out = new LoopNoise[4];
        for (int i = 0; i < out.length; i++) out[i] = new LoopNoise(r, cells << i, steps);
        return out;
    }

    /**
     * 传送面：128×128 的淡蓝色流光。用随机噪声而不是规则的波，再用另外两组噪声把坐标扭一下，
     * 纹路没有固定的间距和方向，看不出循环。横向、纵向和时间都首尾相接，8×8 格之间、动画首尾都无缝。
     */
    static void portalTexture(Path file) throws IOException {
        int frames = 16, size = 16 * TILE_BLOCKS;
        BufferedImage img = new BufferedImage(size, size * frames, BufferedImage.TYPE_INT_ARGB);
        Random r = new Random(20261105);
        LoopNoise[] body = layers(r, 5, 2), warpX = layers(r, 3, 2), warpY = layers(r, 3, 2), veil = layers(r, 9, 2);
        for (int f = 0; f < frames; f++) for (int py = 0; py < size; py++) for (int px = 0; px < size; px++) {
            double x = px / (double) size, y = py / (double) size, t = f / (double) frames;
            double wx = x + .22 * (fractal(warpX, x, y, t) - .5), wy = y + .22 * (fractal(warpY, x, y, t) - .5);
            wx -= Math.floor(wx);
            wy -= Math.floor(wy);
            double v = smooth(.3, .72, fractal(body, wx, wy, t));
            // 零星几缕更亮的光
            double glint = smooth(.42, .78, fractal(veil, x, y, t));
            double k = Math.clamp(v * .65 + glint * .3, 0, 1);
            // 淡蓝 → 浅青蓝，比白天的天空偏青一点，门面不会和背景融在一起。透明度压得低，背后的地面和天空不会把颜色染脏
            int cr = lerp(112, 208, k), cg = lerp(192, 238, k), cb = lerp(240, 255, k), ca = lerp(218, 242, k);
            img.setRGB(px, f * size + py, argb(ca, cr, cg, cb));
        }
        Files.createDirectories(file.getParent());
        ImageIO.write(img, "png", file.toFile());
        Files.writeString(Path.of(file + ".mcmeta"), "{\n  \"animation\": {\n    \"frametime\": 5,\n    \"interpolate\": true\n  }\n}\n");
    }

    /** 一片传送面的模型：from/to 决定画在哪（本格或相邻半格），col/row 决定显示贴图的哪一块。 */
    static String portalModel(int[] from, int[] to, int col, int row) {
        int part = 16 / TILE_BLOCKS, v = (TILE_BLOCKS - 1 - row) * part, south = col * part, north = (TILE_BLOCKS - 1 - col) * part;
        return """
                {
                  "ambientocclusion": false,
                  "textures": {
                    "particle": "logos_dimension:block/gate_portal",
                    "portal": "logos_dimension:block/gate_portal"
                  },
                  "render_type": "neoforge:translucent",
                  "elements": [
                    {
                      "from": [%d, %d, 8],
                      "to": [%d, %d, 8],
                      "shade": false,
                      "light_emission": 15,
                      "faces": {
                        "north": { "texture": "#portal", "uv": [%d, %d, %d, %d] },
                        "south": { "texture": "#portal", "uv": [%d, %d, %d, %d] }
                      }
                    }
                  ]
                }
                """.formatted(from[0], from[1], to[0], to[1], north, v, north + part, v + part, south, v, south + part, v + part);
    }

    /**
     * 传送面的方块状态和模型：col/row 是这格在 4×4 贴图里的位置；edge_* 为真时多画一格盖住相邻半格的空缺，
     * 用的是相邻那一格的贴图块，纹路接得上。Z 向的门面把同样的模型转 90°。
     */
    static void portalModels(Path assets) throws IOException {
        String[] parts = {"up", "down", "negative", "positive"};
        int[][] dCell = {{0, 1}, {0, -1}, {-1, 0}, {1, 0}};
        Path dir = assets.resolve("models/block/gate_portal");
        Files.createDirectories(dir);
        StringBuilder states = new StringBuilder("{\n  \"multipart\": [\n");
        boolean first = true;
        for (int row = 0; row < TILE_BLOCKS; row++) for (int col = 0; col < TILE_BLOCKS; col++) {
            String name = "c" + col + "r" + row;
            Files.writeString(dir.resolve(name + ".json"), portalModel(new int[]{0, 0}, new int[]{16, 16}, col, row));
            for (int i = 0; i < parts.length; i++) {
                int[] d = dCell[i];
                int nextCol = Math.floorMod(col + d[0], TILE_BLOCKS), nextRow = Math.floorMod(row + d[1], TILE_BLOCKS);
                Files.writeString(dir.resolve(name + "_" + parts[i] + ".json"),
                        portalModel(new int[]{d[0] * 16, d[1] * 16}, new int[]{16 + d[0] * 16, 16 + d[1] * 16}, nextCol, nextRow));
            }
            for (String axis : new String[]{"x", "z"}) {
                String rot = axis.equals("z") ? ", \"y\": 90" : "";
                String when = "\"axis\": \"" + axis + "\", \"col\": \"" + col + "\", \"row\": \"" + row + "\"";
                states.append(first ? "" : ",\n").append("    { \"when\": { ").append(when).append(" }, \"apply\": { \"model\": \"logos_dimension:block/gate_portal/").append(name).append("\"").append(rot).append(" } }");
                first = false;
                for (String part : parts)
                    states.append(",\n    { \"when\": { ").append(when).append(", \"edge_").append(part).append("\": \"true\" }, \"apply\": { \"model\": \"logos_dimension:block/gate_portal/").append(name).append("_").append(part).append("\"").append(rot).append(" } }");
            }
        }
        states.append("\n  ]\n}\n");
        Files.writeString(assets.resolve("blockstates/gate_portal.json"), states.toString());
    }

    public static void main(String[] args) throws Exception {
        Path res = Path.of(args[1]);
        Template big = bigGate(Path.of(args[0]));
        checkOpening(big, 9, 1, 3, 57);
        big.save(res.resolve("data/logos_dimension/structure/gate/journey_gate.nbt"));
        Template small = smallGate();
        checkOpening(small, 4, 1, 2, 21);
        small.save(res.resolve("data/logos_dimension/structure/gate/small_gate.nbt"));
        for (var gate : List.of(Map.entry("fox_gate", Map.entry(foxGate(), 25)), Map.entry("cat_gate", Map.entry(catGate(), 29)),
                Map.entry("wolf_gate", Map.entry(wolfGate(), 43)), Map.entry("human_gate", Map.entry(humanGate(), 23)))) {
            Template t = gate.getValue().getKey();
            checkOpening(t, t.sx / 2, 1, t.sz / 2, gate.getValue().getValue());
            t.save(res.resolve("data/logos_dimension/structure/gate/" + gate.getKey() + ".nbt"));
        }
        Path tex = res.resolve("assets/logos_dimension/textures/block");
        for (int c = 0; c <= 4; c++) stoneTexture(tex.resolve("gate_stone_" + c + ".png"), c);
        portalTexture(tex.resolve("gate_portal.png"));
        portalModels(res.resolve("assets/logos_dimension"));
        System.out.println("大号门 " + big.blocks.size() + " 个方块，小号门 " + small.blocks.size() + " 个方块");
    }
}
