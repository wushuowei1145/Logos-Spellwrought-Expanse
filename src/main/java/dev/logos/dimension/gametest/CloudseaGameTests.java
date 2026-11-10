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
        var tests=Map.<String,Consumer<GameTestHelper>>of("cloudsea_registry",CloudseaGameTests::registry,"cloudsea_terrain",CloudseaGameTests::terrain,"cloudsea_village",CloudseaGameTests::village,"cloudsea_tree_blocks",CloudseaGameTests::forest,"journey_seasons",CloudseaGameTests::seasons,"journey_log_families",CloudseaGameTests::logFamilies,"journey_luminous_plants",CloudseaGameTests::luminousPlants);
        tests.forEach((name,test)->FUNCTIONS.register(name,()->test));FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent e)->{var env=e.registerEnvironment(LogosDimension.id("cloudsea_default"),new TestEnvironmentDefinition.AllOf());for(String name:tests.keySet())e.registerTest(LogosDimension.id(name),new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION,LogosDimension.id(name)),new TestData<>(env,Identifier.withDefaultNamespace("empty"),1600,0,true,Rotation.NONE,false,1,1,false,16)));});
    }
    private static ServerLevel level(GameTestHelper h){return Objects.requireNonNull(h.getLevel().getServer().getLevel(LogosDimension.CLOUDSEA));}
    private static void registry(GameTestHelper h){
        var level=level(h);h.assertTrue(NativeWorlds.vanilla(level),"Journey does not use minecraft:noise");
        var biomes=level.registryAccess().lookupOrThrow(Registries.BIOME);
        for(var b:CloudseaBiome.values())biomes.getOrThrow(ResourceKey.create(Registries.BIOME,LogosDimension.id(b.id())));
        h.assertTrue(CloudseaBiome.values().length==34,"New forest, glade and peak biomes missing");
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
    /**
     * 找一块四周都是目标群系的样地。locateBiome 搜不到内部点时会退回边界点，样方就可能跨进相邻群系；
     * 相邻群系的树冠还会伸过边界，所以 reach 要比样方再大一圈。找不到就让测试失败，不退回边界点。
     */
    private static BlockPos interior(ServerLevel level,CloudseaBiome biome,int reach){
        var target=ResourceKey.create(Registries.BIOME,LogosDimension.id(biome.id()));
        var generator=level.getChunkSource().getGenerator();var source=generator.getBiomeSource();var random=level.getChunkSource().randomState();
        for(int ring=0;ring*32<=14000;ring++)for(int dx=-ring;dx<=ring;dx++)for(int dz=-ring;dz<=ring;dz+=Math.abs(dx)==ring||ring==0?1:2*ring){
            int x=dx*32,z=dz*32;
            if(!source.getNoiseBiome(x>>2,24,z>>2,random.sampler()).is(target))continue;
            boolean inside=true;
            for(int ox=-reach;inside&&ox<=reach;ox+=8)for(int oz=-reach;inside&&oz<=reach;oz+=8){
                int y=generator.getBaseHeight(x+ox,z+oz,net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG,level,random);
                inside=source.getNoiseBiome((x+ox)>>2,y>>2,(z+oz)>>2,random.sampler()).is(target);
            }
            if(inside)return new BlockPos(x,96,z);
        }
        throw new IllegalStateException("No interior found for biome: "+biome.id());
    }
    private static void forest(GameTestHelper h){
        var level=level(h);var at=interior(level,CloudseaBiome.ANCIENT_BEECH,32);int logs=0,leaves=0,covered=0,eligible=0,water=0,luminous=0;var seen=new java.util.HashMap<String,Integer>();
        for(int x=at.getX()-16;x<at.getX()+16;x++)for(int z=at.getZ()-16;z<at.getZ()+16;z++){
            boolean plant=false;
            int gy=level.getChunkSource().getGenerator().getBaseHeight(x,z,net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG,level,level.getChunkSource().randomState());
            var gp=new BlockPos(x,gy,z);if(gy<=63)water++;
            if(level.isEmptyBlock(gp)&&level.getBlockState(gp.below()).isFaceSturdy(level,gp.below(),Direction.UP))eligible++;
            seen.merge(level.getBiome(gp).unwrapKey().orElseThrow().identifier().getPath(),1,Integer::sum);
            for(int y=35;y<250;y++){
                var state=level.getBlockState(new BlockPos(x,y,z));var block=state.getBlock();
                if(state.is(net.minecraft.tags.BlockTags.LOGS)){
                    logs++;
                    h.assertTrue(state.is(CloudseaBlocks.TRUNKS.get(CloudseaBiome.ANCIENT_BEECH).get()),"Native beech woodland uses an unrelated trunk: "+state);
                }
                if(block instanceof SeasonalLeavesBlock)leaves++;
                if(state.is(CloudseaBlocks.STARDEW.get())||state.is(CloudseaBlocks.LUMEN_FERN.get()))luminous++;
                if(block instanceof JourneyPlantBlock)h.assertTrue(state.canSurvive(level,new BlockPos(x,y,z)),"Native plant has unsuitable ground at "+x+","+y+","+z+": "+state);
                if(block instanceof JourneyPlantBlock||state.is(net.minecraft.world.level.block.Blocks.MOSS_CARPET)||state.is(net.minecraft.world.level.block.Blocks.LEAF_LITTER))plant=true;
            }if(plant)covered++;
        }
        h.assertTrue(logs>70&&leaves>500,"Native features did not produce a dense connected woodland: "+logs+"/"+leaves);
        System.out.println("NATIVE_FOREST "+at+" logs="+logs+" leaves="+leaves+" luminous="+luminous+" covered="+covered+" eligible="+eligible+" water="+water+" biomes="+seen);
        h.assertTrue(luminous>0,"Native woodland lacks luminous plants");h.assertTrue(covered>400,"Forest floor lacks vegetation: "+covered);h.succeed();
    }
    private static void village(GameTestHelper h){
        var level=level(h);var registry=level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        h.assertTrue(registry.getOrThrow(ResourceKey.create(Registries.STRUCTURE,LogosDimension.id("journey_hamlet"))).value() instanceof net.minecraft.world.level.levelgen.structure.structures.JigsawStructure,"Hamlet does not use native jigsaw structures");
        h.assertTrue(registry.getOrThrow(ResourceKey.create(Registries.STRUCTURE,LogosDimension.id("journey_town"))).value() instanceof net.minecraft.world.level.levelgen.structure.structures.JigsawStructure,"Town missing");h.succeed();
    }
    private static void luminousPlants(GameTestHelper h){
        var l=h.getLevel();var a=h.absolutePos(new BlockPos(3,4,3));var b=h.absolutePos(new BlockPos(10,4,10));
        l.setBlockAndUpdate(a.below(),net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState());l.setBlockAndUpdate(b.below(),net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState());
        l.setBlockAndUpdate(a,CloudseaBlocks.STARDEW.get().defaultBlockState());l.setBlockAndUpdate(b,CloudseaBlocks.LUMEN_FERN.get().defaultBlockState());
        h.assertTrue(l.getBlockState(a).getLightEmission()==2&&l.getBlockState(b).getLightEmission()==3,"Plants lack subtle block light");
        // 光照在后台线程池里异步传播，和同批测试生成区块的任务抢线程；测试服务器又不限速跑刻，固定等几刻不一定传播完，所以轮询到亮了为止
        h.succeedWhen(RealTimeWait.paced(()->{
            h.assertTrue(l.getBrightness(net.minecraft.world.level.LightLayer.BLOCK,a)>=2&&l.getBrightness(net.minecraft.world.level.LightLayer.BLOCK,a.east())>=1,"Stardew does not illuminate nearby ground");
            h.assertTrue(l.getBrightness(net.minecraft.world.level.LightLayer.BLOCK,b)>=3&&l.getBrightness(net.minecraft.world.level.LightLayer.BLOCK,b.east())>=2,"Fern does not illuminate nearby ground");
        }));
    }
    private static void logFamilies(GameTestHelper h){
        var level=h.getLevel();var leaves=new java.util.ArrayList<BlockPos>();int i=0;
        h.assertTrue(CloudseaBlocks.TRUNKS.size()==CloudseaBiome.values().length,"Missing named log families");
        for(var b:CloudseaBiome.values()){
            var trunk=CloudseaBlocks.TRUNKS.get(b).get();var state=trunk.defaultBlockState();
            h.assertTrue(trunk instanceof net.minecraft.world.level.block.RotatedPillarBlock&&state.is(net.minecraft.tags.BlockTags.LOGS),"Log lacks native pillar or leaves support: "+b);
            for(var axis:Direction.Axis.values())h.assertTrue(state.setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS,axis).is(net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE),"Log does not support axes: "+b);
            var pos=h.absolutePos(new BlockPos(2+i%6*2,3,2+i/6*2));i++;
            level.setBlockAndUpdate(pos.above(),CloudseaBlocks.LEAVES.get(b).get().defaultBlockState());
            level.setBlockAndUpdate(pos,state);leaves.add(pos.above());
        }
        h.runAfterDelay(4,()->{for(var p:leaves)h.assertTrue(level.getBlockState(p).getValue(net.minecraft.world.level.block.LeavesBlock.DISTANCE)==1,"Matching leaves do not recognise new log support at "+p+": "+level.getBlockState(p));h.succeed();});
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
