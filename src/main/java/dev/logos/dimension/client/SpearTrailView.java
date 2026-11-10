package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.client.longsword.LongswordClient;
import dev.logos.dimension.client.longsword.LongswordRig;
import dev.logos.dimension.client.trail.WeaponTrails;
import dev.logos.dimension.client.vfx.SpearThrustGhosts;
import dev.logos.dimension.combat.Moveset;
import dev.logos.dimension.item.OathSpearItem;
import dev.logos.lang.Element;
import java.util.Arrays;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * 群誓·狼牙长枪的刀光（docs/WEAPON_TRAILS.md）：拔枪与待机不出光；四段连击（直刺、横扫、过肩刺、上挑）与按住右键的连刺、
 * 转枪下砸，在第三人称背后、正面、侧面机位与第一人称各截图，每一击都检查正在画刀光、回到待机后散尽、样式是长枪自己的
 * “狼牙钢”；连刺时枪尖残影照常放出。最后缠上寒霜魔力刺一枪，检查魔力缠绕仍然换掉样式。
 *
 * <p>截图按招式里的秒对时（刀光画满、还没散的那一刻），不按刻数估。长枪的横扫、上挑都在胸口高度的平面里，
 * 平视时正好侧对镜头，所以第三人称低头 20°（打地上的怪时的视线），侧面机位也从斜上方看。
 */
@LDLRegisterClient(name="weapon_trails_spear", group="weapon_trails_spear", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
public class SpearTrailView implements UIScenario {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("SpearTrailView");
    private boolean previousHideGui;
    private int camera = -1;

    private static boolean phase(String... moves) {
        var p = LongswordClient.pose(Minecraft.getInstance().player.getUUID());
        return p != null && Arrays.asList(moves).contains(p.move());
    }
    /** 本机玩家当前招式播到了第几秒（动作时间）；没有招式时为 -1。 */
    private static double seconds() {
        var mc = Minecraft.getInstance();
        var p = LongswordClient.pose(mc.player.getUUID());
        var rig = p == null ? null : LongswordRig.get(p.variant(), false);
        double length = rig == null ? -1 : rig.length(p.clip());
        if (length < 0) return -1;
        double now = mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        return Moveset.playbackSeconds(p.playback(), length, now - p.started(), p.speed(), p.offset());
    }
    /** 截图那一刻的招式与秒（写进日志，对照截图用）。 */
    private static String shotAt = "";
    /**
     * 等到 {@code move} 播到第 {@code at} 秒就截图（中间不留步骤间的等待），再检查刀光在画。
     * 截图落在条件成立后的下一帧，比 {@code at} 晚不到一刻。
     */
    private static ScenarioBuilder shot(ScenarioBuilder s, String view, String move, double at, String label) {
        return s.settleMs(0).waitUntil(label, ctx -> {
                    boolean ready = phase(move) && seconds() >= at;
                    if (ready) shotAt = String.format("%.3f", seconds());
                    return ready;
                })
                .screenshot(view + "_" + move + "_" + Math.round(at * 100))
                .check(view + label + "出刀光", ctx -> WeaponTrails.ribbons() > 0)
                .step("记录时刻", ctx -> LOGGER.info("{} {}：条件成立于 {} 秒，截图后 {} 秒", view, move, shotAt, String.format("%.3f", seconds())));
    }
    private static void camera(CameraType type) { Minecraft.getInstance().options.setCameraType(type); }
    private static void attack(boolean down) { Minecraft.getInstance().options.keyAttack.setDown(down); }
    private static void hold(boolean use) {
        var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(use);
        if (use) mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND); else mc.gameMode.releaseUsingItem(mc.player);
    }
    private static void pitch(float degrees) { var p = Minecraft.getInstance().player; p.setXRot(degrees); p.xRotO = degrees; }
    /** 本机玩家这道刀光现在用的样式名（路径部分）；没有时为空。 */
    private static String style(boolean firstPerson) {
        var s = LongswordClient.trailStyle(Minecraft.getInstance().player.getId(), firstPerson);
        return s == null ? "" : s.id().getPath();
    }

    @Override public void configure(ScenarioOptions o) { o.tags("weapon_trails").defaultTimeoutMs(60000).scenarioTimeoutMs(300000); }

    /** 四段连击，每一击在刀光画满时截图；松开左键回到待机后刀光散尽。 */
    private static void combo(ScenarioBuilder s, String view) {
        s.step("按住左键", ctx -> attack(true));
        shot(s, view, "thrust_1", .17, "直刺");
        shot(s, view, "sweep_2", .33, "横扫");
        shot(s, view, "overhand_3", .28, "过肩刺");
        // 第四击一开始就松开左键，不再接回第一击。
        s.waitUntil("上挑", ctx -> phase("launch_4")).step("松开左键", ctx -> attack(false));
        shot(s, view, "launch_4", .42, "上挑");
        s.waitUntil("回到待机", ctx -> phase("idle")).ticks(12)
                .check(view + "回到待机后刀光散尽", ctx -> WeaponTrails.ribbons() == 0);
    }

    /** 按住右键连刺三段、转枪下砸；连刺时枪尖残影照常放出。 */
    private static void flurry(ScenarioBuilder s, String view) {
        s.step("按住右键", ctx -> hold(true));
        shot(s, view, "flurry_a", .31, "连刺");
        s.check(view + "连刺的枪尖残影还在", ctx -> !SpearThrustGhosts.ghosts().isEmpty());
        shot(s, view, "flurry_b", .56, "连刺");
        shot(s, view, "slam", .42, "转枪");
        shot(s, view, "slam", .74, "下砸");
        s.step("松开右键", ctx -> hold(false))
                .waitUntil("回到待机", ctx -> phase("idle")).ticks(12)
                .check(view + "下砸后刀光散尽", ctx -> WeaponTrails.ribbons() == 0);
    }

    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("weather clear").runCommand("difficulty easy")
                .step("设置视口", ctx -> {
                    var mc = Minecraft.getInstance(); previousHideGui = mc.options.hideGui;
                    mc.options.hideGui = false; mc.player.getInventory().setSelectedSlot(0);
                    camera(CameraType.THIRD_PERSON_BACK); mc.options.fov().set(70);
                    var w = mc.getWindow(); w.setWindowed(1280, 720); w.setWidth(1280); w.setHeight(720);
                    mc.getMainRenderTarget().resize(1280, 720); mc.gameRenderer.resize(1280, 720); mc.resizeGui();
                }).server("设置练枪场与木桩", ctx -> {
                    var level = ctx.server().overworld();
                    for (int x = -8; x <= 8; x++) for (int z = -8; z <= 10; z++) {
                        level.setBlockAndUpdate(new BlockPos(x, 99, z), Blocks.STONE_BRICKS.defaultBlockState());
                        for (int y = 100; y < 110; y++) level.removeBlock(new BlockPos(x, y, z), false);
                    }
                    DimensionTravel.teleport(ctx.player(), level, new Vec3(.5, 100, .5), 0, 0);
                    var golem = EntityType.IRON_GOLEM.create(level, EntitySpawnReason.COMMAND);
                    golem.setNoAi(true); golem.setNoGravity(true); golem.setInvulnerable(true);
                    golem.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.INVISIBILITY, 12000, 0, false, false));
                    golem.setPos(.5, 100, 3.3); level.addFreshEntity(golem);
                    ctx.player().getInventory().clearContent(); ctx.player().getInventory().setSelectedSlot(0);
                    ctx.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(OathSpearItem.PACK_OATH.get()));
                }).setGameMode(GameType.SURVIVAL)
                // 拔枪从背后转两圈，枪尖很快，但不是攻击，不出光。
                .waitUntil("拔枪", ctx -> phase("draw") && seconds() >= .45)
                .check("拔枪不出刀光", ctx -> WeaponTrails.ribbons() == 0).screenshot("draw")
                .waitUntil("待机", ctx -> phase("idle")).ticks(4)
                .check("待机不出刀光", ctx -> WeaponTrails.ribbons() == 0)
                .step("低头", ctx -> pitch(20)).ticks(2);

        combo(s, "back");
        s.check("默认是狼牙钢刀光", ctx -> "oath_spear".equals(style(false)));
        flurry(s, "back");

        s.step("第三人称正面", ctx -> camera(CameraType.THIRD_PERSON_FRONT));
        combo(s, "front");
        flurry(s, "front");

        // 侧面机位：隐身盔甲架站在持枪的右手一侧（玩家面朝 +Z，右手在 -X）的斜上方。机位不是玩家时原版仍会画第一人称的手，
        // 所以藏起界面。
        s.server("侧面机位", ctx -> {
            var level = ctx.server().overworld();
            ArmorStand stand = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
            stand.setInvisible(true); stand.setNoGravity(true);
            Vec3 at = new Vec3(-4.4, 102.7, 1.4), look = new Vec3(.5, 101.0, 1.4);
            double dx = look.x - at.x, dy = look.y - at.y, dz = look.z - at.z;
            stand.snapTo(at.x, at.y - stand.getEyeHeight(), at.z, (float) -Math.toDegrees(Math.atan2(dx, dz)),
                    (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
            level.addFreshEntity(stand);
            camera = stand.getId();
        }).waitUntil("机位同步", ctx -> Minecraft.getInstance().level.getEntity(camera) != null)
                .step("切到侧面机位", ctx -> {
                    var mc = Minecraft.getInstance(); Entity stand = mc.level.getEntity(camera);
                    stand.setYHeadRot(stand.getYRot()); stand.yRotO = stand.getYRot(); stand.xRotO = stand.getXRot();
                    mc.setCameraEntity(stand); camera(CameraType.FIRST_PERSON); mc.options.hideGui = true;
                }).ticks(2);
        combo(s, "side");
        flurry(s, "side");
        s.step("回到玩家", ctx -> {
                    var mc = Minecraft.getInstance(); mc.setCameraEntity(mc.player); mc.options.hideGui = false;
                }).server("移除机位", ctx -> { var e = ctx.server().overworld().getEntity(camera); if (e != null) e.discard(); });

        s.step("第一人称、平视", ctx -> { camera(CameraType.FIRST_PERSON); pitch(0); }).ticks(3);
        combo(s, "fp");
        s.check("第一人称也是狼牙钢刀光", ctx -> "oath_spear".equals(style(true)));
        flurry(s, "fp");

        // 缠上魔力时刀光换成那种元素的（长枪的样式不 fixed）。
        s.server("缠上寒霜", ctx -> dev.logos.runtime.ManaWrap.wrap(ctx.player(), Element.FROST, 4, 600))
                .step("背后视角", ctx -> { camera(CameraType.THIRD_PERSON_BACK); pitch(20); }).ticks(3)
                .step("按住左键", ctx -> attack(true));
        shot(s, "frost", "thrust_1", .17, "直刺");
        shot(s, "frost", "sweep_2", .33, "横扫");
        s.check("缠着寒霜时是寒霜刀光", ctx -> "frost".equals(style(false)))
                .step("松开左键", ctx -> attack(false))
                .waitUntil("回到待机", ctx -> phase("idle")).ticks(6)
                .server("解除缠绕", ctx -> dev.logos.runtime.ManaWrap.wrap(ctx.player(), Element.PHYSICAL, 0, 0))
                .teardown("恢复界面", ctx -> {
                    attack(false);
                    var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(false);
                    mc.setCameraEntity(mc.player); mc.options.hideGui = previousHideGui; camera(CameraType.FIRST_PERSON);
                });
    }
}
