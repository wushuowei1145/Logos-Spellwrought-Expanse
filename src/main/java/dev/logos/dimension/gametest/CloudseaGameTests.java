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
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CloudseaGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS=DeferredRegister.create(Registries.TEST_FUNCTION,LogosDimension.ID);
    public static void register(IEventBus bus){
        FUNCTIONS.register("cloudsea_registry",()->CloudseaGameTests::registry);
        FUNCTIONS.register("cloudsea_terrain",()->CloudseaGameTests::terrain);
        FUNCTIONS.register("cloudsea_village",()->CloudseaGameTests::village);
        FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent e)->{Holder<TestEnvironmentDefinition<?>> env=e.registerEnvironment(LogosDimension.id("cloudsea_default"),new TestEnvironmentDefinition.AllOf());for(String name:List.of("cloudsea_registry","cloudsea_terrain","cloudsea_village"))e.registerTest(LogosDimension.id(name),new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION,LogosDimension.id(name)),new TestData<>(env,Identifier.withDefaultNamespace("empty"),600,0,true,Rotation.NONE,false,1,1,false,16)));});
    }
    private static ServerLevel level(GameTestHelper h){var level=h.getLevel().getServer().getLevel(LogosDimension.CLOUDSEA);h.assertTrue(level!=null,"Cloudsea did not load");return level;}
    private static void registry(GameTestHelper h){var level=level(h);h.assertTrue(level.getChunkSource().getGenerator() instanceof CloudseaChunkGenerator,"Wrong generator");var biomes=level.registryAccess().lookupOrThrow(Registries.BIOME);for(var b:CloudseaBiome.values()){var actual=biomes.getOrThrow(ResourceKey.create(Registries.BIOME,LogosDimension.id(b.id())));h.assertTrue(actual.value().getMobSettings().getMobs(net.minecraft.world.entity.MobCategory.MONSTER).isEmpty(),"New biome has mobs");}h.assertTrue(!BuiltInRegistries.ITEM.containsKey(LogosDimension.id("cloudsea_portal")),"Entrance was added");h.succeed();}
    private static void terrain(GameTestHelper h){var level=level(h);var gen=(CloudseaChunkGenerator)level.getChunkSource().getGenerator();int plants=0,air=0;for(int x=16;x<32;x++)for(int z=16;z<32;z++){var c=gen.terrain().column(x,z);level.getChunk(x>>4,z>>4);for(int y=-124;y<=c.highest()+24;y++){var state=level.getBlockState(new BlockPos(x,y,z));String id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();h.assertTrue(!id.startsWith("minecraft:")||id.equals("minecraft:air")||id.equals("minecraft:water")||id.equals("minecraft:bedrock"),"Vanilla material or mineral generated: "+id);if(y<0&&state.isAir())air++;if(y<0&&(state.is(CloudseaBlocks.ROOT.get())||state.is(CloudseaBlocks.EYE_FERN.get())||state.is(CloudseaBlocks.DEMON_REED.get())))plants++;}}h.assertTrue(air>100,"Underground has no caves");h.assertTrue(plants>0,"Cave flora is absent");h.assertTrue(level.getBiome(new BlockPos(20,-90,20)).is(LogosDimension.id("cloudsea_demon_depths")),"Core biome missing");h.succeed();}
    private static void village(GameTestHelper h){var level=level(h);var gen=(CloudseaChunkGenerator)level.getChunkSource().getGenerator();CloudseaSettlements.Village v=null;for(int z=-3;z<=3&&v==null;z++)for(int x=-3;x<=3&&v==null;x++)v=gen.settlements().village(gen.terrain().region(x,z));h.assertTrue(v!=null,"No settlement found");var house=v.houses().getFirst();level.getChunk(house.x()>>4,house.z()>>4);h.assertTrue(level.getBlockState(new BlockPos(house.x(),house.y(),house.z())).is(CloudseaBlocks.DARK_TIMBER.get()),"House has no floor");h.assertTrue(level.isEmptyBlock(new BlockPos(house.x(),house.y()+1,house.z()-5)),"Doorway is blocked");h.assertTrue(level.getBlockState(new BlockPos(house.x(),house.y()+10,house.z())).is(CloudseaBlocks.SLATE.get()),"Gabled roof missing");h.succeed();}
    private CloudseaGameTests(){}
}
