package dev.logos.dimension;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 罐栽植物上草皮的配套数据检查。
 *
 * <p>Logos 从 #328 起给 13 种罐栽植物各一个基底标签 {@code logos:plantable_on/<作物>}，
 * 默认空；标签不提供右键种植、收获和掉落，这些由 {@code WildCrops} 与这里的数据配合补齐。
 */
class WildCropsDataTest {
    private static final String[] CROPS = {
        "mandrake_crop", "starlight_moss_crop", "mercury_mushroom_crop", "niter_sprout_crop",
        "copper_pod_crop", "iron_pod_crop", "gold_pod_crop", "netherite_pod_crop",
        "candleflame_crop", "frostbell_crop", "thunder_thistle_crop", "riftshade_crop", "redvein_root_crop",
    };

    private static JsonObject data(String path)throws IOException{
        try(var input=WildCropsDataTest.class.getResourceAsStream("/"+path)){
            assertNotNull(input,"缺少数据文件："+path);
            return JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test void everyJarCropIsPlantableOnOurTurf()throws Exception{
        for(String crop:CROPS){
            var tag=data("data/logos/tags/block/plantable_on/"+crop+".json");
            var values=new ArrayList<String>();
            tag.getAsJsonArray("values").forEach(v->values.add(v.getAsString()));
            assertTrue(values.contains("#logos_dimension:turf"),
                crop+" 的基底标签没有指向草皮标签："+values);
        }
    }

    @Test void turfTagListsOurGrounds()throws Exception{
        var turf=data("data/logos_dimension/tags/block/turf.json");
        var values=new ArrayList<String>();
        turf.getAsJsonArray("values").forEach(v->values.add(v.getAsString()));
        assertTrue(values.size()>30,"草皮方块太少："+values.size());
        assertTrue(values.stream().allMatch(v->v.startsWith("logos_dimension:")),
            "草皮标签里混进了别的模组的方块");
    }

    @Test void elfForestGrowsTwoWildCrops()throws Exception{
        var biome=data("data/logos_dimension/worldgen/biome/elven_forest.json");
        var step9=new ArrayList<String>();
        biome.getAsJsonArray("features").get(9).getAsJsonArray().forEach(v->step9.add(v.getAsString()));
        assertTrue(step9.contains("logos_dimension:wild_mandrake"),"精灵森林缺少野生曼德拉草");
        assertTrue(step9.contains("logos_dimension:wild_starlight_moss"),"精灵森林缺少野生星辉苔");

        for(var entry:List.of(
            Map.entry("wild_mandrake","logos:mandrake_crop"),
            Map.entry("wild_starlight_moss","logos:starlight_moss_crop"))){
            var feature=data("data/logos_dimension/worldgen/configured_feature/"+entry.getKey()+".json");
            String text=feature.toString();
            assertTrue(text.contains(entry.getValue()),entry.getKey()+" 没有种下 "+entry.getValue());
            assertTrue(text.contains("\"age\":\"0\""),entry.getKey()+" 应该从 age=0 开始长");
        }
    }
}
