package dev.logos.dimension;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 林地地下荧光苔：13 个林地（以及继承自山毛榉的精灵森林）在地下装饰阶段都要引用它，
 * 并且只在真实岩壁上生长、只出现在地表以下。
 *
 * <p>只做数据断言，不代替世界生成检查。
 */
class ForestCaveFloraTest {
    private static final String[] FORESTS={
        "cloudsea_lilac_steps","cloudsea_cloudroot_shelves","cloudsea_fir_heart",
        "cloudsea_reverse_gardens","cloudsea_ivory_steps","cloudsea_moon_birch",
        "cloudsea_jade_whirls","cloudsea_verdant_whirls","cloudsea_lantern_shelves",
        "cloudsea_ancient_beech","cloudsea_fernwood","cloudsea_moss_vale","cloudsea_copper_copse",
        "elven_forest"
    };
    /** 地下装饰在 11 个生成阶段里排第 8（索引 7）。 */
    private static final int UNDERGROUND_DECORATION=7;

    private JsonObject data(String path)throws IOException{
        try(var input=getClass().getResourceAsStream("/data/logos_dimension/"+path+".json")){
            assertNotNull(input,path);
            return JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test void woodlandsPlaceCaveLichenUnderground()throws Exception{
        for(String biome:FORESTS){
            var stage=data("worldgen/biome/"+biome).getAsJsonArray("features").get(UNDERGROUND_DECORATION).getAsJsonArray();
            var names=new ArrayList<String>();
            for(var feature:stage)names.add(feature.getAsString());
            assertTrue(names.contains("logos_dimension:forest_cave_lichen"),
                biome+" 的地下阶段没有引用荧光苔");
            assertTrue(names.contains("logos_dimension:forest_cave_moss"),
                biome+" 的地下阶段没有引用苔藓地面");
        }
    }

    /** 地面苔藓：原版 block_pile + moss_block，同样只在地下。 */
    @Test void caveFloorGetsMossPiles()throws Exception{
        var config=data("worldgen/configured_feature/forest_cave_moss");
        assertEquals("minecraft:block_pile",config.get("type").getAsString());
        assertEquals("minecraft:moss_block",
            config.getAsJsonObject("config").getAsJsonObject("state_provider")
                .getAsJsonObject("state").get("Name").getAsString(),
            "地面苔藓必须是原版苔藓块，不另造方块");

        var placement=data("worldgen/placed_feature/forest_cave_moss").getAsJsonArray("placement");
        var types=new ArrayList<String>();
        boolean belowSurface=false, airOnly=false;
        for(var step:placement){
            var object=step.getAsJsonObject();
            var type=object.get("type").getAsString();
            types.add(type);
            if(type.equals("minecraft:surface_relative_threshold_filter"))
                belowSurface=object.get("max_inclusive").getAsInt()<0;
            if(type.equals("minecraft:block_predicate_filter"))airOnly=true;
        }
        assertTrue(types.contains("minecraft:height_range"),"缺少高度区间限制");
        assertTrue(belowSurface,"必须以负的地表相对阈值限制在地下");
        assertTrue(airOnly,"只应放在空气位置，避免替换掉岩体");
        assertEquals("minecraft:biome",types.get(types.size()-1),"必须以群系过滤结尾");
    }

    @Test void lichenOnlyGrowsOnCaveWalls()throws Exception{
        var config=data("worldgen/configured_feature/forest_cave_lichen").getAsJsonObject("config");
        assertEquals("minecraft:glow_lichen",config.get("block").getAsString());
        assertTrue(config.get("can_place_on_wall").getAsBoolean(),"荧光苔必须能贴在洞壁上");
        assertFalse(config.get("can_place_on_floor").getAsBoolean(),"不应落在地面上");
        var blocks=new HashSet<String>();
        for(var block:config.getAsJsonArray("can_be_placed_on"))blocks.add(block.getAsString());
        assertTrue(blocks.contains("minecraft:stone"),"缺少原版石头基底");
        assertTrue(blocks.contains("logos_dimension:journey_limestone"),"缺少本附属的石灰岩基底");
    }

    @Test void lichenStaysBelowTheSurface()throws Exception{
        var placement=data("worldgen/placed_feature/forest_cave_lichen").getAsJsonArray("placement");
        var types=new ArrayList<String>();
        boolean belowSurface=false;
        for(var step:placement){
            var object=step.getAsJsonObject();
            types.add(object.get("type").getAsString());
            if(object.get("type").getAsString().equals("minecraft:surface_relative_threshold_filter"))
                belowSurface=object.get("max_inclusive").getAsInt()<0;
        }
        assertTrue(types.contains("minecraft:height_range"),"缺少高度区间限制");
        assertTrue(types.contains("minecraft:in_square"),"缺少水平随机");
        assertTrue(belowSurface,"必须以负的地表相对阈值限制在地下");
        assertEquals("minecraft:biome",types.get(types.size()-1),"必须以群系过滤结尾");
    }
}
