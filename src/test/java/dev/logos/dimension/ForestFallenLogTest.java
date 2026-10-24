package dev.logos.dimension;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 林地倒木：每个有树的群系都要有自己的 {@code fallen_tree_<biome>}，
 * 树干用该群系自己的原木，并且走原版 {@code minecraft:fallen_tree}。
 *
 * <p>只做数据断言，不代替世界生成检查。
 */
class ForestFallenLogTest {
    /** 与 tools/native_worldgen.py 里的 forests 集合一致：13 个有树群系。 */
    private static final String[] FORESTS={
        "cloudsea_lilac_steps","cloudsea_cloudroot_shelves","cloudsea_fir_heart",
        "cloudsea_reverse_gardens","cloudsea_ivory_steps","cloudsea_moon_birch",
        "cloudsea_jade_whirls","cloudsea_verdant_whirls","cloudsea_lantern_shelves",
        "cloudsea_ancient_beech","cloudsea_fernwood","cloudsea_moss_vale","cloudsea_copper_copse"
    };
    private static final String ELVEN="elven_forest";

    private JsonObject data(String path)throws IOException{
        try(var input=getClass().getResourceAsStream("/data/logos_dimension/"+path+".json")){
            assertNotNull(input,path);
            return JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private List<String> decoration(String biome)throws IOException{
        var list=new ArrayList<String>();
        for(var feature:data("worldgen/biome/"+biome).getAsJsonArray("features").get(9).getAsJsonArray())
            list.add(feature.getAsString());
        return list;
    }

    @Test void everyWoodlandHasFallenTrees()throws Exception{
        for(String biome:FORESTS)
            assertTrue(decoration(biome).contains("logos_dimension:fallen_tree_"+biome),
                biome+" 没有引用自己的倒木");
        // 精灵森林借用山毛榉的原木，引用同一个倒木特征。
        assertTrue(decoration(ELVEN).contains("logos_dimension:fallen_tree_cloudsea_ancient_beech"),
            "精灵森林没有引用山毛榉倒木");
    }

    @Test void fallenTreesUseTheLocalLog()throws Exception{
        for(String biome:FORESTS){
            var config=data("worldgen/configured_feature/fallen_tree_"+biome).getAsJsonObject("config");
            assertEquals("logos_dimension:"+biome+"_log",
                config.getAsJsonObject("trunk_provider").getAsJsonObject("state").get("Name").getAsString(),
                biome+" 的倒木没有用本群系原木");
            var length=config.getAsJsonObject("log_length");
            assertEquals(4,length.get("min_inclusive").getAsInt());
            assertEquals(7,length.get("max_inclusive").getAsInt());
        }
        assertEquals("logos_dimension:cloudsea_ancient_beech_log",
            data("worldgen/configured_feature/fallen_tree_cloudsea_ancient_beech")
                .getAsJsonObject("config").getAsJsonObject("trunk_provider")
                .getAsJsonObject("state").get("Name").getAsString());
    }

    @Test void fallenTreesStayOnSoilAndOutOfTheIslandStone()throws Exception{
        for(String biome:FORESTS){
            var placement=data("worldgen/placed_feature/fallen_tree_"+biome).getAsJsonArray("placement");
            var types=new ArrayList<String>();
            for(var step:placement)types.add(step.getAsJsonObject().get("type").getAsString());
            assertTrue(types.contains("minecraft:rarity_filter"),biome+" 的倒木没有稀疏控制");
            assertEquals(6,placement.get(0).getAsJsonObject().get("chance").getAsInt(),biome+" 的倒木太密");
            assertTrue(types.contains("minecraft:environment_scan"),biome+" 必须先向下找到泥土");
            assertEquals("minecraft:biome",types.get(types.size()-1),biome+" 必须以群系过滤结尾");
        }
    }
}
