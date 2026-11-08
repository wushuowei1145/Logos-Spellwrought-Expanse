package dev.logos.dimension.gametest;

import com.mojang.authlib.GameProfile;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.DiamondHitbox;
import dev.logos.dimension.item.LingxiaoSwordItem;
import dev.logos.dimension.item.LogosNatureBridge;
import dev.logos.dimension.lingxiao.FlyingSword;
import dev.logos.dimension.longsword.LongswordCombat;
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
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 真实服务端检查属性、延迟命中、菱形边缘、墙体、魔力、下剑及载具清理。 */
public final class LingxiaoGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS=DeferredRegister.create(Registries.TEST_FUNCTION,LogosDimension.ID);
    public static void register(IEventBus bus){
        var tests=Map.<String,Consumer<GameTestHelper>>ofEntries(
            Map.entry("lingxiao_attack",LingxiaoGameTests::attack),
            Map.entry("lingxiao_diamond",LingxiaoGameTests::diamond),
            Map.entry("lingxiao_wall",LingxiaoGameTests::wall),
            Map.entry("lingxiao_mana",LingxiaoGameTests::mana),
            Map.entry("lingxiao_exhaustion",LingxiaoGameTests::exhaustion),
            Map.entry("lingxiao_shift",h->stop(h,false)),
            Map.entry("lingxiao_switch",h->stop(h,true)),
            Map.entry("lingxiao_collision",LingxiaoGameTests::collision),
            Map.entry("lingxiao_creative",LingxiaoGameTests::creative),
            Map.entry("lingxiao_no_mana",LingxiaoGameTests::noMana),
            Map.entry("lingxiao_heading",LingxiaoGameTests::heading));
        tests.forEach((n,t)->FUNCTIONS.register(n,()->t));FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent e)->{
            var env=e.registerEnvironment(LogosDimension.id("lingxiao"),new TestEnvironmentDefinition.AllOf());
            for(var n:tests.keySet())e.registerTest(LogosDimension.id(n),new FunctionGameTestInstance(
                ResourceKey.create(Registries.TEST_FUNCTION,LogosDimension.id(n)),new TestData<>(env,
                Identifier.withDefaultNamespace("empty"),140,0,true,Rotation.NONE,false,1,1,false,16)));
        });
    }
    private static final Map<Long,Integer> FORCED=new HashMap<>();
    private static final class Fixture {
        final GameTestHelper h;final ServerPlayer p;final LivingEntity target;final Vec3 origin;final List<Long> chunks=new ArrayList<>();boolean closed;
        Fixture(GameTestHelper h){
            this.h=h;var cookie=CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(),"lingxiao-test"),false);
            p=new ServerPlayer(h.getLevel().getServer(),h.getLevel(),cookie.gameProfile(),cookie.clientInformation());
            var connection=new Connection(PacketFlow.SERVERBOUND);new EmbeddedChannel(connection);
            h.getLevel().getServer().getPlayerList().placeNewPlayer(connection,p,cookie);p.connection.markClientLoaded();
            p.setGameMode(GameType.SURVIVAL);p.tickCount=1;p.setNoGravity(true);
            origin=h.absoluteVec(new Vec3(4,10,4));
            // 嵌入连接不会发送区块跟踪包，强制加载测试区，让载具走真实世界实体刻。
            int cx=BlockPos.containing(origin).getX()>>4,cz=BlockPos.containing(origin).getZ()>>4;
            for(int x=cx-1;x<=cx+1;x++)for(int z=cz-1;z<=cz+1;z++){long key=new net.minecraft.world.level.ChunkPos(x,z).pack();chunks.add(key);if(FORCED.merge(key,1,Integer::sum)==1)h.getLevel().setChunkForced(x,z,true);}
            p.setPos(origin);p.setYRot(-90);p.setYHeadRot(-90);p.setXRot(0);
            p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(LingxiaoSwordItem.ITEM.get()));
            target=mob(origin.add(2.3,0,0));dev.logos.runtime.PlayerMagic.of(p).maxMana=1000;LogosNatureBridge.mana(p,100);
            // Logos 的自然回复照常进行：御剑连回复一起扣，测的就是面板上实际少了多少。
            h.onEachTick(()->{if(closed)return;p.tickCount=1;
                p.doTick();p.setYRot(-90);p.setYHeadRot(-90);p.setXRot(0);});
        }
        LivingEntity mob(Vec3 position){
            var e=EntityType.IRON_GOLEM.create(h.getLevel(),EntitySpawnReason.COMMAND);
            e.setNoAi(true);e.setNoGravity(true);e.setPos(position);h.getLevel().addFreshEntity(e);return e;
        }
        void at(int tick,Runnable action){h.runAfterDelay(tick,()->{try{action.run();}catch(RuntimeException|Error e){close();throw e;}});}
        void use(){p.getMainHandItem().use(h.getLevel(),p,InteractionHand.MAIN_HAND);}
        void idle(){h.assertTrue("idle".equals(LongswordCombat.move(p)),"尚未完成拔剑");}
        void close(){if(closed)return;closed=true;if(p.getVehicle() instanceof FlyingSword s)s.stop();target.discard();h.getLevel().getServer().getPlayerList().remove(p);
            for(long key:chunks){int count=FORCED.get(key)-1;if(count==0){FORCED.remove(key);var c=net.minecraft.world.level.ChunkPos.unpack(key);h.getLevel().setChunkForced(c.x(),c.z(),false);}else FORCED.put(key,count);}
        }
        void success(){close();h.succeed();}
    }
    private static void attack(GameTestHelper h){
        var f=new Fixture(h);
        f.at(30,()->{f.idle();h.assertTrue(Math.abs(f.p.getAttributeValue(Attributes.ATTACK_DAMAGE)-6)<.001,"基础伤害不是铁剑六点");
            h.assertTrue(Math.abs(f.p.getAttributeValue(Attributes.ATTACK_SPEED)-2.4)<.001,"攻速不是铁剑一点五倍");LongswordCombat.attack(f.p);});
        f.at(31,()->h.assertTrue(f.target.getHealth()==100,"前摇尚未到命中点却造成伤害"));
        f.at(36,()->{h.assertTrue(f.target.getHealth()==94,"普通铁剑伤害不应额外强制暴击："+f.target.getHealth());
            h.assertTrue(f.p.getMainHandItem().getDamageValue()==1,"命中未消耗一点耐久");f.success();});
    }
    private static void diamond(GameTestHelper h){
        var f=new Fixture(h);var side=f.mob(f.origin.add(1.5,0,1.1));var rear=f.mob(f.origin.add(-2.3,0,0));var outside=f.mob(f.origin.add(1.5,0,3));
        f.at(30,()->{f.idle();var hits=DiamondHitbox.targets(f.p);h.assertTrue(hits.contains(f.target)&&hits.contains(side),"菱形中心或边缘碰撞箱漏检");
            h.assertTrue(!hits.contains(rear)&&!hits.contains(outside),"菱形判定打到后方或外侧");side.discard();rear.discard();outside.discard();f.success();});
    }
    private static void wall(GameTestHelper h){
        var f=new Fixture(h);
        f.at(30,()->{f.target.setPos(f.origin.add(2.8,0,0));var wall=BlockPos.containing(f.origin.add(1,0,0));for(int y=-1;y<5;y++)for(int z=-2;z<=2;z++)h.getLevel().setBlockAndUpdate(wall.offset(0,y,z),Blocks.STONE.defaultBlockState());
            h.assertTrue(!DiamondHitbox.targets(f.p).contains(f.target),"墙后目标仍被选中");LongswordCombat.attack(f.p);});
        f.at(39,()->{h.assertTrue(f.target.getHealth()==100,"菱形伤害穿过实体墙");f.success();});
    }
    private static void mana(GameTestHelper h){
        var f=new Fixture(h);f.target.discard();
        f.at(30,()->{f.idle();LogosNatureBridge.mana(f.p,100);f.use();h.assertTrue(f.p.getVehicle() instanceof FlyingSword,"右键没有开始御剑");h.assertTrue(!f.p.getVehicle().shouldBeSaved(),"临时载具不应保存");});
        f.at(50,()->{double used=100-LogosNatureBridge.mana(f.p);h.assertTrue(used>=9.5&&used<=10.5,"自然回复照常时一秒应实际少十魔力："+used);
            h.assertTrue(f.p.isNoGravity()&&!f.p.getAbilities().mayfly&&!f.p.getAbilities().flying,"御剑污染了骑手原有重力或飞行能力");f.success();});
    }
    private static void exhaustion(GameTestHelper h){
        var f=new Fixture(h);f.target.discard();final double[] lowest={1};
        f.at(30,()->{LogosNatureBridge.mana(f.p,1);f.use();h.assertTrue(f.p.isPassenger(),"一点魔力也应能起飞一刻");});
        for(int t=31;t<37;t++)f.at(t,()->lowest[0]=Math.min(lowest[0],LogosNatureBridge.mana(f.p)));
        f.at(37,()->{h.assertTrue(!f.p.isPassenger(),"魔力耗尽未下剑");h.assertTrue(lowest[0]>=0,"魔力扣成负数："+lowest[0]);f.success();});
    }
    private static void stop(GameTestHelper h,boolean swap){
        var f=new Fixture(h);f.target.discard();final FlyingSword[] sword={null};
        f.at(30,()->{f.use();sword[0]=(FlyingSword)f.p.getVehicle();h.assertTrue(sword[0]!=null,"不能开始御剑");});
        f.at(35,()->{if(swap)f.p.getInventory().setSelectedSlot(1);else f.p.setLastClientInput(new Input(false,false,false,false,false,true,false));});
        f.at(39,()->{h.assertTrue(!f.p.isPassenger()&&sword[0].isRemoved(),"下剑后仍有载具或切槽未结束御剑");f.success();});
    }
    private static void collision(GameTestHelper h){
        var f=new Fixture(h);f.target.discard();
        f.at(30,()->{var wall=BlockPos.containing(f.origin.add(3,0,0));for(int y=-1;y<6;y++)for(int z=-2;z<=2;z++)h.getLevel().setBlockAndUpdate(wall.offset(0,y,z),Blocks.STONE.defaultBlockState());
            f.use();f.p.setLastClientInput(new Input(true,false,false,false,false,false,false));});
        f.at(52,()->{h.assertTrue(f.p.getX()>f.origin.x+.5&&f.p.getX()<f.origin.x+2.7,"御剑未移动或穿过墙体："+(f.p.getX()-f.origin.x));f.success();});
    }
    private static void creative(GameTestHelper h){
        var f=new Fixture(h);f.target.discard();
        final double[] start={0};
        f.at(30,()->{f.p.setGameMode(GameType.CREATIVE);f.p.getAbilities().flying=true;LogosNatureBridge.mana(f.p,0);f.use();start[0]=LogosNatureBridge.mana(f.p);});
        // 自然回复照常，只要没被扣就行。
        f.at(38,()->{h.assertTrue(f.p.isPassenger()&&LogosNatureBridge.mana(f.p)>=start[0],"创造御剑未免魔力："+LogosNatureBridge.mana(f.p));f.p.stopRiding();
            h.assertTrue(f.p.getAbilities().mayfly&&f.p.getAbilities().flying,"下剑清掉了原有创造飞行");f.success();});
    }
    private static void noMana(GameTestHelper h){
        var f=new Fixture(h);f.at(30,()->{LogosNatureBridge.mana(f.p,.49);f.use();h.assertTrue(!f.p.isPassenger()&&LogosNatureBridge.mana(f.p)==.49,"魔力不足仍起飞或吞掉余额");f.success();});
    }
    /** 剑尖顺着飞行方向：面朝东往左侧飞（朝北），载具的偏航转到北；改成前进再转回东。 */
    private static void heading(GameTestHelper h){
        var f=new Fixture(h);f.target.discard();final FlyingSword[] sword={null};
        f.at(30,()->{f.use();sword[0]=(FlyingSword)f.p.getVehicle();h.assertTrue(sword[0]!=null,"不能开始御剑");
            h.assertTrue(Math.abs(net.minecraft.util.Mth.wrapDegrees(sword[0].getYRot()-f.p.yBodyRot))<1,"起飞时剑尖应先朝身体方向："+sword[0].getYRot()+" / "+f.p.yBodyRot);});
        f.at(44,()->f.p.setLastClientInput(new Input(false,false,true,false,false,false,false)));
        f.at(56,()->{h.assertTrue(Math.abs(net.minecraft.util.Mth.wrapDegrees(sword[0].getYRot()-180))<3,"左侧飞时剑尖没有转向北："+sword[0].getYRot());
            h.assertTrue(Math.abs(sword[0].getXRot())<3,"平飞时剑尖不该俯仰："+sword[0].getXRot());
            f.p.setLastClientInput(new Input(true,false,false,false,false,false,false));});
        f.at(68,()->{h.assertTrue(Math.abs(net.minecraft.util.Mth.wrapDegrees(sword[0].getYRot()+90))<3,"前进时剑尖没有转回东："+sword[0].getYRot());
            h.assertTrue(f.p.getX()-f.origin.x>3,"前进太慢："+(f.p.getX()-f.origin.x));f.success();});
    }
    private LingxiaoGameTests(){}
}
