package dev.logos.dimension.gametest;

import com.mojang.authlib.GameProfile;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.item.LongswordItem;
import dev.logos.dimension.longsword.*;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 实际服务端逐刻检查出招前摇、连段冷却、蓄力、换槽取消、遮挡和距离。 */
public final class LongswordGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS=DeferredRegister.create(Registries.TEST_FUNCTION,LogosDimension.ID);
    public static void register(IEventBus bus) {
        var tests=Map.<String,Consumer<GameTestHelper>>of(
                "longsword_contact",LongswordGameTests::contact,
                "longsword_combo",LongswordGameTests::combo,
                "longsword_charge",LongswordGameTests::charge,
                "longsword_cancel",LongswordGameTests::cancel,
                "longsword_switch",LongswordGameTests::switchSlot,
                "longsword_drop",LongswordGameTests::drop,
                "longsword_wall",h->obstruction(h,false),
                "longsword_reach",h->obstruction(h,true));
        tests.forEach((name,test)->FUNCTIONS.register(name,()->test)); FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event)->{
            var env=event.registerEnvironment(LogosDimension.id("longsword"),new TestEnvironmentDefinition.AllOf());
            for(var name:tests.keySet())event.registerTest(LogosDimension.id(name),new FunctionGameTestInstance(
                    ResourceKey.create(Registries.TEST_FUNCTION,LogosDimension.id(name)),new TestData<>(env,
                    Identifier.withDefaultNamespace("empty"),150,0,true,Rotation.NONE,false,1,1,false,16)));
        });
    }
    private record Fixture(GameTestHelper h,ServerPlayer p,LivingEntity target,Vec3 origin) {
        static Fixture create(GameTestHelper h) {
            var cookie=CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(),"sword-test"),false);
            var p=new ServerPlayer(h.getLevel().getServer(),h.getLevel(),cookie.gameProfile(),cookie.clientInformation());
            var connection=new Connection(PacketFlow.SERVERBOUND); new EmbeddedChannel(connection);
            h.getLevel().getServer().getPlayerList().placeNewPlayer(connection,p,cookie);
            p.connection.markClientLoaded(); p.setGameMode(GameType.SURVIVAL); p.tickCount=1; p.setNoGravity(true);
            var origin=h.absoluteVec(new Vec3(4,10,4));p.setPos(origin); p.setYRot(-90);p.setYHeadRot(-90);p.setXRot(0);
            p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(LongswordItem.BLACKSTEEL.get()));
            var target=EntityType.IRON_GOLEM.create(h.getLevel(),EntitySpawnReason.COMMAND);
            target.setNoAi(true);target.setNoGravity(true);target.setPos(origin.add(2.3,0,0));h.getLevel().addFreshEntity(target);
            // 嵌入连接没有真实网络循环；逐世界刻更新装备和冷却，重置实体刻数以跳过无客户端的周期影雾包。
            h.onEachTick(()->{p.tickCount=1;p.doTick();p.setPos(origin);p.setDeltaMovement(Vec3.ZERO);p.setYRot(-90);p.setYHeadRot(-90);p.setXRot(0);});
            return new Fixture(h,p,target,origin);
        }
        void at(int delay,Runnable action) {h.runAfterDelay(delay,()->{try{action.run();}catch(RuntimeException|Error e){close();throw e;}});}
        void idle(){h.assertTrue(LongswordCombat.pose(p).phase()==LongswordPhase.IDLE,"未完成拔剑");}
        void attack(){LongswordCombat.attack(p);}
        void close(){p.stopUsingItem();target.discard();h.getLevel().getServer().getPlayerList().remove(p);}
        void succeed(){close();h.succeed();}
        void use(){p.getMainHandItem().use(h.getLevel(),p,InteractionHand.MAIN_HAND);}
    }
    private static void contact(GameTestHelper h) {
        var f=Fixture.create(h);
        f.at(30,()->{f.idle();h.assertTrue(Math.abs(f.p.getAttributeValue(Attributes.ATTACK_SPEED)-1.6)<.001,"攻速不是原版剑节奏");for(int i=0;i<100;i++)f.attack();});
        f.at(33,()->h.assertTrue(f.target.getHealth()==100,"前摇提前造成伤害"));
        f.at(38,()->h.assertTrue(f.target.getHealth()==93 && f.p.getMainHandItem().getDamageValue()==1,"没有保留七点伤害和耐久"));
        f.at(47,()->{f.idle();h.assertTrue(f.target.getHealth()==93,"同一刻输入重复命中或跳过冷却");f.succeed();});
    }
    private static void combo(GameTestHelper h) {
        var f=Fixture.create(h);
        f.at(30,f::attack);f.at(40,f::attack);
        f.at(44,()->h.assertTrue(LongswordCombat.pose(f.p).phase()==LongswordPhase.ATTACK_2,"第二段没有接续"));
        f.at(53,f::attack);
        f.at(57,()->h.assertTrue(LongswordCombat.pose(f.p).phase()==LongswordPhase.ATTACK_3,"第三段没有接续"));
        f.at(70,()->{h.assertTrue(f.p.getMainHandItem().getDamageValue()==3 && f.target.getHealth()<81,"三段没有分别命中：生命="+f.target.getHealth()+" 耐久消耗="+f.p.getMainHandItem().getDamageValue());f.idle();f.succeed();});
    }
    private static void charge(GameTestHelper h) {
        var f=Fixture.create(h);f.at(30,f::use);
        f.at(55,()->{h.assertTrue(LongswordCombat.pose(f.p).phase()==LongswordPhase.CHARGING && f.target.getHealth()==100,"蓄力期间伤害或结束");f.p.releaseUsingItem();});
        f.at(62,()->h.assertTrue(f.target.getHealth()==100,"下劈前举提前造成伤害"));
        f.at(69,()->h.assertTrue(f.target.getHealth()==93,"释放后没有下劈命中"));
        f.at(80,f::succeed);
    }
    private static void cancel(GameTestHelper h) {
        var f=Fixture.create(h);f.at(30,f::use);f.at(34,()->f.p.releaseUsingItem());
        f.at(60,()->{f.idle();h.assertTrue(f.target.getHealth()==100,"短按取消仍有伤害");f.succeed();});
    }
    private static void switchSlot(GameTestHelper h) {
        var f=Fixture.create(h);f.at(30,f::attack);f.at(32,()->f.p.getInventory().setSelectedSlot(2));
        f.at(40,()->h.assertTrue(f.target.getHealth()==100 && LongswordCombat.pose(f.p).phase()==LongswordPhase.SHEATHE,"换槽仍结算攻击或没有收剑"));
        f.at(60,()->{h.assertTrue(LongswordCombat.pose(f.p).phase()==LongswordPhase.CLEAR,"收剑没有释放双手");f.succeed();});
    }
    private static void drop(GameTestHelper h) {
        var f=Fixture.create(h);int slot=f.p.getInventory().getSelectedSlot();
        f.at(30,f::attack);f.at(32,()->f.p.drop(false));
        f.at(40,()->{
            h.assertTrue(f.p.getInventory().getSelectedSlot()==slot && f.p.getMainHandItem().isEmpty(),"丢弃后切槽或主手未空");
            h.assertTrue(LongswordCombat.pose(f.p).phase()==LongswordPhase.CLEAR && f.target.getHealth()==100,"Q 丢弃未清除动作或仍结算攻击");
            f.succeed();
        });
    }
    private static void obstruction(GameTestHelper h,boolean far) {
        var f=Fixture.create(h);
        f.at(29,()->{
            if(far)f.target.setPos(f.origin.add(5,0,0));
            else {
                f.target.setPos(f.origin.add(2.9,0,0));
                for(int y=0;y<3;y++)for(int z=-1;z<=1;z++)h.getLevel().setBlockAndUpdate(BlockPos.containing(f.origin.add(1,y,z)),Blocks.STONE.defaultBlockState());
            }
        });
        f.at(30,f::attack);
        f.at(48,()->{h.assertTrue(f.target.getHealth()==100,far?"超出原版近战距离仍命中":"隔墙命中");f.succeed();});
    }
}
