package dev.logos.dimension;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 森林地带的地表植物群落：每个森林群系必须有自己的一份 floor，而不是共用 woodland_floor。
 *
 * <p>这一组断言只看数据：群系引用、权重分布、组合唯一性、放置阶段与预算。
 * 它不代替世界生成检查，也不代表游戏内观感已经验过。
 */
class ForestFloorTest {
    /** 远行大陆的森林地带：13 个林地 + 2 个林隙 + 精灵森林。 */
    private static final String[] FOREST_BIOMES={
        "cloudsea_lilac_steps","cloudsea_cloudroot_shelves","cloudsea_fir_heart",
        "cloudsea_reverse_gardens","cloudsea_ivory_steps","cloudsea_moon_birch",
        "cloudsea_jade_whirls","cloudsea_verdant_whirls","cloudsea_lantern_shelves",
        "cloudsea_ancient_beech","cloudsea_fernwood","cloudsea_moss_vale","cloudsea_copper_copse",
        "cloudsea_sunlit_glade","cloudsea_starflower_glade","elven_forest"
    };
    /** 允许出现在森林地表里的方块：全部来自已注册的地生植物与原版地被，不引入新方块。 */
    private static final Set<String> ALLOWED_VANILLA=Set.of("minecraft:moss_carpet","minecraft:leaf_litter","minecraft:oxeye_daisy");

    private JsonObject data(String path)throws IOException{
        try(var input=getClass().getResourceAsStream("/data/logos_dimension/"+path+".json")){
            assertNotNull(input,path);
            return JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    /** 取群系第 9 个生成阶段的 feature 列表（植被装饰层）。 */
    private List<String> decoration(String biome)throws IOException{
        var features=data("worldgen/biome/"+biome).getAsJsonArray("features");
        assertEquals(11,features.size(),biome+" must keep the vanilla generation stages");
        var list=new ArrayList<String>();
        for(var feature:features.get(9).getAsJsonArray())list.add(feature.getAsString());
        return list;
    }

    /** 群系地表权重表：方块名 → 权重。 */
    private Map<String,Integer> weights(String biome)throws IOException{
        var entries=data("worldgen/configured_feature/"+biome+"_floor")
            .getAsJsonObject("config").getAsJsonObject("to_place").getAsJsonArray("entries");
        var map=new LinkedHashMap<String,Integer>();
        for(var entry:entries){
            var object=entry.getAsJsonObject();
            map.put(object.getAsJsonObject("data").get("Name").getAsString(),object.get("weight").getAsInt());
        }
        return map;
    }

    @Test void everyForestBiomeHasItsOwnFloor()throws Exception{
        for(String biome:FOREST_BIOMES){
            var features=decoration(biome);
            assertTrue(features.contains("logos_dimension:"+biome+"_floor"),biome+" must use its own floor");
            assertFalse(features.contains("logos_dimension:woodland_floor"),biome+" still falls back to the shared woodland floor");
            assertFalse(features.contains("logos_dimension:meadow_floor"),biome+" still falls back to the shared meadow floor");
        }
    }

    @Test void floorEntriesAreRegisteredAndPositiveWeighted()throws Exception{
        for(String biome:FOREST_BIOMES){
            var map=weights(biome);
            assertTrue(map.size()>=5,biome+" floor should mix at least five plants");
            for(var entry:map.entrySet()){
                assertTrue(entry.getValue()>0,biome+" has a non-positive weight for "+entry.getKey());
                assertTrue(ALLOWED_VANILLA.contains(entry.getKey())||entry.getKey().startsWith("logos_dimension:journey_"),
                    biome+" floor uses an unregistered block: "+entry.getKey());
            }
        }
    }

    @Test void floorsAreNotCopiesOfEachOther()throws Exception{
        var signatures=new HashMap<String,String>();
        for(String biome:FOREST_BIOMES){
            var signature=weights(biome).toString();
            var clash=signatures.put(signature,biome);
            assertNull(clash,biome+" and "+clash+" share an identical floor weight table");
        }
    }

    /** 每个森林群系的招牌植物权重占比必须够高，避免"换了名字但还是同一片地被"。 */
    @Test void eachFloorHasItsOwnSignaturePlants()throws Exception{
        var signature=Map.ofEntries(
            Map.entry("cloudsea_ancient_beech","minecraft:leaf_litter"),
            Map.entry("cloudsea_fernwood","logos_dimension:journey_woodland_fern"),
            Map.entry("cloudsea_moss_vale","minecraft:moss_carpet"),
            Map.entry("cloudsea_fir_heart","logos_dimension:journey_bracken"),
            Map.entry("cloudsea_copper_copse","logos_dimension:journey_heather"),
            Map.entry("cloudsea_moon_birch","logos_dimension:journey_moonbell"),
            Map.entry("cloudsea_lilac_steps","logos_dimension:journey_silver_anemone"),
            Map.entry("cloudsea_cloudroot_shelves","minecraft:moss_carpet"),
            Map.entry("cloudsea_reverse_gardens","logos_dimension:journey_heather"),
            Map.entry("cloudsea_ivory_steps","minecraft:leaf_litter"),
            Map.entry("cloudsea_jade_whirls","logos_dimension:journey_bracken"),
            Map.entry("cloudsea_verdant_whirls","logos_dimension:journey_woodland_fern"),
            Map.entry("cloudsea_lantern_shelves","logos_dimension:journey_glowcap"),
            Map.entry("cloudsea_sunlit_glade","logos_dimension:journey_meadow_grass"),
            Map.entry("cloudsea_starflower_glade","logos_dimension:journey_silver_anemone"),
            Map.entry("elven_forest","logos_dimension:journey_stardew"));
        for(String biome:FOREST_BIOMES){
            var map=weights(biome);
            int total=map.values().stream().mapToInt(Integer::intValue).sum();
            String key=signature.get(biome);
            assertNotNull(key,biome+" has no declared signature plant");
            Integer value=map.get(key);
            assertNotNull(value,biome+" floor lacks its signature plant "+key);
            assertTrue(value*100>=total*15,
                biome+" signature plant "+key+" is only "+value+"/"+total+" of the floor");
        }
    }

    @Test void floorPlacementStaysWithinBudget()throws Exception{
        for(String biome:FOREST_BIOMES){
            var placement=data("worldgen/placed_feature/"+biome+"_floor").getAsJsonArray("placement");
            var types=new ArrayList<String>();
            int count=0;
            for(var step:placement){
                var object=step.getAsJsonObject();
                var type=object.get("type").getAsString();
                types.add(type);
                if(type.equals("minecraft:count"))count=object.get("count").getAsInt();
            }
            assertTrue(count>0&&count<=680,biome+" floor count "+count+" is outside the per-chunk budget");
            assertTrue(types.contains("minecraft:environment_scan"),biome+" must scan down to soil instead of trusting the heightmap");
            assertTrue(types.contains("minecraft:heightmap"),biome+" must start from the surface heightmap");
            assertEquals("minecraft:biome",types.get(types.size()-1),biome+" must end with the biome filter");
        }
    }
}
