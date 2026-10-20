package dev.logos.dimension.gametest;

import com.mojang.authlib.GameProfile;
import dev.logos.dimension.*;
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
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class BerryGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS=DeferredRegister.create(Registries.TEST_FUNCTION,LogosDimension.ID);
    public static void register(IEventBus bus){
        var tests=Map.<String,Consumer<GameTestHelper>>of("berry_native_growth",BerryGameTests::growth,"berry_harvest_food_planting",BerryGameTests::harvest);
        tests.forEach((name,test)->FUNCTIONS.register(name,()->test));FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event)->{
            var env=event.registerEnvironment(LogosDimension.id("berries"),new TestEnvironmentDefinition.AllOf());
            for(String name:tests.keySet())event.registerTest(LogosDimension.id(name),new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION,LogosDimension.id(name)),new TestData<>(env,Identifier.withDefaultNamespace("empty"),400,0,true,Rotation.NONE,false,1,1,false,16)));
        });
    }
    private static ServerPlayer player(GameTestHelper h){
        var cookie=CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(),"berry-test"),false);
        var p=new ServerPlayer(h.getLevel().getServer(),h.getLevel(),cookie.gameProfile(),cookie.clientInformation());
        var connection=new Connection(PacketFlow.SERVERBOUND);new EmbeddedChannel(connection);h.getLevel().getServer().getPlayerList().placeNewPlayer(connection,p,cookie);p.connection.markClientLoaded();p.setGameMode(GameType.SURVIVAL);return p;
    }
    private static void growth(GameTestHelper h){
        var l=h.getLevel();var pos=h.absolutePos(new BlockPos(5,4,5));var block=(JourneyBerryBushBlock)CloudseaBlocks.BRAMBLE.get();
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)for(int y=0;y<=3;y++)l.setBlockAndUpdate(pos.offset(x,y,z),Blocks.STONE.defaultBlockState());
        l.setBlockAndUpdate(pos.below(),Blocks.DIRT.defaultBlockState());l.removeBlock(pos.above(),false);l.setBlockAndUpdate(pos,block.defaultBlockState());
        h.startSequence().thenWaitUntil(()->h.assertTrue(l.getRawBrightness(pos.above(),0)<9,"Waiting for dark fixture light propagation"))
        .thenExecute(()->{
        for(int i=0;i<32;i++)l.tickChunk(l.getChunkAt(pos),4096);
        h.assertTrue(l.getBlockState(pos).getValue(SweetBerryBushBlock.AGE)==0,"Bush grows without native light requirement");
        l.setBlockAndUpdate(pos.above().east(),Blocks.GLOWSTONE.defaultBlockState());
        }).thenWaitUntil(()->h.assertTrue(l.getRawBrightness(pos.above(),0)>=9,"Waiting for glowstone light propagation"))
        .thenExecute(()->{
        for(int i=0;i<96&&l.getBlockState(pos).getValue(SweetBerryBushBlock.AGE)<3;i++)l.tickChunk(l.getChunkAt(pos),4096);
        h.assertTrue(l.getBlockState(pos).getValue(SweetBerryBushBlock.AGE)==3,"Native random tick growth never matures berries");
        h.assertTrue(!block.isValidBonemealTarget(l,pos,l.getBlockState(pos)),"Bone meal accepts ripe bush");
        var shape=l.getBlockState(pos).getShape(l,pos).bounds();h.assertTrue(shape.getYsize()>1&&shape.getXsize()>1,"Mature bush remains grass-sized");
        l.setBlockAndUpdate(pos,l.getBlockState(pos).setValue(SweetBerryBushBlock.AGE,1));
        for(int i=0;i<2;i++)block.performBonemeal(l,l.getRandom(),pos,l.getBlockState(pos));
        h.assertTrue(l.getBlockState(pos).getValue(SweetBerryBushBlock.AGE)==3,"Native bone meal does not regrow fruit");
        l.setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());h.assertTrue(l.isEmptyBlock(pos),"Mature bush survives stone support");
        }).thenSucceed();
    }
    private static void harvest(GameTestHelper h){
        var l=h.getLevel();var pos=h.absolutePos(new BlockPos(5,4,5));var block=CloudseaBlocks.BRAMBLE.get();var p=player(h);
        try{
            l.setBlockAndUpdate(pos.below(),Blocks.DIRT.defaultBlockState());var hit=new BlockHitResult(pos.getCenter(),Direction.UP,pos,false);
            for(int age:new int[]{2,3}){
                for(var e:l.getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(3)))e.discard();
                l.setBlockAndUpdate(pos,block.defaultBlockState().setValue(SweetBerryBushBlock.AGE,age));
                h.assertTrue(l.getBlockState(pos).useWithoutItem(l,p,hit).consumesAction(),"Ripe blue berries cannot be harvested");
                int count=l.getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(3)).stream().mapToInt(e->{h.assertTrue(e.getItem().is(block.asItem()),"Harvest drops vanilla red berries");return e.getItem().getCount();}).sum();
                h.assertTrue(count>=age-1&&count<=age,"Harvest count differs from native sweet berries");
                h.assertTrue(l.getBlockState(pos).getValue(SweetBerryBushBlock.AGE)==1,"Harvest does not reset shrub to regrow");
                h.assertTrue(!l.getBlockState(pos).useWithoutItem(l,p,hit).consumesAction(),"Repeated picking duplicates berries");
            }
            p.getFoodData().setFoodLevel(10);p.getFoodData().setSaturation(0);var fruit=new ItemStack(block,2);var rest=fruit.finishUsingItem(l,p);
            h.assertTrue(p.getFoodData().getFoodLevel()==12&&rest.getCount()==1,"Blue berries are not edible like sweet berries");
            l.removeBlock(pos,false);p.setItemInHand(InteractionHand.MAIN_HAND,rest);
            var context=new BlockPlaceContext(p,InteractionHand.MAIN_HAND,rest,new BlockHitResult(pos.below().getCenter(),Direction.UP,pos.below(),false));
            h.assertTrue(((BlockItem)rest.getItem()).place(context).consumesAction()&&l.getBlockState(pos).is(block)&&l.getBlockState(pos).getValue(SweetBerryBushBlock.AGE)==0,"Harvested berry cannot plant native seedling");
            h.assertTrue(rest.isEmpty(),"Planting failed to consume berry");
        }finally{l.getServer().getPlayerList().remove(p);}h.succeed();
    }
    private BerryGameTests(){}
}
