package dev.logos.dimension;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 林地水边植被：13 个林地与精灵森林在植被阶段引用原版的甘蔗与睡莲特征。
 *
 * <p>这两个都是原版 placed feature，自带"必须挨着水"的判定，所以不需要附属另写一套；
 * 这里只保证引用存在、没有被拼错，并且没有扩散到非林地群系。
 */
class ForestWatersideTest {
    private static final String[] FORESTS={
        "cloudsea_lilac_steps","cloudsea_cloudroot_shelves","cloudsea_fir_heart",
        "cloudsea_reverse_gardens","cloudsea_ivory_steps","cloudsea_moon_birch",
        "cloudsea_jade_whirls","cloudsea_verdant_whirls","cloudsea_lantern_shelves",
        "cloudsea_ancient_beech","cloudsea_fernwood","cloudsea_moss_vale","cloudsea_copper_copse",
        "elven_forest"
    };
    private static final List<String> WATERSIDE=List.of("minecraft:patch_sugar_cane","minecraft:patch_waterlily");

    private JsonObject data(String path)throws IOException{
        try(var input=getClass().getResourceAsStream("/data/logos_dimension/"+path+".json")){
            assertNotNull(input,path);
            return JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test void woodlandsReferenceVanillaWatersideFeatures()throws Exception{
        for(String biome:FORESTS){
            var stage=data("worldgen/biome/"+biome).getAsJsonArray("features").get(9).getAsJsonArray();
            var names=new ArrayList<String>();
            for(var feature:stage)names.add(feature.getAsString());
            for(String feature:WATERSIDE)
                assertTrue(names.contains(feature),biome+" 没有引用 "+feature);
        }
    }

    /** 非林地不该被顺手加上水边植被：抽查沙丘与尖塔类群系。 */
    @Test void nonWoodlandBiomesStayUntouched()throws Exception{
        for(String biome:new String[]{"cloudsea_opal_dunes","cloudsea_frost_spires","cloudsea_star_ribbons"}){
            var stage=data("worldgen/biome/"+biome).getAsJsonArray("features").get(9).getAsJsonArray();
            var names=new ArrayList<String>();
            for(var feature:stage)names.add(feature.getAsString());
            for(String feature:WATERSIDE)
                assertFalse(names.contains(feature),biome+" 不该有水边植被");
        }
    }
}
