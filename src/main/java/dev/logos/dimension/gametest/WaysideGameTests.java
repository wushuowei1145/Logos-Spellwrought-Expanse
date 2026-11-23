package dev.logos.dimension.gametest;

import dev.logos.dimension.*;
import dev.logos.dimension.ruins.WaysideBlocks;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pools.*;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 仅用于测试和截图；正式生成由原版 StructureSet、拼接和模板处理器负责。 */
public final class WaysideGameTests {
    public static final List<String> KINDS=List.of("bloom_waystone","fern_scriptorium","root_cradle","frost_resonator","wind_compass","tide_lens","reed_siphon","ravine_thread","rain_memory");
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS=DeferredRegister.create(Registries.TEST_FUNCTION,LogosDimension.ID);
    public static void register(IEventBus bus){
        var tests=Map.<String,Consumer<GameTestHelper>>of("wayside_templates_and_loot",WaysideGameTests::templates,"wayside_native_footings",WaysideGameTests::footings);
        tests.forEach((name,test)->FUNCTIONS.register(name,()->test));FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event)->{
            var env=event.registerEnvironment(LogosDimension.id("wayside"),new TestEnvironmentDefinition.AllOf());
            for(String name:tests.keySet())event.registerTest(LogosDimension.id(name),new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION,LogosDimension.id(name)),new TestData<>(env,Identifier.withDefaultNamespace("empty"),2000,0,true,Rotation.NONE,false,1,1,false,16)));
        });
    }
    public static void template(ServerLevel level,String kind,int variant,BlockPos origin){
        var processors=level.registryAccess().lookupOrThrow(Registries.PROCESSOR_LIST).getOrThrow(ResourceKey.create(Registries.PROCESSOR_LIST,LogosDimension.id("wayside_preserve")));
        var unbaked=SinglePoolElement.single("logos_dimension:wayside/"+kind+"_"+variant,processors);
        var element=unbaked.apply(StructureTemplatePool.Projection.RIGID);
        if(!element.place(level.getStructureManager(),level,level.structureManager(),level.getChunkSource().getGenerator(),origin,origin,Rotation.NONE,BoundingBox.infinite(),RandomSource.create(7711),LiquidSettings.IGNORE_WATERLOGGING,false))throw new IllegalStateException("模板放置失败："+kind);
    }
    public static StructureStart assemble(ServerLevel level,String kind,BlockPos at,long seed){
        var holder=level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(ResourceKey.create(Registries.STRUCTURE,LogosDimension.id("wayside_"+kind)));
        var source=level.getChunkSource();var gen=source.getGenerator();
        return holder.value().generate(holder,level.dimension(),level.registryAccess(),gen,gen.getBiomeSource(),source.randomState(),level.getStructureManager(),seed,new ChunkPos(at.getX()>>4,at.getZ()>>4),0,level,b->true);
    }
    private static void templates(GameTestHelper h){
        var level=h.getLevel().getServer().getLevel(LogosDimension.CLOUDSEA);int index=0;
        for(String kind:KINDS)for(int v=0;v<2;v++){
            var origin=new BlockPos(-24000+index++*32,200,24000);
            for(int x=0;x<18;x++)for(int z=0;z<18;z++){
                level.setBlockAndUpdate(origin.offset(x,3,z),Blocks.STONE.defaultBlockState());
                for(int y=4;y<18;y++)level.setBlock(origin.offset(x,y,z),Blocks.AIR.defaultBlockState(),Block.UPDATE_CLIENTS);
            }
            template(level,kind,v,origin);int caches=0;
            for(var p:BlockPos.betweenClosed(origin,origin.offset(17,18,17))){
                var state=level.getBlockState(p);h.assertTrue(!state.is(Blocks.JIGSAW),"遗留拼图块："+kind+" "+p);
                if(state.is(WaysideBlocks.BLOSSOM.get()))h.assertTrue(state.canSurvive(level,p),"花藤没有墙体："+kind+" "+p);
                if(level.getBlockEntity(p) instanceof RandomizableContainerBlockEntity cache){
                    boolean hasItem=false;for(int slot=0;slot<cache.getContainerSize();slot++)if(!cache.getItem(slot).isEmpty())hasItem=true;
                    h.assertTrue(hasItem,"遗迹战利品为空："+kind);caches++;
                }
            }
            h.assertTrue(caches==1,"每个模板应有一个藏匣："+kind+" "+caches);
        }
        h.succeed();
    }
    private static void footings(GameTestHelper h){
        var level=h.getLevel().getServer().getLevel(LogosDimension.CLOUDSEA);int i=0;
        for(String kind:KINDS){
            var start=assemble(level,kind,new BlockPos(-20000+(i++)*64,96,20000),79101324+i);
            h.assertTrue(start.isValid(),"原版拼接无起点："+kind);
            var supports=start.getPieces().stream().filter(p->p instanceof PoolElementStructurePiece pool&&pool.getElement() instanceof FeaturePoolElement).map(p->(PoolElementStructurePiece)p).toList();
            h.assertTrue(!supports.isEmpty(),"未拼入原版石脚："+kind);
            // 人为挖空三格，证明补柱由特征真正执行，而非原来就有地面。
            for(var p:supports)for(int dy=0;dy<3;dy++)level.setBlock(p.getPosition().below(2+dy),Blocks.AIR.defaultBlockState(),Block.UPDATE_CLIENTS);
            SettlementGameTests.place(level,start);
            for(var p:supports)for(int dy=0;dy<3;dy++)h.assertTrue(level.getBlockState(p.getPosition().below(2+dy)).is(WaysideBlocks.STONE.get()),"石脚没有接地："+kind+" "+p.getPosition());
        }
        h.succeed();
    }
    private WaysideGameTests(){}
}
