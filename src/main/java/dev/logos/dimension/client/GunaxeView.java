package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.client.firearm.AmmoWheelScreen;
import dev.logos.dimension.client.firearm.FirearmClient;
import dev.logos.dimension.client.longsword.LongswordClient;
import dev.logos.dimension.client.longsword.LongswordRig;
import dev.logos.dimension.combat.Moveset;
import dev.logos.dimension.firearm.BuckshotItem;
import dev.logos.dimension.firearm.Firearms;
import dev.logos.dimension.firearm.GunLoad;
import dev.logos.dimension.item.GunaxeItem;
import java.util.Arrays;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * 铳斧与霰弹（docs/GUNAXE.md、docs/FIREARMS.md）的实际操作：
 * 短按 R 换弹（装满六发、背包扣六发），举枪瞄准开枪（木桩掉血、少一发），四连击接第五下开枪，第五下后坐里按右键扇击打光剩下的、立即换弹，
 * 背包没弹药时打空了只空响、不换弹，捡到弹药后空响自动换弹，长按 R 打开弹药轮盘。
 * 第一人称与侧面机位在换弹、瞄准、开枪的那一刻各截图；转轮窗口里看得到还剩几发。
 */
@LDLRegisterClient(name="gunaxe", group="gunaxe", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
public class GunaxeView implements UIScenario {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("GunaxeView");
    private boolean previousHideGui;
    private int camera = -1;
    private static float golemHealth;

    private static boolean phase(String... moves) {
        var p = LongswordClient.pose(Minecraft.getInstance().player.getUUID());
        return p != null && Arrays.asList(moves).contains(p.move());
    }
    private static double seconds() {
        var mc = Minecraft.getInstance();
        var p = LongswordClient.pose(mc.player.getUUID());
        var rig = p == null ? null : LongswordRig.get(p.variant(), false);
        double length = rig == null ? -1 : rig.length(p.clip());
        if (length < 0) return -1;
        double now = mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        return Moveset.playbackSeconds(p.playback(), length, now - p.started(), p.speed(), p.offset());
    }
    private static ScenarioBuilder shot(ScenarioBuilder s, String view, String move, double at) {
        return s.settleMs(0).waitUntil(move + " " + at, ctx -> phase(move) && seconds() >= at)
                .screenshot(view + "_" + move + "_" + Math.round(at * 100))
                .step("记录", ctx -> LOGGER.info("{} {} {} 秒，膛里 {} 发", view, move, String.format("%.3f", seconds()), rounds(Minecraft.getInstance().player)));
    }
    private static int rounds(Player player) { return Firearms.load(player.getMainHandItem()).rounds(); }
    private static int shells(Player player) { return Firearms.count(player, new ItemStack(BuckshotItem.ITEM.get())); }
    private static void camera(CameraType type) { Minecraft.getInstance().options.setCameraType(type); }
    private static void attack(boolean down) { Minecraft.getInstance().options.keyAttack.setDown(down); }
    private static void reloadKey(boolean down) { FirearmClient.RELOAD.setDown(down); }
    private static void aim(boolean use) {
        var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(use);
        if (use) mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND); else mc.gameMode.releaseUsingItem(mc.player);
    }
    private static void pitch(float degrees) { var p = Minecraft.getInstance().player; p.setXRot(degrees); p.xRotO = degrees; }
    private static IronGolem golem(ServerPlayer player) {
        return player.level().getEntitiesOfClass(IronGolem.class, player.getBoundingBox().inflate(20)).stream().findFirst().orElse(null);
    }

    @Override public void configure(ScenarioOptions o) { o.tags("gunaxe", "firearms").defaultTimeoutMs(60000).scenarioTimeoutMs(300000); }

    /** 短按 R：两刻后松开。 */
    private static void tapReload(ScenarioBuilder s) {
        s.step("按下 R", ctx -> reloadKey(true)).ticks(2).step("松开 R", ctx -> reloadKey(false));
    }

    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("weather clear").runCommand("difficulty peaceful")
                .runCommand("gamerule doMobSpawning false")
                .step("设置视口", ctx -> {
                    var mc = Minecraft.getInstance(); previousHideGui = mc.options.hideGui;
                    mc.options.hideGui = false; mc.player.getInventory().setSelectedSlot(0);
                    camera(CameraType.FIRST_PERSON); mc.options.fov().set(70);
                    var w = mc.getWindow(); w.setWindowed(1280, 720); w.setWidth(1280); w.setHeight(720);
                    mc.getMainRenderTarget().resize(1280, 720); mc.gameRenderer.resize(1280, 720); mc.resizeGui();
                }).server("靶场与木桩", ctx -> {
                    var level = ctx.server().overworld();
                    for (int x = -8; x <= 8; x++) for (int z = -8; z <= 14; z++) {
                        level.setBlockAndUpdate(new BlockPos(x, 99, z), Blocks.STONE_BRICKS.defaultBlockState());
                        for (int y = 100; y < 110; y++) level.removeBlock(new BlockPos(x, y, z), false);
                    }
                    for (int x = -8; x <= 8; x++) for (int y = 100; y < 104; y++) level.setBlockAndUpdate(new BlockPos(x, y, 14), Blocks.OAK_PLANKS.defaultBlockState());
                    DimensionTravel.teleport(ctx.player(), level, new Vec3(.5, 100, .5), 0, 0);
                    var golem = EntityType.IRON_GOLEM.create(level, EntitySpawnReason.COMMAND);
                    golem.setNoAi(true); golem.setPos(.5, 100, 4.5); level.addFreshEntity(golem);
                    var inv = ctx.player().getInventory();
                    inv.clearContent(); inv.setSelectedSlot(0);
                    ctx.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(GunaxeItem.ITEM.get()));
                    inv.setItem(1, new ItemStack(BuckshotItem.ITEM.get(), 20));
                }).setGameMode(GameType.SURVIVAL)
                .waitUntil("待机", ctx -> phase("idle")).ticks(4)
                .check("刚拿到的铳斧是空的", ctx -> rounds(Minecraft.getInstance().player) == 0)
                .screenshot("fp_idle_empty");

        // 短按 R 换弹：换弹动作 1.3 秒装到位，膛里六发、背包扣六发。
        tapReload(s);
        s.waitUntil("开始换弹", ctx -> phase("reload"));
        shot(s, "fp", "reload", .5);
        shot(s, "fp", "reload", 1.2);
        s.waitUntil("装到位", ctx -> rounds(Minecraft.getInstance().player) == 6)
                .check("背包扣了六发", ctx -> shells(Minecraft.getInstance().player) == 14);
        shot(s, "fp", "reload", 1.5);
        s.waitUntil("换完回到待机", ctx -> phase("idle")).ticks(4).screenshot("fp_idle_loaded");

        // 举枪瞄准开枪：第一人称照门准星对准，开枪后木桩掉血、少一发。
        s.server("记录木桩血量", ctx -> golemHealth = golem(ctx.player()).getHealth())
                .step("按住右键", ctx -> aim(true))
                .waitUntil("举枪", ctx -> phase("aim")).ticks(8)
                .check("瞄准时不画原版准星", ctx -> FirearmClient.aiming()).screenshot("fp_aim")
                .step("左键开枪", ctx -> attack(true)).ticks(1).step("松开左键", ctx -> attack(false));
        shot(s, "fp", "aim_shot", .06);
        s.waitUntilServer("木桩挨了霰弹", ctx -> golem(ctx.player()).getHealth() < golemHealth - 5)
                .check("少了一发", ctx -> rounds(Minecraft.getInstance().player) == 5)
                .waitUntil("回到瞄准", ctx -> phase("aim")).step("松开右键", ctx -> aim(false))
                .waitUntil("放下枪", ctx -> phase("idle")).ticks(4)
                .check("放下枪后准星回来", ctx -> !FirearmClient.aiming());

        // 四连击接第五下开枪（第三人称侧面机位）。
        s.server("侧面机位", ctx -> {
            var level = ctx.server().overworld();
            ArmorStand stand = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
            stand.setInvisible(true); stand.setNoGravity(true);
            Vec3 at = new Vec3(-5.5, 102.2, 2.6), look = new Vec3(.5, 101.0, 2.2);
            double dx = look.x - at.x, dy = look.y - at.y, dz = look.z - at.z;
            stand.snapTo(at.x, at.y - stand.getEyeHeight(), at.z, (float) -Math.toDegrees(Math.atan2(dx, dz)),
                    (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
            level.addFreshEntity(stand);
            camera = stand.getId();
        }).waitUntil("机位同步", ctx -> Minecraft.getInstance().level.getEntity(camera) != null)
                .step("切到侧面机位", ctx -> {
                    var mc = Minecraft.getInstance(); Entity stand = mc.level.getEntity(camera);
                    stand.setYHeadRot(stand.getYRot()); stand.yRotO = stand.getYRot(); stand.xRotO = stand.getXRot();
                    mc.setCameraEntity(stand); mc.options.hideGui = true;
                }).ticks(2)
                .step("按住左键", ctx -> attack(true));
        shot(s, "side", "attack_1", .3);
        shot(s, "side", "attack_3", .32);
        shot(s, "side", "attack_4", .24);
        s.waitUntil("第五下", ctx -> phase("shot_5")).step("松开左键", ctx -> attack(false));
        shot(s, "side", "shot_5", .1);
        shot(s, "side", "shot_5", .2);
        s.check("第五下开了一枪", ctx -> rounds(Minecraft.getInstance().player) == 4)
                .waitUntil("回到待机", ctx -> phase("idle")).ticks(4);

        // 再来一轮：第五下开完枪趁后坐按右键扇击，剩下的三发一口气打光，打空立即换弹。
        s.step("按住左键", ctx -> attack(true))
                .waitUntil("第五下", ctx -> phase("shot_5")).step("松开左键", ctx -> attack(false))
                .settleMs(0).waitUntil("后坐里", ctx -> phase("shot_5") && seconds() >= .15)
                .step("按右键", ctx -> aim(true))
                .settleMs(0).waitUntil("扇击", ctx -> phase("fan"));
        shot(s, "side", "fan", .2);
        shot(s, "side", "fan", .34);
        s.waitUntil("扇光了", ctx -> rounds(Minecraft.getInstance().player) == 0)
                .waitUntil("打空立即换弹", ctx -> phase("reload"))
                .step("松开右键", ctx -> aim(false))
                .waitUntil("装到位", ctx -> rounds(Minecraft.getInstance().player) == 6)
                .waitUntil("回到待机", ctx -> phase("idle")).ticks(4)
                .step("回到玩家", ctx -> { var mc = Minecraft.getInstance(); mc.setCameraEntity(mc.player); mc.options.hideGui = false; })
                .server("移除机位", ctx -> { var e = ctx.server().overworld().getEntity(camera); if (e != null) e.discard(); });

        // 打空且背包没弹药：第五下只空响，不换弹。
        s.server("膛里清空、拿走弹药", ctx -> {
                    var gun = ctx.player().getMainHandItem();
                    gun.set(Firearms.LOAD.get(), new GunLoad(new ItemStack(BuckshotItem.ITEM.get()), ItemStack.EMPTY, 0));
                    ctx.player().getInventory().setItem(1, ItemStack.EMPTY);
                }).waitUntil("空膛同步", ctx -> rounds(Minecraft.getInstance().player) == 0)
                .step("按住左键", ctx -> attack(true))
                .waitUntil("空响", ctx -> phase("dry_5")).step("松开左键", ctx -> attack(false));
        shot(s, "fp", "dry_5", .2);
        s.waitUntil("回到待机", ctx -> phase("idle")).ticks(10)
                .check("没弹药时不换弹", ctx -> phase("idle") && rounds(Minecraft.getInstance().player) == 0);

        // 捡到弹药：再扣一次空膛（瞄准空响），自动换弹装满。
        s.server("给弹药", ctx -> ctx.player().getInventory().setItem(1, new ItemStack(BuckshotItem.ITEM.get(), 9)))
                .step("按住右键", ctx -> aim(true)).waitUntil("举枪", ctx -> phase("aim")).ticks(4)
                .step("左键", ctx -> attack(true)).ticks(1).step("松开左键", ctx -> attack(false))
                // 空响只有 0.2 秒就接上缓冲的换弹，步骤之间不留等待，看到空响或已经在换弹都算。
                .settleMs(0).waitUntil("瞄准空响", ctx -> phase("aim_dry", "reload"))
                .settleMs(0).waitUntil("自动换弹", ctx -> phase("reload"))
                .step("松开右键", ctx -> aim(false))
                .waitUntil("装到位", ctx -> rounds(Minecraft.getInstance().player) == 6)
                .check("背包剩三发", ctx -> shells(Minecraft.getInstance().player) == 3)
                .waitUntil("回到待机", ctx -> phase("idle")).ticks(4);

        // 长按 R：弹药轮盘。
        s.step("按住 R", ctx -> reloadKey(true))
                .waitUntil("轮盘打开", ctx -> Minecraft.getInstance().screen instanceof AmmoWheelScreen).ticks(2)
                .screenshot("ammo_wheel")
                .step("关掉轮盘", ctx -> { reloadKey(false); Minecraft.getInstance().setScreen(null); }).ticks(4)
                .check("轮盘关上后不换弹", ctx -> phase("idle"))
                .teardown("恢复", ctx -> {
                    attack(false); reloadKey(false);
                    var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(false);
                    mc.setCameraEntity(mc.player); mc.options.hideGui = previousHideGui; camera(CameraType.FIRST_PERSON);
                    if (mc.screen instanceof AmmoWheelScreen) mc.setScreen(null);
                });
        s.server("收走木桩", ctx -> { var g = golem(ctx.player()); if (g != null) g.discard(); });
    }
}
