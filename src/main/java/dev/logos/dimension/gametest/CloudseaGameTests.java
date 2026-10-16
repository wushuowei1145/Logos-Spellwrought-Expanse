package dev.logos.dimension.gametest;

import dev.logos.dimension.*;
import dev.logos.dimension.cloudsea.*;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CloudseaGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS=DeferredRegister.create(Registries.TEST_FUNCTION,LogosDimension.ID);
    public static void register(IEventBus bus){
        FUNCTIONS.register("cloudsea_registry",()->CloudseaGameTests::registry);
        FUNCTIONS.register("cloudsea_terrain",()->CloudseaGameTests::terrain);
        FUNCTIONS.register("cloudsea_village",()->CloudseaGameTests::village);
        FUNCTIONS.register("cloudsea_tree_blocks",()->CloudseaGameTests::treeBlocks);
        FUNCTIONS.register("journey_seasons",()->CloudseaGameTests::seasons);
        FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent e)->{Holder<TestEnvironmentDefinition<?>> env=e.registerEnvironment(LogosDimension.id("cloudsea_default"),new TestEnvironmentDefinition.AllOf());for(String name:List.of("cloudsea_registry","cloudsea_terrain","cloudsea_village","cloudsea_tree_blocks","journey_seasons"))e.registerTest(LogosDimension.id(name),new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION,LogosDimension.id(name)),new TestData<>(env,Identifier.withDefaultNamespace("empty"),600,0,true,Rotation.NONE,false,1,1,false,16)));});
    }
    private static ServerLevel level(GameTestHelper h){var level=h.getLevel().getServer().getLevel(LogosDimension.CLOUDSEA);h.assertTrue(level!=null,"Cloudsea did not load");return level;}
    private static void treeBlocks(GameTestHelper h){
        var level=level(h);var pos=new BlockPos(8,430,8);
        var wood=CloudseaBlocks.STEM.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS,Direction.Axis.X);
        level.setBlockAndUpdate(pos,wood);
        h.assertTrue(level.getBlockState(pos).getValue(RotatedPillarBlock.AXIS)==Direction.Axis.X,"Branch lost its wood axis");
        h.assertTrue(wood.getCollisionShape(level,pos).max(Direction.Axis.X)==1,"Trunk is still a thin non-solid plant");
        for(var b:CloudseaBlocks.LEAVES.values()){
            var state=b.get().defaultBlockState();h.assertTrue(state.getLightEmission()==0,"Entire crown still emits light");
            h.assertTrue(state.getCollisionShape(level,pos).max(Direction.Axis.X)==1,"Leaf block has no full canopy volume");
        }
        level.removeBlock(pos,false);h.succeed();
    }
    private static void registry(GameTestHelper h){var level=level(h);h.assertTrue(level.getChunkSource().getGenerator() instanceof CloudseaChunkGenerator,"Wrong generator");var biomes=level.registryAccess().lookupOrThrow(Registries.BIOME);for(var b:CloudseaBiome.values()){var actual=biomes.getOrThrow(ResourceKey.create(Registries.BIOME,LogosDimension.id(b.id())));h.assertTrue(actual.value().getMobSettings().getMobs(net.minecraft.world.entity.MobCategory.MONSTER).isEmpty(),"New biome has mobs");}h.assertTrue(!BuiltInRegistries.ITEM.containsKey(LogosDimension.id("cloudsea_portal")),"Entrance was added");h.succeed();}
    private static void terrain(GameTestHelper h){
        var level=level(h);var gen=(CloudseaChunkGenerator)level.getChunkSource().getGenerator();int air=0;
        for(int x=16;x<32;x++)for(int z=16;z<32;z++){
            var c=gen.terrain().column(x,z);level.getChunk(x>>4,z>>4);
            for(int y=-124;y<=c.highest()+24;y++){
                var state=level.getBlockState(new BlockPos(x,y,z));String id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                h.assertTrue(!id.contains("_ore")&&!id.equals("minecraft:raw_copper_block")&&!id.equals("minecraft:raw_iron_block"),"Vanilla ore generated: "+id);
                h.assertTrue(!state.is(CloudseaBlocks.DEMONSTONE.get())&&!state.is(CloudseaBlocks.ROOT.get()),"Old magical underground remains");
                if(y<0&&state.isAir())air++;
            }
        }
        h.assertTrue(air>100,"No underground caves");
        h.assertTrue(level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME,LogosDimension.id("cloudsea_root_caves"))).value().getGenerationSettings().getCarvers().iterator().hasNext(),"Native carvers missing");
        h.succeed();
    }
    private static void village(GameTestHelper h){
        var level=level(h);var gen=(CloudseaChunkGenerator)level.getChunkSource().getGenerator();CloudseaSettlements.Village village=null;
        for(int z=-2;z<=0&&village==null;z++)for(int x=-4;x<=4&&village==null;x++)village=gen.settlements().village(gen.terrain().region(x,z));
        h.assertTrue(village!=null,"No suitable settlement");var house=village.houses().getFirst();
        var template=JourneyTemplates.get(house.template());int solid=0,air=0;
        for(int z=0;z<template.depth();z++)for(int x=0;x<template.width();x++)for(int y=0;y<template.height();y++){var state=template.at(x,y,z);if(state!=null){if(state.isAir())air++;else solid++;}}
        h.assertTrue(solid>100&&air>20,"Internet blueprint has no shell or interior");
        h.assertTrue(gen.terrain().riverDistance(village.x(),village.z())<160,"Settlement is not by water");
        h.assertTrue(CloudseaTerrain.REGION_SIZE>=6000,"Civilization density is too high");
        for(var building:village.houses())h.assertTrue(building.y()>gen.terrain().column(building.x(),building.z()).water(),"Building is submerged");
        level.getChunk(house.x()>>4,house.z()>>4);h.assertTrue(!level.getBlockState(new BlockPos(house.x(),house.y(),house.z())).isAir(),"Generated building floor is missing");
        h.succeed();
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
            var leaf=CloudseaBlocks.LEAVES.get(CloudseaBiome.SILK_FOLDS).get();level.setBlockAndUpdate(pos,leaf.defaultBlockState());
            leaf.defaultBlockState().randomTick(level,pos,net.minecraft.util.RandomSource.create(1));
            h.assertTrue(level.getBlockState(pos).getValue(SeasonalLeavesBlock.SEASON)==3,"Leaves do not update to winter");
            level.removeBlock(pos,false);
        }finally{JourneySeasons.CONTEXT.remove();level.clockManager().setTotalTicks(clock,previous);}
        var mineshaft=level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(ResourceKey.create(Registries.STRUCTURE,Identifier.withDefaultNamespace("mineshaft"))).value();
        h.assertTrue(mineshaft.biomes().contains(registry.getOrThrow(ResourceKey.create(Registries.BIOME,LogosDimension.id("cloudsea_root_caves")))),"Native mineshafts do not accept this dimension");
        h.succeed();
    }
    private CloudseaGameTests(){}
}
