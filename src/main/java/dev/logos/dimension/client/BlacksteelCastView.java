package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.client.longsword.LongswordClient;
import dev.logos.dimension.item.LongswordItem;
import dev.logos.dimension.longsword.SwordCasting;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * 黑钢长剑施法的实际客户端：竖剑格挡与身前的符文圈，格挡中左键蓄力（剑尖前指，蓄力特效聚在剑尖），
 * 施放魔弹后向前一送再收回；光柱开头的术式施放后一直前指，光柱从剑尖画出，结束后收回。第一、第三人称都截图，
 * 并在日志里记下剑尖挂点（{@code [cast point]}）。
 */
@LDLRegisterClient(name="blacksteel_cast", group="longsword", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
public class BlacksteelCastView implements UIScenario {
    private static final String BOLT = "(spell :name \"剑弹\" :mana 120 (projectile :speed 1 (on-hit (damage target 2 arcane))))";
    private static final String BEAM = "(spell :name \"剑光\" :mana 120 (beam :radius 0.3 :life 2 :every 0.5 (on-hit (damage target 1 arcane))))";
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private boolean previousHideGui;

    private static boolean phase(String... moves) {
        var mc = Minecraft.getInstance(); var p = LongswordClient.pose(mc.player.getUUID());
        return p != null && Arrays.asList(moves).contains(p.move());
    }
    private static void camera(CameraType type) { Minecraft.getInstance().options.setCameraType(type); }
    private static void use(boolean down) {
        var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(down);
        if (down) mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND); else mc.gameMode.releaseUsingItem(mc.player);
    }
    /** 身体转 50°（原版头与身体最多差这么多）、头不动：第三人称正面镜头斜着看到伸出的手臂。{@code turn} 为 false 时转回。 */
    private static void side(boolean turn, float pitch) {
        var p = Minecraft.getInstance().player; p.yBodyRot = p.yBodyRotO = turn ? 50 : 0; p.setXRot(pitch); p.xRotO = pitch;
    }
    private static void logTip(String when) {
        var mc = Minecraft.getInstance();
        LOGGER.info("[cast point] {}: drawn {} eye {}", when,
                dev.logos.client.CastPoints.drawn(mc.player), mc.player.getEyePosition());
    }

    @Override public void configure(ScenarioOptions o) { o.tags("longsword").defaultTimeoutMs(60000).scenarioTimeoutMs(180000); }
    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("weather clear").runCommand("difficulty peaceful")
                .step("设置视口", ctx -> {
                    var mc = Minecraft.getInstance(); previousHideGui = mc.options.hideGui; mc.options.hideGui = false;
                    mc.player.getInventory().setSelectedSlot(0);
                    camera(CameraType.FIRST_PERSON); mc.options.fov().set(70); mc.options.broadcastOptions();
                    var w = mc.getWindow(); w.setWindowed(1280,720); w.setWidth(1280); w.setHeight(720);
                    mc.getMainRenderTarget().resize(1280,720); mc.gameRenderer.resize(1280,720); mc.resizeGui();
                }).server("场地、目标与挂好载页的黑钢长剑", ctx -> {
                    var level = ctx.server().overworld();
                    for (int x=-7;x<=7;x++) for (int z=-7;z<=12;z++) {
                        level.setBlockAndUpdate(new BlockPos(x,99,z), Blocks.STONE_BRICKS.defaultBlockState());
                        for (int y=100;y<108;y++) level.removeBlock(new BlockPos(x,y,z),false);
                    }
                    DimensionTravel.teleport(ctx.player(),level,new Vec3(.5,100,.5),0,0);
                    var golem = EntityType.IRON_GOLEM.create(level, EntitySpawnReason.COMMAND);
                    golem.setNoAi(true); golem.setNoGravity(true); golem.setPos(.5,100,7); level.addFreshEntity(golem);
                    var sword = new ItemStack(LongswordItem.BLACKSTEEL.get());
                    sword.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(
                            dev.logos.item.SpellPages.inscribed(dev.logos.item.PageTier.CRYSTAL, BOLT, null),
                            dev.logos.item.SpellPages.inscribed(dev.logos.item.PageTier.CRYSTAL, BEAM, null))));
                    ctx.player().getInventory().clearContent(); ctx.player().getInventory().setSelectedSlot(0);
                    ctx.player().setItemInHand(InteractionHand.MAIN_HAND, sword);
                    var m = dev.logos.runtime.PlayerMagic.of(ctx.player()); m.baseMaxMana = m.mana = 1000;
                }).setGameMode(GameType.SURVIVAL)
                .waitUntil("拔剑后待机", ctx -> phase("idle")).ticks(4)
                // 格挡
                .step("按住右键格挡", ctx -> use(true))
                .waitUntil("竖剑格挡", ctx -> phase(SwordCasting.GUARD)).ticks(6).screenshot("guard_first_person")
                .check("格挡中", ctx -> Minecraft.getInstance().player.isBlocking())
                .step("第三人称正面", ctx -> camera(CameraType.THIRD_PERSON_FRONT)).ticks(3).screenshot("guard_front")
                .step("斜侧", ctx -> side(true, 0)).ticks(1).screenshot("guard_three_quarter")
                .step("转回", ctx -> side(false, 0))
                .step("第三人称背后", ctx -> camera(CameraType.THIRD_PERSON_BACK)).ticks(3).screenshot("guard_back")
                // 蓄力：格挡中左键
                .step("回到第一人称，格挡中点左键", ctx -> { camera(CameraType.FIRST_PERSON); Minecraft.getInstance().options.keyAttack.setDown(true); })
                .waitUntil("开始蓄力", ctx -> phase(SwordCasting.CHARGE))
                .step("松开左键", ctx -> Minecraft.getInstance().options.keyAttack.setDown(false))
                .ticks(8).screenshot("charge_first_person").step("记下第一人称剑尖", ctx -> logTip("first person charge"))
                .step("第三人称正面", ctx -> camera(CameraType.THIRD_PERSON_FRONT)).ticks(3).screenshot("charge_front")
                .step("记下第三人称剑尖", ctx -> logTip("third person charge"))
                .step("斜侧平视", ctx -> side(true, 0)).ticks(1).screenshot("charge_three_quarter")
                .step("斜侧抬头 30°", ctx -> side(true, -30)).ticks(1).screenshot("charge_three_quarter_up")
                .step("斜侧低头 30°", ctx -> side(true, 30)).ticks(1).screenshot("charge_three_quarter_down")
                .step("恢复朝向", ctx -> side(false, 0))
                .step("第三人称背后", ctx -> camera(CameraType.THIRD_PERSON_BACK)).ticks(3).screenshot("charge_back")
                .step("抬头 30°", ctx -> Minecraft.getInstance().player.setXRot(-30)).ticks(3).screenshot("charge_back_up")
                .step("视线放平", ctx -> Minecraft.getInstance().player.setXRot(0))
                .waitUntil("蓄满", ctx -> { var c = dev.logos.client.ClientCharges.of(Minecraft.getInstance().player); return c != null && c.full; })
                .ticks(2).screenshot("charge_full_back")
                .step("松开右键施放魔弹", ctx -> use(false))
                .step("斜侧看施放", ctx -> { camera(CameraType.THIRD_PERSON_FRONT); side(true, 0); })
                .waitUntil("向前一送", ctx -> phase(SwordCasting.THRUST)).step("保持斜侧", ctx -> side(true, 0)).ticks(1).screenshot("thrust_three_quarter")
                .ticks(5).step("保持斜侧", ctx -> side(true, 0)).screenshot("thrust_three_quarter_late")
                .step("转回", ctx -> side(false, 0))
                .waitUntil("收回待机", ctx -> phase("idle"))
                // 光柱
                .server("换到光柱载页", ctx -> ctx.player().getMainHandItem().set(dev.logos.LogosMod.SELECTED.get(), 1))
                .ticks(4).step("按住右键", ctx -> use(true))
                .waitUntil("格挡", ctx -> phase(SwordCasting.GUARD_RAISE, SwordCasting.GUARD))
                .step("点左键蓄力", ctx -> Minecraft.getInstance().options.keyAttack.setDown(true))
                .waitUntil("开始蓄力", ctx -> phase(SwordCasting.CHARGE))
                .step("松开左键", ctx -> Minecraft.getInstance().options.keyAttack.setDown(false))
                .waitUntil("蓄满", ctx -> { var c = dev.logos.client.ClientCharges.of(Minecraft.getInstance().player); return c != null && c.full; })
                .step("松开右键施放光柱", ctx -> use(false))
                .waitUntil("光柱期间保持前指", ctx -> phase(SwordCasting.BEAM)).ticks(8).screenshot("beam_back")
                .step("第一人称", ctx -> camera(CameraType.FIRST_PERSON)).ticks(3).screenshot("beam_first_person")
                .step("第三人称正面", ctx -> camera(CameraType.THIRD_PERSON_FRONT)).ticks(3).screenshot("beam_front")
                .check("光柱还在时仍前指", ctx -> phase(SwordCasting.BEAM))
                .waitUntil("光柱结束后收回", ctx -> phase(SwordCasting.LOWER, "idle"))
                .waitUntil("回到待机", ctx -> phase("idle")).screenshot("after_beam_front")
                .teardown("恢复输入", ctx -> {
                    var mc = Minecraft.getInstance(); mc.options.keyAttack.setDown(false); mc.options.keyUse.setDown(false);
                    mc.options.hideGui = previousHideGui; camera(CameraType.FIRST_PERSON); mc.options.broadcastOptions();
                });
    }
}
