package dev.logos.dimension.gametest;

import com.mojang.authlib.GameProfile;
import dev.logos.dimension.DimensionBlocks;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.cloudsea.*;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Tests actual BlockItem placement, support replacement and old-save random-tick cleanup. */
public final class PlantSupportGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS=DeferredRegister.create(Registries.TEST_FUNCTION,LogosDimension.ID);
    public static void register(IEventBus bus){
        var tests=Map.<String,Consumer<GameTestHelper>>of("plant_item_soil_support",PlantSupportGameTests::placement,"plant_support_loss",PlantSupportGameTests::loss,"plant_legacy_cleanup",PlantSupportGameTests::cleanup);
        tests.forEach((name,test)->FUNCTIONS.register(name,()->test));FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event)->{
            var env=event.registerEnvironment(LogosDimension.id("plant_support"),new TestEnvironmentDefinition.AllOf());
            for(String name:tests.keySet())event.registerTest(LogosDimension.id(name),new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION,LogosDimension.id(name)),new TestData<>(env,Identifier.withDefaultNamespace("empty"),400,0,true,Rotation.NONE,false,1,1,false,16)));
        });
    }
    private static List<Block> plants(){
        var result=new ArrayList<Block>(List.of(CloudseaBlocks.FERN.get(),CloudseaBlocks.BRACKEN.get(),CloudseaBlocks.BLUEBELL.get(),CloudseaBlocks.FOXGLOVE.get(),CloudseaBlocks.LUPINE.get(),CloudseaBlocks.MEADOW_GRASS.get(),CloudseaBlocks.REED.get(),CloudseaBlocks.LANTERN.get(),CloudseaBlocks.ROOT.get(),CloudseaBlocks.EYE_FERN.get(),CloudseaBlocks.DEMON_REED.get()));
        CloudseaBlocks.SEASONAL_PLANTS.forEach(p->result.add(p.get()));return result;
    }
    private static ServerPlayer player(GameTestHelper h){
        var cookie=CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(),"plant-test"),false);
        var p=new ServerPlayer(h.getLevel().getServer(),h.getLevel(),cookie.gameProfile(),cookie.clientInformation());
        var connection=new Connection(PacketFlow.SERVERBOUND);new EmbeddedChannel(connection);
        h.getLevel().getServer().getPlayerList().placeNewPlayer(connection,p,cookie);p.connection.markClientLoaded();p.setGameMode(GameType.SURVIVAL);return p;
    }
    private static void placement(GameTestHelper h){
        var level=h.getLevel();var p=player(h);var ground=h.absolutePos(new BlockPos(4,3,4));
        var soils=new ArrayList<Block>(List.of(Blocks.DIRT,Blocks.GRASS_BLOCK,Blocks.COARSE_DIRT,Blocks.ROOTED_DIRT,Blocks.PODZOL,Blocks.MOSS_BLOCK,Blocks.FARMLAND,Blocks.MUD,DimensionBlocks.AETHER_SOIL.get(),DimensionBlocks.AETHER_TURF.get()));
        CloudseaBlocks.GROUND.values().forEach(b->soils.add(b.get()));
        var invalid=List.of(Blocks.STONE,Blocks.COBBLESTONE,Blocks.DEEPSLATE,Blocks.STONE_BRICKS,Blocks.OAK_PLANKS,Blocks.OAK_LOG,Blocks.GLASS,Blocks.IRON_BLOCK,Blocks.SAND,Blocks.GRAVEL,CloudseaBlocks.LIMESTONE.get(),CloudseaBlocks.SLATE.get());
        try{
            // Check the reported failure first, before the valid-substrate matrix.
            for(var block:plants())for(var soil:invalid)place(h,p,ground,block,soil,false);
            for(var block:plants())for(var soil:soils)place(h,p,ground,block,soil,true);
        }finally{level.getServer().getPlayerList().remove(p);}
        h.succeed();
    }
    private static void place(GameTestHelper h,ServerPlayer p,BlockPos ground,Block plant,Block soil,boolean expected){
        var level=h.getLevel();level.removeBlock(ground.above(),false);level.setBlockAndUpdate(ground,soil.defaultBlockState());
        var stack=new ItemStack(plant);p.setItemInHand(InteractionHand.MAIN_HAND,stack);
        var context=new BlockPlaceContext(p,InteractionHand.MAIN_HAND,stack,new BlockHitResult(ground.getCenter(),Direction.UP,ground,false));
        var result=((BlockItem)stack.getItem()).place(context);
        h.assertTrue(result.consumesAction()==expected&&level.getBlockState(ground.above()).is(plant)==expected,"Plant "+plant+" placement on "+soil+" expected "+expected);
        if(!expected)h.assertTrue(stack.getCount()==1,"Rejected plant placement consumed its item");
        else{
            var state=level.getBlockState(ground.above());
            if(state.hasProperty(SeasonalLeavesBlock.SEASON))for(int season=0;season<4;season++)h.assertTrue(state.setValue(SeasonalLeavesBlock.SEASON,season).canSurvive(level,ground.above()),"Season changes valid soil support");
        }
    }
    private static void loss(GameTestHelper h){
        var level=h.getLevel();var pos=h.absolutePos(new BlockPos(4,4,4));
        for(var plant:plants()){
            level.setBlockAndUpdate(pos.below(),Blocks.DIRT.defaultBlockState());level.setBlockAndUpdate(pos,plant.defaultBlockState());
            level.setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());h.assertTrue(level.isEmptyBlock(pos),plant+" survived replacement of soil with stone");
            level.setBlockAndUpdate(pos.below(),Blocks.DIRT.defaultBlockState());level.setBlockAndUpdate(pos,plant.defaultBlockState());
            level.removeBlock(pos.below(),false);h.assertTrue(level.isEmptyBlock(pos),plant+" floated after soil removal");
        }
        h.succeed();
    }
    private static void cleanup(GameTestHelper h){
        var level=h.getLevel();var positions=new ArrayList<BlockPos>();int i=0;
        for(var plant:plants()){
            var pos=h.absolutePos(new BlockPos(2+i%5,4,2+i/5));i++;positions.add(pos);
            // Loading an existing chunk does not issue placement-time neighbour shape updates.
            int fixtureFlags=Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE;
            level.setBlock(pos.below(),Blocks.STONE.defaultBlockState(),fixtureFlags);level.setBlock(pos,plant.defaultBlockState(),fixtureFlags);
            h.assertTrue(level.getBlockState(pos).is(plant),"Old-save fixture disappeared before sampling: "+plant+" at "+pos);
            h.assertTrue(level.getBlockState(pos).isRandomlyTicking(),plant+" will never recheck old-save support");
        }
        h.assertTrue(positions.stream().allMatch(p->!level.isEmptyBlock(p)),"Fixtures were removed by another fixture's neighbour update");
        for(int attempt=0;attempt<64&&positions.stream().anyMatch(p->!level.isEmptyBlock(p));attempt++){
            var chunks=new HashSet<net.minecraft.world.level.chunk.LevelChunk>();for(var pos:positions)chunks.add(level.getChunkAt(pos));
            for(var chunk:chunks)level.tickChunk(chunk,4096);
        }
        h.assertTrue(positions.stream().allMatch(level::isEmptyBlock),"Old unsupported plants survived real chunk random ticks");h.succeed();
    }
    private PlantSupportGameTests(){}
}
