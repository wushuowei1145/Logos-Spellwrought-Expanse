package dev.logos.dimension;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 灵狐樱野的随机地形装饰：巨石、碎石、山泉、白花片、石板遗迹。
 *
 * <p>和村庄一样是随机撒点，落点只在群系内地表；这里钉住"数据存在、挂进群系、
 * 用的都是真实注册过的特征类型"。实际生成效果要靠进游戏看。
 */
class CherryTerrainTest {
    private static final String BIOME = "cloudsea_white_cherry";
    private static final String[] FEATURES = {
        "cherry_boulder", "cherry_pebbles", "cherry_pool", "cherry_white_flowers", "cherry_paving",
    };

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
            assertTrue(placed.getAsJsonArray("placement").toString().contains("minecraft:biome"),
                name + " 没有用群系过滤，会撒到别的群系");
            assertNotNull(data("data/logos_dimension/worldgen/configured_feature/" + name + ".json"));
        }
    }

    /** 26.1 没有 `minecraft:forest_rock`；类型名写错会让整个数据包加载失败，表现是"一直在生成世界"。 */
    @Test void cherryFeatureTypesAreRegisteredOnes() throws Exception {
        var allowed = Set.of(
            "minecraft:block_blob", "minecraft:block_pile", "minecraft:lake",
            "minecraft:random_patch", "minecraft:simple_block");
        for (String name : FEATURES) {
            var configured = data("data/logos_dimension/worldgen/configured_feature/" + name + ".json");
            String type = configured.get("type").getAsString();
            assertTrue(allowed.contains(type), name + " 用了未知特征类型：" + type);
        }
    }
}
