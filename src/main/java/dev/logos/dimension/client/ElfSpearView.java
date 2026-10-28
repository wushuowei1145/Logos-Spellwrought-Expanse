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

/** 真实客户端左右惯用手：原版左键种印、右键架矛并实际向前冲刺触发，检查网络同步与生命易伤。 */
@LDLRegisterClient(name = "elf_spear_right", group = "elf_spear", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class ElfSpearView implements UIScenario {
    private int targetId;
    private long particlesBefore;
    private HumanoidArm previousArm;
    private boolean previousHideGui;
    protected HumanoidArm arm() { return HumanoidArm.RIGHT; }
    @Override public void configure(ScenarioOptions o) { o.tags("elf_spear").defaultTimeoutMs(60000).scenarioTimeoutMs(180000); }
    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("difficulty peaceful")
                .step("设置长矛检查视口", ctx -> {
                    var mc = Minecraft.getInstance();
                    previousArm = mc.options.mainHand().get(); mc.options.mainHand().set(arm());
                    previousHideGui = mc.options.hideGui; mc.options.hideGui = false; mc.options.setCameraType(CameraType.FIRST_PERSON);
                    var w = mc.getWindow(); w.setWindowed(1280, 720); w.setWidth(1280); w.setHeight(720);
                    mc.getMainRenderTarget().resize(1280, 720); mc.gameRenderer.resize(1280, 720); mc.resizeGui();
                    mc.options.fov().set(65); mc.options.renderDistance().set(8); mc.options.broadcastOptions();
                    particlesBefore = SproutMarkParticle.created();
                })
                .server("布置穿刺检查台", ctx -> {
                    var level = ctx.server().overworld();
                    for (int x = -10; x <= 10; x++) for (int z = -10; z <= 12; z++) {
                        level.setBlockAndUpdate(new BlockPos(x, 99, z), Blocks.MOSS_BLOCK.defaultBlockState());
                        for (int y = 100; y < 108; y++) level.removeBlock(new BlockPos(x, y, z), false);
                    }
                    var target = EntityType.IRON_GOLEM.create(level, EntitySpawnReason.COMMAND);
                    target.setNoAi(true); target.setPos(.5, 100, 1); level.addFreshEntity(target); targetId = target.getId();
                    DimensionTravel.teleport(ctx.player(), level, new Vec3(.5, 100, 4.3), 180, 0);
                    ctx.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ElfSpearItem.ITEM.get()));
                    ctx.player().getFoodData().setFoodLevel(20); LogosNatureBridge.mana(ctx.player(), 50);
                })
                .setGameMode(GameType.SURVIVAL)
                .waitUntil("长矛与靶子到达客户端", ctx -> {
                    var mc = Minecraft.getInstance(); return mc.player.getMainHandItem().is(ElfSpearItem.ITEM.get()) && mc.level.getEntity(targetId) != null;
                }).ticks(25)
                .check("惯用手与长矛模板正常", ctx -> Minecraft.getInstance().player.getMainArm() == arm())
                .screenshot("elf_spear_idle")
                .step("原版左键戳刺", ctx -> { var mc = Minecraft.getInstance(); KeyMapping.click(mc.options.keyAttack.getKey()); })
                .waitUntil("萌芽印记同步", ctx -> {
                    var mc = Minecraft.getInstance(); var target = mc.level.getEntity(targetId);
                    return target != null && target.hasData(NatureAttachments.SPROUT_MARK)
                            && target.getData(NatureAttachments.SPROUT_MARK).belongsTo(mc.player.getUUID(), mc.level.getGameTime());
                }).ticks(20)
                .checkServer("左键仅造成四点物理伤害", ctx -> ((LivingEntity) ctx.player().level().getEntity(targetId)).getHealth() == 96)
                .check("印记叶片在客户端生成", ctx -> SproutMarkParticle.created() > particlesBefore)
                .screenshot("elf_spear_sprout_mark")
                .step("原版右键架矛", ctx -> { var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(true); mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND); })
                .ticks(15).screenshot("elf_spear_braced")
                .step("观察第三人称长矛", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK))
                .ticks(3).screenshot("elf_spear_third_person")
                .step("向前冲刺触发印记", ctx -> {
                    var mc = Minecraft.getInstance(); mc.options.setCameraType(CameraType.FIRST_PERSON);
                    mc.options.keyUp.setDown(true); mc.options.keySprint.setDown(true); mc.player.setSprinting(true);
                })
                .waitUntil("冲撞消耗印记", ctx -> {
                    var mc = Minecraft.getInstance(); var target = mc.level.getEntity(targetId);
                    return target != null && target.hasData(NatureAttachments.SPROUT_MARK)
                            && !target.getData(NatureAttachments.SPROUT_MARK).active(mc.level.getGameTime());
                })
                .step("停止前进", ctx -> { var mc = Minecraft.getInstance(); mc.options.keyUp.setDown(false); mc.options.keySprint.setDown(false); mc.player.setDeltaMovement(Vec3.ZERO); })
                .server("后退观察生命绽放", ctx -> { DimensionTravel.teleport(ctx.player(), ctx.player().level(), new Vec3(.5, 100, 4.3), 180, 0); ctx.player().setDeltaMovement(Vec3.ZERO); })
                .ticks(3)
                .checkServer("目标收到生命易伤一级", ctx -> {
                    var target = (LivingEntity) ctx.player().level().getEntity(targetId);
                    var effect = target.getEffect(LogosNatureBridge.lifeVulnerability().orElseThrow());
                    return effect != null && effect.getAmplifier() == 0 && effect.getDuration() > 0 && effect.getDuration() <= 80 && target.getHealth() < 90;
                })
                .screenshot("elf_spear_life_burst")
                .check("实际截图尺寸", ctx -> Minecraft.getInstance().getMainRenderTarget().width == 1280)
                .teardown("恢复按键与惯用手", ctx -> {
                    var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(false); mc.options.keyUp.setDown(false); mc.options.keySprint.setDown(false);
                    mc.options.setCameraType(CameraType.FIRST_PERSON);
                    mc.options.hideGui = previousHideGui;
                    if (previousArm != null) mc.options.mainHand().set(previousArm); mc.options.broadcastOptions();
                });
    }
    @LDLRegisterClient(name = "elf_spear_left", group = "elf_spear", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Left extends ElfSpearView { @Override protected HumanoidArm arm() { return HumanoidArm.LEFT; } }
}
