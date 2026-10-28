package dev.logos.dimension.gametest;

import dev.logos.dimension.*;
import dev.logos.dimension.elven.*;
import java.util.*;
import java.util.function.Consumer;
import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
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
        var tests=Map.<String,Consumer<GameTestHelper>>of("elven_registry",ElvenForestGameTests::registry,"elven_native_assembly",ElvenForestGameTests::assembly,"elven_living_world_tree",ElvenForestGameTests::living,"elven_bookshelf_placement",ElvenForestGameTests::bookshelf,"elven_native_roots",ElvenForestGameTests::roots,"elven_native_habitat",ElvenForestGameTests::habitat,"elven_climbing_rope",ElvenForestGameTests::rope,"elven_tree_houses",ElvenForestGameTests::houses);
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
        var manifest=manifest();var room=manifest.getAsJsonObject("world_tree").getAsJsonObject("interior");
        var jars=manifest.getAsJsonObject("world_tree").getAsJsonArray("cultivation_jars");
        h.assertTrue(jars!=null&&jars.size()>=16,"玻璃花房缺少培养罐清单");
        for(var jar:jars){
            var p=jar.getAsJsonObject().getAsJsonArray("pos");
            var at=tree.pos(p.get(0).getAsInt(),p.get(1).getAsInt(),p.get(2).getAsInt());
            h.assertTrue(BuiltInRegistries.BLOCK.getKey(l.getBlockState(at).getBlock()).equals(Identifier.fromNamespaceAndPath("logos","cultivation_jar")),"玻璃花房培养罐方块缺失："+at);
            var be=l.getBlockEntity(at);
            h.assertTrue(be!=null&&!be.saveCustomOnly(l.registryAccess()).getStringOr("plant","").isEmpty(),"玻璃花房培养罐内未种植植物："+at);
        }
        for(var floor:room.getAsJsonArray("floors")){
            var pos=tree.pos(7,floor.getAsInt(),0);
            h.assertTrue(l.getBlockState(pos).is(ElvenBlocks.PLANKS.get())||l.getBlockState(pos).is(ElvenBlocks.INLAY.get()),"分层楼板缺失");
            h.assertTrue(l.getBlockState(tree.pos(0,floor.getAsInt(),0)).isAir(),"中央采光井封堵");
        }
        int stairIndex=0;
        var walker=new net.minecraft.world.entity.player.Player(l,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"elven-stair-test")){
            @Override public GameType gameMode(){return GameType.SURVIVAL;}
            @Override public boolean isClientAuthoritative(){return false;}
        };
        for(var step:room.getAsJsonArray("stairs")){
            var a=step.getAsJsonArray();var pos=tree.pos(a.get(0).getAsInt(),a.get(1).getAsInt(),a.get(2).getAsInt());
            h.assertTrue(l.getBlockState(pos).is(Blocks.BIRCH_SLAB),"旋转楼梯断开");
            double top=l.getBlockState(pos).getCollisionShape(l,pos).max(Direction.Axis.Y)+pos.getY()-tree.center().getY();
            h.assertTrue(Math.abs(top-(2+stairIndex++*.5))<.001,"旋转楼梯实际碰撞面未逐半格上升");
            h.assertTrue(l.getBlockState(pos.above()).getCollisionShape(l,pos.above()).isEmpty()&&l.getBlockState(pos.above(2)).getCollisionShape(l,pos.above(2)).isEmpty(),"旋转楼梯头顶空间堵塞");
            double targetY=tree.center().getY()+top;
            if(stairIndex==1)walker.setPos(pos.getX()+.5,targetY,pos.getZ()+.5);
            else{
                walker.setOnGround(true);
                walker.move(net.minecraft.world.entity.MoverType.SELF,new net.minecraft.world.phys.Vec3(pos.getX()+.5-walker.getX(),-.05,pos.getZ()+.5-walker.getZ()));
                h.assertTrue(Math.abs(walker.getY()-targetY)<.02&&Math.hypot(walker.getX()-pos.getX()-.5,walker.getZ()-pos.getZ()-.5)<.05,"玩家无法无跳跃步行通过旋转楼梯第 "+stairIndex+" 级："+walker.position()+" 目标 "+pos+" 顶面 "+targetY);
            }
        }
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
    private static JsonObject manifest(){
        var input=Objects.requireNonNull(ElvenForestGameTests.class.getResourceAsStream("/data/logos_dimension/elven_forest_manifest.json"));
        try(var reader=new InputStreamReader(input,StandardCharsets.UTF_8)){return JsonParser.parseReader(reader).getAsJsonObject();}
        catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    private static void rope(GameTestHelper h){
        var l=h.getLevel();var top=h.absolutePos(new BlockPos(5,12,5));
        l.setBlockAndUpdate(top,Blocks.OAK_PLANKS.defaultBlockState());
        for(int i=1;i<=9;i++)l.setBlockAndUpdate(top.below(i),ElvenBlocks.ROPE.get().defaultBlockState());
        var player=h.makeMockPlayer(GameType.SURVIVAL);var foot=top.below(7);player.setPos(foot.getX()+.5,foot.getY(),foot.getZ()+.5);
        h.assertTrue(player.onClimbable(),"玩家未识别悬挂绳梯的攀爬标签");
        player.setDeltaMovement(0,-1,0);double start=player.getY();player.travel(net.minecraft.world.phys.Vec3.ZERO);
        h.assertTrue(player.getY()>start-.3,"绳梯未减缓下落");
        player.setPos(foot.getX()+.5,foot.getY(),foot.getZ()+.5);player.setShiftKeyDown(true);player.setDeltaMovement(0,-1,0);player.travel(net.minecraft.world.phys.Vec3.ZERO);
        h.assertTrue(player.getY()>=foot.getY()-.01,"潜行未能抓住绳梯");
        player.setShiftKeyDown(false);player.setJumping(true);player.setDeltaMovement(0,0,0);
        player.travel(net.minecraft.world.phys.Vec3.ZERO);player.travel(net.minecraft.world.phys.Vec3.ZERO);
        h.assertTrue(player.getY()>foot.getY()+.08,"按跳跃未能沿绳向上攀爬");
        l.removeBlock(top,false);
        for(int i=1;i<=9;i++)h.assertTrue(l.isEmptyBlock(top.below(i)),"上方锚点拆除后留下悬空绳梯");
        var stone=h.absolutePos(new BlockPos(9,2,9));l.setBlockAndUpdate(stone.below(),Blocks.STONE.defaultBlockState());
        h.assertTrue(!ElvenBlocks.BOUGH_LIGHT.get().defaultBlockState().canSurvive(l,stone),"叶辉错误支持石头土壤");
        h.succeed();
    }
    private static void houses(GameTestHelper h){
        var l=level(h);var giants=manifest().getAsJsonArray("giants");int tested=0;
        for(var item:giants){
            var row=item.getAsJsonObject();String name=row.get("template").getAsString();
            if(!name.equals("giant_0_home")&&!name.equals("giant_3_home"))continue;
            var template=l.getStructureManager().getOrCreate(LogosDimension.id("elven/"+name));var origin=new BlockPos(19400+tested*140,250,15000);
            template.placeInWorld(l,origin,origin,new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings(),RandomSource.create(3),Block.UPDATE_CLIENTS);
            var home=row.getAsJsonObject("house");var rope=home.getAsJsonArray("rope");int x=rope.get(0).getAsInt(),z=rope.get(2).getAsInt();
            for(int y=rope.get(1).getAsInt();y<=home.get("rope_top").getAsInt();y++){
                var at=origin.offset(x,y,z);h.assertTrue(l.getBlockState(at).is(ElvenBlocks.ROPE.get())&&l.getBlockState(at).canSurvive(l,at),"树屋绳梯断开或无锚点");
            }
            int floor=home.get("floor").getAsInt();
            h.assertTrue(l.getBlockState(origin.offset(x,floor+1,z-1)).isAir(),"绳口与阳台不连通");
            h.assertTrue(l.getBlockState(origin.offset(x-1,floor+1,z-3)).isAir(),"树屋入口被枝叶堵住");
            h.assertTrue(l.getBlockState(origin.offset(x-3,floor+3,z-1)).is(ElvenBlocks.LAMP.get()),"树屋阳台灯缺失");tested++;
        }
        h.assertTrue(tested==2,"未检查针阔叶两种树屋");h.succeed();
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
