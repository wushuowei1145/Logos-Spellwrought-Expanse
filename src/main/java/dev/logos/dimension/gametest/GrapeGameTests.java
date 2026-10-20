package dev.logos.dimension.gametest;

import com.mojang.authlib.GameProfile;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.cloudsea.CloudseaBlocks;
import dev.logos.dimension.cloudsea.GrapeVineBlock;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class GrapeGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS=DeferredRegister.create(Registries.TEST_FUNCTION,LogosDimension.ID);
    public static void register(IEventBus bus){
        var tests=Map.<String,Consumer<GameTestHelper>>of("grape_attachment",GrapeGameTests::attachment,"grape_harvest_regrowth",GrapeGameTests::harvest,"grape_food",GrapeGameTests::food,"grape_native_growth_sites",GrapeGameTests::wild);
        tests.forEach((name,test)->FUNCTIONS.register(name,()->test));FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event)->{
            var env=event.registerEnvironment(LogosDimension.id("grapes"),new TestEnvironmentDefinition.AllOf());
            for(String name:tests.keySet())event.registerTest(LogosDimension.id(name),new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION,LogosDimension.id(name)),new TestData<>(env,Identifier.withDefaultNamespace("empty"),200,0,true,Rotation.NONE,false,1,1,false,16)));
        });
    }
    private static ServerPlayer player(GameTestHelper h){
        var cookie=CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(),"grape-test"),false);
        var player=new ServerPlayer(h.getLevel().getServer(),h.getLevel(),cookie.gameProfile(),cookie.clientInformation());
        var connection=new Connection(PacketFlow.SERVERBOUND);new EmbeddedChannel(connection);
        h.getLevel().getServer().getPlayerList().placeNewPlayer(connection,player,cookie);
        player.connection.markClientLoaded();player.setGameMode(GameType.SURVIVAL);return player;
    }
    private static void attachment(GameTestHelper h){
        var level=h.getLevel();var p=player(h);
        try {
            var wall=h.absolutePos(new BlockPos(7,4,7));level.setBlockAndUpdate(wall,Blocks.STONE.defaultBlockState());
            for(var facing:new Direction[]{Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST,Direction.DOWN}){
                var target=wall.relative(facing);var stack=new ItemStack(CloudseaBlocks.GRAPEVINE.get());p.setItemInHand(InteractionHand.MAIN_HAND,stack);
                var context=new BlockPlaceContext(p,InteractionHand.MAIN_HAND,stack,new BlockHitResult(wall.getCenter(),facing,wall,false));
                h.assertTrue(((BlockItem)stack.getItem()).place(context).consumesAction(),"Vine failed to place against "+facing);
                var state=level.getBlockState(target);
                h.assertTrue(state.is(CloudseaBlocks.GRAPEVINE.get())&&state.getValue(VineBlock.getPropertyForFace(facing.getOpposite()))&&state.canSurvive(level,target),"Vine floated away from its support");
                h.assertTrue(state.getCollisionShape(level,target).isEmpty(),"Vine gained a solid collision cube");
                h.assertTrue(state.is(net.minecraft.tags.BlockTags.CLIMBABLE),"Attached grapevine is not climbable");
            }
            level.removeBlock(wall,false);
            for(var facing:new Direction[]{Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST,Direction.DOWN})h.assertTrue(level.isEmptyBlock(wall.relative(facing)),"Vine survives without its last support");
        }finally{level.getServer().getPlayerList().remove(p);}
        h.succeed();
    }
    private static void harvest(GameTestHelper h){
        var level=h.getLevel();var p=player(h);var pos=h.absolutePos(new BlockPos(4,4,4));
        var block=(GrapeVineBlock)CloudseaBlocks.GRAPEVINE.get();
        level.setBlockAndUpdate(pos.north(),Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(pos,block.defaultBlockState().setValue(VineBlock.NORTH,true).setValue(GrapeVineBlock.AGE,3));
        var hit=new BlockHitResult(pos.getCenter(),Direction.SOUTH,pos,false);
        try {
            int grapes=p.getInventory().countItem(CloudseaBlocks.GRAPES.get());
            h.assertTrue(level.getBlockState(pos).useWithoutItem(level,p,hit).consumesAction(),"Ripe grapes cannot be picked");
            h.assertTrue(p.getInventory().countItem(CloudseaBlocks.GRAPES.get())==grapes+1&&level.getBlockState(pos).getValue(GrapeVineBlock.AGE)==0,"Grape harvest did not reset fruit growth");
            level.getBlockState(pos).useWithoutItem(level,p,hit);
            h.assertTrue(p.getInventory().countItem(CloudseaBlocks.GRAPES.get())==grapes+1,"Unripe vine duplicates grapes");
            var shears=new ItemStack(Items.SHEARS);p.setItemInHand(InteractionHand.MAIN_HAND,shears);
            h.assertTrue(level.getBlockState(pos).useItemOn(shears,level,p,InteractionHand.MAIN_HAND,hit).consumesAction(),"Grape leaves cannot be picked with shears");
            h.assertTrue(p.getInventory().countItem(CloudseaBlocks.GRAPE_LEAF.get())==1&&!level.getBlockState(pos).getValue(GrapeVineBlock.FOLIAGE)&&shears.getDamageValue()==1,"Leaf harvest did not remove foliage or wear shears");
            level.getBlockState(pos).useItemOn(shears,level,p,InteractionHand.MAIN_HAND,hit);
            h.assertTrue(p.getInventory().countItem(CloudseaBlocks.GRAPE_LEAF.get())==1,"Bare stems duplicate leaves");
            for(int i=0;i<4;i++)block.performBonemeal(level,level.getRandom(),pos,level.getBlockState(pos));
            h.assertTrue(level.getBlockState(pos).getValue(GrapeVineBlock.FOLIAGE)&&level.getBlockState(pos).getValue(GrapeVineBlock.AGE)==3,"Picked foliage and grapes do not regrow");
            p.setShiftKeyDown(true);p.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
            level.getBlockState(pos).useWithoutItem(level,p,hit);
            h.assertTrue(p.getInventory().countItem(CloudseaBlocks.GRAPE_LEAF.get())==2&&!level.getBlockState(pos).getValue(GrapeVineBlock.FOLIAGE),"Sneaking empty-hand leaf picking failed");
        }finally{level.getServer().getPlayerList().remove(p);}
        h.succeed();
    }
    private static void food(GameTestHelper h){
        var p=player(h);
        try {
            p.getFoodData().setFoodLevel(10);p.getFoodData().setSaturation(0);
            var stack=new ItemStack(CloudseaBlocks.GRAPES.get(),2);
            h.assertTrue(stack.has(DataComponents.CONSUMABLE)&&stack.get(DataComponents.FOOD).nutrition()==2,"Grapes lack one hunger icon of nutrition");
            var remainder=stack.finishUsingItem(h.getLevel(),p);
            h.assertTrue(p.getFoodData().getFoodLevel()==12&&remainder.getCount()==1,"Eating a grape does not restore exactly one hunger icon and consume one item");
        }finally{h.getLevel().getServer().getPlayerList().remove(p);}
        h.succeed();
    }
    private static void wild(GameTestHelper h){
        var level=h.getLevel().getServer().getLevel(LogosDimension.CLOUDSEA);
        var at=dev.logos.dimension.NativeWorlds.locateBiome(level,dev.logos.dimension.cloudsea.CloudseaBiome.ANCIENT_BEECH.id());int count=0;
        for(int x=at.getX()-32;x<at.getX()+32;x++)for(int z=at.getZ()-32;z<at.getZ()+32;z++)for(int y=40;y<180;y++){
            var pos=new BlockPos(x,y,z);var state=level.getBlockState(pos);
            if(state.is(CloudseaBlocks.GRAPEVINE.get())){
                count++;h.assertTrue(state.canSurvive(level,pos),"Naturally generated grapevine has no supporting surface");
            }
        }
        h.assertTrue(count>0,"Native forest features did not generate any attached grapevines");
        System.out.println("NATIVE_GRAPEVINES PASS count="+count);h.succeed();
    }
    private GrapeGameTests(){}
}
