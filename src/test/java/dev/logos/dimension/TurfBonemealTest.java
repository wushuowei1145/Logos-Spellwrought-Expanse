package dev.logos.dimension;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 草皮骨粉：草皮要在 {@code minecraft:can_spawn_from_bone_meal} 里能找到候选，
 * 而且候选必须真的是云海群系地表引用的那些 `<biome>_floor` 特征。
 *
 * <p>只做数据断言；"骨粉真的长东西"由 GameTest 覆盖。
 */
class TurfBonemealTest {
    private static final String[] FOREST_BIOMES={
        "cloudsea_lilac_steps","cloudsea_cloudroot_shelves","cloudsea_fir_heart",
        "cloudsea_reverse_gardens","cloudsea_ivory_steps","cloudsea_moon_birch",
        "cloudsea_jade_whirls","cloudsea_verdant_whirls","cloudsea_lantern_shelves",
        "cloudsea_ancient_beech","cloudsea_fernwood","cloudsea_moss_vale",
        "cloudsea_copper_copse","cloudsea_sunlit_glade","cloudsea_starflower_glade",
        "elven_forest"};

    private JsonObject json(String path)throws IOException{
        try(var input=getClass().getResourceAsStream(path)){
            assertNotNull(input,path);
            return JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test void boneMealTagListsTheForestFloors()throws Exception{
        var tag=json("/data/minecraft/tags/configured_feature/can_spawn_from_bone_meal.json");
        var values=new HashSet<String>();
        for(var value:tag.getAsJsonArray("values"))values.add(value.getAsString());
        assertFalse(tag.get("replace").getAsBoolean(),"骨粉标签必须用 replace:false 追加，不能覆盖原版");
        for(String biome:FOREST_BIOMES)
            assertTrue(values.contains("logos_dimension:"+biome+"_floor"),
                "骨粉候选缺少 "+biome+"_floor");
        assertTrue(values.contains("logos_dimension:meadow_floor"),"骨粉候选缺少 meadow_floor");
        assertTrue(values.contains("logos_dimension:woodland_floor"),"骨粉候选缺少 woodland_floor");
    }

    /** 每个云海群系的地表阶段都必须引用一个骨粉候选特征，否则那里撒骨粉仍然没反应。 */
    @Test void everyBiomeReferencesABoneMealCandidate()throws Exception{
        var tag=json("/data/minecraft/tags/configured_feature/can_spawn_from_bone_meal.json");
        var candidates=new HashSet<String>();
        for(var value:tag.getAsJsonArray("values"))candidates.add(value.getAsString());
        for(String biome:FOREST_BIOMES){
            var features=json("/data/logos_dimension/worldgen/biome/"+biome+".json").getAsJsonArray("features");
            var ground=new ArrayList<String>();
            for(var feature:features.get(9).getAsJsonArray())ground.add(feature.getAsString());
            assertTrue(ground.stream().anyMatch(candidates::contains),
                biome+" 的地表特征里没有任何骨粉候选");
        }
    }
}
