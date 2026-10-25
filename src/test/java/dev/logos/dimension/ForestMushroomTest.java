package dev.logos.dimension;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 林地蘑菇的自然生成：六种蘑菇按群系分配，食用菌常见、毒菌稀有，
 * 放置条件走原版的高度图 + 空中 + 可存活判定。
 *
 * <p>只做数据断言，不代替世界生成检查。
 */
class ForestMushroomTest {
    /** 每个群系应当出现的蘑菇（与 tools/native_worldgen.py 的 MUSHROOM_BIOMES 对齐）。 */
    private static final Map<String,List<String>> EXPECTED=Map.ofEntries(
        Map.entry("cloudsea_ancient_beech",List.of("journey_field_mushroom","journey_chanterelle","journey_false_chanterelle")),
        Map.entry("cloudsea_fir_heart",List.of("journey_bolete","journey_fly_agaric")),
        Map.entry("cloudsea_moss_vale",List.of("journey_bolete","journey_death_cap")),
        Map.entry("cloudsea_moon_birch",List.of("journey_chanterelle","journey_fly_agaric")),
        Map.entry("elven_forest",List.of("journey_field_mushroom")));
    /** 六种蘑菇的稀有度（与生成脚本一致）。 */
    private static final Map<String,Integer> CHANCES=Map.of(
        "journey_field_mushroom",24,"journey_chanterelle",32,"journey_bolete",32,
        "journey_fly_agaric",96,"journey_death_cap",128,"journey_false_chanterelle",96);

    private JsonObject data(String path)throws IOException{
        try(var input=getClass().getResourceAsStream("/data/logos_dimension/"+path+".json")){
            assertNotNull(input,path);
            return JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private List<String> vegetation(String biome)throws IOException{
        var list=new ArrayList<String>();
        for(var feature:data("worldgen/biome/"+biome).getAsJsonArray("features").get(9).getAsJsonArray())
            list.add(feature.getAsString().replace("logos_dimension:",""));
        return list;
    }

    @Test void mushroomsAppearInTheirBiomes()throws Exception{
        for(var entry:EXPECTED.entrySet()){
            var present=vegetation(entry.getKey());
            for(String mushroom:entry.getValue())
                assertTrue(present.contains(mushroom),entry.getKey()+" 没有引用 "+mushroom);
        }
    }

    @Test void nonWoodlandBiomesStayUntouched()throws Exception{
        for(String biome:new String[]{"cloudsea_opal_dunes","cloudsea_frost_spires","cloudsea_aurora_dunes"}){
            var present=vegetation(biome);
            for(String mushroom:CHANCES.keySet())
                assertFalse(present.contains(mushroom),biome+" 不该长出蘑菇 "+mushroom);
        }
    }

    @Test void mushroomFeaturesMatchTheirBlock()throws Exception{
        for(String mushroom:CHANCES.keySet()){
            var configured=data("worldgen/configured_feature/"+mushroom);
            assertEquals("minecraft:simple_block",configured.get("type").getAsString());
            assertEquals("logos_dimension:"+mushroom,
                configured.getAsJsonObject("config").getAsJsonObject("to_place")
                    .getAsJsonObject("state").get("Name").getAsString(),
                mushroom+" 的特征没有放置自己的方块");
        }
    }

    @Test void mushroomsUseVanillaPlacementRules()throws Exception{
        for(var entry:CHANCES.entrySet()){
            var placement=data("worldgen/placed_feature/"+entry.getKey()).getAsJsonArray("placement");
            var types=new ArrayList<String>();
            for(var step:placement)types.add(step.getAsJsonObject().get("type").getAsString());
            assertEquals(entry.getValue(),placement.get(0).getAsJsonObject().get("chance").getAsInt(),
                entry.getKey()+" 的稀有度与设计不一致");
            assertEquals("minecraft:rarity_filter",types.get(0));
            assertTrue(types.contains("minecraft:heightmap"),entry.getKey()+" 缺少高度图");
            assertTrue(types.contains("minecraft:block_predicate_filter"),entry.getKey()+" 缺少放置条件");
        }
    }

    /** 六种蘑菇都必须进 Logos 的衰退标签，否则衰退符文对它们无效。 */
    @Test void mushroomsAreFragilePlants()throws Exception{
        // 标签在 logos 命名空间下，不能走 data() 的 logos_dimension 前缀。
        JsonObject tag;
        try(var input=getClass().getResourceAsStream("/data/logos/tags/block/fragile_plants.json")){
            assertNotNull(input,"找不到 logos:fragile_plants 标签");
            tag=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
        }
        var values=new HashSet<String>();
        for(var value:tag.getAsJsonArray("values"))values.add(value.getAsString());
        for(String mushroom:CHANCES.keySet())
            assertTrue(values.contains("logos_dimension:"+mushroom),
                mushroom+" 没有加入 logos:fragile_plants");
    }
}
