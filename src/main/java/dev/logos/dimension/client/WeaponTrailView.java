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
 * 再依次缠上七种元素的魔力各砍一刀，检查刀光换成对应元素的样式并截图，最后从斜上方俯视看整道刀光的形状。
 * 每张都在那一刀挥过命中点约 0.1 秒时定格（/tick freeze）再截：截图本身要花零点几秒，不定格的话拍到的是刀光散了一半之后。
 */
@LDLRegisterClient(name="weapon_trails", group="weapon_trails", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
public class WeaponTrailView implements UIScenario {
    private boolean previousHideGui;
    private int peakRibbons, peakSparks, overhead;

    private static boolean phase(String... moves) {
        var p = LongswordClient.pose(Minecraft.getInstance().player.getUUID());
        return p != null && Arrays.asList(moves).contains(p.move());
    }
    private static void camera(CameraType type) { Minecraft.getInstance().options.setCameraType(type); }
    private static void attack(boolean down) { Minecraft.getInstance().options.keyAttack.setDown(down); }
    /** 四段连招，与各自截图的时刻（招式里的秒：命中点之后约 0.1 秒，整道刀光已经扫出来、头部还新）。 */
    private static final String[] CUTS = {"slash_1", "slash_2", "slash_3", "slash_4"};
    private static final double[] AT = {.25, .28, .3, .5};

    /** 当前招式播到第几秒。 */
    private static double seconds() {
        var mc = Minecraft.getInstance();
        var p = LongswordClient.pose(mc.player.getUUID());
        if (p == null) return -1;
        return (mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false) - p.started()) / 20 * p.speed();
    }
    private static boolean frozen() { return !Minecraft.getInstance().level.tickRateManager().runsNormally(); }

    /** 招式 {@code cut} 播到 {@link #AT} 那一刻定格截图，截完恢复。 */
    private ScenarioBuilder shot(ScenarioBuilder s, String name, int cut) { return shot(s, name, cut, AT[cut]); }

    /** 同上，在招式的第 {@code at} 秒（第一人称要早一点：挥过命中点后刀很快就出了画面）。 */
    private ScenarioBuilder shot(ScenarioBuilder s, String name, int cut, double at) {
        String move = CUTS[cut];
        return s.waitUntil(name + " 开始", ctx -> phase(move))
                .waitUntil(name + " 到 " + at + " 秒", ctx -> phase(move) && seconds() >= at)
                .runCommand("tick freeze").waitUntil(name + " 定格", ctx -> frozen()).ticks(2)
                .step(name + " 记录", ctx -> watch()).screenshot(name)
                .runCommand("tick unfreeze").waitUntil(name + " 恢复", ctx -> !frozen());
    }

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
                .step("按住左键", ctx -> attack(true));
        for (int i = 0; i < 4; i++) shot(s, "back_" + CUTS[i], i);
        s.step("松开左键", ctx -> attack(false))
                .check("挥砍时出刀光", ctx -> peakRibbons > 0)
                .check("刀尖甩出火星", ctx -> peakSparks > 0)
                .check("默认是物理刀光", ctx -> "physical".equals(style()))
                .waitUntil("回到待机", ctx -> phase("idle")).ticks(12)
                .check("回到待机后刀光散尽", ctx -> WeaponTrails.ribbons() == 0)
                .step("第三人称正面", ctx -> camera(CameraType.THIRD_PERSON_FRONT))
                .step("按住左键", ctx -> attack(true));
        shot(s, "front_slash_1", 0);
        shot(s, "front_slash_2", 1)
                .step("松开左键", ctx -> attack(false))
                .waitUntil("回到待机", ctx -> phase("idle")).ticks(6);
        // 七种元素的魔力缠绕：刀光换成对应元素的样式
        for (Element element : Element.values()) {
            String name = element.name().toLowerCase(Locale.ROOT);
            s.server("缠上" + element.zh, ctx -> dev.logos.runtime.ManaWrap.wrap(ctx.player(), element, 4, 600))
                    .step("背后视角", ctx -> camera(CameraType.THIRD_PERSON_BACK)).ticks(3)
                    .step("按住左键", ctx -> attack(true));
            shot(s, name + "_slash_1", 0);
            shot(s, name + "_slash_2", 1)
                    .check(element.zh + "刀光", ctx -> name.equals(style()))
                    .step("松开左键", ctx -> attack(false))
                    .waitUntil("回到待机", ctx -> phase("idle")).ticks(6);
        }
        s.server("解除缠绕", ctx -> dev.logos.runtime.ManaWrap.wrap(ctx.player(), Element.PHYSICAL, 0, 0))
                .step("第一人称", ctx -> camera(CameraType.FIRST_PERSON)).ticks(3)
                .step("按住左键", ctx -> attack(true));
        shot(s, "first_person_slash_1", 0, .17);
        shot(s, "first_person_slash_2", 1, .2);
        shot(s, "first_person_slash_4", 3, .42)
                .step("松开左键", ctx -> attack(false))
                .waitUntil("回到待机", ctx -> phase("idle"));
        // 俯视机位：隐身盔甲架在玩家身后斜上方往下看，看整道刀光的形状（越往尾越窄、越碎）。机位不是玩家时原版仍画第一人称的手，所以藏起界面。
        s.step("第三人称（机位不是玩家时也要画身体）", ctx -> camera(CameraType.THIRD_PERSON_BACK))
                .server("俯视机位", ctx -> {
                    var level = ctx.server().overworld();
                    var stand = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
                    stand.setInvisible(true); stand.setNoGravity(true);
                    Vec3 at = new Vec3(.5, 104.1, -1.7), look = new Vec3(.5, 100.9, 1.1);
                    double dx = look.x - at.x, dy = look.y - at.y, dz = look.z - at.z;
                    stand.snapTo(at.x, at.y - stand.getEyeHeight(), at.z, (float) -Math.toDegrees(Math.atan2(dx, dz)),
                            (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
                    level.addFreshEntity(stand);
                    overhead = stand.getId();
                }).waitUntil("机位同步", ctx -> Minecraft.getInstance().level.getEntity(overhead) != null)
                .step("切到俯视机位", ctx -> {
                    var mc = Minecraft.getInstance(); Entity stand = mc.level.getEntity(overhead);
                    stand.setYHeadRot(stand.getYRot()); stand.yRotO = stand.getYRot(); stand.xRotO = stand.getXRot();
                    mc.setCameraEntity(stand); camera(CameraType.FIRST_PERSON); mc.options.hideGui = true;
                }).ticks(2);
        for (Element element : new Element[]{Element.PHYSICAL, Element.FIRE, Element.VOID}) {
            String name = element.name().toLowerCase(Locale.ROOT);
            s.server("俯视：缠上" + element.zh, ctx -> dev.logos.runtime.ManaWrap.wrap(ctx.player(), element, element == Element.PHYSICAL ? 0 : 4,
                            element == Element.PHYSICAL ? 0 : 600))
                    .ticks(2).step("按住左键", ctx -> attack(true));
            for (int i = 0; i < 4; i++) shot(s, "overhead_" + name + "_" + CUTS[i], i);
            s.step("松开左键", ctx -> attack(false))
                    .waitUntil("回到待机", ctx -> phase("idle")).ticks(6);
        }
        s.step("回到玩家", ctx -> {
                    var mc = Minecraft.getInstance(); mc.setCameraEntity(mc.player); mc.options.hideGui = false;
                }).server("移除机位", ctx -> { var e = ctx.server().overworld().getEntity(overhead); if (e != null) e.discard(); })
                .teardown("恢复界面", ctx -> {
                    attack(false);
                    Minecraft.getInstance().player.connection.sendCommand("tick unfreeze");
                    var mc = Minecraft.getInstance(); mc.setCameraEntity(mc.player);
                    mc.options.hideGui = previousHideGui; camera(CameraType.FIRST_PERSON);
                });
    }
}
