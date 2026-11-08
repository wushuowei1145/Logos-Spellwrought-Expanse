package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.client.longsword.LongswordClient;
import dev.logos.dimension.item.LingxiaoSwordItem;
import dev.logos.dimension.item.LogosNatureBridge;
import dev.logos.dimension.lingxiao.FlyingSword;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * 实际客户端以原版输入包执行：四式连招（第一、第三人称各一遍）、右键起飞（剑在 handoff 那一刻交给载具）、
 * 侧飞时剑尖转向飞行方向、前进、上升、扣魔力、潜行下剑、切槽归鞘。
 */
@LDLRegisterClient(name="lingxiao",group="lingxiao",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
public final class LingxiaoView implements UIScenario {
    private int target;
    private volatile double startZ,startY,manaAt,ticksAt;
    private static final String[] FORMS={"attack_1","attack_2","attack_3","attack_4"};
    private boolean phase(String move){
        var mc=Minecraft.getInstance();if(mc.player==null)return false;
        var p=LongswordClient.pose(mc.player.getUUID());return p!=null&&p.variant()==LingxiaoSwordItem.WEAPON.id()&&p.move().equals(move);
    }
    private static double now(){var mc=Minecraft.getInstance();return mc.level==null?0:mc.level.getGameTime();}
    private static FlyingSword sword(){return Minecraft.getInstance().player.getVehicle() instanceof FlyingSword s?s:null;}
    private static void camera(CameraType type){Minecraft.getInstance().options.setCameraType(type);}
    /** 按住左键打完四式，每一式挥到头定住时拍一张。 */
    private ScenarioBuilder combo(ScenarioBuilder s,String view){
        s.step(view+" 按住左键",ctx->Minecraft.getInstance().options.keyAttack.setDown(true));
        for(var form:FORMS)s.waitUntil(view+" 进入 "+form,ctx->phase(form)).ticks(form.equals("attack_3")?7:form.equals("attack_4")?6:4).screenshot(view+"_"+form);
        return s.step(view+" 松开左键",ctx->Minecraft.getInstance().options.keyAttack.setDown(false))
            .waitUntil(view+" 连招收势结束",ctx->phase("idle"));
    }
    @Override public void configure(ScenarioOptions options){options.tags("lingxiao").defaultTimeoutMs(60000).scenarioTimeoutMs(240000);}
    @Override public void define(ScenarioBuilder s){
        s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("weather clear")
            .runCommand("gamerule spawn_mobs false").runCommand("gamerule spawn_monsters false")
            .step("设置视口",ctx->{var mc=Minecraft.getInstance();camera(CameraType.FIRST_PERSON);mc.options.hideGui=false;
                var w=mc.getWindow();w.setWindowed(1280,720);w.setWidth(1280);w.setHeight(720);
                mc.getMainRenderTarget().resize(1280,720);mc.gameRenderer.resize(1280,720);mc.resizeGui();})
            .server("设置练剑和飞行场地",ctx->{var l=ctx.server().overworld();
                for(int x=-24;x<=24;x++)for(int z=-8;z<=42;z++){
                    l.setBlockAndUpdate(new BlockPos(x,99,z),Blocks.STONE_BRICKS.defaultBlockState());
                    for(int y=100;y<=114;y++)l.removeBlock(new BlockPos(x,y,z),false);
                }
                DimensionTravel.teleport(ctx.player(),l,new Vec3(.5,100,.5),0,0);
                ctx.player().getInventory().clearContent();ctx.player().getInventory().setSelectedSlot(0);
                ctx.player().setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(LingxiaoSwordItem.ITEM.get()));
                var e=EntityType.IRON_GOLEM.create(l,EntitySpawnReason.COMMAND);e.setNoAi(true);e.setPos(.5,100,2.8);l.addFreshEntity(e);target=e.getId();
            }).setGameMode(GameType.SURVIVAL)
            .waitUntil("持剑待机同步",ctx->phase("idle")).ticks(3).screenshot("fp_idle")
            .checkServer("铁剑伤害与一点五倍攻速",ctx->Math.abs(ctx.player().getAttributeValue(Attributes.ATTACK_DAMAGE)-6)<.001
                    &&Math.abs(ctx.player().getAttributeValue(Attributes.ATTACK_SPEED)-2.4)<.001);
        combo(s,"fp")
            .checkServer("真实攻击造成伤害",ctx->ctx.server().overworld().getEntity(target) instanceof LivingEntity l&&l.getHealth()<100)
            // 正面机位在人和靶子之间，靶子会挡满画面：第三人称那一遍不要靶子。
            .server("移除靶子",ctx->{var e=ctx.server().overworld().getEntity(target);if(e!=null)e.discard();})
            .step("第三人称正面",ctx->camera(CameraType.THIRD_PERSON_FRONT)).ticks(4).screenshot("tp_idle");
        combo(s,"tp")
            .server("补充魔力",ctx->{LogosNatureBridge.mana(ctx.player(),200);
                startZ=ctx.player().getZ();startY=ctx.player().getY();})
            .step("第三人称背后并实际右键",ctx->{var mc=Minecraft.getInstance();camera(CameraType.THIRD_PERSON_BACK);
                mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);})
            .waitUntil("客户端收到御剑载具",ctx->sword()!=null)
            .check("剑还在手里时载具不画剑",ctx->!LongswordClient.handedOff(Minecraft.getInstance().player.getUUID(),now()))
            .ticks(5).screenshot("tp_takeoff_throw")
            .waitUntil("巡航动作同步",ctx->phase("flight_idle"))
            .check("交接后载具画剑",ctx->LongswordClient.handedOff(Minecraft.getInstance().player.getUUID(),now()))
            .ticks(3).screenshot("tp_flight")
            .server("记下魔力",ctx->{manaAt=LogosNatureBridge.mana(ctx.player());ticksAt=ctx.player().level().getGameTime();})
            .check("御剑保持站姿",ctx->{var mc=Minecraft.getInstance();var renderer=(net.minecraft.client.renderer.entity.player.AvatarRenderer<?>)mc.getEntityRenderDispatcher().getRenderer(mc.player);
                return Math.abs(renderer.getModel().rightLeg.xRot)<.4&&!mc.player.getAbilities().flying;})
            // 面朝南（偏航 0）往左侧飞是朝东（偏航 -90）：剑尖跟着转过去。
            .step("按左移键侧飞",ctx->Minecraft.getInstance().options.keyLeft.setDown(true)).ticks(14)
            .check("侧飞时剑尖转向飞行方向",ctx->sword()!=null&&Math.abs(Mth.wrapDegrees(sword().getYRot()+90))<12)
            .screenshot("tp_flight_strafe")
            .step("松开左移键",ctx->Minecraft.getInstance().options.keyLeft.setDown(false))
            .step("按前进键",ctx->Minecraft.getInstance().options.keyUp.setDown(true)).ticks(12)
            .check("前进时剑尖转回前方",ctx->sword()!=null&&Math.abs(Mth.wrapDegrees(sword().getYRot()))<12)
            .step("松开前进键，第一人称低头看脚下",ctx->{Minecraft.getInstance().options.keyUp.setDown(false);camera(CameraType.FIRST_PERSON);
                Minecraft.getInstance().player.setXRot(70);}).ticks(6).screenshot("fp_flight_down")
            .step("抬头",ctx->{Minecraft.getInstance().player.setXRot(0);camera(CameraType.THIRD_PERSON_BACK);})
            .checkServer("原版输入包驱动前进",ctx->Math.abs(ctx.player().getX()-.5)+Math.abs(ctx.player().getZ()-startZ)>6)
            .step("按跳跃上升",ctx->Minecraft.getInstance().options.keyJump.setDown(true)).ticks(10)
            .step("松开跳跃键",ctx->Minecraft.getInstance().options.keyJump.setDown(false))
            .checkServer("上升输入生效",ctx->ctx.player().getY()>startY+2).screenshot("tp_ascending")
            // 自然回复照常进行，御剑连回复一起扣：面板每刻实际少 0.5。
            .checkServer("每秒实际少十点魔力",ctx->{double ticks=ctx.player().level().getGameTime()-ticksAt,used=manaAt-LogosNatureBridge.mana(ctx.player());
                return ticks>20&&Math.abs(used-ticks*.5)<=1;})
            .step("按潜行下剑",ctx->Minecraft.getInstance().options.keyShift.setDown(true))
            .waitUntil("客户端已下剑",ctx->!Minecraft.getInstance().player.isPassenger())
            .step("松开潜行键",ctx->Minecraft.getInstance().options.keyShift.setDown(false))
            .checkServer("服务端下剑且未授予创造飞行",ctx->!ctx.player().isPassenger()&&!ctx.player().getAbilities().mayfly&&!ctx.player().getAbilities().flying)
            .waitUntil("落地收回剑",ctx->phase("idle"))
            .step("正面看归鞘",ctx->camera(CameraType.THIRD_PERSON_FRONT))
            .step("切换空槽收剑",ctx->{var mc=Minecraft.getInstance();mc.player.getInventory().setSelectedSlot(1);
                mc.player.connection.send(new ServerboundSetCarriedItemPacket(1));})
            .waitUntil("归鞘同步",ctx->phase("sheathe")).ticks(6).screenshot("tp_sheathe_flower").ticks(13).screenshot("tp_sheathe_insert")
            .waitUntil("交回原版动作",ctx->LongswordClient.pose(Minecraft.getInstance().player.getUUID())==null)
            .teardown("释放测试按键",ctx->{var mc=Minecraft.getInstance();mc.options.keyAttack.setDown(false);mc.options.keyUse.setDown(false);
                mc.options.keyUp.setDown(false);mc.options.keyLeft.setDown(false);mc.options.keyJump.setDown(false);mc.options.keyShift.setDown(false);
                camera(CameraType.FIRST_PERSON);});
    }
}
