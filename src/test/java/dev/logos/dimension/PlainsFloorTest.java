package dev.logos.dimension;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 平原地表：一个一个群系做，先做长风草原。
 *
 * <p>这个测试同时守住"没有顺手改到别的群系"——其余五个草原群系必须还在用共享的
 * {@code meadow_floor}，直到各自的 PR 把它们换掉。
 */
class PlainsFloorTest {
    private static final String SILK="cloudsea_silk_folds";
    /** 还没做地表改造的草原群系：每做一个就从这里删掉一个。 */
    private static final String[] OTHER_PLAINS={
        "cloudsea_rose_dunes","cloudsea_amber_whirls",
        "cloudsea_blue_bell_steps","cloudsea_aurora_dunes"};

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

    private Map<String,Integer> weights(String floor)throws IOException{
        var map=new LinkedHashMap<String,Integer>();
        for(var entry:data("worldgen/configured_feature/"+floor).getAsJsonObject("config")
                .getAsJsonObject("to_place").getAsJsonArray("entries")){
            var object=entry.getAsJsonObject();
            map.put(object.getAsJsonObject("data").get("Name").getAsString(),object.get("weight").getAsInt());
        }
        return map;
    }

    @Test void silkFoldsHasItsOwnFloor()throws Exception{
        var ground=vegetation(SILK);
        assertTrue(ground.contains("logos_dimension:"+SILK+"_floor"),
            "长风草原应该引用自己的地表特征");
        assertFalse(ground.contains("logos_dimension:meadow_floor"),
            "长风草原不应再引用共享草地表");
    }

    /** 长风草原的招牌是"风里的长草"：牧草压过其它，短草与幼草补齐高度层次。 */
    @Test void silkFoldsIsWindsweptGrass()throws Exception{
        var w=weights(SILK+"_floor");
        // 长风草原的地表现在用矮草（journey_meadow_grass_short）做主草，
        // 叠加层的风摆草尖才不会被原版模型挡住；两种牧草都算"牧草权重"。
        int grass=Math.max(w.getOrDefault("logos_dimension:journey_meadow_grass",0),
                           w.getOrDefault("logos_dimension:journey_meadow_grass_short",0));
        int shortGrass=w.getOrDefault("minecraft:short_grass",0);
        int young=w.getOrDefault("logos_dimension:journey_meadow_grass_young",0);
        int flowers=0;
        for(var entry:w.entrySet())
            if(!entry.getKey().contains("meadow_grass")&&!entry.getKey().equals("minecraft:short_grass"))
                flowers+=entry.getValue();
        assertTrue(grass>=40,"牧草权重太低，长风草原会失去长草的样子");
        assertTrue(grass>flowers,"牧草应当压过野花");
        assertTrue(shortGrass>0,"缺少原版短草补层次");
        assertTrue(young>0,"缺少幼草");
    }

    /** 旷野丘陵是起伏草坡：草更碎、夹砾石与卷叶蕨，野花很少。 */
    @Test void dawnRibbonsIsARockyHillside()throws Exception{
        var ground=vegetation("cloudsea_dawn_ribbons");
        assertTrue(ground.contains("logos_dimension:cloudsea_dawn_ribbons_floor"),
            "旷野丘陵应该引用自己的地表特征");
        assertFalse(ground.contains("logos_dimension:meadow_floor"),
            "旷野丘陵不应再引用共享草地表");

        var w=weights("cloudsea_dawn_ribbons_floor");
        int grass=Math.max(w.getOrDefault("logos_dimension:journey_meadow_grass",0),
                           w.getOrDefault("logos_dimension:journey_meadow_grass_short",0));
        int shortGrass=w.getOrDefault("minecraft:short_grass",0);
        assertTrue(w.getOrDefault("minecraft:gravel",0)>0,"丘陵应该有砾石");
        assertTrue(w.getOrDefault("logos_dimension:journey_bracken",0)>0,"丘陵应该有卷叶蕨");
        assertTrue(grass+shortGrass>w.getOrDefault("minecraft:gravel",0)*4,
            "砾石不能压过草，否则不像丘陵而像碎石坡");
    }

    @Test void otherPlainsStillUseTheSharedFloor()throws Exception{
        for(String biome:OTHER_PLAINS)
            assertTrue(vegetation(biome).contains("logos_dimension:meadow_floor"),
                biome+" 这次不应该被改动");
    }

    @Test void floorPlacementUsesSoilScan()throws Exception{
        var placement=data("worldgen/placed_feature/"+SILK+"_floor").getAsJsonArray("placement");
        var types=new ArrayList<String>();
        for(var step:placement)types.add(step.getAsJsonObject().get("type").getAsString());
        assertTrue(types.contains("minecraft:environment_scan"),"缺少向下找土壤");
        assertTrue(types.contains("minecraft:biome"),"必须以群系过滤结尾");
        assertEquals("minecraft:biome",types.get(types.size()-1));
    }
}
