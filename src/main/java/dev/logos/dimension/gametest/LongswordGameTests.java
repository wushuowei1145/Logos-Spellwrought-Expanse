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

/** 实际服务端逐刻检查出招前摇、连段冷却、蓄力、换槽取消、遮挡和距离；黑钢长剑另查格挡与施法。 */
public final class LongswordGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS=DeferredRegister.create(Registries.TEST_FUNCTION,LogosDimension.ID);
    public static void register(IEventBus bus) {
        var tests=Map.<String,Consumer<GameTestHelper>>ofEntries(
                Map.entry("longsword_contact",LongswordGameTests::contact),
                Map.entry("longsword_combo",LongswordGameTests::combo),
                Map.entry("longsword_charge",LongswordGameTests::charge),
                Map.entry("longsword_cancel",LongswordGameTests::cancel),
                Map.entry("longsword_switch",LongswordGameTests::switchSlot),
                Map.entry("longsword_drop",LongswordGameTests::drop),
                Map.entry("longsword_wall",h->obstruction(h,false)),
                Map.entry("longsword_reach",h->obstruction(h,true)),
                Map.entry("longsword_cone",LongswordGameTests::cone),
                Map.entry("longsword_guard",LongswordGameTests::guard),
                Map.entry("longsword_cast",LongswordGameTests::cast),
                Map.entry("longsword_cast_beam",LongswordGameTests::castBeam));
        tests.forEach((name,test)->FUNCTIONS.register(name,()->test)); FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event)->{
            var env=event.registerEnvironment(LogosDimension.id("longsword"),new TestEnvironmentDefinition.AllOf());
            for(var name:tests.keySet())event.registerTest(LogosDimension.id(name),new FunctionGameTestInstance(
                    ResourceKey.create(Registries.TEST_FUNCTION,LogosDimension.id(name)),new TestData<>(env,
                    Identifier.withDefaultNamespace("empty"),150,0,true,Rotation.NONE,false,1,1,false,16)));
        });
    }
    private record Fixture(GameTestHelper h,ServerPlayer p,LivingEntity target,Vec3 origin) {
        static Fixture create(GameTestHelper h) { return create(h, new ItemStack(LongswordItem.BLACKSTEEL.get())); }
        static Fixture create(GameTestHelper h, ItemStack sword) {
            var cookie=CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(),"sword-test"),false);
            var p=new ServerPlayer(h.getLevel().getServer(),h.getLevel(),cookie.gameProfile(),cookie.clientInformation());
            var connection=new Connection(PacketFlow.SERVERBOUND); new EmbeddedChannel(connection);
            h.getLevel().getServer().getPlayerList().placeNewPlayer(connection,p,cookie);
            p.connection.markClientLoaded(); p.setGameMode(GameType.SURVIVAL); p.tickCount=1; p.setNoGravity(true);
            var origin=h.absoluteVec(new Vec3(4,10,4));p.setPos(origin); p.setYRot(-90);p.setYHeadRot(-90);p.setXRot(0);
            p.setItemInHand(InteractionHand.MAIN_HAND,sword);
            var target=EntityType.IRON_GOLEM.create(h.getLevel(),EntitySpawnReason.COMMAND);
            target.setNoAi(true);target.setNoGravity(true);target.setPos(origin.add(2.3,0,0));h.getLevel().addFreshEntity(target);
            // 嵌入连接没有真实网络循环；逐世界刻更新装备和冷却，重置实体刻数以跳过无客户端的周期影雾包。
            h.onEachTick(()->{p.tickCount=1;p.doTick();p.setPos(origin);p.setDeltaMovement(Vec3.ZERO);p.setYRot(-90);p.setYHeadRot(-90);p.setXRot(0);});
            return new Fixture(h,p,target,origin);
        }
        void at(int delay,Runnable action) {h.runAfterDelay(delay,()->{try{action.run();}catch(RuntimeException|Error e){close();throw e;}});}
        void idle(){h.assertTrue("idle".equals(LongswordCombat.move(p)),"未完成拔剑");}
        void attack(){LongswordCombat.attack(p);}
        void close(){p.stopUsingItem();target.discard();h.getLevel().getServer().getPlayerList().remove(p);}
        void succeed(){close();h.succeed();}
        void use(){p.getMainHandItem().use(h.getLevel(),p,InteractionHand.MAIN_HAND);}
    }
    private static void contact(GameTestHelper h) {
        var f=Fixture.create(h);
        f.at(30,()->{f.idle();h.assertTrue(Math.abs(f.p.getAttributeValue(Attributes.ATTACK_SPEED)-1.6)<.001,"攻速不是原版剑节奏");for(int i=0;i<100;i++)f.attack();});
        f.at(33,()->h.assertTrue(f.target.getHealth()==100,"前摇提前造成伤害"));
        f.at(38,()->h.assertTrue(f.target.getHealth()==89.5f && f.p.getMainHandItem().getDamageValue()==1,"准星正对的目标没有暴击（7×1.5）或没有消耗一点耐久：生命="+f.target.getHealth()));
        f.at(47,()->{f.idle();h.assertTrue(f.target.getHealth()==89.5f,"同一刻输入重复命中或跳过冷却");f.succeed();});
    }
    private static void combo(GameTestHelper h) {
        var f=Fixture.create(h);
        // 出刀后缓冲下一段，在取消点（0.36 秒）接上；三下命中只隔 7–8 刻，也检查连招不会被自己上一下的受击冷却挡掉。
        f.at(30,f::attack);f.at(33,f::attack);
        f.at(37,()->h.assertTrue("attack_1".equals(LongswordCombat.move(f.p)),"缓冲的第二段提前接上"));
        f.at(39,()->h.assertTrue("attack_2".equals(LongswordCombat.move(f.p)),"第二段没有在取消点接续"));
        f.at(40,f::attack);
        f.at(46,()->h.assertTrue("attack_3".equals(LongswordCombat.move(f.p)),"第三段没有接续"));
        // 伤害走原版攻击冷却：提前接刀的两下按恢复程度减伤，所以只要求三下都命中且累计超过一下。
        f.at(70,()->{h.assertTrue(f.p.getMainHandItem().getDamageValue()==3 && f.target.getHealth()<90,"三段没有分别命中：生命="+f.target.getHealth()+" 耐久消耗="+f.p.getMainHandItem().getDamageValue());f.idle();f.succeed();});
    }
    /** 双手蓄力下劈现在只有曦誓用（拔剑 1.8 秒，比黑钢长剑晚 12 刻）；曦誓松手时还会多放一道剑气，所以只要求至少挨了下劈一刀。 */
    private static void charge(GameTestHelper h) {
        var f=Fixture.create(h,new ItemStack(LongswordItem.DAWNVOW.get()));f.at(42,f::use);
        f.at(67,()->{h.assertTrue(List.of("charge_start","charge_hold").contains(LongswordCombat.move(f.p)) && f.target.getHealth()==100,"蓄力期间伤害或结束");f.p.releaseUsingItem();});
        f.at(74,()->h.assertTrue(f.target.getHealth()==100,"下劈前举提前造成伤害"));
        f.at(81,()->h.assertTrue(f.target.getHealth()<=89.5f,"释放后没有下劈命中（准星正对的暴击 10.5）：生命="+f.target.getHealth()));
        f.at(92,f::succeed);
    }
    private static void cancel(GameTestHelper h) {
        var f=Fixture.create(h,new ItemStack(LongswordItem.DAWNVOW.get()));f.at(42,f::use);f.at(46,()->f.p.releaseUsingItem());
        f.at(72,()->{f.idle();h.assertTrue(f.target.getHealth()==100,"短按取消仍有伤害");f.succeed();});
    }
    /** 黑钢长剑挂上一张术式载页，并给 1000 魔力。 */
    private static ItemStack castingSword(String spell) {
        var sword=new ItemStack(LongswordItem.BLACKSTEEL.get());
        sword.set(net.minecraft.core.component.DataComponents.CONTAINER,net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(
                dev.logos.item.SpellPages.inscribed(dev.logos.item.PageTier.CRYSTAL,spell,null))));
        return sword;
    }
    private static void mana(ServerPlayer p){var m=dev.logos.runtime.PlayerMagic.of(p);m.maxMana=m.mana=1000;m.trace=false;}
    /** 黑钢长剑格挡：0.3 秒举剑前摇之后挡住正面的一击，不掉血也不损耗耐久；没挂载页时左键开不起蓄力，仍在格挡；松开放下剑。 */
    private static void guard(GameTestHelper h) {
        var f=Fixture.create(h);f.at(30,f::use);
        f.at(34,()->h.assertTrue(SwordCasting.GUARD_RAISE.equals(LongswordCombat.move(f.p)) && !f.p.isBlocking(),"举剑前摇里就挡住了"));
        f.at(40,()->{
            h.assertTrue(f.p.isBlocking(),"前摇之后没有格挡");
            float health=f.p.getHealth();
            f.p.hurtServer(h.getLevel(),f.p.damageSources().mobAttack(f.target),6);
            h.assertTrue(f.p.getHealth()==health && f.p.getMainHandItem().getDamageValue()==0,"格挡没有挡住或损耗了耐久：生命="+f.p.getHealth()+" 耐久消耗="+f.p.getMainHandItem().getDamageValue());
            f.attack();
        });
        f.at(42,()->{h.assertTrue(SwordCasting.GUARD.equals(LongswordCombat.move(f.p)),"没挂载页时左键离开了格挡："+LongswordCombat.move(f.p));f.p.releaseUsingItem();});
        f.at(44,()->h.assertTrue("guard_lower".equals(LongswordCombat.move(f.p)),"松开右键没有放下剑："+LongswordCombat.move(f.p)));
        f.at(70,()->{f.idle();f.succeed();});
    }
    /**
     * 黑钢长剑施法：格挡中左键开始 Logos 蓄力（剑尖前指，不再格挡），蓄满后松开右键施放并向前一送；
     * 主手拿着时魔力缠绕的消耗与伤害属性为 0.7 与 1.3。
     */
    private static void cast(GameTestHelper h) {
        var f=Fixture.create(h,castingSword("(spell :name \"剑\" :mana 20 (mark caster #charged))"));mana(f.p);
        f.at(30,()->{
            h.assertTrue(Math.abs(f.p.getAttributeValue(dev.logos.runtime.SpellModifiers.WRAP_COST)-.7)<1e-9
                    && Math.abs(f.p.getAttributeValue(dev.logos.runtime.SpellModifiers.WRAP_POWER)-1.3)<1e-9,
                    "主手黑钢长剑的缠绕属性不对："+f.p.getAttributeValue(dev.logos.runtime.SpellModifiers.WRAP_COST)+"/"+f.p.getAttributeValue(dev.logos.runtime.SpellModifiers.WRAP_POWER));
            f.use();
        });
        f.at(33,f::attack);
        f.at(34,()->h.assertTrue(SwordCasting.CHARGE.equals(LongswordCombat.move(f.p)) && dev.logos.runtime.Charging.of(f.p)!=null,"格挡中左键没有开始蓄力："+LongswordCombat.move(f.p)));
        f.at(40,()->{
            float health=f.p.getHealth();
            f.p.hurtServer(h.getLevel(),f.p.damageSources().mobAttack(f.target),4);
            h.assertTrue(f.p.getHealth()<health,"蓄力时仍在格挡");
        });
        f.at(50,()->{
            h.assertTrue(dev.logos.runtime.Charging.of(f.p).full(),"10 多刻还没蓄满 20 魔力");
            f.p.releaseUsingItem();
            h.assertTrue(dev.logos.runtime.StatusData.stacks(f.p,new dev.logos.lang.Status("#charged"))==1,"松开右键没有施放");
            h.assertTrue(SwordCasting.THRUST.equals(LongswordCombat.move(f.p)),"施放后不是向前一送："+LongswordCombat.move(f.p));
        });
        f.at(75,()->{f.idle();f.succeed();});
    }
    /** 术式以光柱开头：施放后一直剑尖前指（Logos 的光柱姿势在），光柱结束后收回待机。 */
    private static void castBeam(GameTestHelper h) {
        var f=Fixture.create(h,castingSword("(spell :name \"光\" :mana 60 (beam :radius 0.3 :life 1 :every 0.5))"));mana(f.p);
        f.at(30,f::use);f.at(33,f::attack);
        f.at(50,()->{
            f.p.releaseUsingItem();
            h.assertTrue(SwordCasting.BEAM.equals(LongswordCombat.move(f.p)),"光柱开头的术式施放后没有直接保持前指："+LongswordCombat.move(f.p));
        });
        f.at(53,()->h.assertTrue(dev.logos.runtime.CastPose.holding(f.p).contains(dev.logos.runtime.CastPose.BEAM),"光柱开头的术式没有保持光柱姿势"));
        f.at(60,()->h.assertTrue(SwordCasting.BEAM.equals(LongswordCombat.move(f.p)),"光柱还在就收回了："+LongswordCombat.move(f.p)));
        f.at(100,()->{f.idle();h.assertTrue(!dev.logos.runtime.CastPose.holding(f.p).contains(dev.logos.runtime.CastPose.BEAM),"光柱姿势没有结束");f.succeed();});
    }
    private static void switchSlot(GameTestHelper h) {
        var f=Fixture.create(h);f.at(30,f::attack);f.at(32,()->f.p.getInventory().setSelectedSlot(2));
        f.at(40,()->h.assertTrue(f.target.getHealth()==100 && "sheathe".equals(LongswordCombat.move(f.p)),"换槽仍结算攻击或没有收剑"));
        f.at(60,()->{h.assertTrue(LongswordCombat.move(f.p)==null,"收剑没有释放双手");f.succeed();});
    }
    private static void drop(GameTestHelper h) {
        var f=Fixture.create(h);int slot=f.p.getInventory().getSelectedSlot();
        f.at(30,f::attack);f.at(32,()->f.p.drop(false));
        f.at(40,()->{
            h.assertTrue(f.p.getInventory().getSelectedSlot()==slot && f.p.getMainHandItem().isEmpty(),"丢弃后切槽或主手未空");
            h.assertTrue(LongswordCombat.move(f.p)==null && f.target.getHealth()==100,"Q 丢弃未清除动作或仍结算攻击");
            f.succeed();
        });
    }
    /**
     * 命中锥形：准星正对的铁傀儡暴击（7×1.5），斜前方 35° 的挨普通一刀（7），70° 外的和攻击距离外的不受伤；
     * 同一刀不再触发原版横扫。
     */
    private static void cone(GameTestHelper h) {
        var f=Fixture.create(h);
        var golems=new ArrayList<LivingEntity>();
        f.at(29,()->{
            f.target.setPos(f.origin.add(2.6,0,0));
            double[][] at={{35,2.7},{70,2.7},{0,5.2}};
            for(var a:at){
                var g=EntityType.IRON_GOLEM.create(h.getLevel(),EntitySpawnReason.COMMAND);
                g.setNoAi(true);g.setNoGravity(true);
                double r=Math.toRadians(a[0]);
                g.setPos(f.origin.add(Math.cos(r)*a[1],0,Math.sin(r)*a[1]));h.getLevel().addFreshEntity(g);golems.add(g);
            }
        });
        f.at(30,f::attack);
        f.at(45,()->{
            try{
                h.assertTrue(f.target.getHealth()==89.5f,"准星正对的铁傀儡没有暴击：生命="+f.target.getHealth());
                h.assertTrue(golems.get(0).getHealth()==93,"斜前方 35° 的铁傀儡没有挨到普通一刀：生命="+golems.get(0).getHealth());
                h.assertTrue(golems.get(1).getHealth()==100,"70° 外的铁傀儡也被打到了：生命="+golems.get(1).getHealth());
                h.assertTrue(golems.get(2).getHealth()==100,"攻击距离外的铁傀儡也被打到了：生命="+golems.get(2).getHealth());
            }finally{golems.forEach(Entity::discard);}
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
