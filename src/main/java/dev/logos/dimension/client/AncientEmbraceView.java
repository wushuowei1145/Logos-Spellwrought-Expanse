package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.item.*;
import net.minecraft.client.CameraType;
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

/** 左右惯用手实际举盾与收盾，逐次真实格挡同步五片发光叶，夜间检查局部常亮与治疗。 */
@LDLRegisterClient(name = "ancient_embrace_right", group = "ancient_embrace", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class AncientEmbraceView implements UIScenario {
    private HumanoidArm previousArm;
    private boolean previousHideGui;
    private int attackerId;
    private double manaBefore;
    protected HumanoidArm arm() { return HumanoidArm.RIGHT; }
    private static int leaves() {
        var mc = Minecraft.getInstance();
        return mc.player.getOffhandItem().getOrDefault(AncientEmbraceItem.CHARGE.get(), AncientEmbraceCharge.EMPTY).activeLeaves(mc.level.getGameTime());
    }
    @Override public void configure(ScenarioOptions o) { o.tags("ancient_embrace").defaultTimeoutMs(60000).scenarioTimeoutMs(180000); }
    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set midnight").runCommand("weather clear").runCommand("difficulty normal")
                .step("设置盾牌检查视口", ctx -> {
                    var mc = Minecraft.getInstance(); previousArm = mc.options.mainHand().get(); previousHideGui = mc.options.hideGui;
                    mc.options.hideGui = false; mc.options.mainHand().set(arm()); mc.options.setCameraType(CameraType.FIRST_PERSON);
                    var w = mc.getWindow(); w.setWindowed(1280, 720); w.setWidth(1280); w.setHeight(720);
                    mc.getMainRenderTarget().resize(1280, 720); mc.gameRenderer.resize(1280, 720); mc.resizeGui();
                    mc.options.fov().set(65); mc.options.broadcastOptions();
                })
                .server("布置夜间格挡台", ctx -> {
                    var level = ctx.server().overworld();
                    for (int x = -10; x <= 10; x++) for (int z = -10; z <= 12; z++) {
                        level.setBlockAndUpdate(new BlockPos(x, 99, z), Blocks.MOSS_BLOCK.defaultBlockState());
                        for (int y = 100; y < 108; y++) level.removeBlock(new BlockPos(x, y, z), false);
                    }
                    var attacker = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
                    attacker.setInvisible(true); attacker.setNoGravity(true); attacker.setPos(.5, 100, 0); level.addFreshEntity(attacker); attackerId = attacker.getId();
                    DimensionTravel.teleport(ctx.player(), level, new Vec3(.5, 100, 4.3), 180, 0);
                    ctx.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    ctx.player().setItemInHand(InteractionHand.OFF_HAND, new ItemStack(AncientEmbraceItem.ITEM.get()));
                    ctx.player().setData(AncientEmbraceItem.COOLDOWN, 0L);
                    // 测试只测一次五点扣费，不把 Logos 的自动回魔混入读取间隔。
                    var regen = net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.get(
                            net.minecraft.resources.Identifier.fromNamespaceAndPath("logos", "mana_regeneration")).orElseThrow();
                    ctx.player().getAttribute(regen).setBaseValue(0);
                    ctx.player().getFoodData().setFoodLevel(6); ctx.player().setHealth(10); LogosNatureBridge.mana(ctx.player(), 50);
                }).setGameMode(GameType.SURVIVAL)
                .waitUntil("盾牌抵达客户端", ctx -> Minecraft.getInstance().player.getOffhandItem().is(AncientEmbraceItem.ITEM.get()))
                .ticks(25).screenshot("ancient_embrace_idle")
                .step("实际右键举盾", ctx -> {
                    var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(true); mc.gameMode.useItem(mc.player, InteractionHand.OFF_HAND);
                }).ticks(10)
                .check("客户端举盾且惯用手正确", ctx -> Minecraft.getInstance().player.isBlocking() && Minecraft.getInstance().player.getMainArm() == arm())
                .screenshot("ancient_embrace_blocking")
                .step("观察盾牌正面", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_FRONT))
                .ticks(3).screenshot("ancient_embrace_leaves_0");
        for (int i = 1; i <= 5; i++) {
            final int expected = i;
            s.server("第" + i + "次实际格挡", ctx -> {
                var player = ctx.player(); var level = player.level(); var attacker = (LivingEntity) level.getEntity(attackerId);
                attacker.setPos(.5, 100, 0); player.invulnerableTime = 0;
                player.hurtServer(level, level.damageSources().mobAttack(attacker), 4);
            }).waitUntil("第" + i + "片叶同步", ctx -> leaves() == expected)
                    .check("发光叶片数为" + i, ctx -> leaves() == expected)
                    .ticks(2).screenshot("ancient_embrace_leaves_" + i);
        }
        s.checkServer("五次格挡没有扣血或魔力", ctx -> ctx.player().getHealth() == 10 && LogosNatureBridge.mana(ctx.player()) == 50)
                .server("记录治疗前魔力", ctx -> manaBefore = LogosNatureBridge.mana(ctx.player()))
                .step("实际松开右键收盾", ctx -> {
                    var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(false); mc.gameMode.releaseUsingItem(mc.player);
                }).waitUntil("治疗与叶片熄灭同步", ctx -> leaves() == 0 && Minecraft.getInstance().player.getHealth() == 14)
                .checkServer("收盾扣费并进入八秒冷却", ctx -> LogosNatureBridge.mana(ctx.player()) == manaBefore - 5
                        && ctx.player().getData(AncientEmbraceItem.COOLDOWN) > ctx.player().level().getGameTime())
                .ticks(3).screenshot("ancient_embrace_heal")
                .step("恢复第一人称", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON))
                .teardown("恢复盾牌检查设置", ctx -> {
                    var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(false); mc.options.setCameraType(CameraType.FIRST_PERSON);
                    mc.options.hideGui = previousHideGui;
                    if (previousArm != null) mc.options.mainHand().set(previousArm); mc.options.broadcastOptions();
                });
    }
    @LDLRegisterClient(name = "ancient_embrace_left", group = "ancient_embrace", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Left extends AncientEmbraceView { @Override protected HumanoidArm arm() { return HumanoidArm.LEFT; } }
}
