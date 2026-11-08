package dev.logos.dimension.fox;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 地下封印场地的共用设计数据（tools/fox_undercroft.py 生成的 undercroft.json）：大鸟居之间与封印之间在每套方案里都在同一处，
 * 这里是要挖空的范围、岩壳厚度、分块模板、替换住宅用的封じの堂，以及九根封印杭的位置。坐标是设计坐标，y 相对主街标高。
 */
final class SanctuaryUndercroft {
    /** 约三分之一的聚落有地下封印场地。 */
    static final int CHANCE = 3;

    private static SanctuaryUndercroft data;

    /** 挖空范围，每 6 个一组：u0, y0, v0, u1, y1, v1（含两端）。 */
    final int[] boxes;
    final int shell;
    final List<SanctuaryLayout.Tile> tiles = new ArrayList<>();
    final List<Integer> tileY = new ArrayList<>();
    final String gateHouse;
    /** 封印之间的空气范围 [u0, v0, u1, v1, 地面层, 顶层] 与正中杀生石脚下的设计点。 */
    final int[] arena = new int[6];
    final int[] center = new int[3];
    /** 封印杭下半格的设计坐标，每 3 个一组。 */
    final int[] seals;

    private SanctuaryUndercroft(JsonObject json) {
        JsonArray b = json.getAsJsonArray("boxes");
        boxes = new int[b.size() * 6];
        for (int i = 0; i < b.size(); i++) {
            JsonArray box = b.get(i).getAsJsonArray();
            for (int k = 0; k < 6; k++) boxes[i * 6 + k] = box.get(k).getAsInt();
        }
        shell = json.get("shell").getAsInt();
        for (JsonElement e : json.getAsJsonArray("tiles")) {
            JsonObject t = e.getAsJsonObject();
            tiles.add(new SanctuaryLayout.Tile(t.get("template").getAsString(), t.get("u").getAsInt(), t.get("v").getAsInt()));
            tileY.add(t.get("y").getAsInt());
        }
        gateHouse = json.get("gate_house").getAsString();
        JsonArray a = json.getAsJsonArray("arena");
        for (int k = 0; k < 6; k++) arena[k] = a.get(k).getAsInt();
        JsonArray c = json.getAsJsonArray("center");
        for (int k = 0; k < 3; k++) center[k] = c.get(k).getAsInt();
        JsonArray s = json.getAsJsonArray("seals");
        seals = new int[s.size() * 3];
        for (int i = 0; i < s.size(); i++) {
            for (int k = 0; k < 3; k++) seals[i * 3 + k] = s.get(i).getAsJsonArray().get(k).getAsInt();
        }
    }

    static synchronized SanctuaryUndercroft get() {
        if (data == null) data = new SanctuaryUndercroft(SanctuaryLayout.read("/data/logos_dimension/fox_sanctuary/undercroft.json"));
        return data;
    }
}
