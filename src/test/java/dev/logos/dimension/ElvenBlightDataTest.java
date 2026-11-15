package dev.logos.dimension;

import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 生成权重、污染隔离与动画资源的静态检查，不代替世界测试。 */
class ElvenBlightDataTest {
    private JsonObject read(String path) throws Exception {
        try (var in = getClass().getResourceAsStream(path)) {
            assertNotNull(in, path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    @Test void oneOfFiveElvenClimateBranchesIsCorrupted() throws Exception {
        var main = read("/data/logos_dimension/dimension/cloudsea.json").getAsJsonObject("generator").getAsJsonObject("biome_source").getAsJsonArray("biomes");
        int healthy = 0, blighted = 0;
        Map<String, Set<Double>> branches = new HashMap<>();
        for (var entry : main) {
            var e = entry.getAsJsonObject();
            String name = e.get("biome").getAsString();
            if (!name.equals("logos_dimension:elven_forest") && !name.equals("logos_dimension:blighted_elven_forest")) continue;
            if (name.endsWith(":elven_forest")) healthy++; else blighted++;
            var parameters = e.getAsJsonObject("parameters").deepCopy();
            double weirdness = parameters.remove("weirdness").getAsDouble();
            branches.computeIfAbsent(parameters.toString(), k -> new HashSet<>()).add(weirdness);
            assertEquals(weirdness == -.35, name.contains("blighted_"));
        }
        assertEquals(64, healthy);
        assertEquals(16, blighted);
        assertEquals(16, branches.size());
        branches.values().forEach(v -> assertEquals(5, v.size()));
        var fixture = read("/fixtures/journey-world-preset.json").getAsJsonObject("dimensions").getAsJsonObject("logos_dimension:cloudsea")
                .getAsJsonObject("generator").getAsJsonObject("biome_source").getAsJsonArray("biomes");
        assertEquals(main, fixture, "世界测试必须使用实际群系参数");
    }
    @Test void corruptedForestHasNoHealthyPlantsCropsOrOrdinaryResidents() throws Exception {
        var biome = read("/data/logos_dimension/worldgen/biome/blighted_elven_forest.json");
        assertTrue(biome.getAsJsonObject("spawners").isEmpty());
        String features = biome.get("features").toString();
        for (String forbidden : List.of("forest_floor", "wild_", "mushroom", "sugar_cane", "waterlily", "crops", "glow_grove")) assertFalse(features.contains(forbidden), forbidden);
        assertTrue(features.contains("blighted_elven_growth"));
        assertTrue(features.contains("blighted_elven_outcrop"));
        assertTrue(features.contains("blight_nodes"));
        assertFalse(features.contains(":forest_rock_outcrop"));
        var giants = read("/data/logos_dimension/worldgen/template_pool/blighted_elven/giants.json").getAsJsonArray("elements");
        assertEquals(5, giants.size());
        assertFalse(giants.toString().contains("_home"));
        var set = read("/data/logos_dimension/worldgen/structure_set/elven_world_trees.json").getAsJsonArray("structures");
        assertEquals(2, set.size(), "健康与腐败世界树共用排斥区，避免重叠");
    }
    @Test void exteriorGroundAndGrowthUseRealCorruption() throws Exception {
        var settings = read("/data/logos_dimension/worldgen/noise_settings/journey.json");
        var rules = new ArrayList<JsonObject>();
        findSurface(settings, rules);
        assertEquals(1, rules.size());
        var sequence = rules.getFirst().getAsJsonObject("then_run").getAsJsonArray("sequence");
        assertEquals("logos_dimension:blight_memory", sequence.get(0).getAsJsonObject().getAsJsonObject("then_run").getAsJsonObject("result_state").get("Name").getAsString());
        var growth = read("/data/logos_dimension/worldgen/configured_feature/blighted_elven_growth.json").toString();
        for (String form : List.of("corruption_vein", "corruption_bud", "corruption_spines", "corruption_ribs")) assertTrue(growth.contains(form));
        var root = read("/data/logos_dimension/worldgen/configured_feature/blighted_elven_roots.json");
        assertEquals("logos_dimension:blight_memory", root.getAsJsonObject("config").getAsJsonArray("layers").get(0).getAsJsonObject().getAsJsonObject("provider").getAsJsonObject("state").get("Name").getAsString());
        var trees = read("/data/logos_dimension/worldgen/placed_feature/blighted_elven_trees.json").toString();
        assertTrue(trees.contains("blighted_loam"), "新树不能在同材质的腐败树冠上扎根");
    }
    private void findSurface(JsonElement e, List<JsonObject> found) {
        if (e.isJsonObject()) {
            var o = e.getAsJsonObject();
            if (o.has("if_true") && o.getAsJsonObject("if_true").has("biome_is")
                    && o.getAsJsonObject("if_true").getAsJsonArray("biome_is").toString().equals("[\"logos_dimension:blighted_elven_forest\"]")) found.add(o);
            for (var value : o.entrySet()) findSurface(value.getValue(), found);
        } else if (e.isJsonArray()) for (var value : e.getAsJsonArray()) findSurface(value, found);
    }
    @Test void queenAnimationsAndUvMatchTheIndependentModel() throws Exception {
        var model = read("/assets/logos_dimension/geckolib/models/entity/corrupted_elf_queen.geo.json").getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
        var bones = model.getAsJsonArray("bones");
        int textureWidth = model.getAsJsonObject("description").get("texture_width").getAsInt();
        int textureHeight = model.getAsJsonObject("description").get("texture_height").getAsInt();
        Set<String> names = new HashSet<>();
        int cubes = 0;
        for (var b : bones) assertTrue(names.add(b.getAsJsonObject().get("name").getAsString()));
        for (var b : bones) {
            var bone = b.getAsJsonObject();
            if (bone.has("parent")) assertTrue(names.contains(bone.get("parent").getAsString()));
            if (!bone.has("cubes")) continue;
            for (var c : bone.getAsJsonArray("cubes")) {
                cubes++;
                for (var face : c.getAsJsonObject().getAsJsonObject("uv").entrySet()) {
                    var uv = face.getValue().getAsJsonObject().getAsJsonArray("uv");
                    var size = face.getValue().getAsJsonObject().getAsJsonArray("uv_size");
                    for (int i = 0; i < 2; i++) {
                        double a = uv.get(i).getAsDouble(), z = a + size.get(i).getAsDouble();
                        assertTrue(Math.min(a, z) >= 0 && Math.max(a, z) <= (i == 0 ? textureWidth : textureHeight), bone.get("name").getAsString());
                    }
                }
            }
        }
        assertTrue(cubes > 200 && cubes < 600, "女王只保留选中零件，不能复制所有随机外观");
        assertTrue(names.contains("queen_branch_diadem") && names.contains("queen_regal_collar"));
        Map<String, String> parents = new HashMap<>();
        for (var b : bones) {
            var bone = b.getAsJsonObject();
            if (bone.has("parent")) parents.put(bone.get("name").getAsString(), bone.get("parent").getAsString());
        }
        assertEquals("RightHandLocator", parents.get("queen_scepter_socket"), "权杖必须跟随持物挂点");
        assertEquals("queen_scepter_socket", parents.get("queen_weapon_branch_scepter"), "武器根必须能整组隐藏");
        assertTrue(names.contains("queen_weapon_stow_locator") && names.contains("queen_scepter_tip_locator"));
        assertFalse(names.contains("queen_corrupt_shoulder") || names.stream().anyMatch(n -> n.startsWith("queen_root_")), "新版华服女王不应再长肉瘤或根须");
        var clips = read("/assets/logos_dimension/geckolib/animations/entity/corrupted_elf_queen.animation.json").getAsJsonObject("animations");
        for (String clip : List.of("idle", "walk", "death", "queen_guard", "queen_rise", "queen_entrance", "queen_reap", "queen_roots", "queen_thorns")) {
            assertTrue(clips.has(clip), clip);
            for (String bone : clips.getAsJsonObject(clip).getAsJsonObject("bones").keySet()) assertTrue(names.contains(bone), bone);
        }
        assertTrue(clips.getAsJsonObject("queen_guard").get("loop").getAsBoolean());
        assertEquals(2, clips.getAsJsonObject("queen_rise").get("animation_length").getAsDouble());
        assertEquals(5, clips.getAsJsonObject("queen_entrance").get("animation_length").getAsDouble());
    }
}
