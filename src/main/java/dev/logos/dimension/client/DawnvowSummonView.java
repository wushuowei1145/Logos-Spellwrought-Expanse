package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.client.longsword.LongswordClient;
import dev.logos.dimension.client.vfx.DawnvowSummon;
import dev.logos.dimension.item.LongswordItem;
import java.util.Arrays;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** 曦誓不挂剑鞘：切到曦誓时光点聚拢凝成剑，切走时剑散成光点。第三人称正面、第一人称各拍一遍。 */
@LDLRegisterClient(name = "dawnvow_summon", group = "dawnvow_summon", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class DawnvowSummonView implements UIScenario {
    private boolean previousHideGui;

    @Override public void configure(ScenarioOptions o) { o.tags("dawnvow_summon").defaultTimeoutMs(60000).scenarioTimeoutMs(240000); }

    private static boolean phase(String... moves) {
        var mc = Minecraft.getInstance();
        var p = LongswordClient.pose(mc.player.getUUID());
        return p != null && Arrays.asList(moves).contains(p.move());
    }

    private static boolean swordShown() {
        var mc = Minecraft.getInstance();
        var p = LongswordClient.pose(mc.player.getUUID());
        return p == null || DawnvowSummon.visible(p, mc.level.getGameTime());
    }

    private static void select(int slot) { Minecraft.getInstance().player.getInventory().setSelectedSlot(slot); }

    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set 13000").runCommand("weather clear").runCommand("difficulty peaceful")
                .step("视口", ctx -> {
                    var mc = Minecraft.getInstance();
                    previousHideGui = mc.options.hideGui;
                    mc.options.hideGui = true;
                    mc.options.fov().set(70);
                    mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
                })
                .server("石台", ctx -> {
                    var level = ctx.server().overworld();
                    for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) {
                        level.setBlockAndUpdate(new BlockPos(x, 99, z), Blocks.SMOOTH_STONE.defaultBlockState());
                        for (int y = 100; y < 106; y++) level.removeBlock(new BlockPos(x, y, z), false);
                    }
                    DimensionTravel.teleport(ctx.player(), level, new Vec3(.5, 100, .5), 180, 0);
                    ctx.player().getInventory().clearContent();
                    ctx.player().getInventory().setItem(0, new ItemStack(LongswordItem.DAWNVOW.get()));
                })
                .setGameMode(GameType.SURVIVAL)
                .step("空手", ctx -> select(2)).ticks(10)
                .step("切到曦誓", ctx -> select(0))
                .waitUntil("拔剑", ctx -> phase("draw"))
                .check("凝成之前不画剑", ctx -> !swordShown())
                .ticks(4).screenshot("draw_gather")
                .ticks(5).screenshot("draw_condense")
                .waitUntil("凝成", DawnvowSummonView::swordShownCtx).screenshot("draw_formed")
                .waitUntil("待机", ctx -> phase("idle")).ticks(6).screenshot("idle_no_sheath")
                .step("切走", ctx -> select(2))
                .waitUntil("收剑", ctx -> phase("sheathe")).ticks(2).screenshot("sheathe_glow")
                .ticks(3).check("散去后不画剑", ctx -> !swordShown()).screenshot("sheathe_scatter")
                .ticks(4).screenshot("sheathe_fade")
                .waitUntil("双手放开", ctx -> LongswordClient.pose(Minecraft.getInstance().player.getUUID()) == null)
                .step("第一人称", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.options.setCameraType(CameraType.FIRST_PERSON);
                    // 隐藏界面时原版不画第一人称的手
                    mc.options.hideGui = false;
                }).ticks(4)
                .step("第一人称切到曦誓", ctx -> select(0))
                .waitUntil("第一人称拔剑", ctx -> phase("draw")).ticks(6).screenshot("fp_draw_gather")
                .waitUntil("第一人称凝成", DawnvowSummonView::swordShownCtx).ticks(1).screenshot("fp_draw_formed")
                .waitUntil("第一人称待机", ctx -> phase("idle")).ticks(4)
                .step("第一人称切走", ctx -> select(2))
                .waitUntil("第一人称收剑", ctx -> phase("sheathe")).ticks(5).screenshot("fp_sheathe_scatter")
                .teardown("恢复", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.options.hideGui = previousHideGui;
                    mc.options.setCameraType(CameraType.FIRST_PERSON);
                });
    }

    private static boolean swordShownCtx(Object ctx) { return swordShown(); }
}
