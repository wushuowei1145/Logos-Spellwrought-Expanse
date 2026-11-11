package dev.logos.dimension;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 数据约束只负责结构接线、选址参数和高度；实际拼接、路线、净空另跑世界测试。 */
class TaixuanDataTest {
    private JsonObject data(String name) throws IOException {
        try (var in = getClass().getResourceAsStream("/data/logos_dimension/" + name + ".json")) {
            assertNotNull(in, name);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test void nativePoolsRetainEveryPartAndLeaveTerrainUntouched() throws Exception {
        var manifest = data("taixuan_manifest");
        var size = manifest.getAsJsonArray("canvas_size");int tile = manifest.get("tile_size").getAsInt();
        int nx = size.get(0).getAsInt() / tile, ny = size.get(1).getAsInt() / tile, nz = size.get(2).getAsInt() / tile;
        assertEquals(28, tile);
        assertEquals(nx * ny * nz, manifest.get("tiles").getAsInt());
        assertTrue(manifest.get("depth").getAsInt() < 19, "拼接深度超过原版上限");
        assertEquals(0, manifest.get("entities").getAsInt());
        var structure = data("worldgen/structure/taixuan_sanctuary");
        assertEquals("logos_dimension:taixuan_sanctuary", structure.get("type").getAsString());
        assertEquals("none", structure.get("terrain_adaptation").getAsString());
        var layout = structure.getAsJsonObject("layout");
        assertEquals(128, layout.getAsJsonObject("max_distance_from_center").get("horizontal").getAsInt());
        assertEquals(256, layout.getAsJsonObject("max_distance_from_center").get("vertical").getAsInt());
        assertEquals("logos_dimension:taixuan/" + manifest.get("root").getAsString(), layout.get("start_pool").getAsString());
        for (int x = 0; x < nx; x++) for (int y = 0; y < ny; y++) for (int z = 0; z < nz; z++) {
            String name = "tile_" + x + "_" + y + "_" + z;
            assertNotNull(getClass().getResource("/data/logos_dimension/structure/taixuan/" + name + ".nbt"), name);
            assertEquals("logos_dimension:taixuan/" + name, data("worldgen/template_pool/taixuan/" + name)
                .getAsJsonArray("elements").get(0).getAsJsonObject().getAsJsonObject("element").get("location").getAsString());
        }
    }

    /** 选址：只在云岫峰林里，深渊口外的空地都要在群系里（外圈许缺两个点）；深渊口一圈的地面大多不低于自适应石阶的底。 */
    @Test void theSiteCheckKeepsTheWholeLandmarkInsideCloudPeaks() throws Exception {
        var structure = data("worldgen/structure/taixuan_sanctuary");
        assertEquals("#logos_dimension:has_structure/taixuan_sanctuary", structure.get("biomes").getAsString());
        assertEquals("[\"logos_dimension:cloudsea_cloud_peaks\"]", data("tags/worldgen/biome/has_structure/taixuan_sanctuary").getAsJsonArray("values").toString());
        var manifest = data("taixuan_manifest");var heights = manifest.getAsJsonObject("heights");int offset = manifest.get("world_y_offset").getAsInt();
        var basin = manifest.getAsJsonObject("abyss").getAsJsonObject("details").getAsJsonObject("basin");
        assertTrue(structure.get("biome_radius").getAsInt() >= basin.get("radius").getAsInt() + basin.get("wobble").getAsInt(), "群系检查半径盖不住深渊口外的空地");
        assertTrue(structure.get("outer_misses").getAsInt() <= 2 && structure.get("rim_misses").getAsInt() <= 3, "选址放得太宽");
        int liningTop = heights.get("lining_top").getAsInt() + offset;
        assertTrue(structure.get("rim_min_height").getAsInt() >= liningTop, "深渊口地面可以低过石壁顶");
        assertTrue(structure.get("rim_min_height").getAsInt() < heights.get("rim").getAsInt() + offset, "深渊口要求高过深渊口本身，几乎选不到");
        // 云岫峰林已经不在别的结构群系标签里重复
        assertFalse(data("dimension/cloudsea").toString().contains("taixuan_vale"), "旧的太玄谷群系还在");
        assertEquals("minecraft:block_column", data("worldgen/configured_feature/taixuan_footings").get("type").getAsString());
    }

    /**
     * 高度：起始高度比锚的设计高度（画布 y + 偏移）高 1，原版拼接会把起点部件按地面差往下挪一格；
     * 画布底到顶都在远行大陆的世界高度里；雾底、雾层、链台、深渊口由低到高。
     */
    @Test void theAbyssLayersStackInOrder() throws Exception {
        var manifest = data("taixuan_manifest");var heights = manifest.getAsJsonObject("heights");
        int offset = manifest.get("world_y_offset").getAsInt();
        assertEquals(manifest.get("start_height").getAsInt(), manifest.getAsJsonArray("anchor_canvas").get(1).getAsInt() + offset + 1);
        assertEquals(manifest.get("start_height").getAsInt(), data("worldgen/structure/taixuan_sanctuary").getAsJsonObject("layout")
            .getAsJsonObject("start_height").get("absolute").getAsInt());
        var dimension = data("dimension_type/cloudsea");
        int minY = dimension.get("min_y").getAsInt(), maxY = minY + dimension.get("height").getAsInt() - 1;
        assertTrue(offset >= minY && manifest.getAsJsonArray("canvas_size").get(1).getAsInt() - 1 + offset <= maxY, "画布超出远行大陆高度");
        var mist = heights.getAsJsonArray("mist");
        assertTrue(heights.get("arena").getAsInt() < mist.get(0).getAsInt() && mist.get(1).getAsInt() < heights.get("platform").getAsInt()
            && heights.get("platform").getAsInt() < heights.get("lining_top").getAsInt() && heights.get("lining_top").getAsInt() < heights.get("rim").getAsInt());
    }

    @Test void theSectAbyssAndRoutesAreRecorded() throws Exception {
        var manifest = data("taixuan_manifest");
        assertTrue(manifest.getAsJsonArray("rooms").size() >= 10);
        assertTrue(manifest.getAsJsonArray("footings").size() >= 300);
        assertEquals(6, manifest.getAsJsonArray("islands").size());
        var landscape = manifest.getAsJsonObject("landscape");
        assertTrue(landscape.getAsJsonArray("pines").size() >= 5);
        assertTrue(landscape.get("bamboo_stalks").getAsInt() >= 15);
        assertTrue(landscape.get("vine_blocks").getAsInt() > 100);
        var abyss = manifest.getAsJsonObject("abyss");
        assertEquals(4, abyss.getAsJsonArray("towers").size());
        assertEquals(2, abyss.getAsJsonArray("waterfalls").size());
        assertTrue(abyss.get("chain_links").getAsInt() > 100);
        var routes = manifest.getAsJsonObject("routes");
        for (String name : new String[]{"rim_terrace", "stair_bridge", "platform", "plank_walk", "arena", "tower_bridge",
            "tower_0_stairs", "tower_1_stairs", "tower_2_stairs", "tower_3_stairs", "tower_1_door"})
            assertTrue(routes.has(name) && !routes.getAsJsonArray(name).isEmpty(), "缺少路线 " + name);
        var processor = data("worldgen/processor_list/taixuan_preserve").getAsJsonArray("processors").get(0).getAsJsonObject();
        assertEquals("minecraft:structure_void", processor.getAsJsonArray("blocks").get(0).getAsJsonObject().get("Name").getAsString());
    }

    /** 远行大陆的树、高树、倒木、林地石堆和云岫峰林的松树不在建筑、原版草方块、树冠上扎根，否则自然生成时浮岛上会长出整棵树。 */
    @Test void forestFeaturesDoNotRootOnBuildings() throws Exception {
        var tag = data("tags/block/no_tree_roots").getAsJsonArray("values").toString();
        for (String block : new String[]{"minecraft:grass_block", "minecraft:calcite", "minecraft:stone_bricks", "minecraft:iron_chain",
            "logos_dimension:taixuan_brass_tiles", "logos_dimension:taixuan_dark_iron", "logos_dimension:taixuan_abyss_mist",
            "#minecraft:slabs", "#minecraft:stairs", "#minecraft:leaves"}) assertTrue(tag.contains("\"" + block + "\""), block);
        assertFalse(tag.contains("\"minecraft:chain\""), "26.1 的铁链是 iron_chain，旧 ID 会让整个标签读不进来");
        var guarded = new java.util.HashSet<String>();var unguarded = new java.util.ArrayList<String>();
        for (var entry : data("dimension/cloudsea").getAsJsonObject("generator").getAsJsonObject("biome_source").getAsJsonArray("biomes")) {
            var biome = data("worldgen/biome/" + entry.getAsJsonObject().get("biome").getAsString().split(":")[1]);
            for (var step : biome.getAsJsonArray("features")) for (var feature : step.getAsJsonArray()) {
                String name = feature.getAsString();
                if (!name.startsWith("logos_dimension:")) continue;
                String path = name.split(":")[1];
                if (!(path.startsWith("trees_cloudsea_") || path.startsWith("tall_trees_cloudsea_") || path.startsWith("fallen_tree_cloudsea_")
                    || path.equals("forest_rock_outcrop") || path.startsWith("cloud_peaks_") && path.contains("pines") || path.equals("cloud_peaks_valley_trees"))) continue;
                if (!guarded.add(path)) continue;
                var placed = data("worldgen/placed_feature/" + path).toString();
                if (!placed.contains("logos_dimension:no_tree_roots")) unguarded.add(path);
                // 装饰阶段 *_WG 高度图停在原始地形，按它种的树看不见结构；要用原版树同样的 OCEAN_FLOOR
                if (placed.contains("OCEAN_FLOOR_WG")) unguarded.add(path + "（用了 OCEAN_FLOOR_WG）");
            }
        }
        assertTrue(guarded.size() >= 30, "找到的森林放置规则太少：" + guarded.size());
        assertTrue(unguarded.isEmpty(), "这些放置规则会在建筑上种树：" + unguarded);
        // 贴壁长的松和灌丛只看旁边有没有实心方块；不加这一道会长进镇渊柱的楼梯井、贴着桥长出来挡路
        for (String wall : new String[]{"cloud_peaks_wall_pines", "cloud_peaks_cliff_shrubs", "cloud_peaks_cliff_vines"})
            assertTrue(data("worldgen/placed_feature/" + wall).toString().contains("logos_dimension:built_surfaces"), wall + " 会贴着建筑长");
        var built = data("tags/block/built_surfaces").getAsJsonArray("values").toString();
        for (String natural : new String[]{"minecraft:smooth_sandstone", "minecraft:andesite", "minecraft:tuff", "minecraft:calcite", "minecraft:stone"})
            assertFalse(built.contains("\"" + natural + "\""), "天然崖壁的材料 " + natural + " 不能算建筑，否则天然崖壁上也不长松");
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
