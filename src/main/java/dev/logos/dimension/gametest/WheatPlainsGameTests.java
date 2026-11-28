package dev.logos.dimension.gametest;

import com.mojang.authlib.GameProfile;
import dev.logos.dimension.*;
import dev.logos.dimension.wheat.*;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.*;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pools.*;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 只在测试世界取样和搭台；正式放置走原版，选址使用维护者授权的唯一性检查。 */
public final class WheatPlainsGameTests {
    public static final ResourceKey<Biome> BIOME=ResourceKey.create(Registries.BIOME,LogosDimension.id("wheat_plains"));
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS=DeferredRegister.create(Registries.TEST_FUNCTION,LogosDimension.ID);
    public static void register(IEventBus bus){
        var tests=Map.<String,Consumer<GameTestHelper>>of("wheat_distribution",WheatPlainsGameTests::distribution,
                "wheat_natural_cover",WheatPlainsGameTests::natural,"wheat_circle_terrain",WheatPlainsGameTests::circle,
                "wheat_first_encounter",WheatPlainsGameTests::encounter,"wheat_native_assembly",WheatPlainsGameTests::assembly,
                "wheat_unique_per_patch",WheatPlainsGameTests::unique);
        tests.forEach((name,test)->FUNCTIONS.register(name,()->test));FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event)->{
            var env=event.registerEnvironment(LogosDimension.id("wheat_plains"),new TestEnvironmentDefinition.AllOf());
            for(String name:tests.keySet())event.registerTest(LogosDimension.id(name),new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION,LogosDimension.id(name)),new TestData<>(env,Identifier.withDefaultNamespace("empty"),2400,0,true,Rotation.NONE,false,1,1,false,16)));
        });
    }
    private static ServerLevel world(GameTestHelper h){return Objects.requireNonNull(h.getLevel().getServer().getLevel(LogosDimension.CLOUDSEA));}
    private static Climate.TargetPoint surface(Climate.TargetPoint p){return new Climate.TargetPoint(p.temperature(),p.humidity(),p.continentalness(),p.erosion(),0,p.weirdness());}
    private static void distribution(GameTestHelper h){
        var level=world(h);var source=(MultiNoiseBiomeSource)level.getChunkSource().getGenerator().getBiomeSource();
        for(long seed:new long[]{0,4315130594838060947L}){
            var random=RandomState.create(level.registryAccess(),ResourceKey.create(Registries.NOISE_SETTINGS,LogosDimension.id("journey")),seed);
            int samples=0,wheat=0,mushroom=0;
            for(int x=-32768;x<32768;x+=256)for(int z=-32768;z<32768;z+=256){
                var p=surface(random.sampler().sample(x>>2,24,z>>2));
                boolean rare=source.getNoiseBiome(p).is(BIOME),vanilla=p.continentalness()<-10500;
                if(rare){
                    wheat++;
                    h.assertTrue(p.continentalness()>=500&&p.erosion()>=4500&&p.temperature()>=3500&&p.temperature()<=5500
                            &&p.humidity()>=-6000&&p.humidity()<=2000&&p.weirdness()==20000,"麦田泄漏到海洋、山地或普通气候区");
                }
                if(vanilla)mushroom++;samples++;
            }
            System.out.println("WHEAT_DISTRIBUTION seed="+seed+" samples="+samples+" wheat="+wheat+" mushroom_comparison="+mushroom);
            h.assertTrue(wheat>0&&wheat<samples*.005,"麦田不够稀有或完全不生成");
        }
        h.succeed();
    }
    /** 只查原版气候，找到能容纳完整怪圈的内陆平原，再让原版生成实际区块。 */
    public static BlockPos findInterior(ServerLevel level){
        var source=(MultiNoiseBiomeSource)level.getChunkSource().getGenerator().getBiomeSource();
        var sampler=level.getChunkSource().randomState().sampler();
        for(int ring=0;ring<=160;ring++)for(int dx=-ring;dx<=ring;dx++)for(int dz=-ring;dz<=ring;dz+=Math.abs(dx)==ring||ring==0?1:2*ring){
            int x=dx*256,z=dz*256;
            if(!source.getNoiseBiome(surface(sampler.sample(x>>2,24,z>>2))).is(BIOME))continue;
            boolean inside=true;
            for(int ox:new int[]{-80,0,80})for(int oz:new int[]{-80,0,80})
                if(!source.getNoiseBiome(surface(sampler.sample((x+ox)>>2,24,(z+oz)>>2))).is(BIOME))inside=false;
            if(inside)return new BlockPos(x,96,z);
        }
        throw new IllegalStateException("未在 40960 格内找到完整麦田样地");
    }
    private static void natural(GameTestHelper h){
        var level=world(h);var center=findInterior(level);int crops=0,eligible=0,low=320,high=-64;
        level.getChunkAt(center);
        int cy=level.getHeight(Heightmap.Types.OCEAN_FLOOR,center.getX(),center.getZ());
        System.out.println("WHEAT_DIAGNOSTIC center="+center+" floor="+cy+" state="+level.getBlockState(new BlockPos(center.getX(),cy-1,center.getZ()))+" biome="+level.getBiome(new BlockPos(center.getX(),cy,center.getZ())).unwrapKey()+" climate="+level.getChunkSource().randomState().sampler().sample(center.getX()>>2,cy>>2,center.getZ()>>2));
        h.assertTrue(level.getBiome(new BlockPos(center.getX(),cy-32,center.getZ())).is(LogosDimension.id("cloudsea_root_caves")),"麦田标记侵入了地下洞穴群系");
        for(int x=center.getX()-16;x<center.getX()+16;x++)for(int z=center.getZ()-16;z<center.getZ()+16;z++){
            level.getChunk(x>>4,z>>4);
            int y=level.getHeight(Heightmap.Types.OCEAN_FLOOR,x,z);var top=new BlockPos(x,y-1,z);
            low=Math.min(low,y);high=Math.max(high,y);
            // 麦穗没有实体碰撞，高度图停在耕地的上一格。
            // 原版露天洞口可能落到地下群系，检查只统计仍在麦田地表的可耕位置。
            if(!level.getBiome(top.above()).is(BIOME))continue;
            if(level.getBlockState(top).is(Blocks.FARMLAND)||level.getBlockState(top).is(Blocks.GRASS_BLOCK)||level.getBlockState(top).is(Blocks.DIRT)){
                eligible++;if(level.getBlockState(top.above()).is(Blocks.WHEAT))crops++;
            }
        }
        System.out.println("WHEAT_NATURAL center="+center+" wheat="+crops+" eligible="+eligible);
        h.assertTrue(eligible>950&&crops>=eligible*.995,"自然麦田未连续覆盖地表");
        h.assertTrue(high-low<=5,"麦田没有落在平坦地形上");
        h.succeed();
    }
    public static void template(ServerLevel level,BlockPos origin,Rotation rotation){
        var processors=level.registryAccess().lookupOrThrow(Registries.PROCESSOR_LIST).getOrThrow(ResourceKey.create(Registries.PROCESSOR_LIST,LogosDimension.id("crop_circle_ground")));
        var pool=SinglePoolElement.single("logos_dimension:wheat/team_crop_circle",processors).apply(StructureTemplatePool.Projection.RIGID);
        if(!pool.place(level.getStructureManager(),level,level.structureManager(),level.getChunkSource().getGenerator(),origin,origin,rotation,BoundingBox.infinite(),RandomSource.create(72311917),LiquidSettings.IGNORE_WATERLOGGING,false))throw new IllegalStateException("怪圈模板放置失败");
    }
    private static void assembly(GameTestHelper h){
        var level=world(h);var at=findInterior(level);var source=level.getChunkSource();var generator=source.getGenerator();
        var holder=level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(ResourceKey.create(Registries.STRUCTURE,LogosDimension.id("wheat_crop_circle")));
        for(long seed=0;seed<4;seed++){
            var selected=WheatCirclePlacement.patch(context(level,new ChunkPos(at.getX()>>4,at.getZ()>>4),seed)).winner().orElseThrow();
            var chunk=new ChunkPos(selected.blockX()>>4,selected.blockZ()>>4);
            StructureStart start=holder.value().generate(holder,level.dimension(),level.registryAccess(),generator,generator.getBiomeSource(),source.randomState(),level.getStructureManager(),seed,chunk,0,level,b->b.is(BIOME));
            h.assertTrue(start.isValid()&&start.getPieces().size()==1,"原版拼接没有输出怪圈部件");
            var box=start.getBoundingBox();
            h.assertTrue(box.getXSpan()==128&&box.getZSpan()==128,"旋转后的怪圈尺寸错误");
            // 检查整个模板而不是只有图案中的非空像素，避免邻近群系留下缺口。
            for(int x=box.minX()-8;x<=box.maxX()+8;x+=4)for(int z=box.minZ()-8;z<=box.maxZ()+8;z+=4)
                h.assertTrue(((MultiNoiseBiomeSource)generator.getBiomeSource()).getNoiseBiome(surface(source.randomState().sampler().sample(x>>2,18,z>>2))).is(BIOME),"怪圈或边界余量跨出了麦田群系");
        }
        h.succeed();
    }
    private static Structure.GenerationContext context(ServerLevel level,ChunkPos chunk,long seed){
        var source=level.getChunkSource();var generator=source.getGenerator();
        return new Structure.GenerationContext(level.registryAccess(),generator,generator.getBiomeSource(),source.randomState(),level.getStructureManager(),seed,chunk,level,b->b.is(BIOME));
    }
    private static void unique(GameTestHelper h){
        var level=world(h);var at=findInterior(level);long seed=level.getSeed();
        var patch=WheatCirclePlacement.patch(context(level,new ChunkPos(at.getX()>>4,at.getZ()>>4),seed));
        h.assertTrue(patch.winner().isPresent(),"完整麦田没有唯一候选点");
        var placement=(net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement)level.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET).getOrThrow(ResourceKey.create(Registries.STRUCTURE_SET,LogosDimension.id("wheat_crop_circles"))).value().placement();
        var candidates=new ArrayList<ChunkPos>();
        for(int x=Math.floorDiv(patch.minX(),4);x<=Math.floorDiv(patch.maxX(),4);x++)for(int z=Math.floorDiv(patch.minZ(),4);z<=Math.floorDiv(patch.maxZ(),4);z++){
            var potential=placement.getPotentialStructureChunk(seed,x,z);
            if(potential.x()!=x||potential.z()!=z)continue;
            var chunk=new ChunkPos(x,z);
            if(WheatCirclePlacement.patch(context(level,chunk,seed)).winner().equals(patch.winner()))candidates.add(chunk);
        }
        h.assertTrue(candidates.size()>1,"样地没有多个旧版随机起点，无法验证去重");
        for(boolean reverse:new boolean[]{false,true}){
            if(reverse)Collections.reverse(candidates);
            int accepted=0;
            for(var chunk:candidates)if(WheatCirclePlacement.allows(context(level,chunk,seed)))accepted++;
            h.assertTrue(accepted==1,"同片麦田生成了多个怪圈，或选择受探索顺序影响");
        }
        System.out.println("WHEAT_UNIQUE cells="+patch.cells()+" old_candidates="+candidates.size()+" winner="+patch.winner().orElseThrow());
        h.succeed();
    }
    private static void circle(GameTestHelper h){
        var level=h.getLevel();var origin=new BlockPos(-2048,180,2048);
        for(int x=0;x<128;x++)for(int z=0;z<128;z++){
            int y=180+x/32;var p=new BlockPos(origin.getX()+x,y,origin.getZ()+z);
            level.setBlock(p,Blocks.GRASS_BLOCK.defaultBlockState(),2);
            for(int dy=1;dy<=3;dy++)level.setBlock(p.above(dy),Blocks.AIR.defaultBlockState(),2);
        }
        var sea=origin.offset(64,3,1);level.setBlock(sea,Blocks.WATER.defaultBlockState(),2);
        var rock=origin.offset(64,2,10);level.setBlock(rock,Blocks.STONE.defaultBlockState(),2);
        template(level,origin,Rotation.NONE);
        h.assertTrue(level.getBlockState(sea).is(Blocks.WATER),"怪圈覆盖水面");
        h.assertTrue(level.getBlockState(rock).is(Blocks.STONE),"怪圈覆盖裸岩");
        int marked=0;
        for(int x=0;x<128;x++)for(int z=0;z<128;z++){
            var p=new BlockPos(origin.getX()+x,180+x/32,origin.getZ()+z);var s=level.getBlockState(p);
            if(s.is(Blocks.HAY_BLOCK)||s.is(Blocks.DIRT_PATH)||s.is(Blocks.MUD))marked++;
            h.assertTrue(!level.getBlockState(p.above()).is(Blocks.HAY_BLOCK),"怪圈在坡面悬浮");
            h.assertTrue(!s.is(Blocks.JIGSAW),"中心遗留拼图方块");
        }
        System.out.println("WHEAT_CIRCLE placed="+marked);
        h.assertTrue(marked>6000,"怪圈图案未完整落在起伏地面");
        h.succeed();
    }
    private static void encounter(GameTestHelper h){
        var level=world(h);var server=level.getServer();var at=findInterior(level);
        level.getChunkAt(at);
        int y=level.getHeight(Heightmap.Types.OCEAN_FLOOR,at.getX(),at.getZ());
        var cookie=CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(),"wheat-visit"),false);
        var player=new ServerPlayer(server,h.getLevel(),cookie.gameProfile(),cookie.clientInformation());
        var connection=new Connection(PacketFlow.SERVERBOUND);new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection,player,cookie);player.connection.markClientLoaded();
        try{
            var advancement=server.getAdvancements().get(LogosDimension.id("adventure/crop_circle"));
            h.assertTrue(advancement!=null,"未加载麦田成就");
            CriteriaTriggers.LOCATION.trigger(player);
            h.assertTrue(!player.getAdvancements().getOrStartProgress(advancement).isDone(),"普通世界误触发成就");
            DimensionTravel.teleport(player,level,new net.minecraft.world.phys.Vec3(at.getX()+.5,y,at.getZ()+.5),0,0);
            CriteriaTriggers.LOCATION.trigger(player);
            var progress=player.getAdvancements().getOrStartProgress(advancement);
            h.assertTrue(progress.isDone(),"首次进入麦田没有触发成就");
            var date=progress.getCriterion("first_encounter").getObtained();
            CriteriaTriggers.LOCATION.trigger(player);
            h.assertTrue(Objects.equals(date,progress.getCriterion("first_encounter").getObtained()),"重复进入再次授予成就");
        }finally{server.getPlayerList().remove(player);player.discard();}
        h.succeed();
    }
    private WheatPlainsGameTests(){}
}
