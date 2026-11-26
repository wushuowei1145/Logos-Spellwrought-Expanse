package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.client.longsword.LongswordClient;
import dev.logos.dimension.client.longsword.LongswordRig;
import dev.logos.dimension.combat.Moveset;
import dev.logos.dimension.firearm.AmmoBench;
import dev.logos.dimension.firearm.AmmoBenchBlockEntity;
import dev.logos.dimension.firearm.BlankRoundItem;
import dev.logos.dimension.firearm.BuckshotItem;
import dev.logos.dimension.firearm.Firearms;
import dev.logos.dimension.firearm.GunLoad;
import dev.logos.dimension.firearm.RifleRoundItem;
import dev.logos.dimension.item.GunaxeItem;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * 铸弹台与三种普通弹（docs/FIREARMS.md 第 5、6 节）的实际观感：铸弹台三个机位，快捷栏里七种弹的图标，
 * 打开界面压一批铁霰弹（压制中、压完各一张），然后拿装着铁步枪弹的铳斧瞄准打二十格外的木桩（第一人称、侧面各一张）。
 */
@LDLRegisterClient(name="ammo_bench", group="ammo", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
public class AmmoBenchView implements UIScenario {
    private static final BlockPos BENCH = new BlockPos(3, 100, 3);
    private static final Vec3 BENCH_CENTRE = new Vec3(3.5, 100.75, 3.5);
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
    private static void attack(boolean down) { Minecraft.getInstance().options.keyAttack.setDown(down); }
    private static void aim(boolean use) {
        var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(use);
        if (use) mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND); else mc.gameMode.releaseUsingItem(mc.player);
    }
    private static IronGolem golem(ServerPlayer player) {
        return player.level().getEntitiesOfClass(IronGolem.class, player.getBoundingBox().inflate(30)).stream().findFirst().orElse(null);
    }
    private static AmmoBenchBlockEntity bench(ServerPlayer player) {
        return (AmmoBenchBlockEntity) player.level().getBlockEntity(BENCH);
    }

    @Override public void configure(ScenarioOptions o) { o.tags("ammo", "firearms").defaultTimeoutMs(60000).scenarioTimeoutMs(240000); }

    /** 从 {@code at} 看向 {@code look} 的一个隐形机位，切过去截一张。 */
    private ScenarioBuilder shoot(ScenarioBuilder s, String name, Vec3 at, Vec3 look) {
        return s.server("机位 " + name, ctx -> {
            ServerLevel level = ctx.server().overworld();
            var old = camera < 0 ? null : level.getEntity(camera);
            if (old != null) old.discard();
            ArmorStand stand = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
            stand.setInvisible(true); stand.setNoGravity(true);
            double dx = look.x - at.x, dy = look.y - at.y, dz = look.z - at.z;
            stand.snapTo(at.x, at.y - stand.getEyeHeight(), at.z, (float) -Math.toDegrees(Math.atan2(dx, dz)),
                    (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
            level.addFreshEntity(stand);
            camera = stand.getId();
        }).waitUntil("机位同步 " + name, ctx -> Minecraft.getInstance().level.getEntity(camera) != null)
                .step("切到机位 " + name, ctx -> {
                    var mc = Minecraft.getInstance(); Entity stand = mc.level.getEntity(camera);
                    stand.setYHeadRot(stand.getYRot()); stand.yRotO = stand.getYRot(); stand.xRotO = stand.getXRot();
                    mc.setCameraEntity(stand); mc.options.hideGui = true;
                }).ticks(3).screenshot(name);
    }

    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("weather clear").runCommand("difficulty peaceful")
                .runCommand("gamerule doMobSpawning false")
                .step("设置视口", ctx -> {
                    var mc = Minecraft.getInstance(); previousHideGui = mc.options.hideGui;
                    mc.options.hideGui = false; mc.player.getInventory().setSelectedSlot(0);
                    mc.options.setCameraType(CameraType.FIRST_PERSON); mc.options.fov().set(70);
                    var w = mc.getWindow(); w.setWindowed(1280, 720); w.setWidth(1280); w.setHeight(720);
                    mc.getMainRenderTarget().resize(1280, 720); mc.gameRenderer.resize(1280, 720); mc.resizeGui();
                }).server("靶场与铸弹台", ctx -> {
                    var level = ctx.server().overworld();
                    for (int x = -8; x <= 8; x++) for (int z = -8; z <= 26; z++) {
                        level.setBlockAndUpdate(new BlockPos(x, 99, z), Blocks.STONE_BRICKS.defaultBlockState());
                        for (int y = 100; y < 110; y++) level.removeBlock(new BlockPos(x, y, z), false);
                    }
                    for (int x = -8; x <= 8; x++) for (int y = 100; y < 104; y++) level.setBlockAndUpdate(new BlockPos(x, y, 26), Blocks.OAK_PLANKS.defaultBlockState());
                    level.setBlockAndUpdate(BENCH, AmmoBench.BENCH.get().defaultBlockState());
                    DimensionTravel.teleport(ctx.player(), level, new Vec3(.5, 100, .5), -35, 15);
                    var inv = ctx.player().getInventory();
                    inv.clearContent(); inv.setSelectedSlot(0);
                    inv.setItem(0, new ItemStack(AmmoBench.BENCH.get()));
                    var rounds = List.of(BuckshotItem.COPPER.get(), BuckshotItem.ITEM.get(), BuckshotItem.NETHERITE.get(),
                            RifleRoundItem.COPPER.get(), RifleRoundItem.IRON.get(), RifleRoundItem.NETHERITE.get(), BlankRoundItem.ITEM.get());
                    for (int i = 0; i < rounds.size(); i++) inv.setItem(i + 1, new ItemStack(rounds.get(i), 16));
                }).ticks(20).screenshot("hotbar_rounds");

        // 铸弹台三个机位：正面偏左、右后、俯看台面。
        shoot(s, "bench_front", new Vec3(2.1, 101.7, 1.7), BENCH_CENTRE);
        shoot(s, "bench_back", new Vec3(5.2, 101.8, 5.3), BENCH_CENTRE);
        shoot(s, "bench_top", new Vec3(3.5, 102.2, 2.3), BENCH_CENTRE.add(0, .1, .2));
        s.step("回到玩家", ctx -> { var mc = Minecraft.getInstance(); mc.setCameraEntity(mc.player); mc.options.hideGui = false; })
                .server("移除机位", ctx -> { var e = ctx.server().overworld().getEntity(camera); if (e != null) e.discard(); camera = -1; });

        // 界面：放进纸壳、火药、铁锭各五份，压一批八发铁霰弹。
        s.server("放材料、打开界面", ctx -> {
                    var bench = bench(ctx.player());
                    bench.inputs().set(AmmoBenchBlockEntity.CASING, ItemResource.of(new ItemStack(Items.PAPER)), 5);
                    bench.inputs().set(AmmoBenchBlockEntity.POWDER, ItemResource.of(new ItemStack(Items.GUNPOWDER)), 5);
                    bench.inputs().set(AmmoBenchBlockEntity.HEAD, ItemResource.of(new ItemStack(Items.IRON_INGOT)), 5);
                    BlockUIMenuType.openUI(ctx.player(), BENCH);
                }).waitUntil("界面打开", ctx -> Minecraft.getInstance().screen != null).ticks(20)
                .screenshot("bench_ui_pressing")
                .waitUntilServer("压出一批", ctx -> bench(ctx.player()).output().getAmountAsInt(0) >= 8).ticks(3)
                .screenshot("bench_ui_done")
                .waitUntilServer("压出来的是铁霰弹", ctx -> bench(ctx.player()).output().getResource(0).getItem() == BuckshotItem.ITEM.get())
                .step("关掉界面", ctx -> Minecraft.getInstance().setScreen(null)).ticks(4);

        // 铁步枪弹：二十格外的木桩，第一人称瞄准一枪，侧面再一枪。
        s.server("装上铁步枪弹的铳斧、木桩", ctx -> {
                    var level = ctx.server().overworld();
                    var golem = EntityType.IRON_GOLEM.create(level, EntitySpawnReason.COMMAND);
                    golem.setNoAi(true); golem.setPos(.5, 100, 20.5); level.addFreshEntity(golem);
                    var gun = new ItemStack(GunaxeItem.ITEM.get());
                    var round = new ItemStack(RifleRoundItem.IRON.get());
                    gun.set(Firearms.LOAD.get(), new GunLoad(round.copy(), round.copy(), 6));
                    ctx.player().getInventory().setItem(0, gun);
                    DimensionTravel.teleport(ctx.player(), level, new Vec3(.5, 100, .5), 0, 0);
                }).setGameMode(GameType.SURVIVAL)
                .waitUntil("待机", ctx -> phase("idle")).ticks(4)
                .server("记录木桩血量", ctx -> golemHealth = golem(ctx.player()).getHealth())
                .step("按住右键", ctx -> aim(true)).waitUntil("举枪", ctx -> phase("aim")).ticks(8)
                .step("左键开枪", ctx -> attack(true)).ticks(1).step("松开左键", ctx -> attack(false))
                .settleMs(0).waitUntil("开枪", ctx -> phase("aim_shot") && seconds() >= .05).screenshot("fp_rifle_shot")
                .waitUntilServer("木桩挨了一枪", ctx -> golem(ctx.player()).getHealth() < golemHealth - 8)
                .waitUntilServer("一颗子弹，不是一蓬", ctx -> golem(ctx.player()).getHealth() > golemHealth - 12)
                .waitUntil("回到瞄准", ctx -> phase("aim"));
        shoot(s, "side_rifle_aim", new Vec3(-12, 102.5, 10.5), new Vec3(.5, 101, 10.5));
        s.step("左键开枪", ctx -> attack(true)).ticks(1).step("松开左键", ctx -> attack(false))
                .settleMs(0).waitUntil("开枪", ctx -> phase("aim_shot") && seconds() >= .05).screenshot("side_rifle_shot")
                .step("松开右键", ctx -> aim(false))
                .teardown("恢复", ctx -> {
                    attack(false);
                    var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(false);
                    mc.setCameraEntity(mc.player); mc.options.hideGui = previousHideGui; mc.options.setCameraType(CameraType.FIRST_PERSON);
                    if (mc.screen != null) mc.setScreen(null);
                });
        s.server("收走木桩和机位", ctx -> {
            var g = golem(ctx.player()); if (g != null) g.discard();
            var e = camera < 0 ? null : ctx.server().overworld().getEntity(camera); if (e != null) e.discard();
        });
    }
}
