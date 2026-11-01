package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.client.longsword.LongswordClient;
import dev.logos.dimension.item.LongswordItem;
import dev.logos.dimension.longsword.LongswordPhase;
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
    private boolean phase(LongswordPhase phase) {
        var mc = Minecraft.getInstance(); var p = LongswordClient.pose(mc.player.getUUID());
        return p != null && p.variant() == variant() && p.phase() == phase;
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
                }).setGameMode(GameType.SURVIVAL)
                .waitUntil("拔剑同步", ctx -> phase(LongswordPhase.DRAW)).ticks(5).screenshot("draw")
                .step("拔剑中提前按住右键",ctx->Minecraft.getInstance().options.keyUse.setDown(true)).ticks(3)
                .check("拔剑中没有提前开始使用物品",ctx->!Minecraft.getInstance().player.isUsingItem())
                .waitUntil("拔剑后持续按键开始蓄力",ctx->phase(LongswordPhase.CHARGING)).ticks(14)
                .step("释放提前按住的右键",ctx->{
                    var mc=Minecraft.getInstance();mc.options.keyUse.setDown(false);mc.gameMode.releaseUsingItem(mc.player);
                }).waitUntil("恢复侧持",ctx->phase(LongswordPhase.IDLE))
                .server("恢复练剑目标生命",ctx->((LivingEntity)ctx.server().overworld().getEntity(target)).setHealth(100))
                .waitUntil("待机同步", ctx -> phase(LongswordPhase.IDLE)).ticks(4).screenshot("first_person_idle")
                .step("第三人称正面", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_FRONT))
                .ticks(3).screenshot("third_person_idle")
                .step("持续左键连招", ctx -> Minecraft.getInstance().options.keyAttack.setDown(true))
                .waitUntil("第一段实际出招", ctx -> phase(LongswordPhase.ATTACK_1)).ticks(4).screenshot("attack_1")
                .waitUntil("第二段实际出招", ctx -> phase(LongswordPhase.ATTACK_2)).ticks(4).screenshot("attack_2")
                .waitUntil("第三段实际出招", ctx -> phase(LongswordPhase.ATTACK_3)).ticks(4).screenshot("attack_3")
                .step("松开连招", ctx -> Minecraft.getInstance().options.keyAttack.setDown(false))
                .waitUntil("连招收势", ctx -> phase(LongswordPhase.IDLE))
                .checkServer("三段连招造成服务端伤害", ctx -> ((LivingEntity)ctx.server().overworld().getEntity(target)).getHealth()<100)
                .server("记录蓄力前生命", ctx -> health=((LivingEntity)ctx.server().overworld().getEntity(target)).getHealth())
                .step("右键蓄力", ctx -> {
                    var mc=Minecraft.getInstance(); mc.options.setCameraType(CameraType.FIRST_PERSON); mc.options.keyUse.setDown(true);
                    mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);
                }).waitUntil("蓄力同步", ctx -> phase(LongswordPhase.CHARGING)).ticks(20).screenshot("first_person_charge")
                .checkServer("持续蓄力不提前伤害", ctx -> ((LivingEntity)ctx.server().overworld().getEntity(target)).getHealth()==health)
                .step("第三人称竖剑", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_FRONT))
                .ticks(3).screenshot("third_person_charge")
                .step("松开右键", ctx -> {
                    var mc=Minecraft.getInstance(); mc.options.keyUse.setDown(false); mc.gameMode.releaseUsingItem(mc.player);
                }).waitUntil("下劈同步", ctx -> phase(LongswordPhase.RELEASE)).ticks(6).screenshot("release_raised")
                .ticks(5).screenshot("release_contact")
                .waitUntil("下劈结束", ctx -> phase(LongswordPhase.IDLE))
                .checkServer("松开后下劈伤害", ctx -> ((LivingEntity)ctx.server().overworld().getEntity(target)).getHealth()<health)
                .server("检查分段胸甲",ctx->ctx.player().setItemSlot(EquipmentSlot.CHEST,new ItemStack(net.minecraft.world.item.Items.DIAMOND_CHESTPLATE)))
                .ticks(5).screenshot("third_person_armor")
                .step("恢复第一人称", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON))
                .step("切换空槽收剑", ctx -> Minecraft.getInstance().player.getInventory().setSelectedSlot(2))
                .waitUntil("收剑同步", ctx -> phase(LongswordPhase.SHEATHE)).ticks(8).screenshot("sheathe")
                .waitUntil("收剑结束释放双手", ctx -> LongswordClient.pose(Minecraft.getInstance().player.getUUID())==null)
                .step("检查原版手臂恢复",ctx->Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_FRONT))
                .ticks(8).check("收剑后原版头部与双臂恢复",ctx->{
                    var mc=Minecraft.getInstance();
                    var renderer=(net.minecraft.client.renderer.entity.player.AvatarRenderer<?>)mc.getEntityRenderDispatcher().getRenderer(mc.player);
                    var model=renderer.getModel();
                    return model.head.visible && model.leftArm.visible && model.rightArm.visible && mc.player.getMainHandItem().isEmpty();
                }).screenshot("third_person_sheathed_armor")
                .server("移除练剑目标", ctx -> { var e=ctx.server().overworld().getEntity(target); if(e!=null)e.discard(); })
                .teardown("恢复输入", ctx -> {
                    var mc=Minecraft.getInstance(); mc.options.keyAttack.setDown(false); mc.options.keyUse.setDown(false);
                    mc.options.hideGui=previousHideGui; mc.options.setCameraType(CameraType.FIRST_PERSON);
                    if(previousArm!=null)mc.options.mainHand().set(previousArm); mc.options.broadcastOptions();
                });
    }
    @LDLRegisterClient(name="blacksteel", group="longsword", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Blacksteel extends LongswordView { @Override protected int variant(){return 1;} }
    @LDLRegisterClient(name="blacksteel_left", group="longsword", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Left extends Blacksteel { @Override protected HumanoidArm arm(){return HumanoidArm.LEFT;} }
}
