package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.item.*;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** 四种握持/惯用手组合，验证统一的右键举盾、举盾戳刺与两键同刻操作。 */
@LDLRegisterClient(name = "spear_shield_main_right", group = "spear_shield", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class SpearShieldView implements UIScenario {
    private HumanoidArm previousArm;
    private boolean previousHideGui;
    private int targetId;
    protected HumanoidArm arm() { return HumanoidArm.RIGHT; }
    protected boolean reverse() { return false; }
    private static void clickAttack() { var mc = Minecraft.getInstance(); KeyMapping.click(mc.options.keyAttack.getKey()); }
    @Override public void configure(ScenarioOptions o) { o.tags("spear_shield").defaultTimeoutMs(60000).scenarioTimeoutMs(180000); }
    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("weather clear").runCommand("difficulty normal")
                .step("设置矛盾联动检查视口", ctx -> {
                    var mc = Minecraft.getInstance(); previousArm = mc.options.mainHand().get(); previousHideGui = mc.options.hideGui;
                    mc.options.hideGui = false; mc.options.mainHand().set(arm()); mc.options.setCameraType(CameraType.FIRST_PERSON);
                    var w = mc.getWindow(); w.setWindowed(1280, 720); w.setWidth(1280); w.setHeight(720);
                    mc.getMainRenderTarget().resize(1280, 720); mc.gameRenderer.resize(1280, 720); mc.resizeGui();
                    mc.options.fov().set(65); mc.options.broadcastOptions();
                }).server("布置矛盾联动检查台", ctx -> {
                    var level = ctx.server().overworld();
                    for (int x = -10; x <= 10; x++) for (int z = -10; z <= 12; z++) {
                        level.setBlockAndUpdate(new BlockPos(x, 99, z), Blocks.MOSS_BLOCK.defaultBlockState());
                        for (int y = 100; y < 108; y++) level.removeBlock(new BlockPos(x, y, z), false);
                    }
                    var target = EntityType.IRON_GOLEM.create(level, EntitySpawnReason.COMMAND);
                    target.setNoAi(true); target.setPos(.5, 100, 1); level.addFreshEntity(target); targetId = target.getId();
                    DimensionTravel.teleport(ctx.player(), level, new Vec3(.5, 100, 4.3), 180, 0);
                    ctx.player().stopUsingItem(); ctx.player().setHealth(20); ctx.player().getFoodData().setFoodLevel(20);
                    ctx.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(reverse() ? AncientEmbraceItem.ITEM.get() : ElfSpearItem.ITEM.get()));
                    ctx.player().setItemInHand(InteractionHand.OFF_HAND, new ItemStack(reverse() ? ElfSpearItem.ITEM.get() : AncientEmbraceItem.ITEM.get()));
                    ctx.player().setData(AncientEmbraceItem.COOLDOWN, 0L); LogosNatureBridge.mana(ctx.player(), 50);
                }).setGameMode(GameType.SURVIVAL)
                .waitUntil("双持物品同步", ctx -> SpearShieldGuard.paired(Minecraft.getInstance().player))
                .ticks(25).step("未举盾时实际左键", ctx -> clickAttack())
                .waitUntil("普通戳刺命中", ctx -> ((LivingEntity) Minecraft.getInstance().level.getEntity(targetId)).getHealth() == 96)
                .checkServer("普通戳刺种印", ctx -> ctx.player().level().getEntity(targetId).getData(NatureAttachments.SPROUT_MARK)
                        .belongsTo(ctx.player().getUUID(), ctx.player().level().getGameTime()))
                .server("复位靶子", ctx -> { var t = ctx.player().level().getEntity(targetId); t.setPos(.5, 100, 1); t.setDeltaMovement(Vec3.ZERO); })
                .ticks(16).step("实际右键自动选择盾", ctx -> {
                    var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(true); KeyMapping.click(mc.options.keyUse.getKey());
                    KeyMapping.click(mc.options.keyAttack.getKey());
                }).waitUntil("举盾同步", ctx -> SpearShieldGuard.guarding(Minecraft.getInstance().player))
                .ticks(15)
                .check("实际使用手始终为盾", ctx -> Minecraft.getInstance().player.getUsedItemHand() == (reverse() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND)
                        && Minecraft.getInstance().player.getUseItem().is(AncientEmbraceItem.ITEM.get()))
                .checkServer("两键同刻可以戳刺并举盾", ctx -> ((LivingEntity) ctx.player().level().getEntity(targetId)).getHealth() == 92)
                .server("复位同时攻击后的靶子", ctx -> { var t = ctx.player().level().getEntity(targetId); t.setPos(.5, 100, 1); t.setDeltaMovement(Vec3.ZERO); })
                .screenshot("spear_shield_first_person")
                .step("举盾中实际左键", ctx -> clickAttack()).ticks(5)
                .checkServer("两种握持均可举盾戳刺", ctx -> ((LivingEntity) ctx.player().level().getEntity(targetId)).getHealth() == 88)
                .check("左键没有放下盾", ctx -> SpearShieldGuard.guarding(Minecraft.getInstance().player))
                .server("确认仍可实际格挡", ctx -> {
                    var target = (LivingEntity) ctx.player().level().getEntity(targetId); target.setPos(.5, 100, 1); target.setDeltaMovement(Vec3.ZERO);
                    ctx.player().invulnerableTime = 0; ctx.player().hurtServer(ctx.player().level(), ctx.player().level().damageSources().mobAttack(target), 4);
                }).checkServer("格挡不掉血且只亮一片叶", ctx -> ctx.player().getHealth() == 20
                        && ctx.player().getUseItem().getOrDefault(AncientEmbraceItem.CHARGE.get(), AncientEmbraceCharge.EMPTY).leaves() == 1)
                .step("检查第三人称双手姿态", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK))
                .ticks(3).screenshot("spear_shield_third_person")
                .server("再次复位靶子", ctx -> { var t = ctx.player().level().getEntity(targetId); t.setPos(.5, 100, 1); t.setDeltaMovement(Vec3.ZERO); })
                .ticks(16).step("松开右键同时实际左键", ctx -> {
                    var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(false); mc.options.setCameraType(CameraType.FIRST_PERSON); clickAttack();
                }).waitUntil("收盾后立即恢复普通戳刺", ctx -> !Minecraft.getInstance().player.isUsingItem()
                        && ((LivingEntity) Minecraft.getInstance().level.getEntity(targetId)).getHealth() == 84)
                .checkServer("收盾未消费治疗魔力", ctx -> LogosNatureBridge.mana(ctx.player()) >= 50)
                .teardown("恢复矛盾检查设置", ctx -> {
                    var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(false); mc.options.setCameraType(CameraType.FIRST_PERSON);
                    mc.options.hideGui = previousHideGui; if (previousArm != null) mc.options.mainHand().set(previousArm); mc.options.broadcastOptions();
                });
    }
    @LDLRegisterClient(name = "spear_shield_main_left", group = "spear_shield", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class MainLeft extends SpearShieldView { @Override protected HumanoidArm arm() { return HumanoidArm.LEFT; } }
    @LDLRegisterClient(name = "spear_shield_reverse_right", group = "spear_shield", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class ReverseRight extends SpearShieldView { @Override protected boolean reverse() { return true; } }
    @LDLRegisterClient(name = "spear_shield_reverse_left", group = "spear_shield", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class ReverseLeft extends ReverseRight { @Override protected HumanoidArm arm() { return HumanoidArm.LEFT; } }
}
