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

    private static final String RESOURCE = "/data/logos_dimension/fox_sanctuary/layout.json";
    private static SanctuaryLayout instance;

    final int size;
    final byte[] heights;
    final byte[] ground;
    /** 入口石阶底部（核心外一格）的设计坐标与相对高度。 */
    final int entranceU, entranceV, entranceY;
    final int tileMinY;
    final List<Tile> tiles = new ArrayList<>();
    final List<Lot> lots = new ArrayList<>();
    final Map<String, List<String>> variants = new HashMap<>();

    record Tile(String template, int u, int v) {
    }

    /** 可替换的地块：模板按 quarterTurns 次顺时针旋转后放在 (u, y, v)。 */
    record Lot(String kind, int u, int y, int v, int quarterTurns) {
    }

    static synchronized SanctuaryLayout get() {
        if (instance == null) {
            try (InputStream in = SanctuaryLayout.class.getResourceAsStream(RESOURCE)) {
                if (in == null) throw new IllegalStateException("缺少 " + RESOURCE);
                instance = new SanctuaryLayout(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
            } catch (java.io.IOException e) {
                throw new IllegalStateException("读取神狐聚落设计数据失败", e);
            }
        }
        return instance;
    }

    private SanctuaryLayout(JsonObject json) {
        size = json.get("size").getAsInt();
        heights = Base64.getDecoder().decode(json.get("heights").getAsString());
        ground = Base64.getDecoder().decode(json.get("ground").getAsString());
        JsonObject entrance = json.getAsJsonObject("entrance");
        entranceU = entrance.get("u").getAsInt();
        entranceV = entrance.get("v").getAsInt();
        entranceY = entrance.get("y").getAsInt();
        tileMinY = json.get("tile_min_y").getAsInt();
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
        if (heights.length != size * size || ground.length != size * size) throw new IllegalStateException("神狐聚落高度图尺寸不符");
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
