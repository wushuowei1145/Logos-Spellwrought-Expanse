package dev.logos.dimension;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 灵狐樱野的随机地形装饰：巨石、碎石、山泉、白花片。
 *
 * <p>和村庄一样是随机撒点，落点只在群系内地表；这里钉住"数据存在且挂进群系"，
 * 实际生成效果要靠进游戏看。
 */
class CherryTerrainTest {
    private static final String BIOME = "cloudsea_white_cherry";
    private static final String[] FEATURES = {"cherry_boulder", "cherry_pebbles", "cherry_pool", "cherry_white_flowers"};

    private static JsonObject data(String path) throws IOException {
        try (var input = CherryTerrainTest.class.getResourceAsStream("/" + path)) {
            assertNotNull(input, "缺少数据文件：" + path);
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test void cherryBiomeHasRandomTerrainPieces() throws Exception {
        var biome = data("data/logos_dimension/worldgen/biome/" + BIOME + ".json");
        var step9 = new ArrayList<String>();
        biome.getAsJsonArray("features").get(9).getAsJsonArray().forEach(v -> step9.add(v.getAsString()));
        for (String name : FEATURES) {
            assertTrue(step9.contains("logos_dimension:" + name), "灵狐樱野缺少地形装饰：" + name);
        }
    }

    @Test void terrainPiecesStayInsideTheBiome() throws Exception {
        for (String name : FEATURES) {
            var placed = data("data/logos_dimension/worldgen/placed_feature/" + name + ".json");
            var placement = placed.getAsJsonArray("placement").toString();
            assertTrue(placement.contains("minecraft:biome"), name + " 没有用群系过滤，会撒到别的群系");
            assertNotNull(data("data/logos_dimension/worldgen/configured_feature/" + name + ".json"));
        }
    }
}
