package dev.logos.dimension;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 保证九类遗迹共用一个稀疏分布网格，并且全部走原版结构与模板池。 */
class WaysideDataTest {
    private JsonObject read(String path)throws Exception{
        try(var in=getClass().getResourceAsStream("/data/logos_dimension/"+path+".json")){
            assertNotNull(in,path);return JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    @Test void nineKindsShareOneSparseGrid()throws Exception{
        var set=read("worldgen/structure_set/wayside_ruins");var ids=new HashSet<String>();
        for(var entry:set.getAsJsonArray("structures"))ids.add(entry.getAsJsonObject().get("structure").getAsString());
        assertEquals(9,ids.size());assertEquals("minecraft:random_spread",set.getAsJsonObject("placement").get("type").getAsString());
        assertTrue(set.getAsJsonObject("placement").get("spacing").getAsInt()>=24);
        for(String id:ids){
            String name=id.split(":")[1];var structure=read("worldgen/structure/"+name);
            assertEquals("minecraft:jigsaw",structure.get("type").getAsString());
            assertEquals("none",structure.get("terrain_adaptation").getAsString());
            assertEquals("OCEAN_FLOOR_WG",structure.get("project_start_to_heightmap").getAsString());
            var pool=read("worldgen/template_pool/"+structure.get("start_pool").getAsString().split(":")[1]);
            assertEquals(2,pool.getAsJsonArray("elements").size());
            for(var e:pool.getAsJsonArray("elements")){
                var element=e.getAsJsonObject().getAsJsonObject("element");
                assertEquals("minecraft:single_pool_element",element.get("element_type").getAsString());
                assertEquals("apply_waterlogging",element.get("override_liquid_settings").getAsString());
            }
            var tag=read("tags/worldgen/biome/has_structure/"+name);
            for(var b:tag.getAsJsonArray("values"))assertTrue(b.getAsString().startsWith("logos_dimension:cloudsea_"));
        }
    }
    @Test void foundationsDoNotIgnoreAirOrReplaceSolidTerrain()throws Exception{
        var processor=read("worldgen/processor_list/wayside_preserve").getAsJsonArray("processors").get(0).getAsJsonObject();
        assertEquals("minecraft:structure_void",processor.getAsJsonArray("blocks").get(0).getAsJsonObject().get("Name").getAsString());
        var footing=read("worldgen/configured_feature/wayside_footing");assertEquals("minecraft:block_column",footing.get("type").getAsString());
        assertEquals("down",footing.getAsJsonObject("config").get("direction").getAsString());
        assertTrue(footing.getAsJsonObject("config").getAsJsonObject("allowed_placement").toString().contains("minecraft:solid"));
    }
}
