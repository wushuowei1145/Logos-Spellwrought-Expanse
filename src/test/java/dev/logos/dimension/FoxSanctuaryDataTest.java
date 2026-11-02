package dev.logos.dimension;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 神狐聚落的数据：设计图尺寸、入口、模板与地块变体都在，结构集间距与 Java 常量一致，
 * 樱野的树与倒木只认自然草皮。只做数据断言；山路与建筑的实际观感看 fox_sanctuary_views 截图。
 */
class FoxSanctuaryDataTest {
    private static JsonObject json(String path) throws IOException {
        try (var in = FoxSanctuaryDataTest.class.getResourceAsStream("/data/logos_dimension/" + path)) {
            assertNotNull(in, "缺少数据文件：" + path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static boolean exists(String path) {
        return FoxSanctuaryDataTest.class.getResource("/data/logos_dimension/" + path) != null;
    }

    @Test void layoutIsCompleteAndSquare() throws Exception {
        var layout = json("fox_sanctuary/layout.json");
        int size = layout.get("size").getAsInt();
        assertEquals(size * size, Base64.getDecoder().decode(layout.get("heights").getAsString()).length);
        assertEquals(size * size, Base64.getDecoder().decode(layout.get("ground").getAsString()).length);
        // 入口在核心外一格，石阶底比主街低
        var entrance = layout.getAsJsonObject("entrance");
        assertEquals(-1, entrance.get("u").getAsInt());
        assertTrue(entrance.get("y").getAsInt() < 0);
        // 核心半宽 + 外圈山坡不超过起始区块周围 8 个区块的引用范围
        assertTrue(size / 2 + 40 <= 128, "聚落太大，超出结构引用范围");
    }

    @Test void everyTemplateAndVariantExists() throws Exception {
        var layout = json("fox_sanctuary/layout.json");
        List<String> missing = new ArrayList<>();
        for (var t : layout.getAsJsonArray("tiles")) {
            String name = t.getAsJsonObject().get("template").getAsString();
            if (!exists("structure/fox_sanctuary/" + name + ".nbt")) missing.add(name);
        }
        var variants = layout.getAsJsonObject("variants");
        for (var lot : layout.getAsJsonArray("lots")) {
            String kind = lot.getAsJsonObject().get("kind").getAsString();
            assertTrue(variants.has(kind) && !variants.getAsJsonArray(kind).isEmpty(), "地块没有变体：" + kind);
        }
        for (var e : variants.entrySet()) {
            for (var n : e.getValue().getAsJsonArray()) {
                if (!exists("structure/fox_sanctuary/" + n.getAsString() + ".nbt")) missing.add(n.getAsString());
            }
        }
        assertTrue(missing.isEmpty(), "缺少模板：" + missing);
    }

    @Test void structureSetMatchesJavaPlacement() throws Exception {
        var placement = json("worldgen/structure_set/fox_sanctuary.json").getAsJsonObject("placement");
        // 与 FoxSanctuaryStructure 的 SPACING / SEPARATION / SALT 一致：结构靠它推算邻近候选，好让每片樱野只留一处
        assertEquals(8, placement.get("spacing").getAsInt());
        assertEquals(4, placement.get("separation").getAsInt());
        assertEquals(20261102, placement.get("salt").getAsInt());
        assertEquals("logos_dimension:fox_sanctuary", json("worldgen/structure/fox_sanctuary.json").get("type").getAsString());
    }

    @Test void wildTreesOnlyGrowOnNaturalTurf() throws Exception {
        var biome = json("worldgen/biome/cloudsea_white_cherry.json").getAsJsonArray("features").toString();
        for (String id : new String[]{"fox_wild_trees", "fox_wild_fallen_tree", "fox_wild_rock_outcrop", "fox_wild_pebbles"}) {
            assertTrue(biome.contains("logos_dimension:" + id), "樱野没有改用 " + id);
            String placement = json("worldgen/placed_feature/" + id + ".json").getAsJsonArray("placement").toString();
            assertTrue(placement.contains("logos_dimension:cloudsea_white_cherry_ground"), id + " 没有限定自然草皮");
            assertTrue(placement.contains("minecraft:biome"), id + " 没有群系过滤");
        }
        assertFalse(biome.contains("\"logos_dimension:trees_cloudsea_white_cherry\""), "樱野仍引用不限地表的樱树");
    }
}
