package dev.logos.dimension;

import com.google.gson.*;
import dev.logos.dimension.cloudsea.JourneySeasons;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeDataPolicyTest {
    private JsonObject data(String path)throws IOException{
        try(var input=getClass().getResourceAsStream("/data/logos_dimension/"+path+".json")){
            assertNotNull(input,path);return JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    @Test void entrypointsUseOnlyVanillaNoiseAndBiomeSelection()throws Exception{
        for(String path:new String[]{"dimension/cloudsea","cauldron/crucible"}){
            var generator=data(path).getAsJsonObject("generator");
            assertEquals("minecraft:noise",generator.get("type").getAsString());
            assertEquals("minecraft:multi_noise",generator.getAsJsonObject("biome_source").get("type").getAsString());
        }
        assertNull(getClass().getResource("/data/logos_dimension/dimension/crucible.json"),"Template must not register a public world");
    }
    @Test void nativeGeologyDoesNotEnableVanillaOreVeins()throws Exception{
        for(String name:new String[]{"journey","crucible"})assertFalse(data("worldgen/noise_settings/"+name).get("ore_veins_enabled").getAsBoolean());
    }
    @Test void biomeFeaturesExcludeVanillaOreDecorators()throws Exception{
        var entries=data("dimension/cloudsea").getAsJsonObject("generator").getAsJsonObject("biome_source").getAsJsonArray("biomes");
        var names=new java.util.HashSet<String>();
        for(var entry:entries)names.add(entry.getAsJsonObject().get("biome").getAsString().split(":")[1]);
        assertTrue(names.remove("cloudsea_root_caves"),"Native underground biome missing");
        assertEquals(32,names.size());
        for(String name:names)for(var step:data("worldgen/biome/"+name).getAsJsonArray("features"))for(var feature:step.getAsJsonArray())
            assertFalse(feature.getAsString().contains("ore_"),feature.toString());
    }
    @Test void seasonClockRetainsEightDaySeasons(){
        assertEquals(0,JourneySeasons.at(0));assertEquals(1,JourneySeasons.at(192000));
        assertEquals(3,JourneySeasons.at(-1));assertEquals(0,JourneySeasons.at(768000));
    }
}
