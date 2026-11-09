package dev.logos.dimension;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 数据约束只负责结构接线与群系入口；实际拼接、净空另跑世界测试。 */
class TaixuanDataTest {
    private JsonObject data(String name) throws IOException {
        try (var in = getClass().getResourceAsStream("/data/logos_dimension/" + name + ".json")) {
            assertNotNull(in, name);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test void nativePoolsRetainEveryPartAndLeaveTerrainUntouched() throws Exception {
        var manifest = data("taixuan_manifest");
        assertEquals(729, manifest.get("tiles").getAsInt());
        assertEquals(28, manifest.get("tile_size").getAsInt());
        assertTrue(manifest.get("depth").getAsInt() < 19);
        assertTrue(manifest.get("components").getAsInt() >= 50);
        assertEquals(0, manifest.get("entities").getAsInt());
        var structure = data("worldgen/structure/taixuan_sanctuary");
        assertEquals("minecraft:jigsaw", structure.get("type").getAsString());
        assertEquals("none", structure.get("terrain_adaptation").getAsString());
        assertEquals(128, structure.getAsJsonObject("max_distance_from_center").get("horizontal").getAsInt());
        assertEquals(256, structure.getAsJsonObject("max_distance_from_center").get("vertical").getAsInt());
        for (int x = 0; x < 9; x++) for (int y = 0; y < 9; y++) for (int z = 0; z < 9; z++) {
            String name = "tile_" + x + "_" + y + "_" + z;
            assertNotNull(getClass().getResource("/data/logos_dimension/structure/taixuan/" + name + ".nbt"), name);
            assertEquals("logos_dimension:taixuan/" + name, data("worldgen/template_pool/taixuan/" + name)
                .getAsJsonArray("elements").get(0).getAsJsonObject().getAsJsonObject("element").get("location").getAsString());
        }
    }

    @Test void theBiomeHasClimateSamplesAndNoAutomaticAnimals() throws Exception {
        int samples = 0;
        for (var entry : data("dimension/cloudsea").getAsJsonObject("generator").getAsJsonObject("biome_source").getAsJsonArray("biomes"))
            if (entry.getAsJsonObject().get("biome").getAsString().equals("logos_dimension:taixuan_vale")) samples++;
        assertTrue(samples >= 8, "群系气候窗口过小，定位和自然选址不可靠");
        assertTrue(data("worldgen/biome/taixuan_vale").getAsJsonObject("spawners").isEmpty());
        assertEquals("minecraft:block_column", data("worldgen/configured_feature/taixuan_footings").get("type").getAsString());
        assertTrue(data("worldgen/noise_settings/journey").getAsJsonObject("surface_rule").toString().contains("logos_dimension:taixuan_vale"),
            "新群系没有接入草皮与土壤的原版表面规则");
    }

    @Test void theCityAndGardenHaveRealRoomsSoilAndWater() throws Exception {
        var manifest = data("taixuan_manifest");
        assertEquals("太玄城", manifest.get("city_name").getAsString());
        assertEquals(46, manifest.get("city_houses").getAsInt());
        assertTrue(manifest.getAsJsonArray("rooms").size() >= 40);
        assertTrue(manifest.getAsJsonArray("footings").size() >= 500);
        assertEquals(7, manifest.getAsJsonArray("islands").size());
        var landscape = manifest.getAsJsonObject("landscape");
        assertTrue(landscape.getAsJsonArray("pines").size() >= 5);
        assertTrue(landscape.get("bamboo_stalks").getAsInt() >= 15);
        assertEquals(2, landscape.getAsJsonArray("ponds").size());
        assertTrue(landscape.get("vine_blocks").getAsInt() > 100);
        var processor = data("worldgen/processor_list/taixuan_preserve").getAsJsonArray("processors").get(0).getAsJsonObject();
        assertEquals("minecraft:structure_void", processor.getAsJsonArray("blocks").get(0).getAsJsonObject().get("Name").getAsString());
    }

    @Test void theWorldTestPresetMatchesTheShippedDimension() throws Exception {
        try (var input = getClass().getResourceAsStream("/fixtures/journey-world-preset.json")) {
            assertNotNull(input, "测试预设资源未复制");
            var preset = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(data("dimension/cloudsea"), preset.getAsJsonObject("dimensions").getAsJsonObject("logos_dimension:cloudsea"),
                "测试预设必须随正式群系数据更新，不能在旧群系列表中测试新增内容");
        }
    }
}
