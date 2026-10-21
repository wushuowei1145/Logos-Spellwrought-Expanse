package dev.logos.dimension.gametest;

import dev.logos.dimension.*;
import dev.logos.dimension.elven.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pools.*;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 原版拼接、房间净空、实际光照及家具放置回归，仅在测试或开发时调用。 */
public final class ElvenForestGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS=DeferredRegister.create(Registries.TEST_FUNCTION,LogosDimension.ID);
    public static final BlockPos STUDY=new BlockPos(-2032,80,-944);
    public record Tree(StructureStart start,BlockPos center,Rotation rotation) {
        public BlockPos pos(int x,int y,int z){return center.offset(new BlockPos(x,y,z).rotate(rotation));}
    }
    public static void register(IEventBus bus){
        var tests=Map.<String,Consumer<GameTestHelper>>of("elven_registry",ElvenForestGameTests::registry,"elven_native_assembly",ElvenForestGameTests::assembly,"elven_living_world_tree",ElvenForestGameTests::living,"elven_bookshelf_placement",ElvenForestGameTests::bookshelf,"elven_native_roots",ElvenForestGameTests::roots,"elven_native_habitat",ElvenForestGameTests::habitat);
        tests.forEach((n,t)->FUNCTIONS.register(n,()->t));FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent e)->{var env=e.registerEnvironment(LogosDimension.id("elven"),new TestEnvironmentDefinition.AllOf());for(var n:tests.keySet())e.registerTest(LogosDimension.id(n),new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION,LogosDimension.id(n)),new TestData<>(env,Identifier.withDefaultNamespace("empty"),6000,0,true,Rotation.NONE,false,1,1,false,16)));});
    }
    public static ServerLevel level(GameTestHelper h){return Objects.requireNonNull(h.getLevel().getServer().getLevel(LogosDimension.CLOUDSEA));}
    public static Tree assemble(ServerLevel l,BlockPos at,long seed){
        var holder=l.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(ResourceKey.create(Registries.STRUCTURE,LogosDimension.id("elven_world_tree")));
        var source=l.getChunkSource();var gen=source.getGenerator();
        var start=holder.value().generate(holder,l.dimension(),l.registryAccess(),gen,gen.getBiomeSource(),source.randomState(),l.getStructureManager(),seed,new ChunkPos(at.getX()>>4,at.getZ()>>4),0,l,b->true);
        if(!start.isValid())throw new IllegalStateException("世界树未能进行原版拼接");
        var root=(PoolElementStructurePiece)start.getPieces().getFirst();
        return new Tree(start,root.getPosition().offset(new BlockPos(16,4,16).rotate(root.getRotation())),root.getRotation());
    }
    public static Tree placeStudy(ServerLevel l){var tree=assemble(l,STUDY,81);SettlementGameTests.place(l,tree.start());return tree;}
    public static long roots(Tree t){return t.start().getPieces().stream().filter(p->p instanceof PoolElementStructurePiece q&&q.getElement() instanceof FeaturePoolElement).count();}
    private static void registry(GameTestHelper h){
        var l=level(h);h.assertTrue(NativeWorlds.vanilla(l),"精灵森林必须使用原版噪声地形");
        l.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME,LogosDimension.id("elven_forest")));
        h.assertTrue(l.getMaxY()>=447,"世界树缺少上方空间");
        h.assertTrue(ElvenBlocks.LAMP.get().defaultBlockState().getLightEmission()==15&&ElvenBlocks.INLAY.get().defaultBlockState().getLightEmission()==15,"树心光源未真正发光");h.succeed();
    }
    private static void assembly(GameTestHelper h){
        for(long seed:new long[]{0,81}){
            var t=assemble(level(h),STUDY,seed);long tiles=t.start().getPieces().stream().filter(p->p instanceof PoolElementStructurePiece q&&q.getElement() instanceof SinglePoolElement).count();
            long roots=roots(t);long expected=0;
            for(var p:t.start().getPieces())if(p instanceof PoolElementStructurePiece piece&&piece.getElement() instanceof SinglePoolElement single)
                expected+=level(h).getStructureManager().getOrCreate(single.getTemplateLocation()).filterBlocks(piece.getPosition(),new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings().setRotation(piece.getRotation()),Blocks.JIGSAW).stream().filter(b->b.nbt()!=null&&b.nbt().getStringOr("pool","").equals("logos_dimension:elven/roots")).count();
            h.assertTrue(tiles==343,"原版碰撞遗漏世界树部件："+tiles);h.assertTrue(roots>=1500&&roots==expected,"世界树根部支撑接入数与模板不符："+roots+" / "+expected);
            h.assertTrue(t.start().getBoundingBox().maxY()<=level(h).getMaxY(),"世界树被维度高度截断");
            System.out.println("ELVEN_ASSEMBLY seed="+seed+" tiles="+tiles+" roots="+roots+" bounds="+t.start().getBoundingBox());
        }h.succeed();
    }
    private static void living(GameTestHelper h){
        var l=level(h);var tree=assemble(l,STUDY,81);var marker=tree.pos(-95,7,-95);l.setBlock(marker,Blocks.CHEST.defaultBlockState(),Block.UPDATE_CLIENTS);
        SettlementGameTests.place(l,tree.start());
        // 异维度测试没有玩家，保留树心区块直到异步光照传播完成。
        int cx=tree.center().getX()>>4,cz=tree.center().getZ()>>4;
        for(int x=cx-1;x<=cx+1;x++)for(int z=cz-1;z<=cz+1;z++)l.setChunkForced(x,z,true);
        h.assertTrue(l.getBlockState(tree.pos(0,1,0)).is(ElvenBlocks.INLAY.get()),"树心地面光源被根部覆盖");
        h.assertTrue(roots(tree)>=1500,"已放置世界树缺少根部子件");
        h.assertTrue(l.getBlockState(marker).is(Blocks.CHEST),"结构外部空位覆盖了原方块");
        for(int x=-4;x<=4;x++)for(int z=-4;z<=4;z++)if(x*x+z*z<=16)for(int y=2;y<=9;y++)h.assertTrue(l.getBlockState(tree.pos(x,y,z)).getCollisionShape(l,tree.pos(x,y,z)).isEmpty(),"中央通行区域被占用");
        for(int x:new int[]{-7,-6,-5})h.assertTrue(l.getBlockState(tree.pos(x,2,9)).is(ElvenBlocks.BOOKCASE.get())&&l.getBlockState(tree.pos(x,3,9)).is(ElvenBlocks.BOOKCASE.get()),"原版放置丢失书架上下半部");
        long unsupported=tree.start().getPieces().stream().filter(p->p instanceof PoolElementStructurePiece q&&q.getElement() instanceof FeaturePoolElement).map(p->((PoolElementStructurePiece)p).getPosition().below(2)).filter(p->!l.getBlockState(p).isSolid()).count();
        h.assertTrue(unsupported==0,"根部仍有悬空支点："+unsupported);
        h.succeedWhen(()->{
            int min=15;
            for(int x:new int[]{-4,0,4})for(int z:new int[]{-4,0,4}){int light=l.getBrightness(LightLayer.BLOCK,tree.pos(x,3,z));min=Math.min(min,light);h.assertTrue(light>=8,"树心中央方块光照不足："+light);}
            System.out.println("ELVEN_ROOM center="+tree.center()+" min_block_light="+min+" unsupported_roots="+unsupported);
            for(int x=cx-1;x<=cx+1;x++)for(int z=cz-1;z<=cz+1;z++)l.setChunkForced(x,z,false);
        });
    }
    private static void bookshelf(GameTestHelper h){
        var l=level(h);var pos=new BlockPos(19000,280,15000);l.setBlock(pos.below(),Blocks.STONE.defaultBlockState(),Block.UPDATE_ALL);l.setBlock(pos,Blocks.AIR.defaultBlockState(),Block.UPDATE_ALL);l.setBlock(pos.above(),Blocks.AIR.defaultBlockState(),Block.UPDATE_ALL);
        var item=new ItemStack(ElvenBlocks.BOOKCASE.get());var context=new DirectionalPlaceContext(l,pos,Direction.DOWN,item,Direction.NORTH);
        h.assertTrue(((BlockItem)item.getItem()).place(context).consumesAction(),"书架物品放置失败");
        var low=l.getBlockState(pos);var high=l.getBlockState(pos.above());h.assertTrue(low.is(ElvenBlocks.BOOKCASE.get())&&high.is(ElvenBlocks.BOOKCASE.get())&&high.getValue(ElvenBookshelfBlock.HALF)==DoubleBlockHalf.UPPER,"书架没有成对生成");
        h.assertTrue(low.getValue(ElvenBookshelfBlock.FACING)==high.getValue(ElvenBookshelfBlock.FACING),"书架上下朝向不同");
        l.setBlock(pos.below(),Blocks.AIR.defaultBlockState(),Block.UPDATE_ALL);h.assertTrue(l.getBlockState(pos).isAir()&&l.getBlockState(pos.above()).isAir(),"失去支撑后书架没有成对移除");h.succeed();
    }
    private static void roots(GameTestHelper h){
        var l=level(h);var feature=l.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE).getOrThrow(ResourceKey.create(Registries.PLACED_FEATURE,LogosDimension.id("elven_roots"))).value();
        for(int i=0;i<2;i++){
            var cap=new BlockPos(19020+i*4,240,15000);int gap=i==0?4:32;
            for(int y=0;y<=gap;y++)l.setBlock(cap.below(y),Blocks.WATER.defaultBlockState(),Block.UPDATE_CLIENTS);
            l.setBlock(cap,ElvenBlocks.LOG.get().defaultBlockState(),Block.UPDATE_CLIENTS);l.setBlock(cap.below(gap+1),Blocks.DIRT.defaultBlockState(),Block.UPDATE_CLIENTS);
            feature.place(l,l.getChunkSource().getGenerator(),RandomSource.create(3),cap.above());
            for(int y=1;y<=gap;y++)h.assertTrue(l.getBlockState(cap.below(y)).is(ElvenBlocks.LOG.get()),"树根未连接实地");
            h.assertTrue(l.getBlockState(cap.below(gap+1)).is(Blocks.DIRT),"树根挖穿原土层");
        }h.succeed();
    }
    private static void habitat(GameTestHelper h){
        var l=level(h);var source=l.getChunkSource();var gen=source.getGenerator();
        var at=NativeWorlds.locateBiome(l,"elven_forest");
        var holder=l.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(ResourceKey.create(Registries.STRUCTURE,LogosDimension.id("elven_world_tree")));
        var set=l.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET).getOrThrow(ResourceKey.create(Registries.STRUCTURE_SET,LogosDimension.id("elven_world_trees"))).value();
        var placement=(net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement)set.placement();
        int spacing=placement.spacing(),gx=Math.floorDiv(at.getX()>>4,spacing),gz=Math.floorDiv(at.getZ()>>4,spacing);
        for(int radius=0;radius<=16;radius++)for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++){
            if(Math.max(Math.abs(dx),Math.abs(dz))!=radius)continue;
            var chunk=placement.getPotentialStructureChunk(l.getSeed(),(gx+dx)*spacing,(gz+dz)*spacing);
            if(!gen.getBiomeSource().getNoiseBiome(chunk.x()*4+2,24,chunk.z()*4+2,source.randomState().sampler()).is(ResourceKey.create(Registries.BIOME,LogosDimension.id("elven_forest"))))continue;
            var start=holder.value().generate(holder,l.dimension(),l.registryAccess(),gen,gen.getBiomeSource(),source.randomState(),l.getStructureManager(),l.getSeed(),chunk,0,l,holder.value().biomes()::contains);
            if(!start.isValid())continue;
            long tiles=start.getPieces().stream().filter(p->p instanceof PoolElementStructurePiece q&&q.getElement() instanceof SinglePoolElement).count();
            h.assertTrue(tiles==343,"自然候选点树冠被截断："+tiles);
            System.out.println("ELVEN_HABITAT biome="+at+" native_candidate="+chunk+" tiles="+tiles);h.succeed();return;
        }
        h.assertTrue(false,"精灵森林附近未找到通过真实群系过滤的原版世界树候选点");
    }
    private ElvenForestGameTests(){}
}
