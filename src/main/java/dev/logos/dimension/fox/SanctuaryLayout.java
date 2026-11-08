package dev.logos.dimension.fox;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 山顶聚落的设计数据，由 tools/fox_sanctuary.py 生成并随 Mod 打包。
 *
 * <p>坐标是“设计坐标”(u, v)：u 向东、v 向南，核心区为 [0, size)²；高度相对主街标高 P。
 * 入口石阶朝西，山路从入口往北绕山下行；实际朝向由结构按地形选定的镜像与旋转决定。
 */
final class SanctuaryLayout {
    /** 地表类型：可被自然地形抬高的外围坡（保留樱野草皮，自然生长樱树）。 */
    static final int SOFT_TURF = 0;
    /** 精确高度的樱野草皮。 */
    static final int TURF = 1;
    static final int LAWN = 2;
    static final int MOSS = 3;
    static final int COARSE = 4;
    static final int PATH = 5;
    static final int GRAVEL = 6;
    static final int STONE = 7;

    /** 方案 0 沿用最早的位置（旧存档里生成到一半的聚落还认这些模板），其余方案在 plan_N/ 下。 */
    private static final String ROOT = "/data/logos_dimension/fox_sanctuary/";
    private static SanctuaryLayout[] plans;

    /** 第几套布局方案。 */
    final int plan;
    final int size;
    final byte[] heights;
    final byte[] ground;
    /** 入口石阶底部（核心外一格）的设计坐标与相对高度。 */
    final int entranceU, entranceV, entranceY;
    final int tileMinY;
    /** 神社境内的设计坐标范围 [u0, v0, u1, v1] 与平台相对高度；旧数据里没有时范围为空。 */
    final int shrineU, shrineY;
    private final int shrineU0, shrineV0, shrineU1, shrineV1;
    /** 神社与旅馆片区翻转轴的两倍；0 表示没有翻转。 */
    final int flipAxis, ryokanFlipAxis;
    /** 街尾空地的范围 [u0, v0, u1, v1]（开发预览用）。 */
    final int[] endBox = new int[4];
    final List<Tile> tiles = new ArrayList<>();
    final List<Lot> lots = new ArrayList<>();
    final Map<String, List<String>> variants = new HashMap<>();
    /** 居酒屋模板：每处聚落至多随机 2–3 家（商铺与住宅地块合计）。 */
    final java.util.Set<String> izakaya = new java.util.HashSet<>();
    /** 地下封印场地的路线：哪块住宅换成封じの堂，以及从堂下回旋石阶到大鸟居之间的那段通道模板。 */
    final List<Route> routes = new ArrayList<>();

    record Tile(String template, int u, int v) {
    }

    /** 可替换的地块：模板按 quarterTurns 次顺时针旋转后放在 (u, y, v)。 */
    record Lot(String kind, int u, int y, int v, int quarterTurns) {
    }

    /** 一条地下路线：lot 为被换成封じの堂的住宅地块序号，模板原点在 (u, y, v)；井心、堂的地面高度与通道中线的折点供开发预览用。 */
    record Route(int lot, String template, int u, int y, int v, int shaftU, int shaftV, int groundY, int[] path) {
    }

    /** 布局方案的数量（plans.json）。 */
    static int plans() {
        return all().length;
    }

    static SanctuaryLayout get(int plan) {
        SanctuaryLayout[] all = all();
        return all[Math.floorMod(plan, all.length)];
    }

    private static synchronized SanctuaryLayout[] all() {
        if (plans == null) {
            int count = read(ROOT + "plans.json").get("count").getAsInt();
            SanctuaryLayout[] loaded = new SanctuaryLayout[count];
            for (int i = 0; i < count; i++) {
                loaded[i] = new SanctuaryLayout(i, read(ROOT + (i == 0 ? "" : "plan_" + i + "/") + "layout.json"));
            }
            plans = loaded;
        }
        return plans;
    }

    static JsonObject read(String resource) {
        try (InputStream in = SanctuaryLayout.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("缺少 " + resource);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读取神狐聚落设计数据失败", e);
        }
    }

    private SanctuaryLayout(int plan, JsonObject json) {
        this.plan = plan;
        size = json.get("size").getAsInt();
        heights = Base64.getDecoder().decode(json.get("heights").getAsString());
        ground = Base64.getDecoder().decode(json.get("ground").getAsString());
        JsonObject entrance = json.getAsJsonObject("entrance");
        entranceU = entrance.get("u").getAsInt();
        entranceV = entrance.get("v").getAsInt();
        entranceY = entrance.get("y").getAsInt();
        tileMinY = json.get("tile_min_y").getAsInt();
        JsonObject shrine = json.getAsJsonObject("shrine");
        JsonArray box = shrine.getAsJsonArray("box");
        shrineU0 = box.get(0).getAsInt();
        shrineV0 = box.get(1).getAsInt();
        shrineU1 = box.get(2).getAsInt();
        shrineV1 = box.get(3).getAsInt();
        shrineU = shrine.get("u").getAsInt();
        shrineY = shrine.get("y").getAsInt();
        flipAxis = shrine.get("flip_axis").getAsInt();
        ryokanFlipAxis = shrine.get("ryokan_flip_axis").getAsInt();
        JsonArray end = shrine.getAsJsonArray("end_box");
        for (int i = 0; i < 4; i++) endBox[i] = end.get(i).getAsInt();
        for (JsonElement e : json.getAsJsonArray("tiles")) {
            JsonObject t = e.getAsJsonObject();
            tiles.add(new Tile(t.get("template").getAsString(), t.get("u").getAsInt(), t.get("v").getAsInt()));
        }
        for (JsonElement e : json.getAsJsonArray("lots")) {
            JsonObject l = e.getAsJsonObject();
            lots.add(new Lot(l.get("kind").getAsString(), l.get("u").getAsInt(), l.get("y").getAsInt(), l.get("v").getAsInt(), l.get("turns").getAsInt()));
        }
        for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("variants").entrySet()) {
            List<String> names = new ArrayList<>();
            for (JsonElement n : (JsonArray) e.getValue()) names.add(n.getAsString());
            variants.put(e.getKey(), names);
        }
        if (json.has("izakaya")) for (JsonElement n : json.getAsJsonArray("izakaya")) izakaya.add(n.getAsString());
        if (json.has("undercroft")) {
            for (JsonElement e : json.getAsJsonObject("undercroft").getAsJsonArray("routes")) {
                JsonObject r = e.getAsJsonObject();
                JsonArray shaft = r.getAsJsonArray("shaft"), path = r.getAsJsonArray("path");
                int[] pts = new int[path.size() * 2];
                for (int i = 0; i < path.size(); i++) {
                    pts[i * 2] = path.get(i).getAsJsonArray().get(0).getAsInt();
                    pts[i * 2 + 1] = path.get(i).getAsJsonArray().get(1).getAsInt();
                }
                routes.add(new Route(r.get("lot").getAsInt(), r.get("template").getAsString(), r.get("u").getAsInt(), r.get("y").getAsInt(),
                    r.get("v").getAsInt(), shaft.get(0).getAsInt(), shaft.get(1).getAsInt(), r.get("depth").getAsInt() + r.get("y").getAsInt(), pts));
            }
        }
        if (heights.length != size * size || ground.length != size * size) throw new IllegalStateException("神狐聚落高度图尺寸不符");
    }

    boolean inShrine(int u, int v) {
        return u >= shrineU0 && u <= shrineU1 && v >= shrineV0 && v <= shrineV1;
    }

    boolean inside(int u, int v) {
        return u >= 0 && v >= 0 && u < size && v < size;
    }

    int height(int u, int v) {
        return heights[v * size + u];
    }

    int ground(int u, int v) {
        return ground[v * size + u];
    }
}
