package dev.logos.dimension.gametest;

import dev.logos.dimension.*;
import dev.logos.dimension.cloudsea.*;
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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.structure.*;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Test/dev native assembly only. No production world-generation hook calls this class. */
public final class SettlementGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS=DeferredRegister.create(Registries.TEST_FUNCTION,LogosDimension.ID);
    public static void register(IEventBus bus){
        var tests=Map.<String,Consumer<GameTestHelper>>of("settlement_native_assembly",SettlementGameTests::assembly,"settlement_wheat_follows_surface",SettlementGameTests::wheat);
        tests.forEach((name,test)->FUNCTIONS.register(name,()->test));FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event)->{
            var env=event.registerEnvironment(LogosDimension.id("settlements"),new TestEnvironmentDefinition.AllOf());
            for(String name:tests.keySet())event.registerTest(LogosDimension.id(name),new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION,LogosDimension.id(name)),new TestData<>(env,Identifier.withDefaultNamespace("empty"),1600,0,true,Rotation.NONE,false,1,1,false,16)));
        });
    }
    public static StructureStart assemble(ServerLevel l,boolean town,BlockPos at,long seed){
        var holder=l.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(ResourceKey.create(Registries.STRUCTURE,LogosDimension.id(town?"journey_town":"journey_hamlet")));
        var source=l.getChunkSource();var gen=source.getGenerator();
        return holder.value().generate(holder,l.dimension(),l.registryAccess(),gen,gen.getBiomeSource(),source.randomState(),l.getStructureManager(),seed,new ChunkPos(at.getX()>>4,at.getZ()>>4),0,l,b->true);
    }
    public static long count(StructureStart start,String name){return start.getPieces().stream().filter(p->p instanceof PoolElementStructurePiece pool&&pool.getElement().toString().contains("settlement/"+name)).count();}
    public static long homes(StructureStart start){return count(start,"house_")+count(start,"farm_");}
    public static long wheatSeeds(StructureStart start){return start.getPieces().stream().filter(p->p instanceof PoolElementStructurePiece pool&&pool.getElement() instanceof net.minecraft.world.level.levelgen.structure.pools.FeaturePoolElement).count();}
    public static void place(ServerLevel l,StructureStart start){
        var b=start.getBoundingBox();var gen=l.getChunkSource().getGenerator();
        for(int x=b.minX()>>4;x<=b.maxX()>>4;x++)for(int z=b.minZ()>>4;z<=b.maxZ()>>4;z++)l.getChunk(x,z);
        for(int x=b.minX()>>4;x<=b.maxX()>>4;x++)for(int z=b.minZ()>>4;z<=b.maxZ()>>4;z++){
            var chunk=new ChunkPos(x,z);l.getChunk(x,z);
            start.placeInChunk(l,l.structureManager(),gen,RandomSource.create(8103),new BoundingBox(x*16,l.getMinY(),z*16,x*16+15,l.getMaxY(),z*16+15),chunk);
        }
    }
    private static void assembly(GameTestHelper h){
        var l=Objects.requireNonNull(h.getLevel().getServer().getLevel(LogosDimension.CLOUDSEA));
        for(boolean town:new boolean[]{false,true})for(int seed=0;seed<4;seed++){
            var start=assemble(l,town,new BlockPos(1536,80,1536),seed);long houses=homes(start),fields=wheatSeeds(start);
            System.out.println("SETTLEMENT_ASSEMBLY town="+town+" seed="+seed+" homes="+houses+" fields="+fields+" pieces="+start.getPieces().size()+" bounds="+start.getBoundingBox());
            h.assertTrue(start.isValid()&&count(start,(town?"town":"hamlet")+"_road")==4,"Four streets failed native assembly");
            h.assertTrue(houses==(town?24:16),"Native collision rejected housing lots in fixed assembly samples");
            h.assertTrue(fields>=16&&count(start,"rice_paddy")==0,"Wheat feature seeds missing or old paddy still generated");
            h.assertTrue(start.getStructure().terrainAdaptation()==TerrainAdjustment.NONE,"Settlement still raises/buries terrain through Beardifier");
            for(var p:start.getPieces())if(p instanceof PoolElementStructurePiece pool&&pool.getElement() instanceof net.minecraft.world.level.levelgen.structure.pools.FeaturePoolElement){
                var b=p.getBoundingBox();h.assertTrue(b.getXSpan()==1&&b.getYSpan()==1&&b.getZSpan()==1,"Planting reserves a large terrain box");
            }
            h.assertTrue(start.getBoundingBox().getXSpan()<=273&&start.getBoundingBox().getZSpan()<=273,"Settlement exceeds native bounded footprint");
        }
        h.succeed();
    }
    public static final BlockPos WHEAT_STUDY=new BlockPos(1001000,235,1001000);
    public static void wheatStudy(ServerLevel l){
        // Dedicated test fixture only. The production feature never fills soil or clears air.
        for(int x=-10;x<=10;x++)for(int z=-10;z<=10;z++){
            int rise=Math.floorDiv(x+10,4)+Math.floorDiv(z+10,7);
            for(int y=-3;y<=14;y++)l.setBlock(WHEAT_STUDY.offset(x,y,z),y<=rise?Blocks.DIRT.defaultBlockState():Blocks.AIR.defaultBlockState(),Block.UPDATE_CLIENTS);
            var soil=WHEAT_STUDY.offset(x,rise,z);
            l.setBlock(soil,(x%2==0?CloudseaBlocks.GROUND.get(CloudseaBiome.SUNLIT_GLADE).get():Blocks.GRASS_BLOCK).defaultBlockState(),Block.UPDATE_CLIENTS);
            if(x==-5)l.setBlock(soil,Blocks.STONE.defaultBlockState(),Block.UPDATE_CLIENTS);
            if(x==-3)l.setBlock(soil,Blocks.OAK_PLANKS.defaultBlockState(),Block.UPDATE_CLIENTS);
            if(x==-1)l.setBlock(soil,Blocks.DIRT_PATH.defaultBlockState(),Block.UPDATE_CLIENTS);
            if(x==1)l.setBlock(soil.above(),Blocks.SHORT_GRASS.defaultBlockState(),Block.UPDATE_CLIENTS);
            if(x==3)l.setBlock(soil.above(),Blocks.CHEST.defaultBlockState(),Block.UPDATE_CLIENTS);
            if(x==5)l.setBlock(soil.above(),Blocks.WATER.defaultBlockState(),Block.UPDATE_CLIENTS);
        }
    }
    public static void plantWheat(ServerLevel l,BlockPos at,long seed){
        var feature=l.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE).getOrThrow(ResourceKey.create(Registries.PLACED_FEATURE,LogosDimension.id("settlement_wheat"))).value();
        for(int i=0;i<12;i++)feature.place(l,l.getChunkSource().getGenerator(),RandomSource.create(seed+i),at);
    }
    private static void wheat(GameTestHelper h){
        var l=Objects.requireNonNull(h.getLevel().getServer().getLevel(LogosDimension.CLOUDSEA));wheatStudy(l);
        var before=new HashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        for(var p:BlockPos.betweenClosed(WHEAT_STUDY.offset(-10,-3,-10),WHEAT_STUDY.offset(10,14,10)))before.put(p.immutable(),l.getBlockState(p));
        plantWheat(l,WHEAT_STUDY,19);int crops=0;var heights=new HashSet<Integer>();
        for(var e:before.entrySet()){
            var p=e.getKey();var old=e.getValue();var now=l.getBlockState(p);
            if(old.equals(now))continue;
            if(now.is(Blocks.FARMLAND)){
                h.assertTrue(old.is(Blocks.GRASS_BLOCK)||old.is(Blocks.DIRT)||old.is(CloudseaBlocks.GROUND.get(CloudseaBiome.SUNLIT_GLADE).get()),"Planting replaced protected soil/stone/road");
                h.assertTrue(before.get(p.above()).isAir()&&l.getBlockState(p.above()).is(Blocks.WHEAT),"Farmland created without a safe wheat cell");
            }else if(now.is(Blocks.WHEAT)){
                h.assertTrue(old.isAir()&&l.getBlockState(p.below()).is(Blocks.FARMLAND),"Crop buried or replaced an existing block");crops++;heights.add(p.getY());
            }else h.assertTrue(false,"Planting filled, cleared, flooded or moved terrain at "+p+": "+old+" -> "+now);
        }
        h.assertTrue(crops>=75&&heights.size()>=4,"Wheat failed to follow multiple slope heights: "+crops+" / "+heights);
        System.out.println("WHEAT_SURFACE crops="+crops+" heights="+heights+" protected_snapshot="+before.size());h.succeed();
    }
    private SettlementGameTests(){}
}
