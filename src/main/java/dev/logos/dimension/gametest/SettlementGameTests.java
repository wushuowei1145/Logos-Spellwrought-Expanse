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
        var tests=Map.<String,Consumer<GameTestHelper>>of("settlement_native_assembly",SettlementGameTests::assembly,"rice_water_soil_growth",SettlementGameTests::rice);
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
            var start=assemble(l,town,new BlockPos(1536,80,1536),seed);long houses=homes(start),fields=count(start,"rice_paddy");
            System.out.println("SETTLEMENT_ASSEMBLY town="+town+" seed="+seed+" homes="+houses+" fields="+fields+" pieces="+start.getPieces().size()+" bounds="+start.getBoundingBox());
            h.assertTrue(start.isValid()&&count(start,(town?"town":"hamlet")+"_road")==4,"Four streets failed native assembly");
            h.assertTrue(houses==(town?24:16),"Native collision rejected housing lots in fixed assembly samples");
            h.assertTrue(fields>=2,"Large rice paddies cannot connect to native lots");
            h.assertTrue(start.getBoundingBox().getXSpan()<=273&&start.getBoundingBox().getZSpan()<=273,"Settlement exceeds native bounded footprint");
        }
        h.succeed();
    }
    private static void rice(GameTestHelper h){
        var l=h.getLevel();var pos=h.absolutePos(new BlockPos(5,5,5));var block=(JourneyRiceBlock)CloudseaBlocks.RICE.get();
        l.setBlockAndUpdate(pos.above().east(),Blocks.GLOWSTONE.defaultBlockState());
        l.setBlockAndUpdate(pos.below(),Blocks.MUD.defaultBlockState());l.setBlockAndUpdate(pos,Blocks.WATER.defaultBlockState());
        var context=new DirectionalPlaceContext(l,pos,Direction.DOWN,new ItemStack(block),Direction.UP);
        var seedling=block.getStateForPlacement(context);h.assertTrue(seedling!=null,"Cannot plant rice in shallow water");
        l.setBlockAndUpdate(pos,Objects.requireNonNull(seedling));
        h.startSequence().thenWaitUntil(()->h.assertTrue(l.getRawBrightness(pos,0)>=8,"Wait for crop light"))
        .thenExecute(()->{
            h.assertTrue(l.getBlockState(pos).canSurvive(l,pos),"Rice rejects wet mud");
            for(int i=0;i<4;i++)block.performBonemeal(l,l.getRandom(),pos,l.getBlockState(pos));
            h.assertTrue(l.getBlockState(pos).getValue(CropBlock.AGE)==7&&!l.getFluidState(pos).isEmpty(),"Native growth loses water or maturity");
            var loot=Block.getDrops(l.getBlockState(pos),l,pos,null);int grains=loot.stream().filter(s->s.is(block.asItem())).mapToInt(ItemStack::getCount).sum();
            h.assertTrue(grains>=2&&grains<=4,"Mature rice fails harvest/replant yield");
            l.setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());h.assertTrue(!l.getBlockState(pos).is(block),"Rice survives stone support");
            l.setBlockAndUpdate(pos.below(),Blocks.MUD.defaultBlockState());l.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());
            h.assertTrue(block.getStateForPlacement(context)==null,"Rice plants without shallow water");
        }).thenSucceed();
    }
    private SettlementGameTests(){}
}
