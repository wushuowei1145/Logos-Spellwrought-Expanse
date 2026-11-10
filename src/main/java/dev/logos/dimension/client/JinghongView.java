package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.client.jinghong.SwordArrayClient;
import dev.logos.dimension.client.longsword.LongswordClient;
import dev.logos.dimension.item.JinghongSwordItem;
import dev.logos.dimension.jinghong.SwordArray;
import dev.logos.dimension.jinghong.SwordFlight;
import dev.logos.dimension.longsword.LongswordCombat;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * 实际客户端走一遍惊鸿（docs/JINGHONG.md）：剑身刻度（0、2、5 把）、身后剑阵（背面、左前）、左键追击、横剑格挡、
 * 弹剑反击、两把激活时掷剑协同、万剑归宗（侧面机位，结环、剑雨、贯下各一张）、收剑合拢、掉在地上的立体模型。
 * 追击和格挡的概率在场景里定成 1（{@link SwordArray#testChance}），拍到的每一步都确定。飞剑的画面定格（/tick freeze）再截。
 * 正面的几张用左前方的机位：原版第三人称正面的镜头会被站在玩家面前的靶子挡住。
 */
@LDLRegisterClient(name="jinghong",group="jinghong",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
public final class JinghongView implements UIScenario {
    private int target, dropped, front, side;
    private boolean phase(String move){
        var mc=Minecraft.getInstance();if(mc.player==null)return false;
        var p=LongswordClient.pose(mc.player.getUUID());return p!=null&&p.variant()==JinghongSwordItem.WEAPON.id()&&p.move().equals(move);
    }
    private static double now(){var mc=Minecraft.getInstance();return mc.level.getGameTime()+mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);}
    private static java.util.UUID self(){return Minecraft.getInstance().player.getUUID();}
    private static int lit(){return SwordArrayClient.lit(self(),now());}
    private static int flying(){return SwordArrayClient.flying(self(),now());}
    private static boolean frozen(){return !Minecraft.getInstance().level.tickRateManager().runsNormally();}
    private static void camera(CameraType type){Minecraft.getInstance().options.setCameraType(type);}
    private static void attack(boolean down){Minecraft.getInstance().options.keyAttack.setDown(down);}
    private static void use(boolean down){Minecraft.getInstance().options.keyUse.setDown(down);}
    /** 定格、截图、恢复。 */
    private static ScenarioBuilder shot(ScenarioBuilder s,String name){
        return s.runCommand("tick freeze").waitUntil(name+" 定格",ctx->frozen()).ticks(2).screenshot(name)
            .runCommand("tick unfreeze").waitUntil(name+" 恢复",ctx->!frozen());
    }
    /** 一个隐身盔甲架机位，在 {@code at} 看向 {@code look}。 */
    private static int stand(ServerLevel level,Vec3 at,Vec3 look){
        var st=EntityType.ARMOR_STAND.create(level,EntitySpawnReason.COMMAND);
        st.setInvisible(true);st.setNoGravity(true);
        double dx=look.x-at.x,dy=look.y-at.y,dz=look.z-at.z;
        st.snapTo(at.x,at.y-st.getEyeHeight(),at.z,(float)-Math.toDegrees(Math.atan2(dx,dz)),(float)-Math.toDegrees(Math.atan2(dy,Math.hypot(dx,dz))));
        level.addFreshEntity(st);return st.getId();
    }
    /** 切到机位：镜头就在机位上（第一人称），玩家的身体照画；原版仍会画第一人称的手，所以藏起界面。 */
    private static void view(int id){
        var mc=Minecraft.getInstance();Entity st=mc.level.getEntity(id);
        st.setYHeadRot(st.getYRot());st.yRotO=st.getYRot();st.xRotO=st.getXRot();
        mc.setCameraEntity(st);camera(CameraType.FIRST_PERSON);mc.options.hideGui=true;
    }
    private static void player(CameraType type){var mc=Minecraft.getInstance();mc.setCameraEntity(mc.player);mc.options.hideGui=false;camera(type);}
    @Override public void configure(ScenarioOptions options){options.tags("jinghong").defaultTimeoutMs(60000).scenarioTimeoutMs(300000);}
    @Override public void define(ScenarioBuilder s){
        s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("weather clear").runCommand("difficulty easy")
            .runCommand("gamerule spawn_mobs false").runCommand("gamerule spawn_monsters false")
            .step("设置视口",ctx->{var mc=Minecraft.getInstance();camera(CameraType.FIRST_PERSON);mc.options.hideGui=false;
                var w=mc.getWindow();w.setWindowed(1280,720);w.setWidth(1280);w.setHeight(720);
                mc.getMainRenderTarget().resize(1280,720);mc.gameRenderer.resize(1280,720);mc.resizeGui();})
            .server("设置练剑场地",ctx->{var l=ctx.server().overworld();
                for(int x=-14;x<=14;x++)for(int z=-10;z<=16;z++){
                    l.setBlockAndUpdate(new BlockPos(x,99,z),Blocks.STONE_BRICKS.defaultBlockState());
                    for(int y=100;y<=112;y++)l.removeBlock(new BlockPos(x,y,z),false);
                }
                DimensionTravel.teleport(ctx.player(),l,new Vec3(.5,100,.5),0,0);
                ctx.player().getInventory().clearContent();ctx.player().getInventory().setSelectedSlot(0);
                ctx.player().setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(JinghongSwordItem.ITEM.get()));
                // 靶子血量给够：一整套下来伤害不少
                var e=EntityType.IRON_GOLEM.create(l,EntitySpawnReason.COMMAND);e.setNoAi(true);e.setPos(.5,100,2.8);
                e.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);e.setHealth(1000);l.addFreshEntity(e);target=e.getId();
                // 机位：玩家左前方（看正面、格挡、反击），侧面远处（看万剑归宗的剑环）
                front=stand(l,new Vec3(3.6,101.7,3.4),new Vec3(.5,101.2,.6));
                side=stand(l,new Vec3(8.2,104.2,1.6),new Vec3(.5,104.6,1.7));
            }).setGameMode(GameType.SURVIVAL)
            .waitUntil("机位同步",ctx->Minecraft.getInstance().level.getEntity(front)!=null&&Minecraft.getInstance().level.getEntity(side)!=null)
            .waitUntil("持剑待机同步",ctx->phase("idle")).ticks(3).screenshot("fp_gauge_0")
            .check("还没有激活的剑",ctx->lit()==0)
            .checkServer("数值同凌霄：铁剑伤害、攻速 2.4",ctx->Math.abs(ctx.player().getAttributeValue(Attributes.ATTACK_DAMAGE)-6)<.001
                    &&Math.abs(ctx.player().getAttributeValue(Attributes.ATTACK_SPEED)-2.4)<.001)
            // ---- 刻度与剑阵
            .server("激活两把",ctx->SwordArray.activateForTest(ctx.player(),2)).ticks(3)
            .check("客户端收到两把激活",ctx->lit()==2).screenshot("fp_gauge_2")
            .step("第三人称背面",ctx->camera(CameraType.THIRD_PERSON_BACK)).ticks(4)
            .check("身后五把都画了",ctx->SwordArrayClient.drawnFan()==SwordFlight.FAN).screenshot("tp_back_fan")
            .step("左前机位",ctx->view(front)).ticks(4).screenshot("front_fan")
            .step("第一人称",ctx->player(CameraType.FIRST_PERSON)).ticks(3)
            .check("第一人称不画自己身后那一扇",ctx->SwordArrayClient.drawnFan()==0)
            // ---- 左键追击：概率定成 1，第一刀砍中时五把都飞出去
            .server("追击概率定为 1",ctx->{SwordArray.testChance(ctx.player(),1.0);SwordArray.activateForTest(ctx.player(),0);})
            .step("第三人称背面",ctx->camera(CameraType.THIRD_PERSON_BACK)).ticks(3)
            .step("按住左键",ctx->attack(true))
            .waitUntil("进入第一式",ctx->phase("attack_1"))
            .waitUntil("身后的剑飞出去",ctx->flying()>=3).ticks(4);
        shot(s,"tp_follow_strike")
            .step("松开左键",ctx->attack(false))
            .waitUntil("连招收势",ctx->phase("idle"))
            .waitUntil("剑都回到身后",ctx->flying()==0)
            .check("被触发的五把都激活",ctx->lit()==SwordFlight.FAN)
            .checkServer("追击伤到靶子",ctx->ctx.server().overworld().getEntity(target) instanceof LivingEntity l&&l.getHealth()<1000-6)
            .ticks(2).screenshot("tp_back_fan_lit")
            .step("第一人称看刻度",ctx->camera(CameraType.FIRST_PERSON)).ticks(3).screenshot("fp_gauge_5")
            // ---- 横剑格挡与弹剑
            .server("激活清零",ctx->SwordArray.activateForTest(ctx.player(),0))
            .step("按住右键",ctx->use(true))
            .waitUntil("进入格挡",ctx->phase("guard")).ticks(6).screenshot("fp_guard")
            .step("左前机位看格挡",ctx->view(front)).ticks(3).screenshot("front_guard")
            .step("松开右键",ctx->use(false)).waitUntil("回到待机",ctx->phase("idle")).ticks(2)
            .step("再按住右键",ctx->use(true))
            // 格挡开始的那一刻就挨靶子一拳：真实走原版格挡与 LivingShieldBlockEvent，落在 0.3 秒的弹剑窗口里
            .waitUntilServer("格挡一开始就挨一拳",ctx->{
                if(!"guard".equals(LongswordCombat.move(ctx.player())))return false;
                var l=ctx.server().overworld();
                if(l.getEntity(target) instanceof LivingEntity g)ctx.player().hurtServer(l,ctx.player().damageSources().mobAttack(g),4);
                return true;})
            .waitUntil("弹剑",ctx->phase("parry"))
            .waitUntil("反击的剑飞出去",ctx->flying()>=SwordArray.PARRY_MIN).ticks(3);
        shot(s,"front_parry_counter")
            .checkServer("挡下了，没掉血",ctx->ctx.player().getHealth()>=ctx.player().getMaxHealth()-.01)
            .waitUntilServer("反击的剑都回位",ctx->SwordArray.settled(ctx.player()))
            // ---- 两把激活时掷剑：手里一把加身后两把
            .server("激活两把",ctx->SwordArray.activateForTest(ctx.player(),2))
            .waitUntil("仍在格挡",ctx->phase("guard")).ticks(2)
            .step("格挡中按左键",ctx->attack(true))
            .waitUntil("掷剑",ctx->phase("throw"))
            .waitUntil("三把剑在外",ctx->flying()>=3).ticks(3);
        shot(s,"front_throw_volley")
            .step("松开左键",ctx->attack(false))
            .waitUntil("剑都回来",ctx->flying()==0)
            .check("掷剑之后激活清除",ctx->lit()==0)
            .waitUntilServer("剑阵收回",ctx->SwordArray.settled(ctx.player()))
            // ---- 万剑归宗：侧面机位
            .server("五把都激活",ctx->SwordArray.activateForTest(ctx.player(),SwordFlight.FAN))
            .step("第一人称看满刻度",ctx->player(CameraType.FIRST_PERSON)).ticks(4).screenshot("fp_gauge_full")
            .step("侧面机位",ctx->view(side)).ticks(3).screenshot("side_ready")
            .waitUntil("仍在格挡",ctx->phase("guard"))
            .step("格挡中按左键",ctx->attack(true))
            .waitUntil("万剑归宗",ctx->SwordArrayClient.finale(self(),now()))
            .step("松开左键",ctx->attack(false))
            .waitUntil("剑环结成",ctx->SwordArrayClient.finaleAge(self(),now())>=SwordFlight.FINALE_GATHER+1.5);
        shot(s,"side_finale_ring")
            .waitUntil("剑雨",ctx->SwordArrayClient.finaleAge(self(),now())>=SwordFlight.PHANTOM_FIRST_HIT+6)
            .check("虚影剑在画",ctx->SwordArrayClient.drawnPhantoms()>0);
        shot(s,"side_finale_rain")
            .waitUntil("贯下",ctx->SwordArrayClient.finaleAge(self(),now())>=SwordFlight.PLUNGE_HIT+.5);
        shot(s,"side_finale_plunge")
            .waitUntil("万剑归宗结束",ctx->!SwordArrayClient.finale(self(),now()))
            .check("万剑归宗之后激活清除",ctx->lit()==0)
            // ---- 收剑合拢、掉在地上的立体模型
            .step("松开右键，回到玩家",ctx->{use(false);player(CameraType.THIRD_PERSON_BACK);})
            .waitUntil("回到待机",ctx->phase("idle"))
            .step("切换空槽收剑",ctx->{var mc=Minecraft.getInstance();
                mc.player.getInventory().setSelectedSlot(1);mc.player.connection.send(new ServerboundSetCarriedItemPacket(1));})
            .waitUntil("归鞘同步",ctx->phase("sheathe")).ticks(4).screenshot("tp_sheathe_fold")
            .waitUntil("交回原版动作",ctx->LongswordClient.pose(self())==null).ticks(2)
            .check("收剑后身后的剑也收了",ctx->SwordArrayClient.drawnFan()==0)
            .server("地上放一把",ctx->{var l=ctx.server().overworld();
                for(int id:new int[]{target,front,side}){var e=l.getEntity(id);if(e!=null)e.discard();}
                var item=new ItemEntity(l,.5,100.1,2.0,new ItemStack(JinghongSwordItem.ITEM.get()),0,0,0);
                item.setNeverPickUp();item.setUnlimitedLifetime();l.addFreshEntity(item);dropped=item.getId();})
            .step("第一人称低头看",ctx->{player(CameraType.FIRST_PERSON);Minecraft.getInstance().player.setXRot(50);}).ticks(10).screenshot("fp_dropped")
            .server("收走地上的剑",ctx->{var e=ctx.server().overworld().getEntity(dropped);if(e!=null)e.discard();SwordArray.testChance(ctx.player(),null);})
            .teardown("恢复",ctx->{var mc=Minecraft.getInstance();attack(false);use(false);mc.player.setXRot(0);
                mc.player.connection.sendCommand("tick unfreeze");player(CameraType.FIRST_PERSON);});
    }
}
