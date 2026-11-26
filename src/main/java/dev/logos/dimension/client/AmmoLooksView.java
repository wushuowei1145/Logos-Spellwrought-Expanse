package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.client.longsword.LongswordClient;
import dev.logos.dimension.firearm.AmmoBench;
import dev.logos.dimension.firearm.AmmoBenchBlockEntity;
import dev.logos.dimension.firearm.AmmoBlueprintItem;
import dev.logos.dimension.firearm.BlankRoundItem;
import dev.logos.dimension.firearm.BuckshotItem;
import dev.logos.dimension.firearm.Firearms;
import dev.logos.dimension.firearm.GunLoad;
import dev.logos.dimension.firearm.RifleRoundItem;
import dev.logos.dimension.firearm.RoundEffects;
import dev.logos.dimension.firearm.SpellRounds;
import dev.logos.dimension.item.GunaxeItem;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * 弹的外观和铸弹台三页（docs/FIREARMS.md 第 9–11 节）：背包里排开每种弹的每种外观（七档术式、七种改装），
 * 铸弹台翻到铭刻页点一档配方看空格子里的虚影、翻到改装页，最后侧面看一发烟火弹打在墙上炸开。
 */
@LDLRegisterClient(name="ammo_looks", group="ammo", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
public class AmmoLooksView implements UIScenario {
    private static final BlockPos BENCH = new BlockPos(3, 100, 3);
    private static final List<String> SPELLS = List.of("spell_1", "spell_2", "spell_3", "spell_4", "spell_5", "spell_6", "spell_7");
    /** 药水弹按金属换一种药水，看得出按药水染色。 */
    private static final List<net.minecraft.core.Holder<net.minecraft.world.item.alchemy.Potion>> POTIONS = List.of(
            net.minecraft.world.item.alchemy.Potions.HEALING, net.minecraft.world.item.alchemy.Potions.SWIFTNESS, net.minecraft.world.item.alchemy.Potions.POISON);
    private boolean previousHideGui;
    private int camera = -1;

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

    /** {@code item} 的这种外观：烟火弹配一颗红色的烟火之星，药水弹配第 {@code row} 种药水，看得出染色。 */
    private static ItemStack finished(Item item, String finish, int row) {
        var stack = new ItemStack(item, 8);
        stack.set(Firearms.FINISH.get(), finish);
        if (finish.equals(RoundEffects.FIREWORK)) stack.set(DataComponents.FIREWORK_EXPLOSION,
                new FireworkExplosion(FireworkExplosion.Shape.LARGE_BALL, IntList.of(0xE04030), IntList.of(0xFFD040), true, false));
        if (finish.equals(RoundEffects.POTION)) stack.set(DataComponents.POTION_CONTENTS,
                new net.minecraft.world.item.alchemy.PotionContents(POTIONS.get(row % POTIONS.size())));
        return stack;
    }

    /** 背包排一页：每种弹从左到右排开 {@code finishes} 这几种外观（一行九格，放不下的接到下一行）；空弹头只有术式外观。 */
    private static void lay(ServerPlayer p, List<Item> rounds, List<String> finishes) {
        var inv = p.getInventory();
        inv.clearContent();
        List<ItemStack> all = new ArrayList<>();
        for (int row = 0; row < rounds.size(); row++) {
            Item item = rounds.get(row);
            for (String finish : finishes) {
                if (item instanceof BlankRoundItem && !finish.startsWith("spell_")) continue;
                all.add(finished(item, finish, row));
            }
        }
        for (int i = 0; i < all.size() && i < 36; i++) inv.setItem(i < 27 ? i + 9 : i - 27, all.get(i));
    }

    private static List<Item> buckshot() {
        return List.of(BuckshotItem.COPPER.get(), BuckshotItem.ITEM.get(), BuckshotItem.NETHERITE.get());
    }

    private static List<Item> rifle() {
        return List.of(RifleRoundItem.COPPER.get(), RifleRoundItem.IRON.get(), RifleRoundItem.NETHERITE.get());
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
                }).server("场地和铸弹台", ctx -> {
                    var level = ctx.server().overworld();
                    for (int x = -8; x <= 8; x++) for (int z = -8; z <= 26; z++) {
                        level.setBlockAndUpdate(new BlockPos(x, 99, z), Blocks.STONE_BRICKS.defaultBlockState());
                        for (int y = 100; y < 110; y++) level.removeBlock(new BlockPos(x, y, z), false);
                    }
                    for (int x = -8; x <= 8; x++) for (int y = 100; y < 104; y++) level.setBlockAndUpdate(new BlockPos(x, y, 12), Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(BENCH, AmmoBench.BENCH.get().defaultBlockState());
                    DimensionTravel.teleport(ctx.player(), level, new Vec3(.5, 100, .5), 0, 0);
                    var shells = new ArrayList<>(buckshot());
                    shells.add(BlankRoundItem.ITEM.get());
                    lay(ctx.player(), shells, SPELLS);
                }).setGameMode(GameType.SURVIVAL).ticks(10)
                // 背包四页（生存模式的背包，不是创造物品栏）：霰弹和空弹头的术式外观、步枪弹的术式外观，再是两种弹的改装外观。
                .step("打开背包", ctx -> Minecraft.getInstance().setScreen(new InventoryScreen(Minecraft.getInstance().player)))
                .ticks(6).screenshot("looks_buckshot")
                .step("关掉背包", ctx -> Minecraft.getInstance().setScreen(null))
                .server("换成步枪弹", ctx -> lay(ctx.player(), rifle(), SPELLS))
                .ticks(4)
                .step("打开背包", ctx -> Minecraft.getInstance().setScreen(new InventoryScreen(Minecraft.getInstance().player)))
                .ticks(6).screenshot("looks_rifle")
                .step("关掉背包", ctx -> Minecraft.getInstance().setScreen(null))
                .server("换成改装霰弹", ctx -> lay(ctx.player(), buckshot(), RoundEffects.MODS))
                .ticks(4)
                .step("打开背包", ctx -> Minecraft.getInstance().setScreen(new InventoryScreen(Minecraft.getInstance().player)))
                .ticks(6).screenshot("looks_buckshot_mods")
                .step("关掉背包", ctx -> Minecraft.getInstance().setScreen(null))
                .server("换成改装步枪弹", ctx -> lay(ctx.player(), rifle(), RoundEffects.MODS))
                .ticks(4)
                .step("打开背包", ctx -> Minecraft.getInstance().setScreen(new InventoryScreen(Minecraft.getInstance().player)))
                .ticks(6).screenshot("looks_rifle_mods")
                .step("关掉背包", ctx -> Minecraft.getInstance().setScreen(null)).ticks(2);

        // 铸弹台：铭刻页放了图纸和弹，点第三档（回响）的配方，催化、导体格里出现回响酊和活金锭的虚影；再翻到改装页（放着末影珍珠）。
        s.server("放图纸和弹、打开界面", ctx -> {
                    var bench = bench(ctx.player());
                    bench.etching().set(AmmoBenchBlockEntity.BLUEPRINT,
                            ItemResource.of(AmmoBlueprintItem.of(AmmoBlueprintItem.template(SpellRounds.RIFLE, "步枪弹术式"))), 1);
                    bench.etching().set(AmmoBenchBlockEntity.ROUNDS, ItemResource.of(new ItemStack(RifleRoundItem.IRON.get())), 16);
                    bench.modding().set(AmmoBenchBlockEntity.MOD_ROUNDS, ItemResource.of(new ItemStack(BuckshotItem.ITEM.get())), 16);
                    // 改装页放末影珍珠：一颗只改一发，配方册里末影那一行亮
                    bench.modding().set(AmmoBenchBlockEntity.ADDITIVE, ItemResource.of(new ItemStack(net.minecraft.world.item.Items.ENDER_PEARL)), 2);
                    BlockUIMenuType.openUI(ctx.player(), BENCH);
                }).waitUntil("界面打开", ctx -> Minecraft.getInstance().screen != null).ticks(20)
                .click("#bench_book_press_1").ticks(3).screenshot("page_press")
                .click("#bench_tab_1").ticks(3)
                .click("#bench_book_etch_2").ticks(3).screenshot("page_etch")
                .click("#bench_tab_2").ticks(10).screenshot("page_modify")
                .step("关掉界面", ctx -> Minecraft.getInstance().setScreen(null)).ticks(4);

        // 烟火弹打在墙上：侧面看炸开。
        s.server("装上烟火弹、机位", ctx -> {
                    var level = ctx.server().overworld();
                    var gun = new ItemStack(GunaxeItem.ITEM.get());
                    var round = finished(RifleRoundItem.IRON.get(), RoundEffects.FIREWORK, 0).copyWithCount(1);
                    gun.set(Firearms.LOAD.get(), new GunLoad(round.copy(), round.copy(), 6));
                    ctx.player().getInventory().clearContent();
                    ctx.player().getInventory().setItem(0, gun);
                    DimensionTravel.teleport(ctx.player(), level, new Vec3(.5, 100, .5), 0, 0);
                    ArmorStand stand = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
                    stand.setInvisible(true); stand.setNoGravity(true);
                    Vec3 at = new Vec3(-9.5, 102.5, 7.5), look = new Vec3(.5, 101.5, 9.5);
                    double dx = look.x - at.x, dy = look.y - at.y, dz = look.z - at.z;
                    stand.snapTo(at.x, at.y - stand.getEyeHeight(), at.z, (float) -Math.toDegrees(Math.atan2(dx, dz)),
                            (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
                    level.addFreshEntity(stand);
                    camera = stand.getId();
                }).setGameMode(GameType.SURVIVAL)
                .waitUntil("待机", ctx -> phase("idle")).ticks(4)
                .waitUntil("机位同步", ctx -> Minecraft.getInstance().level.getEntity(camera) != null)
                .step("切到侧面", ctx -> {
                    var mc = Minecraft.getInstance(); Entity stand = mc.level.getEntity(camera);
                    stand.setYHeadRot(stand.getYRot()); stand.yRotO = stand.getYRot(); stand.xRotO = stand.getXRot();
                    mc.setCameraEntity(stand); mc.options.hideGui = true;
                })
                .step("按住右键", ctx -> aim(true)).waitUntil("举枪", ctx -> phase("aim")).ticks(8)
                .step("左键开枪", ctx -> attack(true)).ticks(1).step("松开左键", ctx -> attack(false))
                .settleMs(0).waitUntil("开枪", ctx -> phase("aim_shot")).ticks(4).screenshot("firework_burst")
                .step("松开右键", ctx -> aim(false))
                .teardown("恢复", ctx -> {
                    attack(false);
                    var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(false);
                    mc.setCameraEntity(mc.player); mc.options.hideGui = previousHideGui; mc.options.setCameraType(CameraType.FIRST_PERSON);
                    if (mc.screen != null) mc.setScreen(null);
                });
        s.server("收走机位", ctx -> {
            var e = camera < 0 ? null : ctx.server().overworld().getEntity(camera); if (e != null) e.discard();
        });
    }
}
