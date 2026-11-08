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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** 实际客户端以原版输入包执行攻击、右键起飞、方向移动、上升和潜行下剑。 */
@LDLRegisterClient(name="lingxiao",group="lingxiao",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
public final class LingxiaoView implements UIScenario {
    private int target;
    private volatile double startZ,startY;
    private boolean phase(String move){
        var mc=Minecraft.getInstance();if(mc.player==null)return false;
        var p=LongswordClient.pose(mc.player.getUUID());return p!=null&&p.variant()==11&&p.move().equals(move);
    }
    @Override public void configure(ScenarioOptions options){options.tags("lingxiao").defaultTimeoutMs(60000).scenarioTimeoutMs(180000);}
    @Override public void define(ScenarioBuilder s){
        s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("weather clear")
            .step("设置视口",ctx->{var mc=Minecraft.getInstance();mc.options.setCameraType(CameraType.FIRST_PERSON);mc.options.hideGui=false;
                var w=mc.getWindow();w.setWindowed(1280,720);w.setWidth(1280);w.setHeight(720);
                mc.getMainRenderTarget().resize(1280,720);mc.gameRenderer.resize(1280,720);mc.resizeGui();})
            .server("设置练剑和飞行场地",ctx->{var l=ctx.server().overworld();
                for(int x=-8;x<=8;x++)for(int z=-8;z<=42;z++){
                    l.setBlockAndUpdate(new BlockPos(x,99,z),Blocks.STONE_BRICKS.defaultBlockState());
                    for(int y=100;y<=112;y++)l.removeBlock(new BlockPos(x,y,z),false);
                }
                DimensionTravel.teleport(ctx.player(),l,new Vec3(.5,100,.5),0,0);
                ctx.player().getInventory().clearContent();ctx.player().getInventory().setSelectedSlot(0);
                ctx.player().setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(LingxiaoSwordItem.ITEM.get()));
                var e=EntityType.IRON_GOLEM.create(l,EntitySpawnReason.COMMAND);e.setNoAi(true);e.setPos(.5,100,2.8);l.addFreshEntity(e);target=e.getId();
            }).setGameMode(GameType.SURVIVAL)
            .waitUntil("持剑待机同步",ctx->phase("idle")).ticks(3).screenshot("fp_idle")
            .checkServer("铁剑伤害与一点五倍攻速",ctx->Math.abs(ctx.player().getAttributeValue(Attributes.ATTACK_DAMAGE)-6)<.001
                    &&Math.abs(ctx.player().getAttributeValue(Attributes.ATTACK_SPEED)-2.4)<.001)
            .step("按住左键",ctx->Minecraft.getInstance().options.keyAttack.setDown(true))
            .waitUntil("攻击开始同步",ctx->phase("attack_1")).ticks(4).screenshot("fp_attack")
            .ticks(11).step("松开左键",ctx->Minecraft.getInstance().options.keyAttack.setDown(false))
            .waitUntil("连招收势结束",ctx->phase("idle"))
            .checkServer("真实攻击造成伤害",ctx->((LivingEntity)ctx.server().overworld().getEntity(target)).getHealth()<100)
            .server("移除目标并补充魔力",ctx->{ctx.server().overworld().getEntity(target).discard();LogosNatureBridge.mana(ctx.player(),200);
                startZ=ctx.player().getZ();startY=ctx.player().getY();})
            .step("第三人称并实际右键",ctx->{var mc=Minecraft.getInstance();mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
                mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);})
            .waitUntil("客户端收到御剑载具",ctx->Minecraft.getInstance().player.getVehicle() instanceof FlyingSword)
            .waitUntil("巡航动作同步",ctx->phase("flight_idle")).ticks(3).screenshot("tp_flight")
            .checkServer("服务端载具存在且开始扣魔力",ctx->ctx.player().getVehicle() instanceof FlyingSword&&LogosNatureBridge.mana(ctx.player())<199)
            .check("御剑保持站姿",ctx->{var mc=Minecraft.getInstance();var renderer=(net.minecraft.client.renderer.entity.player.AvatarRenderer<?>)mc.getEntityRenderDispatcher().getRenderer(mc.player);
                return Math.abs(renderer.getModel().rightLeg.xRot)<.4&&!mc.player.getAbilities().flying;})
            .step("按前进键",ctx->Minecraft.getInstance().options.keyUp.setDown(true)).ticks(25)
            .step("松开前进键",ctx->Minecraft.getInstance().options.keyUp.setDown(false))
            .checkServer("原版输入包驱动前进",ctx->ctx.player().getZ()>startZ+5)
            .step("按跳跃上升",ctx->Minecraft.getInstance().options.keyJump.setDown(true)).ticks(10)
            .step("松开跳跃键",ctx->Minecraft.getInstance().options.keyJump.setDown(false))
            .checkServer("上升输入生效",ctx->ctx.player().getY()>startY+2).screenshot("tp_ascending")
            .step("按潜行下剑",ctx->Minecraft.getInstance().options.keyShift.setDown(true))
            .waitUntil("客户端已下剑",ctx->!Minecraft.getInstance().player.isPassenger())
            .step("松开潜行键",ctx->Minecraft.getInstance().options.keyShift.setDown(false))
            .checkServer("服务端下剑且未授予创造飞行",ctx->!ctx.player().isPassenger()&&!ctx.player().getAbilities().mayfly&&!ctx.player().getAbilities().flying)
            .waitUntil("落地收回剑",ctx->phase("idle"))
            .step("切换空槽收剑",ctx->{var mc=Minecraft.getInstance();mc.player.getInventory().setSelectedSlot(1);
                mc.player.connection.send(new ServerboundSetCarriedItemPacket(1));})
            .waitUntil("收剑剑花同步",ctx->phase("sheathe")).ticks(12).screenshot("tp_sheathe_flourish")
            .waitUntil("交回原版动作",ctx->LongswordClient.pose(Minecraft.getInstance().player.getUUID())==null)
            .teardown("释放测试按键",ctx->{var mc=Minecraft.getInstance();mc.options.keyAttack.setDown(false);mc.options.keyUse.setDown(false);
                mc.options.keyUp.setDown(false);mc.options.keyJump.setDown(false);mc.options.keyShift.setDown(false);mc.options.setCameraType(CameraType.FIRST_PERSON);});
    }
}
