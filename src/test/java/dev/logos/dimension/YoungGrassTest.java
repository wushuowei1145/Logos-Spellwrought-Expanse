package dev.logos.dimension;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 幼年原野牧草：三个年龄的方块状态、掉落自己、进衰退标签，
 * 并且真的出现在林隙与共享草地的地表组合里。
 *
 * <p>只做数据断言；生长与催熟由 GameTest 覆盖。
 */
class YoungGrassTest {
    private static final String NAME="journey_meadow_grass_young";

    private JsonObject data(String path)throws IOException{
        try(var input=getClass().getResourceAsStream("/data/logos_dimension/"+path+".json")){
            assertNotNull(input,path);
            return JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test void hasAgeVariants()throws Exception{
        // 方块状态在 assets 下，不能走 data() 的 data/logos_dimension 前缀。
        JsonObject variants;
        try(var input=getClass().getResourceAsStream("/assets/logos_dimension/blockstates/"+NAME+".json")){
            assertNotNull(input,"找不到 "+NAME+" 的方块状态");
            variants=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("variants");
        }
        for(int age=0;age<=2;age++)
            assertTrue(variants.has("age="+age),"缺少 age="+age+" 的方块状态");
    }

    @Test void dropsItself()throws Exception{
        var pool=data("loot_table/blocks/"+NAME).getAsJsonArray("pools").get(0).getAsJsonObject();
        var entry=pool.getAsJsonArray("entries").get(0).getAsJsonObject();
        assertEquals("logos_dimension:"+NAME,entry.get("name").getAsString(),"幼年牧草应当掉落自己");
    }

    @Test void isInFragilePlantTag()throws Exception{
        JsonObject tag;
        try(var input=getClass().getResourceAsStream("/data/logos/tags/block/fragile_plants.json")){
            assertNotNull(input,"找不到 logos:fragile_plants 标签");
            tag=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
        }
        var values=new HashSet<String>();
        for(var value:tag.getAsJsonArray("values"))values.add(value.getAsString());
        assertTrue(values.contains("logos_dimension:"+NAME),"幼年牧草没有加入 logos:fragile_plants");
    }

    /** 幼年牧草要真的进入地表组合，否则只是"存在但见不到"。 */
    @Test void appearsInGladeGroundCover()throws Exception{
        for(String floor:new String[]{"cloudsea_sunlit_glade_floor","cloudsea_starflower_glade_floor","meadow_floor"})
            assertTrue(floorContains(floor),"logos_dimension:"+NAME+" 没有出现在 "+floor+" 里");
    }

    private boolean floorContains(String floor)throws IOException{
        var entries=data("worldgen/configured_feature/"+floor).getAsJsonObject("config")
            .getAsJsonObject("to_place").getAsJsonArray("entries");
        for(var entry:entries){
            var data=entry.getAsJsonObject().getAsJsonObject("data");
            if(data!=null&&data.has("Name")&&("logos_dimension:"+NAME).equals(data.get("Name").getAsString()))return true;
        }
        return false;
    }
}
