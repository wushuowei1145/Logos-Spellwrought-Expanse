package dev.logos.dimension.gametest;

import com.mojang.authlib.GameProfile;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.cloudsea.CloudseaBlocks;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 蘑菇类食物的世界内检查：露灯菌（以及之后加的各种蘑菇）吃下去要真的回饱食度，
 * 有毒的还要真的挂上效果。
 *
 * <p>只验证物品与食用行为，不验证自然生成。
 */
public final class MushroomGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS=DeferredRegister.create(Registries.TEST_FUNCTION,LogosDimension.ID);

    public static void register(IEventBus bus){
        var tests=Map.<String,Consumer<GameTestHelper>>of(
            "glowcap_food",MushroomGameTests::glowcapFood,
            "woodland_mushroom_food",MushroomGameTests::woodlandMushrooms);
        tests.forEach((name,test)->FUNCTIONS.register(name,()->test));FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event)->{
            var env=event.registerEnvironment(LogosDimension.id("mushrooms"),new TestEnvironmentDefinition.AllOf());
            for(String name:tests.keySet())event.registerTest(LogosDimension.id(name),new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION,LogosDimension.id(name)),new TestData<>(env,Identifier.withDefaultNamespace("empty"),1600,0,true,Rotation.NONE,false,1,1,false,16)));
        });
    }

    private static void glowcapFood(GameTestHelper h){
        var player=mockPlayer(h);
        try{
            player.getFoodData().setFoodLevel(10);
            player.getFoodData().setSaturation(0);
            var stack=new ItemStack(CloudseaBlocks.GLOWCAP.get().asItem(),2);
            h.assertTrue(stack.has(DataComponents.CONSUMABLE)&&stack.get(DataComponents.FOOD).nutrition()==2,
                "露灯菌没有一格饱食度的营养值");
            var remainder=stack.finishUsingItem(h.getLevel(),player);
            h.assertTrue(player.getFoodData().getFoodLevel()==12&&remainder.getCount()==1,
                "吃掉一份露灯菌没有回满一格饱食度或没有消耗物品");
        }finally{h.getLevel().getServer().getPlayerList().remove(player);}
        h.succeed();
    }

    /** 三种林地无毒蘑菇：营养值都是 2，吃一份回一格饱食度并消耗一个。 */
    private static void woodlandMushrooms(GameTestHelper h){
        var player=mockPlayer(h);
        try{
            var mushrooms=new net.minecraft.world.item.Item[]{
                CloudseaBlocks.FIELD_MUSHROOM.get().asItem(),
                CloudseaBlocks.CHANTERELLE.get().asItem(),
                CloudseaBlocks.BOLETE.get().asItem()};
            for(var item:mushrooms){
                player.getFoodData().setFoodLevel(10);
                player.getFoodData().setSaturation(0);
                var stack=new ItemStack(item,2);
                h.assertTrue(stack.has(DataComponents.CONSUMABLE)&&stack.get(DataComponents.FOOD).nutrition()==2,
                    item+" 没有一格饱食度的营养值");
                var remainder=stack.finishUsingItem(h.getLevel(),player);
                h.assertTrue(player.getFoodData().getFoodLevel()==12&&remainder.getCount()==1,
                    item+" 没有正确回复饱食度或没有消耗");
            }
        }finally{h.getLevel().getServer().getPlayerList().remove(player);}
        h.succeed();
    }

    /** 与葡萄测试一致的模拟玩家：需要真实连接，否则 FoodData 不会同步。 */
    private static ServerPlayer mockPlayer(GameTestHelper h){
        var cookie=CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(),"mushroom-test"),false);
        var player=new ServerPlayer(h.getLevel().getServer(),h.getLevel(),cookie.gameProfile(),cookie.clientInformation());
        var connection=new Connection(PacketFlow.SERVERBOUND);new EmbeddedChannel(connection);
        h.getLevel().getServer().getPlayerList().placeNewPlayer(connection,player,cookie);
        player.connection.markClientLoaded();player.setGameMode(GameType.SURVIVAL);return player;
    }

    private MushroomGameTests(){}
}
