package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.client.longsword.LongswordClient;
import dev.logos.dimension.client.trail.WeaponTrails;
import dev.logos.dimension.item.RyukaItem;
import dev.logos.lang.Element;
import dev.logos.runtime.PlayerMagic;
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

/**
 * 流火的刀光（docs/WEAPON_TRAILS.md）实际试玩：五段连斩在第三人称背后、正面与第一人称各截图；刀光是流火自己的
 * 晶刃狐火样式，缠上别的元素也不换（{@code fixed}）；弹刀与突刺也有；凝聚、散去时刀身不完整，不出刀光；回到待机后刀光散尽。
 */
@LDLRegisterClient(name="ryuka_trails", group="weapon_trails_ryuka", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
public class RyukaTrailView implements UIScenario {
    private boolean previousHideGui;
    private int peakRibbons, peakSparks;
    /** 凝聚、散去（刀身不完整）时看到过刀光。 */
    private boolean leaked;
    private int target;

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
    /** 本机玩家当前招式是 {@code move} 时它已经播到的秒数；不是这一招时为 -1。 */
    private static double seconds(String move) {
        var mc = Minecraft.getInstance();
        var p = LongswordClient.pose(mc.player.getUUID());
        if (p == null || !p.move().equals(move)) return -1;
        return (mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false) - p.started()) / 20 * p.speed();
    }
    /** 五段连斩各在哪一刻截图：出光时段（combat/trails/ryuka.json）将尽、刀刚挥到底的时候。 */
    private static final String[] SLASHES = {"slash_1", "slash_2", "slash_3", "slash_4", "slash_5"};
    private static final double[] AT = {.14, .13, .15, .19, .24};
    /** 按住左键打完五段，每段挥到底时截图（{@code prefix} + 招式名）。 */
    private void combo(ScenarioBuilder s, String prefix) {
        s.step(prefix + "按住左键", ctx -> attack(true));
        for (int i = 0; i < SLASHES.length; i++) {
            String move = SLASHES[i];
            double at = AT[i];
            s.waitUntil(prefix + move, ctx -> { watch(); return seconds(move) >= at; }).screenshot(prefix + move);
        }
        s.step(prefix + "松开左键", ctx -> attack(false));
    }
    /** 本机玩家这道刀光现在用的样式名（路径部分）；没有时为空。 */
    private static String style(boolean firstPerson) {
        var s = LongswordClient.trailStyle(Minecraft.getInstance().player.getId(), firstPerson);
        return s == null ? "" : s.id().getPath();
    }

    @Override public void configure(ScenarioOptions o) { o.tags("weapon_trails_ryuka").defaultTimeoutMs(60000).scenarioTimeoutMs(300000); }

    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("weather clear").runCommand("difficulty easy")
                .runCommand("gamerule spawn_mobs false")
                .step("设置视口", ctx -> {
                    var mc = Minecraft.getInstance(); previousHideGui = mc.options.hideGui;
                    mc.options.hideGui = true; mc.player.getInventory().setSelectedSlot(0);
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
                    golem.setPos(.5, 100, 2.8); level.addFreshEntity(golem); target = golem.getId();
                    var magic = PlayerMagic.of(ctx.player()); magic.baseMaxMana = Math.max(magic.baseMaxMana, 3000); magic.mana = 3000;
                    // 流火放在第 2 格，先空手：切过去那一刻开始凝聚
                    ctx.player().getInventory().clearContent();
                    ctx.player().getInventory().setItem(1, new ItemStack(RyukaItem.ITEM.get()));
                }).setGameMode(GameType.SURVIVAL).ticks(10)
                // ---- 凝聚：刀身还没长全，不出刀光
                .step("切到流火", ctx -> { leaked = false; Minecraft.getInstance().player.getInventory().setSelectedSlot(1); })
                .waitUntil("凝聚", ctx -> phase("manifest"))
                .waitUntil("凝聚完回到待机", ctx -> { leaked |= WeaponTrails.ribbons() > 0; return phase("idle"); })
                .check("凝聚时不出刀光", ctx -> !leaked)
                .ticks(6).check("待机时不出刀光", ctx -> WeaponTrails.ribbons() == 0);
        // ---- 第三人称背后：五段连斩
        combo(s, "back_");
        s.check("挥砍时出刀光", ctx -> peakRibbons > 0)
                .check("刀尖甩出花瓣", ctx -> peakSparks > 0)
                .check("流火自己的刀光样式", ctx -> "ryuka".equals(style(false)))
                .waitUntil("回到待机", ctx -> phase("idle")).ticks(12)
                .check("回到待机后刀光散尽", ctx -> WeaponTrails.ribbons() == 0)
                // ---- 第三人称正面
                .step("第三人称正面", ctx -> camera(CameraType.THIRD_PERSON_FRONT));
        combo(s, "front_");
        s.waitUntil("回到待机", ctx -> phase("idle")).ticks(6)
                // ---- 缠上火焰：流火的狐火是自带的，刀光不换成火焰
                .server("缠上火焰", ctx -> dev.logos.runtime.ManaWrap.wrap(ctx.player(), Element.FIRE, 4, 600))
                .step("背后视角", ctx -> camera(CameraType.THIRD_PERSON_BACK)).ticks(3)
                .step("按住左键", ctx -> attack(true))
                .waitUntil("一", ctx -> seconds("slash_1") >= AT[0]).screenshot("fire_wrap_slash_1")
                .step("松开左键", ctx -> attack(false))
                .check("缠着火焰也还是流火的刀光", ctx -> "ryuka".equals(style(false)))
                .waitUntil("回到待机", ctx -> phase("idle")).ticks(6)
                .server("解除缠绕", ctx -> dev.logos.runtime.ManaWrap.wrap(ctx.player(), Element.PHYSICAL, 0, 0))
                // ---- 弹刀与突刺：一进入格挡就被木桩砍中（0.5 秒内弹刀），左键早已按住，弹开后马上突刺
                .step("格挡并按住左键", ctx -> {
                    camera(CameraType.THIRD_PERSON_FRONT);
                    var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(true); mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                    attack(true);
                })
                .waitUntilServer("一进入格挡就被砍中", ctx -> {
                    if (!"guard".equals(dev.logos.dimension.longsword.LongswordCombat.move(ctx.player()))) return false;
                    var golem = (LivingEntity) ctx.server().overworld().getEntity(target);
                    ctx.player().hurtServer(ctx.server().overworld(), ctx.server().overworld().damageSources().mobAttack(golem), 4);
                    return true;
                })
                .step("清零", ctx -> peakRibbons = 0)
                .waitUntil("突刺", ctx -> { watch(); return seconds("riposte") >= .07; }).screenshot("riposte")
                .check("弹刀与突刺出刀光", ctx -> peakRibbons > 0)
                .step("松开左右键", ctx -> {
                    attack(false);
                    var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(false); mc.gameMode.releaseUsingItem(mc.player);
                })
                .waitUntil("回到待机", ctx -> phase("idle")).ticks(6)
                // ---- 第一人称
                .step("第一人称", ctx -> {
                    // 隐藏界面时第一人称连手带刀都不画，这一段要打开
                    camera(CameraType.FIRST_PERSON); Minecraft.getInstance().options.hideGui = false; peakRibbons = 0;
                }).ticks(3);
        combo(s, "first_person_");
        s.check("第一人称出刀光", ctx -> peakRibbons > 0)
                .check("第一人称也是流火的刀光", ctx -> "ryuka".equals(style(true)))
                .waitUntil("回到待机", ctx -> phase("idle")).ticks(12)
                .check("第一人称回到待机后刀光散尽", ctx -> WeaponTrails.ribbons() == 0)
                // ---- 散去：刀身从尖往柄散成花瓣，不出刀光
                .step("第三人称切走流火", ctx -> {
                    leaked = false; camera(CameraType.THIRD_PERSON_BACK);
                    Minecraft.getInstance().player.getInventory().setSelectedSlot(2);
                })
                .waitUntil("散去", ctx -> phase("dissipate"))
                .waitUntil("散去结束", ctx -> {
                    leaked |= WeaponTrails.ribbons() > 0;
                    return LongswordClient.pose(Minecraft.getInstance().player.getUUID()) == null;
                })
                .check("散去时不出刀光", ctx -> !leaked)
                .server("移除木桩", ctx -> { var e = ctx.server().overworld().getEntity(target); if (e != null) e.discard(); })
                .teardown("恢复界面", ctx -> {
                    attack(false);
                    var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(false);
                    mc.options.hideGui = previousHideGui; camera(CameraType.FIRST_PERSON);
                });
    }
}
