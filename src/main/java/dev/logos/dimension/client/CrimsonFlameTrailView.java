package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.client.longsword.LongswordClient;
import dev.logos.dimension.client.trail.WeaponTrails;
import dev.logos.dimension.dragonfire.Dragonfire;
import dev.logos.dimension.item.CrimsonFlameLongswordItem;
import dev.logos.lang.Element;
import dev.logos.runtime.ManaWrap;
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
 * 炽焰长剑的刀光（docs/WEAPON_TRAILS.md、docs/CRIMSON_FLAME_LONGSWORD.md）：四式连招在第三人称背后、正面、第一人称和俯视各截一张。
 * 检查挥剑时出光、默认是龙炎刀光、缠上冰霜魔力时换成冰霜、缠上龙炎时仍是龙炎、回到待机后散尽。
 */
@LDLRegisterClient(name="weapon_trails_crimson_flame", group="weapon_trails_crimson_flame", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
public class CrimsonFlameTrailView implements UIScenario {
    private static final String[] CUTS = {"cleave_1", "rend_2", "ascend_3", "cataclysm_4"};
    /** 各式命中后约 0.1 秒（命中 0.28、0.30、0.34、0.52）；第一人称早一点，刀挥过命中点很快就出了画面。 */
    private static final double[] AT = {.38, .4, .44, .62}, FP = {.3, .32, .36, .55};
    private boolean previousHideGui;
    private int peakRibbons, peakSparks, overhead = -1, golem = -1;

    private static boolean phase(String... moves) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        var p = LongswordClient.pose(mc.player.getUUID());
        if (p == null || p.variant() != CrimsonFlameLongswordItem.ITEM.get().combatWeapon().id()) return false;
        for (String m : moves) if (m.equals(p.move())) return true;
        return false;
    }
    private static void camera(CameraType type) { Minecraft.getInstance().options.setCameraType(type); }
    private static void attack(boolean down) { Minecraft.getInstance().options.keyAttack.setDown(down); }
    private static double seconds() {
        var mc = Minecraft.getInstance();
        var p = LongswordClient.pose(mc.player.getUUID());
        if (p == null) return -1;
        return (mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false) - p.started()) / 20 * p.speed();
    }
    private static boolean frozen() { return !Minecraft.getInstance().level.tickRateManager().runsNormally(); }
    private void watch() {
        peakRibbons = Math.max(peakRibbons, WeaponTrails.ribbons());
        peakSparks = Math.max(peakSparks, WeaponTrails.sparks());
    }
    /** 本机玩家这道第三人称刀光现在用的样式名（路径部分）；没有时为空。 */
    private static String style() {
        var s = LongswordClient.trailStyle(Minecraft.getInstance().player.getId(), false);
        return s == null ? "" : s.id().getPath();
    }

    /** 招式 {@code move} 播到 {@code at} 秒时定格截图，截完恢复。 */
    private ScenarioBuilder shot(ScenarioBuilder s, String name, String move, double at) {
        return s.waitUntil(name + " 开始", ctx -> phase(move))
                .waitUntil(name + " 到 " + at + " 秒", ctx -> phase(move) && seconds() >= at)
                .runCommand("tick freeze").waitUntil(name + " 定格", ctx -> frozen()).ticks(2)
                .step(name + " 记录", ctx -> watch()).screenshot(name)
                .runCommand("tick unfreeze").waitUntil(name + " 恢复", ctx -> !frozen());
    }

    /** 按住左键打完四式，每式定格拍一张。 */
    private ScenarioBuilder combo(ScenarioBuilder s, String view, double[] at) {
        s.step(view + " 按住左键", ctx -> attack(true));
        for (int i = 0; i < 4; i++) shot(s, view + "_" + CUTS[i], CUTS[i], at[i]);
        return s.step(view + " 松开左键", ctx -> attack(false))
                .waitUntil(view + " 回到待机", ctx -> phase("idle")).ticks(14);
    }

    @Override public void configure(ScenarioOptions o) { o.tags("weapon_trails").defaultTimeoutMs(60000).scenarioTimeoutMs(300000); }

    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("weather clear").runCommand("difficulty easy")
                .runCommand("gamerule spawn_mobs false").runCommand("gamerule spawn_monsters false")
                .step("设置视口", ctx -> {
                    var mc = Minecraft.getInstance(); previousHideGui = mc.options.hideGui;
                    mc.options.hideGui = false; mc.player.getInventory().setSelectedSlot(0);
                    camera(CameraType.THIRD_PERSON_BACK); mc.options.fov().set(70);
                    var w = mc.getWindow(); w.setWindowed(1280, 720); w.setWidth(1280); w.setHeight(720);
                    mc.getMainRenderTarget().resize(1280, 720); mc.gameRenderer.resize(1280, 720); mc.resizeGui();
                }).server("设置练剑场与木桩", ctx -> {
                    var level = ctx.server().overworld();
                    for (int x = -8; x <= 8; x++) for (int z = -7; z <= 10; z++) {
                        level.setBlockAndUpdate(new BlockPos(x, 99, z), Blocks.STONE_BRICKS.defaultBlockState());
                        for (int y = 100; y < 110; y++) level.removeBlock(new BlockPos(x, y, z), false);
                    }
                    DimensionTravel.teleport(ctx.player(), level, new Vec3(.5, 100, .5), 0, 0);
                    // 隐身的铁傀儡当靶子：挨剑但不挡镜头
                    var g = EntityType.IRON_GOLEM.create(level, EntitySpawnReason.COMMAND);
                    g.setNoAi(true); g.setNoGravity(true); g.setInvulnerable(true);
                    g.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.INVISIBILITY, 12000, 0, false, false));
                    g.setPos(.5, 100, 3); level.addFreshEntity(g);
                    golem = g.getId();
                    ctx.player().getInventory().clearContent(); ctx.player().getInventory().setSelectedSlot(0);
                    ctx.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(CrimsonFlameLongswordItem.ITEM.get()));
                }).setGameMode(GameType.SURVIVAL)
                .waitUntil("持剑待机", ctx -> phase("idle")).ticks(4)
                .check("待机时不出刀光", ctx -> WeaponTrails.ribbons() == 0);
        combo(s, "back", AT)
                .check("挥剑时出刀光", ctx -> peakRibbons > 0)
                .check("刀上洒出火星", ctx -> peakSparks > 0)
                .check("默认是龙炎刀光", ctx -> "dragonfire".equals(style()))
                .check("回到待机后刀光散尽", ctx -> WeaponTrails.ribbons() == 0)
                .step("第三人称正面", ctx -> camera(CameraType.THIRD_PERSON_FRONT)).ticks(3);
        combo(s, "front", AT)
                .check("正面回到待机后散尽", ctx -> WeaponTrails.ribbons() == 0)
                .server("缠上冰霜", ctx -> ManaWrap.wrap(ctx.player(), Element.FROST, 4, 600))
                .step("背后视角", ctx -> camera(CameraType.THIRD_PERSON_BACK)).ticks(3)
                .step("冰霜 按住左键", ctx -> attack(true));
        shot(s, "frost_cleave_1", "cleave_1", AT[0])
                .check("缠上冰霜时换成冰霜刀光", ctx -> "frost".equals(style()))
                .step("冰霜 松开左键", ctx -> attack(false))
                .waitUntil("冰霜 回到待机", ctx -> phase("idle")).ticks(8)
                .server("换成龙炎缠绕", ctx -> { ManaWrap.wrap(ctx.player(), Element.PHYSICAL, 0, 0); Dragonfire.wrap(ctx.player(), 3, 30); })
                .ticks(3).step("龙炎 按住左键", ctx -> attack(true));
        shot(s, "dragonfire_wrap_cleave_1", "cleave_1", AT[0])
                .check("缠上龙炎时是龙炎刀光（不是奥秘）", ctx -> "dragonfire".equals(style()))
                .step("龙炎 松开左键", ctx -> attack(false))
                .waitUntil("龙炎 回到待机", ctx -> phase("idle")).ticks(8)
                .server("解开缠绕", ctx -> Dragonfire.wrap(ctx.player(), 0, 0))
                .step("第一人称", ctx -> camera(CameraType.FIRST_PERSON)).ticks(3);
        combo(s, "first_person", FP)
                .check("第一人称回到待机后散尽", ctx -> WeaponTrails.ribbons() == 0);
        // 俯视机位：隐身盔甲架在玩家身后斜上方往下看整道刀光（越往尾越窄、越碎）。机位不是玩家时原版仍画第一人称的手，所以藏起界面。
        s.step("第三人称（机位不是玩家时也要画身体）", ctx -> camera(CameraType.THIRD_PERSON_BACK))
                .server("俯视机位", ctx -> {
                    var level = ctx.server().overworld();
                    var stand = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
                    stand.setInvisible(true); stand.setNoGravity(true);
                    Vec3 at = new Vec3(.5, 105, -2.2), look = new Vec3(.5, 100.9, 1.3);
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
        combo(s, "overhead", AT);
        s.step("回到玩家", ctx -> {
                    var mc = Minecraft.getInstance(); mc.setCameraEntity(mc.player); mc.options.hideGui = false;
                }).server("移除机位和靶子", ctx -> {
                    for (int id : new int[]{overhead, golem}) { var e = ctx.server().overworld().getEntity(id); if (e != null) e.discard(); }
                })
                .teardown("恢复界面", ctx -> {
                    attack(false);
                    Minecraft.getInstance().player.connection.sendCommand("tick unfreeze");
                    var mc = Minecraft.getInstance(); mc.setCameraEntity(mc.player);
                    mc.options.hideGui = previousHideGui; camera(CameraType.FIRST_PERSON);
                });
    }
}
