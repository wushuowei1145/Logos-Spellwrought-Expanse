package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.client.longsword.LongswordClient;
import dev.logos.dimension.client.trail.WeaponTrails;
import dev.logos.dimension.item.UchigatanaItem;
import dev.logos.lang.Element;
import java.util.Arrays;
import java.util.Locale;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * 刀光（docs/WEAPON_TRAILS.md）的实际试玩：打刀四段连招在第三人称背后、正面与第一人称各截一张，
 * 再依次缠上七种元素的魔力各砍一刀，检查刀光换成对应元素的样式并截图。
 */
@LDLRegisterClient(name="weapon_trails", group="weapon_trails", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
public class WeaponTrailView implements UIScenario {
    private boolean previousHideGui;
    private int peakRibbons, peakSparks;

    private static boolean phase(String... moves) {
        var p = LongswordClient.pose(Minecraft.getInstance().player.getUUID());
        return p != null && Arrays.asList(moves).contains(p.move());
    }
    private static void camera(CameraType type) { Minecraft.getInstance().options.setCameraType(type); }
    private static void attack(boolean down) { Minecraft.getInstance().options.keyAttack.setDown(down); }
    private void watch() {
        peakRibbons = Math.max(peakRibbons, WeaponTrails.ribbons());
        peakSparks = Math.max(peakSparks, WeaponTrails.sparks());
    }
    /** 本机玩家这道第三人称刀光现在用的样式名（路径部分）；没有时为空。 */
    private static String style() {
        var s = LongswordClient.trailStyle(Minecraft.getInstance().player.getId(), false);
        return s == null ? "" : s.id().getPath();
    }

    @Override public void configure(ScenarioOptions o) { o.tags("weapon_trails").defaultTimeoutMs(60000).scenarioTimeoutMs(240000); }

    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("weather clear").runCommand("difficulty easy")
                .step("设置视口", ctx -> {
                    var mc = Minecraft.getInstance(); previousHideGui = mc.options.hideGui;
                    mc.options.hideGui = false; mc.player.getInventory().setSelectedSlot(0);
                    camera(CameraType.THIRD_PERSON_BACK); mc.options.fov().set(70);
                    var w = mc.getWindow(); w.setWindowed(1280, 720); w.setWidth(1280); w.setHeight(720);
                    mc.getMainRenderTarget().resize(1280, 720); mc.gameRenderer.resize(1280, 720); mc.resizeGui();
                }).server("设置练刀场与木桩", ctx -> {
                    var level = ctx.server().overworld();
                    for (int x = -7; x <= 7; x++) for (int z = -7; z <= 8; z++) {
                        level.setBlockAndUpdate(new BlockPos(x, 99, z), Blocks.STONE_BRICKS.defaultBlockState());
                        for (int y = 100; y < 108; y++) level.removeBlock(new BlockPos(x, y, z), false);
                    }
                    DimensionTravel.teleport(ctx.player(), level, new Vec3(.5, 100, .5), 0, 0);
                    var golem = EntityType.IRON_GOLEM.create(level, EntitySpawnReason.COMMAND);
                    golem.setNoAi(true); golem.setNoGravity(true); golem.setInvulnerable(true);
                    golem.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.INVISIBILITY, 7200, 0, false, false));
                    golem.setPos(.5, 100, 2.8); level.addFreshEntity(golem);
                    ctx.player().getInventory().clearContent(); ctx.player().getInventory().setSelectedSlot(0);
                    ctx.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(UchigatanaItem.INK.get()));
                }).setGameMode(GameType.SURVIVAL)
                .waitUntil("待机", ctx -> phase("idle")).ticks(4)
                .check("待机时不出刀光", ctx -> WeaponTrails.ribbons() == 0)
                .step("按住左键", ctx -> attack(true))
                .waitUntil("第一刀", ctx -> phase("slash_1")).ticks(4).step("记录", ctx -> watch()).screenshot("back_slash_1")
                .waitUntil("第二刀", ctx -> phase("slash_2")).ticks(4).step("记录", ctx -> watch()).screenshot("back_slash_2")
                .waitUntil("第三刀", ctx -> phase("slash_3")).ticks(5).step("记录", ctx -> watch()).screenshot("back_slash_3")
                .waitUntil("第四刀", ctx -> phase("slash_4")).ticks(8).step("记录", ctx -> watch()).screenshot("back_slash_4")
                .step("松开左键", ctx -> attack(false))
                .check("挥砍时出刀光", ctx -> peakRibbons > 0)
                .check("刀尖甩出火星", ctx -> peakSparks > 0)
                .check("默认是物理刀光", ctx -> "physical".equals(style()))
                .waitUntil("回到待机", ctx -> phase("idle")).ticks(12)
                .check("回到待机后刀光散尽", ctx -> WeaponTrails.ribbons() == 0)
                .step("第三人称正面", ctx -> camera(CameraType.THIRD_PERSON_FRONT))
                .step("按住左键", ctx -> attack(true))
                .waitUntil("第一刀", ctx -> phase("slash_1")).ticks(4).screenshot("front_slash_1")
                .waitUntil("第二刀", ctx -> phase("slash_2")).ticks(4).screenshot("front_slash_2")
                .step("松开左键", ctx -> attack(false))
                .waitUntil("回到待机", ctx -> phase("idle")).ticks(6);
        // 七种元素的魔力缠绕：刀光换成对应元素的样式
        for (Element element : Element.values()) {
            String name = element.name().toLowerCase(Locale.ROOT);
            s.server("缠上" + element.zh, ctx -> dev.logos.runtime.ManaWrap.wrap(ctx.player(), element, 4, 600))
                    .step("背后视角", ctx -> camera(CameraType.THIRD_PERSON_BACK)).ticks(3)
                    .step("按住左键", ctx -> attack(true))
                    .waitUntil("第一刀", ctx -> phase("slash_1")).ticks(4).screenshot(name + "_slash_1")
                    .waitUntil("第二刀", ctx -> phase("slash_2")).ticks(4).screenshot(name + "_slash_2")
                    .check(element.zh + "刀光", ctx -> name.equals(style()))
                    .step("松开左键", ctx -> attack(false))
                    .waitUntil("回到待机", ctx -> phase("idle")).ticks(6);
        }
        s.server("解除缠绕", ctx -> dev.logos.runtime.ManaWrap.wrap(ctx.player(), Element.PHYSICAL, 0, 0))
                .step("第一人称", ctx -> camera(CameraType.FIRST_PERSON)).ticks(3)
                .step("按住左键", ctx -> attack(true))
                .waitUntil("第一刀", ctx -> phase("slash_1")).ticks(4).screenshot("first_person_slash_1")
                .waitUntil("第二刀", ctx -> phase("slash_2")).ticks(4).screenshot("first_person_slash_2")
                .waitUntil("第四刀", ctx -> phase("slash_4")).ticks(8).screenshot("first_person_slash_4")
                .step("松开左键", ctx -> attack(false))
                .waitUntil("回到待机", ctx -> phase("idle"))
                .teardown("恢复界面", ctx -> {
                    attack(false);
                    var mc = Minecraft.getInstance(); mc.options.hideGui = previousHideGui; camera(CameraType.FIRST_PERSON);
                });
    }
}
