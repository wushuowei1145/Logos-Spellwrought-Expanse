package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.client.SpellEditor;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.client.longsword.LongswordClient;
import dev.logos.dimension.firearm.AmmoBench;
import dev.logos.dimension.firearm.AmmoBenchBlockEntity;
import dev.logos.dimension.firearm.AmmoBlueprintItem;
import dev.logos.dimension.firearm.Firearms;
import dev.logos.dimension.firearm.GunLoad;
import dev.logos.dimension.firearm.RifleRoundItem;
import dev.logos.dimension.firearm.SpellRounds;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * 术式弹（docs/FIREARMS.md 第 9 节）的实际观感：新弹药图纸在术式工坊里是「启式 → 步枪弹」三个事件空着，写好的接上了事件；
 * 铸弹台铭刻一批（铭刻中、刻好各一张）；拿刻好的弹装进铳斧瞄准打木桩，侧面看物理伤害加上术式的火焰。
 */
@LDLRegisterClient(name="spell_rounds", group="ammo", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
public class SpellRoundView implements UIScenario {
    private static final BlockPos BENCH = new BlockPos(3, 100, 3);
    private static final String WRITTEN = "(spell :name \"爆裂弹\" :mana 30 (rifle-round (on-hit (damage target 5 fire))"
            + " (on-impact (damage-all (enemies :radius 3) 3 fire)) (on-expire (mark caster #far))))";
    private boolean previousHideGui;
    private int camera = -1;
    private static float golemHealth;

    private static boolean phase(String... moves) {
        var p = LongswordClient.pose(Minecraft.getInstance().player.getUUID());
        return p != null && Arrays.asList(moves).contains(p.move());
    }
    private static void attack(boolean down) { Minecraft.getInstance().options.keyAttack.setDown(down); }
    private static void aim(boolean use) {
        var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(use);
        if (use) mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND); else mc.gameMode.releaseUsingItem(mc.player);
    }
    private static AmmoBenchBlockEntity bench(ServerPlayer player) { return (AmmoBenchBlockEntity) player.level().getBlockEntity(BENCH); }
    private static IronGolem golem(ServerPlayer player) {
        return player.level().getEntitiesOfClass(IronGolem.class, player.getBoundingBox().inflate(30)).stream().findFirst().orElse(null);
    }

    @Override public void configure(ScenarioOptions o) { o.tags("ammo", "firearms").defaultTimeoutMs(60000).scenarioTimeoutMs(240000); }

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
                    level.setBlockAndUpdate(BENCH, AmmoBench.BENCH.get().defaultBlockState());
                    DimensionTravel.teleport(ctx.player(), level, new Vec3(.5, 100, .5), 0, 0);
                    ctx.player().getInventory().clearContent();
                }).ticks(10);

        // 术式工坊：新图纸（启式 → 步枪弹，三个事件空着），再看写好的。
        s.step("打开新图纸", ctx -> SpellEditor.open(new BlockPos(0, 100, 5), AmmoBlueprintItem.of(AmmoBlueprintItem.template(SpellRounds.RIFLE, "步枪弹术式"))))
                .waitUntil("工坊打开", ctx -> Minecraft.getInstance().screen != null).ticks(15).screenshot("blueprint_new")
                .step("换成写好的图纸", ctx -> { Minecraft.getInstance().setScreen(null); SpellEditor.open(new BlockPos(0, 100, 5), AmmoBlueprintItem.of(WRITTEN)); })
                .waitUntil("工坊打开", ctx -> Minecraft.getInstance().screen != null).ticks(15).screenshot("blueprint_written")
                .step("关掉工坊", ctx -> Minecraft.getInstance().setScreen(null)).ticks(4);

        // 铸弹台铭刻：写好的图纸、十发铁步枪弹、第二档材料（紫水晶 + 金锭）、300 魔力。
        s.server("放图纸和材料、打开界面", ctx -> {
                    var bench = bench(ctx.player());
                    bench.receiveMana(300);
                    bench.etching().set(AmmoBenchBlockEntity.BLUEPRINT, ItemResource.of(AmmoBlueprintItem.of(WRITTEN)), 1);
                    bench.etching().set(AmmoBenchBlockEntity.ROUNDS, ItemResource.of(new ItemStack(RifleRoundItem.IRON.get())), 10);
                    bench.etching().set(AmmoBenchBlockEntity.CATALYST, ItemResource.of(new ItemStack(Items.AMETHYST_SHARD)), 1);
                    bench.etching().set(AmmoBenchBlockEntity.CONDUCTOR, ItemResource.of(new ItemStack(Items.GOLD_INGOT)), 1);
                    bench.fuel().set(0, ItemResource.of(new ItemStack(Items.LAPIS_LAZULI)), 3);
                    BlockUIMenuType.openUI(ctx.player(), BENCH);
                }).waitUntil("界面打开", ctx -> Minecraft.getInstance().screen != null).ticks(30)
                .screenshot("bench_etching")
                .waitUntilServer("刻好一批", ctx -> bench(ctx.player()).etched().getAmountAsInt(0) >= 8).ticks(3)
                .screenshot("bench_etched")
                .step("关掉界面", ctx -> Minecraft.getInstance().setScreen(null)).ticks(4);

        // 刻好的弹装进铳斧：瞄准十二格外的木桩，侧面看命中时的火焰。
        s.server("装弹、木桩、机位", ctx -> {
                    var level = ctx.server().overworld();
                    var golem = EntityType.IRON_GOLEM.create(level, EntitySpawnReason.COMMAND);
                    golem.setNoAi(true); golem.setPos(.5, 100, 12.5); level.addFreshEntity(golem);
                    var round = bench(ctx.player()).etched().getResource(0).toStack(1);
                    var gun = new ItemStack(GunaxeItem.ITEM.get());
                    gun.set(Firearms.LOAD.get(), new GunLoad(round.copy(), round.copy(), 6));
                    var inv = ctx.player().getInventory();
                    inv.setItem(0, gun);
                    inv.setItem(1, bench(ctx.player()).etched().getResource(0).toStack(bench(ctx.player()).etched().getAmountAsInt(0)));
                    DimensionTravel.teleport(ctx.player(), level, new Vec3(.5, 100, .5), 0, 0);
                    golemHealth = golem.getHealth();
                    ArmorStand stand = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
                    stand.setInvisible(true); stand.setNoGravity(true);
                    Vec3 at = new Vec3(-7.5, 102.2, 9.5), look = new Vec3(.5, 101, 9.5);
                    double dx = look.x - at.x, dy = look.y - at.y, dz = look.z - at.z;
                    stand.snapTo(at.x, at.y - stand.getEyeHeight(), at.z, (float) -Math.toDegrees(Math.atan2(dx, dz)),
                            (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
                    level.addFreshEntity(stand);
                    camera = stand.getId();
                }).setGameMode(GameType.SURVIVAL)
                .waitUntil("待机", ctx -> phase("idle")).ticks(4).screenshot("hotbar_etched")
                .waitUntil("机位同步", ctx -> Minecraft.getInstance().level.getEntity(camera) != null)
                .step("切到侧面", ctx -> {
                    var mc = Minecraft.getInstance(); Entity stand = mc.level.getEntity(camera);
                    stand.setYHeadRot(stand.getYRot()); stand.yRotO = stand.getYRot(); stand.xRotO = stand.getXRot();
                    mc.setCameraEntity(stand); mc.options.hideGui = true;
                })
                .step("按住右键", ctx -> aim(true)).waitUntil("举枪", ctx -> phase("aim")).ticks(8)
                .step("左键开枪", ctx -> attack(true)).ticks(1).step("松开左键", ctx -> attack(false))
                .settleMs(0).waitUntil("开枪", ctx -> phase("aim_shot")).ticks(2).screenshot("etched_shot")
                .waitUntilServer("木桩挨了物理和术式", ctx -> golem(ctx.player()).getHealth() < golemHealth - 14)
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
