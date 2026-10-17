package dev.logos.dimension.gametest;
import dev.logos.dimension.*;
import dev.logos.dimension.cloudsea.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
public final class CloudseaGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS=DeferredRegister.create(Registries.TEST_FUNCTION,LogosDimension.ID);
    public static void register(IEventBus bus){
        var tests=Map.<String,Consumer<GameTestHelper>>of("cloudsea_registry",CloudseaGameTests::registry,"cloudsea_terrain",CloudseaGameTests::terrain,"cloudsea_village",CloudseaGameTests::village,"cloudsea_tree_blocks",CloudseaGameTests::forest,"journey_seasons",CloudseaGameTests::seasons);
        tests.forEach((name,test)->FUNCTIONS.register(name,()->test));FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent e)->{var env=e.registerEnvironment(LogosDimension.id("cloudsea_default"),new TestEnvironmentDefinition.AllOf());for(String name:tests.keySet())e.registerTest(LogosDimension.id(name),new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION,LogosDimension.id(name)),new TestData<>(env,Identifier.withDefaultNamespace("empty"),1600,0,true,Rotation.NONE,false,1,1,false,16)));});
    }
    private static ServerLevel level(GameTestHelper h){return Objects.requireNonNull(h.getLevel().getServer().getLevel(LogosDimension.CLOUDSEA));}
    private static void registry(GameTestHelper h){
        var level=level(h);h.assertTrue(NativeWorlds.vanilla(level),"Journey does not use minecraft:noise");
        var biomes=level.registryAccess().lookupOrThrow(Registries.BIOME);
        for(var b:CloudseaBiome.values())biomes.getOrThrow(ResourceKey.create(Registries.BIOME,LogosDimension.id(b.id())));
        h.assertTrue(CloudseaBiome.values().length==32,"New forest and glade biomes missing");
        h.assertTrue(!BuiltInRegistries.ITEM.containsKey(LogosDimension.id("cloudsea_portal")),"Unrequested entrance item added");h.succeed();
    }
    private static void terrain(GameTestHelper h){
        var level=level(h);int air=0;
        for(int x=-128;x<256;x+=16)for(int z=-128;z<256;z+=16)for(int y=-56;y<80;y++){
            var state=level.getBlockState(new BlockPos(x,y,z));String id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            h.assertTrue(!id.contains("_ore")&&!id.contains("raw_copper")&&!id.contains("raw_iron"),"Vanilla ore generated: "+id);
            if(y<0&&(state.isAir()||!state.getFluidState().isEmpty()))air++;
        }
        h.assertTrue(air>30,"Native caves missing");h.succeed();
    }
    private static void forest(GameTestHelper h){
        var level=level(h);var at=NativeWorlds.locateBiome(level,CloudseaBiome.ANCIENT_BEECH.id());int logs=0,leaves=0,covered=0,eligible=0,water=0;var seen=new java.util.HashMap<String,Integer>();
        for(int x=at.getX()-16;x<at.getX()+16;x++)for(int z=at.getZ()-16;z<at.getZ()+16;z++){
            boolean plant=false;
            int gy=level.getChunkSource().getGenerator().getBaseHeight(x,z,net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG,level,level.getChunkSource().randomState());
            var gp=new BlockPos(x,gy,z);if(gy<=63)water++;
            if(level.isEmptyBlock(gp)&&level.getBlockState(gp.below()).isFaceSturdy(level,gp.below(),Direction.UP))eligible++;
            seen.merge(level.getBiome(gp).unwrapKey().orElseThrow().identifier().getPath(),1,Integer::sum);
            for(int y=35;y<250;y++){
                var state=level.getBlockState(new BlockPos(x,y,z));var block=state.getBlock();
                if(state.is(net.minecraft.tags.BlockTags.LOGS))logs++;
                if(block instanceof SeasonalLeavesBlock)leaves++;
                if(block instanceof JourneyPlantBlock)h.assertTrue(state.canSurvive(level,new BlockPos(x,y,z)),"Native plant has unsuitable ground at "+x+","+y+","+z+": "+state);
                if(block instanceof JourneyPlantBlock||state.is(net.minecraft.world.level.block.Blocks.MOSS_CARPET)||state.is(net.minecraft.world.level.block.Blocks.LEAF_LITTER))plant=true;
            }if(plant)covered++;
        }
        h.assertTrue(logs>70&&leaves>500,"Native features did not produce a dense connected woodland: "+logs+"/"+leaves);
        System.out.println("NATIVE_FOREST "+at+" logs="+logs+" leaves="+leaves+" covered="+covered+" eligible="+eligible+" water="+water+" biomes="+seen);
        h.assertTrue(covered>400,"Forest floor lacks vegetation: "+covered);h.succeed();
    }
    private static void village(GameTestHelper h){
        var level=level(h);var registry=level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        h.assertTrue(registry.getOrThrow(ResourceKey.create(Registries.STRUCTURE,LogosDimension.id("journey_hamlet"))).value() instanceof net.minecraft.world.level.levelgen.structure.structures.JigsawStructure,"Hamlet does not use native jigsaw structures");
        h.assertTrue(registry.getOrThrow(ResourceKey.create(Registries.STRUCTURE,LogosDimension.id("journey_town"))).value() instanceof net.minecraft.world.level.levelgen.structure.structures.JigsawStructure,"Town missing");h.succeed();
    }
    private static void seasons(GameTestHelper h){
        var level=level(h);var registry=level.registryAccess().lookupOrThrow(Registries.BIOME);
        var biome=registry.getOrThrow(ResourceKey.create(Registries.BIOME,LogosDimension.id(CloudseaBiome.SILK_FOLDS.id()))).value();
        var pos=new BlockPos(12,100,12);var clock=level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(JourneySeasons.CLOCK);
        long previous=level.clockManager().getTotalTicks(clock);
        try{
            level.clockManager().setTotalTicks(clock,0);JourneySeasons.enter(level);
            h.assertTrue(biome.getPrecipitationAt(pos,80)==net.minecraft.world.level.biome.Biome.Precipitation.RAIN,"Spring is not rainy");
            level.clockManager().setTotalTicks(clock,JourneySeasons.SEASON_TICKS*3);JourneySeasons.enter(level);
            h.assertTrue(biome.getPrecipitationAt(pos,80)==net.minecraft.world.level.biome.Biome.Precipitation.SNOW,"Winter temperature does not produce snow");
            JourneySeasons.CONTEXT.remove();
            h.assertTrue(biome.getPrecipitationAt(pos,80)==net.minecraft.world.level.biome.Biome.Precipitation.RAIN,"Season context leaked outside the dimension tick");
            var leaf=CloudseaBlocks.LEAVES.get(CloudseaBiome.SILK_FOLDS).get();
            var healthy=leaf.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.DISTANCE,1).setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT,true);
            h.assertTrue(healthy.isRandomlyTicking(),"Healthy and player-placed leaves are excluded from seasonal random ticks");
            level.setBlockAndUpdate(pos,healthy);
            for(int attempt=0;attempt<32&&level.getBlockState(pos).getValue(SeasonalLeavesBlock.SEASON)!=3;attempt++)
                level.tickChunk(level.getChunkAt(pos),4096);
            h.assertTrue(level.getBlockState(pos).getValue(SeasonalLeavesBlock.SEASON)==3,"Leaves do not update to winter");
            level.removeBlock(pos,false);
        }finally{JourneySeasons.CONTEXT.remove();level.clockManager().setTotalTicks(clock,previous);}
        var mineshaft=level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(ResourceKey.create(Registries.STRUCTURE,Identifier.withDefaultNamespace("mineshaft"))).value();
        h.assertTrue(mineshaft.biomes().contains(registry.getOrThrow(ResourceKey.create(Registries.BIOME,LogosDimension.id("cloudsea_root_caves")))),"Native mineshafts do not accept this dimension");
        h.succeed();
    }
    private CloudseaGameTests(){}
}
