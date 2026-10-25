package dev.logos.dimension;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 林地岩石露头：13 个林地与精灵森林引用同一份 {@code forest_rock_outcrop}，
 * 由原版 {@code block_pile} 用本附属的石灰岩堆成；非林地不该出现。
 *
 * <p>只做数据断言，不代替世界生成检查。
 */
class ForestRockOutcropTest {
    private static final String[] FORESTS={
        "cloudsea_lilac_steps","cloudsea_cloudroot_shelves","cloudsea_fir_heart",
        "cloudsea_reverse_gardens","cloudsea_ivory_steps","cloudsea_moon_birch",
        "cloudsea_jade_whirls","cloudsea_verdant_whirls","cloudsea_lantern_shelves",
        "cloudsea_ancient_beech","cloudsea_fernwood","cloudsea_moss_vale","cloudsea_copper_copse",
        "elven_forest"
    };
    private static final String OUTCROP="logos_dimension:forest_rock_outcrop";

    private JsonObject data(String path)throws IOException{
        try(var input=getClass().getResourceAsStream("/data/logos_dimension/"+path+".json")){
            assertNotNull(input,path);
            return JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private List<String> vegetation(String biome)throws IOException{
        var list=new ArrayList<String>();
        for(var feature:data("worldgen/biome/"+biome).getAsJsonArray("features").get(9).getAsJsonArray())
            list.add(feature.getAsString());
        return list;
    }

    @Test void woodlandsHaveRockOutcrops()throws Exception{
        for(String biome:FORESTS)
            assertTrue(vegetation(biome).contains(OUTCROP),biome+" 没有引用岩石露头");
    }

    @Test void nonWoodlandBiomesStayUntouched()throws Exception{
        for(String biome:new String[]{"cloudsea_opal_dunes","cloudsea_frost_spires","cloudsea_star_ribbons"})
            assertFalse(vegetation(biome).contains(OUTCROP),biome+" 不该有岩石露头");
    }

    @Test void outcropIsAStonePileOfLimestone()throws Exception{
        var config=data("worldgen/configured_feature/forest_rock_outcrop");
        assertEquals("minecraft:block_pile",config.get("type").getAsString());
        assertEquals("logos_dimension:journey_limestone",
            config.getAsJsonObject("config").getAsJsonObject("state_provider")
                .getAsJsonObject("state").get("Name").getAsString(),
            "露头必须用本附属的石灰岩，而不是另造一种石头");

        var placement=data("worldgen/placed_feature/forest_rock_outcrop").getAsJsonArray("placement");
        var types=new ArrayList<String>();
        for(var step:placement)types.add(step.getAsJsonObject().get("type").getAsString());
        assertTrue(types.contains("minecraft:rarity_filter"),"露头必须有稀疏控制");
        assertEquals(8,placement.get(0).getAsJsonObject().get("chance").getAsInt());
        assertEquals("minecraft:biome",types.get(types.size()-1),"必须以群系过滤结尾");
    }
}
