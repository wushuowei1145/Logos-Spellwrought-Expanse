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
        assertEquals(34,names.size());
        for(String name:names)for(var step:data("worldgen/biome/"+name).getAsJsonArray("features"))for(var feature:step.getAsJsonArray())
            assertFalse(feature.getAsString().contains("ore_"),feature.toString());
    }
    @Test void seasonClockRetainsEightDaySeasons(){
        assertEquals(0,JourneySeasons.at(0));assertEquals(1,JourneySeasons.at(192000));
        assertEquals(3,JourneySeasons.at(-1));assertEquals(0,JourneySeasons.at(768000));
    }
    @Test void settlementsUseBoundedNativePoolsAndOnlyOpenBiomes()throws Exception{
        for(String name:new String[]{"hamlet","town"}){
            var structure=data("worldgen/structure/journey_"+name);assertEquals("minecraft:jigsaw",structure.get("type").getAsString());
            assertEquals(4,structure.get("size").getAsInt());assertEquals(112,structure.get("max_distance_from_center").getAsInt());
            assertEquals("none",structure.get("terrain_adaptation").getAsString(),"Farming must not alter terrain density");
            var allowed=java.util.Set.of("sunlit_glade","starflower_glade","silk_folds","rose_dunes");
            for(var b:structure.getAsJsonArray("biomes"))assertTrue(allowed.contains(b.getAsString().replace("logos_dimension:cloudsea_","")));
            assertNull(getClass().getResource("/data/logos_dimension/structure/journey_"+name+".nbt"),"Obsolete monolithic structure remains active");
        }
        var fields=data("settlement_manifest");assertEquals(7,fields.get("sources").getAsInt());
        assertEquals("minecraft:wheat",fields.get("crop").getAsString());
        for(String n:new String[]{"rice_paddy_0","rice_paddy_1"})assertNull(getClass().getResource("/data/logos_dimension/structure/settlement/"+n+".nbt"));
        var planting=data("worldgen/configured_feature/settlement_wheat_column");assertEquals("minecraft:block_column",planting.get("type").getAsString());
        var layers=planting.getAsJsonObject("config").getAsJsonArray("layers");assertEquals(2,layers.size());
        assertEquals("minecraft:farmland",layers.get(0).getAsJsonObject().getAsJsonObject("provider").getAsJsonObject("state").get("Name").getAsString());
    }
    @Test void settlementAirIsNotIgnoredAndAnimalsAreDisabled()throws Exception{
        var processors=data("worldgen/processor_list/settlement_preserve_void").getAsJsonArray("processors");
        assertEquals(1,processors.size());
        assertEquals("minecraft:block_ignore",processors.get(0).getAsJsonObject().get("processor_type").getAsString());
        var ignored=processors.get(0).getAsJsonObject().getAsJsonArray("blocks");assertEquals(1,ignored.size());
        assertEquals("minecraft:structure_void",ignored.get(0).getAsJsonObject().get("Name").getAsString());
        assertFalse(data("settlement_manifest").get("entities").getAsBoolean());
        for(var biome:dev.logos.dimension.cloudsea.CloudseaBiome.values())
            assertTrue(data("worldgen/biome/"+biome.id()).getAsJsonObject("spawners").isEmpty(),"Animal spawns remain in "+biome.id());
        for(String name:new String[]{"hamlet","town"}){
            var structure=data("worldgen/structure/journey_"+name);
            assertEquals(-3,structure.getAsJsonObject("start_height").get("absolute").getAsInt());
            assertTrue(structure.getAsJsonObject("spawn_overrides").getAsJsonObject("creature").getAsJsonArray("spawns").isEmpty());
        }
    }
    @Test void nativeTreesUseTheMatchingLogAndLeaves()throws Exception{
        for(var b:dev.logos.dimension.cloudsea.CloudseaBiome.values())if(!b.barren()){
            var tree=data("worldgen/configured_feature/trees_"+b.id());assertEquals("minecraft:tree",tree.get("type").getAsString());
            var config=tree.getAsJsonObject("config");
            assertEquals("logos_dimension:"+b.id()+"_log",config.getAsJsonObject("trunk_provider").getAsJsonObject("state").get("Name").getAsString());
            assertEquals("logos_dimension:"+b.id()+"_leaf",config.getAsJsonObject("foliage_provider").getAsJsonObject("state").get("Name").getAsString());
        }
    }
    @Test void elvenForestUsesNativeStructuresAndRealLights()throws Exception{
        var biome=data("worldgen/biome/elven_forest");assertTrue(biome.getAsJsonObject("spawners").isEmpty());
        var structure=data("worldgen/structure/elven_world_tree");assertEquals("minecraft:jigsaw",structure.get("type").getAsString());
        var range=structure.getAsJsonObject("max_distance_from_center");assertEquals(128,range.get("horizontal").getAsInt());assertEquals(256,range.get("vertical").getAsInt());
        assertEquals("none",structure.get("terrain_adaptation").getAsString());assertEquals("logos_dimension:elven_forest",structure.getAsJsonArray("biomes").get(0).getAsString());
        var manifest=data("elven_forest_manifest");assertEquals(343,manifest.getAsJsonObject("world_tree").get("tiles").getAsInt());
        assertEquals(8,manifest.getAsJsonObject("world_tree").get("surrounding_giants").getAsInt());assertTrue(manifest.get("block_light_sources").getAsBoolean());
    }
    @Test void tallTreesRemainSparseNativeFeatures()throws Exception{
        for(String name:new String[]{"ancient_beech","jade_whirls","fernwood","moss_vale","fir_heart","lilac_steps","cloudroot_shelves"}){
            String id="tall_trees_cloudsea_"+name;var tree=data("worldgen/configured_feature/"+id);
            assertEquals("minecraft:tree",tree.get("type").getAsString());
            var config=tree.getAsJsonObject("config");assertEquals("logos_dimension:cloudsea_"+name+"_log",config.getAsJsonObject("trunk_provider").getAsJsonObject("state").get("Name").getAsString());
            int base=config.getAsJsonObject("trunk_placer").get("base_height").getAsInt();assertTrue(base>=16&&base<=22);
            var placed=data("worldgen/placed_feature/"+id);assertEquals(3,placed.getAsJsonArray("placement").get(0).getAsJsonObject().get("chance").getAsInt());
        }
    }
}
