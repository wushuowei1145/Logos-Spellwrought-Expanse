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
        "cloudsea_amber_whirls","cloudsea_blue_bell_steps","cloudsea_aurora_dunes"};

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
        // 长风草原的主草是原版高草丛（两格高、自带群系草色），我们的矮草与短草只是补层次；
        // 高草丛的上端由渲染层的叠加几何带出摆动。
        int tall=w.getOrDefault("minecraft:tall_grass",0);
        int grass=tall
                 +w.getOrDefault("logos_dimension:journey_meadow_grass",0)
                 +w.getOrDefault("minecraft:short_grass",0);
        int young=w.getOrDefault("logos_dimension:journey_meadow_grass_young",0);
        int flowers=0;
        for(var entry:w.entrySet())
            if(!entry.getKey().contains("meadow_grass")
                &&!entry.getKey().equals("minecraft:short_grass")
                &&!entry.getKey().equals("minecraft:tall_grass"))
                flowers+=entry.getValue();
        assertTrue(tall>=40,"高草丛应该是长风草原的主草（现在 "+tall+"）");
        assertTrue(grass>flowers*2,"草的权重应当明显压过野花");
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
        int grass=w.getOrDefault("logos_dimension:journey_meadow_grass",0);
        int shortGrass=w.getOrDefault("minecraft:short_grass",0);
        assertTrue(w.getOrDefault("minecraft:gravel",0)>0,"丘陵应该有砾石");
        assertTrue(w.getOrDefault("logos_dimension:journey_bracken",0)>0,"丘陵应该有卷叶蕨");
        assertTrue(grass+shortGrass>w.getOrDefault("minecraft:gravel",0)*4,
            "砾石不能压过草，否则不像丘陵而像碎石坡");
    }

    /** 野花草甸：野花权重压过草，是六个平原里最"花"的一个。 */
    @Test void roseDunesIsAFlowerMeadow()throws Exception{
        var ground=vegetation("cloudsea_rose_dunes");
        assertTrue(ground.contains("logos_dimension:cloudsea_rose_dunes_floor"),
            "野花草甸应该引用自己的地表特征");
        assertFalse(ground.contains("logos_dimension:meadow_floor"),
            "野花草甸不应再引用共享草地表");

        var w=weights("cloudsea_rose_dunes_floor");
        int grass=w.getOrDefault("logos_dimension:journey_meadow_grass",0)
                 +w.getOrDefault("minecraft:short_grass",0);
        int flowers=0;
        for(var e:w.entrySet())
            if(e.getKey().startsWith("logos_dimension:journey_")&&
               !e.getKey().contains("meadow_grass")) flowers+=e.getValue();
        flowers+=w.getOrDefault("minecraft:oxeye_daisy",0);
        assertTrue(flowers>grass,"野花权重应当压过草（花甸才像花甸）：花="+flowers+" 草="+grass);
        assertTrue(w.getOrDefault("logos_dimension:journey_fireweed",0)>0,"缺少柳兰点缀");
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
