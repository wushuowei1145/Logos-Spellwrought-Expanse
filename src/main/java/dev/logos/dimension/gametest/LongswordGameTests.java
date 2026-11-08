package dev.logos.dimension.gametest;

import com.mojang.authlib.GameProfile;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.Moveset;
import dev.logos.dimension.combat.Parry;
import dev.logos.dimension.item.LongswordItem;
import dev.logos.dimension.item.UchigatanaItem;
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
                Map.entry("longsword_mana_guard",LongswordGameTests::manaGuard),
                Map.entry("longsword_ryuka_guard",LongswordGameTests::ryukaGuard),
                Map.entry("longsword_cast",LongswordGameTests::cast),
                Map.entry("longsword_cast_beam",LongswordGameTests::castBeam),
                Map.entry("longsword_parried",LongswordGameTests::parried),
                Map.entry("longsword_parried_effect",LongswordGameTests::parriedEffect),
                Map.entry("longsword_parried_fox",LongswordGameTests::parriedFox),
                Map.entry("longsword_fox_parries",LongswordGameTests::foxParries),
                Map.entry("longsword_parried_others",LongswordGameTests::parriedOthers));
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
    private static double manaOf(ServerPlayer p){return dev.logos.runtime.PlayerMagic.of(p).mana;}
    /** 黑钢长剑格挡：0.3 秒举剑前摇之后挡住正面的一击，不掉血也不损耗耐久，改扣原版盾牌该掉的耐久那么多魔力（6 伤害 → 7）；没挂载页时左键开不起蓄力，仍在格挡；松开放下剑。 */
    private static void guard(GameTestHelper h) {
        var f=Fixture.create(h);mana(f.p);f.at(30,f::use);
        f.at(34,()->h.assertTrue(SwordCasting.GUARD_RAISE.equals(LongswordCombat.move(f.p)) && !f.p.isBlocking(),"举剑前摇里就挡住了"));
        f.at(40,()->{
            h.assertTrue(f.p.isBlocking(),"前摇之后没有格挡");
            float health=f.p.getHealth();
            f.p.hurtServer(h.getLevel(),f.p.damageSources().mobAttack(f.target),6);
            h.assertTrue(f.p.getHealth()==health && f.p.getMainHandItem().getDamageValue()==0,"格挡没有挡住或损耗了耐久：生命="+f.p.getHealth()+" 耐久消耗="+f.p.getMainHandItem().getDamageValue());
            h.assertTrue(Math.abs(manaOf(f.p)-993)<1e-9,"格挡 6 点伤害应扣 7 魔力，剩 "+manaOf(f.p));
            f.attack();
        });
        f.at(42,()->{h.assertTrue(SwordCasting.GUARD.equals(LongswordCombat.move(f.p)),"没挂载页时左键离开了格挡："+LongswordCombat.move(f.p));f.p.releaseUsingItem();});
        f.at(44,()->h.assertTrue("guard_lower".equals(LongswordCombat.move(f.p)),"松开右键没有放下剑："+LongswordCombat.move(f.p)));
        f.at(70,()->{f.idle();f.succeed();});
    }
    /** 持打刀的另一名玩家站在持剑者正前方，刚架刀时挨这一刀：弹刀不掉血不耗耐久；攻击方僵直 0.5 秒，身后的木桩不再挨刀，这段时间左键作废，播完能接着出招。 */
    private static void parried(GameTestHelper h) {
        var f=Fixture.create(h,new ItemStack(UchigatanaItem.INK.get()));
        var foe=new ServerPlayer[1];var health=new float[1];
        f.at(1,()->{foe[0]=facing(f,1.6);foe[0].setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(UchigatanaItem.INK.get()));foe[0].setGameMode(GameType.SURVIVAL);foe[0].connection.markClientLoaded();health[0]=foe[0].getHealth();});
        f.at(30,f::attack);
        f.at(31,()->foe[0].getMainHandItem().use(h.getLevel(),foe[0],InteractionHand.MAIN_HAND));
        f.at(35,()->{
            h.assertTrue(Moveset.PARRIED.equals(LongswordCombat.move(f.p)),"攻击方没有僵直："+LongswordCombat.move(f.p)+" 对方："+LongswordCombat.move(foe[0]));
            h.assertTrue("parry".equals(LongswordCombat.move(foe[0])),"对方没有弹刀："+LongswordCombat.move(foe[0]));
            h.assertTrue(foe[0].getHealth()==health[0] && foe[0].getMainHandItem().getDamageValue()==0,"弹刀掉了血或耗了耐久");
            h.assertTrue(f.target.getHealth()==100,"被弹开的这一刀还砍到了后面的木桩");
            f.attack();
        });
        f.at(40,()->{h.assertTrue(Moveset.PARRIED.equals(LongswordCombat.move(f.p)),"僵直提前结束或左键接上了："+LongswordCombat.move(f.p));f.attack();});
        f.at(46,()->{f.idle();f.attack();});
        f.at(48,()->{h.assertTrue("slash_1".equals(LongswordCombat.move(f.p)),"僵直之后没有从第一刀开始："+LongswordCombat.move(f.p));
            h.getLevel().getServer().getPlayerList().remove(foe[0]);f.succeed();});
    }
    /** 上挑被弹开：挑飞不生效。 */
    private static void parriedEffect(GameTestHelper h) {
        var f=Fixture.create(h,new ItemStack(dev.logos.dimension.item.OathSpearItem.PACK_OATH.get()));
        var foe=new ServerPlayer[1];
        f.at(1,()->{foe[0]=facing(f,1.6);foe[0].setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(UchigatanaItem.INK.get()));foe[0].setGameMode(GameType.SURVIVAL);foe[0].connection.markClientLoaded();});
        f.at(30,()->LongswordCombat.force(f.p,"launch_4"));
        f.at(36,()->foe[0].getMainHandItem().use(h.getLevel(),foe[0],InteractionHand.MAIN_HAND));
        f.at(39,()->{
            h.assertTrue(Moveset.PARRIED.equals(LongswordCombat.move(f.p)),"长枪没有僵直："+LongswordCombat.move(f.p));
            h.assertTrue(!dev.logos.dimension.combat.Airborne.is(foe[0]),"被弹开的上挑还是把人挑飞了");
            h.getLevel().getServer().getPlayerList().remove(foe[0]);f.succeed();
        });
    }
    /** 面朝 -X 站在持剑者身前、不走 AI（招式状态机照常跑）的神狐。 */
    private static dev.logos.dimension.npc.DivineFox fox(Fixture f,double ahead) {
        var fox=dev.logos.dimension.npc.NpcEntities.DIVINE_FOX.get().create(f.h.getLevel(),EntitySpawnReason.COMMAND);
        fox.setNoAi(true);fox.setNoGravity(true);var at=f.origin.add(ahead,0,0);fox.snapTo(at.x,at.y,at.z,90,0);fox.setYHeadRot(90);fox.setYBodyRot(90);
        f.h.getLevel().addFreshEntity(fox);fox.setKatana(1);fox.drawKatana();fox.setTarget(f.p);
        return fox;
    }
    /**
     * 神狐的一刀打在刚架刀的玩家身上：神狐僵直，这段时间挡不了（平时近战一半被格开）。
     * 无头服务器里没有玩家的区块不 tick 实体，神狐自己不会出刀，所以直接给玩家这一下；招式播完回到待机由实际客户端场景 {@code group:parried} 检查。
     */
    private static void parriedFox(GameTestHelper h) {
        var f=Fixture.create(h,new ItemStack(UchigatanaItem.INK.get()));
        var fox=fox(f,1.6);var health=new float[1];
        f.at(30,f::use);
        f.at(31,()->{
            health[0]=f.p.getHealth();
            f.p.hurtServer(h.getLevel(),f.p.damageSources().mobAttack(fox),6);
            h.assertTrue("parry".equals(LongswordCombat.move(f.p)) && f.p.getHealth()==health[0],"玩家没有弹刀："+LongswordCombat.move(f.p));
            h.assertTrue(fox.staggered(),"神狐没有僵直："+(fox.katanaMove()==null?null:fox.katanaMove().name()));
            h.assertTrue(Moveset.PARRIED_NPC.equals(fox.katanaMove().name()),"NPC 该用自己的僵直："+fox.katanaMove().name());
            fox.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);fox.setHealth(100);
            for(int i=0;i<24;i++){
                float before=fox.getHealth();fox.invulnerableTime=0;
                fox.hurtServer(h.getLevel(),f.p.damageSources().playerAttack(f.p),1);
                h.assertTrue(fox.getHealth()<before,"僵直里的神狐还是格开了第 "+i+" 下");
            }
            fox.discard();f.succeed();
        });
    }
    /** 神狐格开玩家的近战：玩家僵直；箭被挡下时射手不受影响。 */
    private static void foxParries(GameTestHelper h) {
        var f=Fixture.create(h,new ItemStack(UchigatanaItem.INK.get()));
        var fox=fox(f,1.6);
        f.at(30,()->{
            f.idle();fox.setTarget(null);
            fox.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);fox.setHealth(1000);
            var arrow=EntityType.ARROW.create(h.getLevel(),EntitySpawnReason.COMMAND);
            for(int i=0;i<30;i++){fox.invulnerableTime=0;fox.hurtServer(h.getLevel(),f.p.damageSources().arrow(arrow,f.p),.01f);}
            f.idle();
            for(int i=0;i<64 && !Moveset.PARRIED.equals(LongswordCombat.move(f.p));i++){fox.invulnerableTime=0;fox.hurtServer(h.getLevel(),f.p.damageSources().playerAttack(f.p),.01f);}
            h.assertTrue(Moveset.PARRIED.equals(LongswordCombat.move(f.p)),"神狐格开之后玩家没有僵直："+LongswordCombat.move(f.p));
            h.assertTrue("parry".equals(fox.katanaMove().name()),"神狐没有弹刀："+fox.katanaMove().name());
            f.attack();
        });
        f.at(34,()->h.assertTrue(Moveset.PARRIED.equals(LongswordCombat.move(f.p)),"僵直里左键接上了"));
        f.at(42,()->{f.idle();fox.discard();f.succeed();});
    }
    /** 不会反应的攻击方不受影响：原版生物、拿和弓的玩家；箭也不算近战。 */
    private static void parriedOthers(GameTestHelper h) {
        var f=Fixture.create(h,new ItemStack(dev.logos.dimension.item.YumiItem.BAMBOO.get()));
        f.at(30,()->{
            h.assertTrue(!LongswordCombat.parried(f.p),"和弓不该有被弹反的僵直");
            h.assertTrue(!Parry.stagger(f.p.damageSources().mobAttack(f.target),f.p),"原版生物不该有反应");
            h.assertTrue(!Parry.melee(f.p.damageSources().arrow(EntityType.ARROW.create(h.getLevel(),EntitySpawnReason.COMMAND),f.p)),"箭不是近战");
            h.assertTrue(Parry.melee(f.p.damageSources().playerAttack(f.p)),"玩家近战没有认出来");
            f.succeed();
        });
    }
    /** 站在持剑者正前方 {@code ahead} 格、面朝他的另一名玩家，用来朝他施法。 */
    private static ServerPlayer facing(Fixture f,double ahead) {
        var cookie=CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(),"sword-foe"),false);
        var foe=new ServerPlayer(f.h.getLevel().getServer(),f.h.getLevel(),cookie.gameProfile(),cookie.clientInformation());
        var connection=new Connection(PacketFlow.SERVERBOUND); new EmbeddedChannel(connection);
        f.h.getLevel().getServer().getPlayerList().placeNewPlayer(connection,foe,cookie);
        foe.setNoGravity(true);var at=f.origin.add(ahead,0,0);foe.snapTo(at.x,at.y,at.z,90,0);foe.setYHeadRot(90);mana(foe);
        return foe;
    }
    private static void castAt(GameTestHelper h,ServerPlayer caster,String src) {
        var inst=dev.logos.runtime.Casting.cast(caster,dev.logos.runtime.Casting.compile(src),null,null);
        h.assertTrue(inst!=null && !inst.dead,"施法失败："+src);
    }
    /**
     * 魔力格挡：魔法伤害照样挡、扣魔力（4 → 5）；虚空伤害穿透；魔力不够时挡不住，也不扣。正前方敌人的魔弹停在剑前，
     * 付炼成体的拦截费 0.5 加命中伤害折成的魔力（5 → 6）；虚空魔弹穿过打中；不许作用于玩家的术式穿过不扣魔力。
     */
    private static void manaGuard(GameTestHelper h) {
        var f=Fixture.create(h);mana(f.p);
        // 魔力每刻回复 0.3，关掉它才量得出每次格挡扣了多少。
        f.p.getAttribute(dev.logos.runtime.SpellModifiers.MANA_REGENERATION).addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                LogosDimension.id("test_no_regeneration"),-1,net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        // 被挡下的术式结束后剩下的魔力会变成游离魔力，飘到持剑者身上被吸收；每刻清掉。
        h.onEachTick(()->h.getLevel().getEntitiesOfClass(dev.logos.runtime.FreeManaEntity.class,new net.minecraft.world.phys.AABB(f.origin,f.origin).inflate(16)).forEach(Entity::discard));
        var foe=new ServerPlayer[1];var health=new float[1];
        f.at(30,f::use);
        f.at(40,()->{
            h.assertTrue(f.p.isBlocking(),"前摇之后没有格挡");
            float before=f.p.getHealth();
            f.p.hurtServer(h.getLevel(),f.p.damageSources().indirectMagic(f.target,f.target),4);
            h.assertTrue(f.p.getHealth()==before && Math.abs(manaOf(f.p)-995)<1e-9,"魔法伤害没有挡住或没扣 5 魔力：生命="+f.p.getHealth()+" 魔力="+manaOf(f.p));
            f.p.invulnerableTime=0;
            dev.logos.runtime.Effects.damage(f.target,f.p,4,dev.logos.lang.Element.VOID);
            h.assertTrue(f.p.getHealth()<before && Math.abs(manaOf(f.p)-995)<1e-9,"虚空伤害被挡住了：生命="+f.p.getHealth()+" 魔力="+manaOf(f.p));
            f.p.setHealth(f.p.getMaxHealth());f.p.invulnerableTime=0;
            dev.logos.runtime.PlayerMagic.of(f.p).mana=3;
            f.p.hurtServer(h.getLevel(),f.p.damageSources().mobAttack(f.target),6);
            h.assertTrue(f.p.getHealth()<f.p.getMaxHealth() && manaOf(f.p)==3,"魔力不够仍然挡住了或扣了魔力：生命="+f.p.getHealth()+" 魔力="+manaOf(f.p));
            f.p.setHealth(f.p.getMaxHealth());f.p.invulnerableTime=0;mana(f.p);
            // 傀儡挪到身后，不挡在敌人和持剑者之间。
            f.target.setPos(f.origin.add(-3,0,0));foe[0]=facing(f,6);health[0]=f.p.getHealth();
            castAt(h,foe[0],"(spell :players true :mana 30 (projectile :speed 1 (on-hit (damage target 5))))");
        });
        f.at(52,()->{
            h.assertTrue(f.p.getHealth()==health[0],"正面的魔弹没有挡住：生命="+f.p.getHealth());
            h.assertTrue(Math.abs(manaOf(f.p)-993.5)<1e-9,"挡下魔弹应扣 0.5 + 6 魔力，剩 "+manaOf(f.p));
            castAt(h,foe[0],"(spell :players true :mana 30 (projectile :speed 1 (on-hit (damage target 5 void))))");
        });
        f.at(64,()->{
            h.assertTrue(f.p.getHealth()<health[0] && Math.abs(manaOf(f.p)-993.5)<1e-9,"虚空魔弹没有穿透：生命="+f.p.getHealth()+" 魔力="+manaOf(f.p));
            castAt(h,foe[0],"(spell :mana 30 (projectile :speed 1 (on-hit (damage target 5))))");
        });
        f.at(76,()->{
            h.assertTrue(Math.abs(manaOf(f.p)-993.5)<1e-9,"打不着玩家的术式也扣了魔力，剩 "+manaOf(f.p));
            h.getLevel().getServer().getPlayerList().remove(foe[0]);f.succeed();
        });
    }
    /**
     * 流火的魔力护盾：拿斧头的木桩一刚架刀就砍来 → 弹刀，不扣魔力也不破防；过了弹刀窗口再砍 → 照样挡下，扣 6 伤害的 7 点
     * 加破盾 5 秒 × 4 = 20 点，不破防；魔法伤害照样挡（4 → 5）；虚空穿透；魔力只够挡不够顶住斧头时 → 挡下这一击，但破防 5 秒。
     */
    private static void ryukaGuard(GameTestHelper h) {
        var f=Fixture.create(h,new ItemStack(dev.logos.dimension.item.RyukaItem.ITEM.get()));mana(f.p);
        f.p.getAttribute(dev.logos.runtime.SpellModifiers.MANA_REGENERATION).addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                LogosDimension.id("test_no_regeneration"),-1,net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        var axe=new ItemStack(net.minecraft.world.item.Items.IRON_AXE);
        f.at(30,()->{f.idle();f.use();});
        f.at(32,()->{
            h.assertTrue(f.p.isBlocking() && "guard".equals(LongswordCombat.move(f.p)),"右键没有立即格挡："+LongswordCombat.move(f.p));
            f.target.setItemInHand(InteractionHand.MAIN_HAND,axe.copy());
            h.assertTrue(f.target.getSecondsToDisableBlocking()==5,"拿斧头的木桩不破盾");
            float health=f.p.getHealth();double before=manaOf(f.p);
            f.p.hurtServer(h.getLevel(),f.p.damageSources().mobAttack(f.target),6);
            h.assertTrue("parry".equals(LongswordCombat.move(f.p)),"架刀之初挨斧头没有弹刀："+LongswordCombat.move(f.p));
            h.assertTrue(f.p.getHealth()==health && manaOf(f.p)==before,"弹刀应挡下且不花魔力：生命="+f.p.getHealth()+" 魔力 "+before+" → "+manaOf(f.p));
            h.assertTrue(f.p.isUsingItem() && !f.p.getCooldowns().isOnCooldown(f.p.getMainHandItem()),"弹开斧头却破防了");
        });
        f.at(60,()->{
            h.assertTrue(f.p.isBlocking() && "guard".equals(LongswordCombat.move(f.p)),"弹刀后没有回到格挡："+LongswordCombat.move(f.p));
            float health=f.p.getHealth();double before=manaOf(f.p);f.p.invulnerableTime=0;
            f.p.hurtServer(h.getLevel(),f.p.damageSources().mobAttack(f.target),6);
            h.assertTrue("guard".equals(LongswordCombat.move(f.p)),"过了 0.5 秒仍然弹刀");
            h.assertTrue(f.p.getHealth()==health && Math.abs(before-manaOf(f.p)-27)<1e-9,"硬挡斧头应挡下并扣 7 + 20 魔力：生命="+f.p.getHealth()+" 扣了 "+(before-manaOf(f.p)));
            h.assertTrue(f.p.isUsingItem() && !f.p.getCooldowns().isOnCooldown(f.p.getMainHandItem()),"魔力够却破防了");
            // 魔法：木桩空手，不算破盾
            f.target.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
            before=manaOf(f.p);f.p.invulnerableTime=0;
            f.p.hurtServer(h.getLevel(),f.p.damageSources().indirectMagic(f.target,f.target),4);
            h.assertTrue(f.p.getHealth()==health && Math.abs(before-manaOf(f.p)-5)<1e-9,"魔法伤害没有挡住或没扣 5 魔力：生命="+f.p.getHealth()+" 扣了 "+(before-manaOf(f.p)));
            before=manaOf(f.p);f.p.invulnerableTime=0;
            dev.logos.runtime.Effects.damage(f.target,f.p,4,dev.logos.lang.Element.VOID);
            h.assertTrue(f.p.getHealth()<health && manaOf(f.p)==before,"虚空伤害被挡住了：生命="+f.p.getHealth());
            f.p.setHealth(f.p.getMaxHealth());health=f.p.getHealth();
            // 魔力只够这一下的 7 点，顶不住斧头的 20 点：挡下，但破防
            f.target.setItemInHand(InteractionHand.MAIN_HAND,axe.copy());
            dev.logos.runtime.PlayerMagic.of(f.p).mana=8;f.p.invulnerableTime=0;
            f.p.hurtServer(h.getLevel(),f.p.damageSources().mobAttack(f.target),6);
            h.assertTrue(f.p.getHealth()==health && Math.abs(manaOf(f.p)-1)<1e-9,"魔力不够顶住斧头时这一击也该挡下、只扣 7：生命="+f.p.getHealth()+" 魔力="+manaOf(f.p));
            h.assertTrue(!f.p.isUsingItem() && f.p.getCooldowns().isOnCooldown(f.p.getMainHandItem()),"魔力不够却没有破防");
        });
        f.at(80,()->{
            h.assertTrue(!f.p.isBlocking() && f.p.getCooldowns().isOnCooldown(f.p.getMainHandItem()),"破防没有持续");
            f.succeed();
        });
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
