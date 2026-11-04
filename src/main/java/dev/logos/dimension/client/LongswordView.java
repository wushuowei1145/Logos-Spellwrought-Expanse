package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.client.longsword.LongswordClient;
import dev.logos.dimension.item.LongswordItem;
import java.util.Arrays;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** 实际客户端输入、网络动作同步、延迟伤害与第一和第三人称截图。 */
@LDLRegisterClient(name="dawnvow", group="longsword", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
public class LongswordView implements UIScenario {
    private HumanoidArm previousArm;
    private boolean previousHideGui;
    private int target;
    private float health;
    protected int variant() { return 0; }
    protected HumanoidArm arm() { return HumanoidArm.RIGHT; }
    /** 按住右键时进入的招式：曦誓是双手竖剑蓄力，黑钢长剑是竖剑格挡（施法另见 {@link BlacksteelCastView}）。 */
    protected String[] holdMoves() { return new String[]{"charge_start","charge_hold"}; }
    /** 松开右键是否举剑下劈（黑钢长剑只是放下剑）。 */
    protected boolean slashes() { return true; }
    private boolean phase(String... moves) {
        var mc = Minecraft.getInstance(); var p = LongswordClient.pose(mc.player.getUUID());
        return p != null && p.variant() == variant() && Arrays.asList(moves).contains(p.move());
    }
    private boolean crouchedWaistConnected() {
        var mc=Minecraft.getInstance();
        var model=((net.minecraft.client.renderer.entity.player.AvatarRenderer<?>)mc.getEntityRenderDispatcher().getRenderer(mc.player)).getModel();
        var waist=new org.joml.Matrix4f().translation(model.body.x,model.body.y,model.body.z)
                .rotateZYX(model.body.zRot,model.body.yRot,model.body.xRot).transformPosition(new org.joml.Vector3f(0,12,0));
        var hips=new org.joml.Vector3f((model.leftLeg.x+model.rightLeg.x)/2,(model.leftLeg.y+model.rightLeg.y)/2,(model.leftLeg.z+model.rightLeg.z)/2);
        return mc.player.isCrouching() && model.body.xRot>.3f && waist.distance(hips)<3.8f;
    }
    @Override public void configure(ScenarioOptions o) { o.tags("longsword").defaultTimeoutMs(60000).scenarioTimeoutMs(180000); }
    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("weather clear").runCommand("difficulty peaceful")
                .step("设置长剑视口", ctx -> {
                    var mc = Minecraft.getInstance(); previousArm = mc.options.mainHand().get(); previousHideGui = mc.options.hideGui;
                    mc.options.hideGui = false; mc.options.mainHand().set(arm());
                    mc.player.getInventory().setSelectedSlot(0);
                    mc.options.setCameraType(CameraType.FIRST_PERSON); mc.options.fov().set(70); mc.options.broadcastOptions();
                    var w = mc.getWindow(); w.setWindowed(1280,720); w.setWidth(1280); w.setHeight(720);
                    mc.getMainRenderTarget().resize(1280,720); mc.gameRenderer.resize(1280,720); mc.resizeGui();
                }).server("设置无移动练剑目标", ctx -> {
                    var level = ctx.server().overworld();
                    for (int x=-7;x<=7;x++) for (int z=-7;z<=8;z++) {
                        level.setBlockAndUpdate(new BlockPos(x,99,z), Blocks.STONE_BRICKS.defaultBlockState());
                        for (int y=100;y<108;y++) level.removeBlock(new BlockPos(x,y,z),false);
                    }
                    DimensionTravel.teleport(ctx.player(),level,new Vec3(.5,100,.5),0,0);
                    var golem = EntityType.IRON_GOLEM.create(level, EntitySpawnReason.COMMAND);
                    golem.setNoAi(true); golem.setNoGravity(true);
                    golem.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.INVISIBILITY,7200,0,false,false));
                    golem.setPos(.5,100,2.8); level.addFreshEntity(golem); target = golem.getId();
                    ctx.player().getInventory().clearContent(); ctx.player().getInventory().setSelectedSlot(0);
                    ctx.player().setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(variant()==0 ? LongswordItem.DAWNVOW.get() : LongswordItem.BLACKSTEEL.get()));
                    dev.logos.runtime.ManaWrap.wrap(ctx.player(),variant()==0?dev.logos.lang.Element.ARCANE:dev.logos.lang.Element.FIRE,4,300);
                }).setGameMode(GameType.SURVIVAL)
                .waitUntil("拔剑同步", ctx -> phase("draw")).ticks(5).screenshot("draw")
                .step("拔剑中提前按住右键",ctx->Minecraft.getInstance().options.keyUse.setDown(true)).ticks(3)
                .check("拔剑中没有提前开始使用物品",ctx->!Minecraft.getInstance().player.isUsingItem())
                .waitUntil("拔剑后持续按键开始蓄力",ctx->phase(holdMoves())).ticks(14)
                .step("释放提前按住的右键",ctx->{
                    var mc=Minecraft.getInstance();mc.options.keyUse.setDown(false);mc.gameMode.releaseUsingItem(mc.player);
                }).waitUntil("恢复侧持",ctx->phase("idle"))
                .server("恢复练剑目标生命",ctx->((LivingEntity)ctx.server().overworld().getEntity(target)).setHealth(100))
                .waitUntil("待机同步", ctx -> phase("idle")).ticks(4).screenshot("first_person_idle")
                .check("第一人称魔力缠绕产生表面与拖尾",ctx->dev.logos.client.SurfaceAuras.emitters()>0 && dev.logos.client.SurfaceAuras.motes()>0)
                .step("第三人称正面", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_FRONT))
                .ticks(3).screenshot("third_person_idle")
                .check("第三人称魔力缠绕仍在剑上",ctx->dev.logos.client.SurfaceAuras.emitters()>0 && dev.logos.runtime.ManaWrap.of(Minecraft.getInstance().player)!=null)
                .step("实际按住潜行键",ctx->Minecraft.getInstance().options.keyShift.setDown(true))
                .waitUntil("玩家进入潜行",ctx->Minecraft.getInstance().player.isCrouching()).ticks(5)
                .check("潜行持剑保留弯腰且腰腿相接",ctx->crouchedWaistConnected()).screenshot("crouch_idle")
                .step("观察潜行腰部侧面",ctx->{var p=Minecraft.getInstance().player;p.yBodyRot=65;p.yBodyRotO=65;})
                .ticks(1).screenshot("crouch_side")
                .step("恢复身体朝向",ctx->{var p=Minecraft.getInstance().player;p.yBodyRot=0;p.yBodyRotO=0;})
                .step("持续左键连招", ctx -> Minecraft.getInstance().options.keyAttack.setDown(true))
                .waitUntil("第一段实际出招", ctx -> phase("attack_1")).ticks(4).screenshot("attack_1")
                .waitUntil("第二段实际出招", ctx -> phase("attack_2")).ticks(4).screenshot("attack_2")
                .waitUntil("第三段实际出招", ctx -> phase("attack_3")).ticks(4).screenshot("attack_3")
                .step("松开连招", ctx -> Minecraft.getInstance().options.keyAttack.setDown(false))
                .waitUntil("连招收势", ctx -> phase("idle"))
                .checkServer("三段连招造成服务端伤害", ctx -> ((LivingEntity)ctx.server().overworld().getEntity(target)).getHealth()<100)
                .server("清除魔力缠绕",ctx->dev.logos.runtime.ManaWrap.wrap(ctx.player(),dev.logos.lang.Element.ARCANE,0,0))
                .waitUntil("缠绕解除同步",ctx->dev.logos.runtime.ManaWrap.of(Minecraft.getInstance().player)==null).ticks(6)
                .check("解除后不再从剑发射特效",ctx->dev.logos.client.SurfaceAuras.emitters()==0)
                .server("恢复魔力缠绕",ctx->dev.logos.runtime.ManaWrap.wrap(ctx.player(),variant()==0?dev.logos.lang.Element.ARCANE:dev.logos.lang.Element.FIRE,4,300))
                .waitUntil("恢复缠绕同步",ctx->dev.logos.runtime.ManaWrap.of(Minecraft.getInstance().player)!=null)
                .server("记录蓄力前生命", ctx -> health=((LivingEntity)ctx.server().overworld().getEntity(target)).getHealth())
                // 黑钢长剑也是法杖：潜行右键打开载页栏位，所以格挡前先站起来，测完再潜行。
                .step("黑钢长剑先站起", ctx -> { if (!slashes()) Minecraft.getInstance().options.keyShift.setDown(false); })
                .waitUntil("可以右键", ctx -> slashes() || !Minecraft.getInstance().player.isCrouching())
                .step("右键蓄力", ctx -> {
                    var mc=Minecraft.getInstance(); mc.options.setCameraType(CameraType.FIRST_PERSON); mc.options.keyUse.setDown(true);
                    mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);
                }).waitUntil("蓄力同步", ctx -> phase(holdMoves())).ticks(20).screenshot("first_person_charge")
                .checkServer("持续蓄力不提前伤害", ctx -> ((LivingEntity)ctx.server().overworld().getEntity(target)).getHealth()==health)
                .step("第三人称竖剑", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_FRONT))
                .ticks(3).screenshot("third_person_charge")
                .step("松开右键", ctx -> {
                    var mc=Minecraft.getInstance(); mc.options.keyUse.setDown(false); mc.gameMode.releaseUsingItem(mc.player);
                });
        if (slashes()) s.waitUntil("下劈同步", ctx -> phase("charge_release")).ticks(6).screenshot("release_raised")
                .ticks(5).screenshot("release_contact")
                .waitUntil("下劈结束", ctx -> phase("idle"))
                .checkServer("松开后下劈伤害", ctx -> ((LivingEntity)ctx.server().overworld().getEntity(target)).getHealth()<health);
        else s.waitUntil("放下剑", ctx -> phase("guard_lower")).ticks(4).screenshot("guard_lower")
                .waitUntil("放下剑结束", ctx -> phase("idle"))
                .checkServer("格挡放下没有伤害", ctx -> ((LivingEntity)ctx.server().overworld().getEntity(target)).getHealth()==health)
                .step("重新潜行", ctx -> Minecraft.getInstance().options.keyShift.setDown(true))
                .waitUntil("重新进入潜行", ctx -> Minecraft.getInstance().player.isCrouching());
        s.server("检查分段胸甲",ctx->ctx.player().setItemSlot(EquipmentSlot.CHEST,new ItemStack(net.minecraft.world.item.Items.DIAMOND_CHESTPLATE)))
                .ticks(5).screenshot("third_person_armor")
                .check("穿胸甲后潜行腰部仍连接",ctx->crouchedWaistConnected())
                .step("结束潜行",ctx->Minecraft.getInstance().options.keyShift.setDown(false))
                .waitUntil("恢复站立",ctx->!Minecraft.getInstance().player.isCrouching()).ticks(5).screenshot("standing_after_crouch")
                .step("恢复第一人称", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON))
                .step("切换空槽收剑", ctx -> Minecraft.getInstance().player.getInventory().setSelectedSlot(2))
                .waitUntil("收剑同步", ctx -> phase("sheathe")).ticks(8).screenshot("sheathe")
                .waitUntil("收剑结束释放双手", ctx -> LongswordClient.pose(Minecraft.getInstance().player.getUUID())==null)
                .step("检查原版手臂恢复",ctx->Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_FRONT))
                .ticks(8).check("收剑后原版头部与双臂恢复",ctx->{
                    var mc=Minecraft.getInstance();
                    var renderer=(net.minecraft.client.renderer.entity.player.AvatarRenderer<?>)mc.getEntityRenderDispatcher().getRenderer(mc.player);
                    var model=renderer.getModel();
                    return model.head.visible && model.leftArm.visible && model.rightArm.visible && mc.player.getMainHandItem().isEmpty();
                }).screenshot("third_person_sheathed_armor")
                .step("原槽重新持剑",ctx->{var mc=Minecraft.getInstance();mc.options.setCameraType(CameraType.FIRST_PERSON);mc.player.getInventory().setSelectedSlot(0);})
                .waitUntil("重新拔剑",ctx->phase("draw")).waitUntil("重新侧持",ctx->phase("idle"))
                .step("走原版 Q 丢弃路径",ctx->Minecraft.getInstance().player.drop(false))
                .waitUntil("不切槽清除持剑动作",ctx->{var mc=Minecraft.getInstance();return mc.player.getMainHandItem().isEmpty()&&LongswordClient.pose(mc.player.getUUID())==null;})
                .check("Q 丢弃后仍在同一物品栏槽",ctx->Minecraft.getInstance().player.getInventory().getSelectedSlot()==0)
                .ticks(4).screenshot("dropped_first_person")
                .step("检查丢弃后的第三人称",ctx->Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_FRONT))
                .ticks(5).screenshot("dropped_third_person")
                .server("移除练剑目标", ctx -> { var e=ctx.server().overworld().getEntity(target); if(e!=null)e.discard(); })
                .teardown("恢复输入", ctx -> {
                    var mc=Minecraft.getInstance(); mc.options.keyAttack.setDown(false); mc.options.keyUse.setDown(false);mc.options.keyShift.setDown(false);
                    mc.options.hideGui=previousHideGui; mc.options.setCameraType(CameraType.FIRST_PERSON);
                    if(previousArm!=null)mc.options.mainHand().set(previousArm); mc.options.broadcastOptions();
                });
    }
    @LDLRegisterClient(name="blacksteel", group="longsword", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Blacksteel extends LongswordView {
        @Override protected int variant(){return 1;}
        @Override protected String[] holdMoves(){return new String[]{"guard_raise","guard"};}
        @Override protected boolean slashes(){return false;}
    }
    @LDLRegisterClient(name="blacksteel_left", group="longsword", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Left extends Blacksteel { @Override protected HumanoidArm arm(){return HumanoidArm.LEFT;} }
}
