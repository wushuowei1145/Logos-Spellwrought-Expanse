package dev.logos.dimension.gametest;

import com.mojang.authlib.GameProfile;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.firearm.AmmoBench;
import dev.logos.dimension.firearm.AmmoBenchBlockEntity;
import dev.logos.dimension.firearm.AmmoPressRecipe;
import dev.logos.dimension.firearm.BlankRoundItem;
import dev.logos.dimension.firearm.BuckshotItem;
import dev.logos.dimension.firearm.Firearms;
import dev.logos.dimension.firearm.GunLoad;
import dev.logos.dimension.firearm.RifleRoundItem;
import dev.logos.dimension.item.GunaxeItem;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * 铸弹台压弹（配方、一批八发、出货格、漏斗只进料只出弹），以及三种普通弹打出去的分工：
 * 步枪弹打得远、只打第一个，下界合金弹头多穿一个，空弹头不伤人（docs/FIREARMS.md 第 5、6 节）。
 */
public final class FirearmGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, LogosDimension.ID);

    public static void register(IEventBus bus) {
        var tests = Map.<String, Consumer<GameTestHelper>>of(
                "ammo_bench_presses_batches", FirearmGameTests::press,
                "ammo_bench_hoppers_feed_and_take", FirearmGameTests::automation,
                "rifle_round_reaches_past_buckshot", FirearmGameTests::reach,
                "netherite_rifle_round_pierces_one_more", FirearmGameTests::pierce,
                "blank_round_does_no_harm", FirearmGameTests::blank);
        tests.forEach((name, test) -> FUNCTIONS.register(name, () -> test));
        FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event) -> {
            var env = event.registerEnvironment(LogosDimension.id("firearms"), new TestEnvironmentDefinition.AllOf());
            // 射击的几个要四十格的靶道，留足间距。
            for (String name : tests.keySet()) event.registerTest(LogosDimension.id(name), new FunctionGameTestInstance(
                    ResourceKey.create(Registries.TEST_FUNCTION, LogosDimension.id(name)),
                    new TestData<>(env, Identifier.withDefaultNamespace("empty"), 300, 0, true, Rotation.NONE, false, 1, 1, false, 48)));
        });
    }

    private static AmmoBenchBlockEntity bench(GameTestHelper h) {
        var pos = h.absolutePos(new BlockPos(1, 1, 1));
        h.getLevel().setBlockAndUpdate(pos, AmmoBench.BENCH.get().defaultBlockState());
        return (AmmoBenchBlockEntity) h.getLevel().getBlockEntity(pos);
    }

    private static void put(AmmoBenchBlockEntity bench, int slot, Item item, int count) {
        bench.inputs().set(slot, ItemResource.of(new ItemStack(item)), count);
    }

    /** 纸壳、火药、铁锭各两份：每两秒压出一批八发铁霰弹，压完两批停下。 */
    private static void press(GameTestHelper h) {
        var bench = bench(h);
        var level = h.getLevel();
        h.assertTrue(AmmoPressRecipe.usable(level, AmmoBenchBlockEntity.HEAD, new ItemStack(Items.IRON_INGOT)), "铁锭能当弹头");
        h.assertTrue(!AmmoPressRecipe.usable(level, AmmoBenchBlockEntity.HEAD, new ItemStack(Items.DIRT)), "泥土不能当弹头");
        h.assertTrue(!AmmoPressRecipe.usable(level, AmmoBenchBlockEntity.CASING, new ItemStack(Items.GUNPOWDER)), "火药不能当弹壳");
        put(bench, AmmoBenchBlockEntity.CASING, Items.PAPER, 2);
        put(bench, AmmoBenchBlockEntity.POWDER, Items.GUNPOWDER, 2);
        put(bench, AmmoBenchBlockEntity.HEAD, Items.IRON_INGOT, 2);
        h.assertTrue(bench.pressing().is(BuckshotItem.ITEM.get()) && bench.pressing().getCount() == 8, "纸壳加铁弹头压铁霰弹，一批八发");
        h.runAfterDelay(30, () -> h.assertTrue(bench.output().getAmountAsInt(0) == 0 && bench.progress() > .5f, "一批要两秒，还在压"));
        h.runAfterDelay(45, () -> h.assertTrue(bench.output().getAmountAsInt(0) == 8 && bench.inputs().getAmountAsInt(0) == 1, "第一批八发，材料各扣一份"));
        h.succeedWhen(() -> {
            h.assertTrue(bench.output().getAmountAsInt(0) == 16, "压完两批");
            h.assertTrue(bench.output().getResource(0).getItem() == BuckshotItem.ITEM.get(), "出的是铁霰弹");
            for (int i = 0; i < 3; i++) h.assertTrue(bench.inputs().getAmountAsInt(i) == 0, "材料用光");
            h.assertTrue(bench.progress() == 0 && bench.pressing().isEmpty(), "没料了就停");
        });
    }

    /** 漏斗只能往三格送料（各进各的格），只能从出货格取弹；出货格里是别的弹时不压。 */
    private static void automation(GameTestHelper h) {
        var bench = bench(h);
        var pipe = bench.automation();
        try (var tx = Transaction.openRoot()) {
            h.assertTrue(pipe.insert(ItemResource.of(new ItemStack(Items.GUNPOWDER)), 4, tx) == 4, "火药送得进");
            h.assertTrue(pipe.insert(ItemResource.of(new ItemStack(Items.PAPER)), 4, tx) == 4, "纸送得进");
            h.assertTrue(pipe.insert(ItemResource.of(new ItemStack(Items.NETHERITE_SCRAP)), 4, tx) == 4, "下界合金碎片送得进");
            h.assertTrue(pipe.insert(ItemResource.of(new ItemStack(Items.DIRT)), 4, tx) == 0, "泥土送不进");
            h.assertTrue(pipe.extract(ItemResource.of(new ItemStack(Items.PAPER)), 4, tx) == 0, "材料取不出来");
            tx.commit();
        }
        h.assertTrue(bench.inputs().getResource(AmmoBenchBlockEntity.CASING).getItem() == Items.PAPER, "纸进了弹壳格");
        h.assertTrue(bench.inputs().getResource(AmmoBenchBlockEntity.POWDER).getItem() == Items.GUNPOWDER, "火药进了火药格");
        h.assertTrue(bench.inputs().getResource(AmmoBenchBlockEntity.HEAD).getItem() == Items.NETHERITE_SCRAP, "碎片进了弹头格");
        h.runAfterDelay(45, () -> {
            h.assertTrue(bench.output().getResource(0).getItem() == BuckshotItem.NETHERITE.get(), "压出下界合金霰弹");
            try (var tx = Transaction.openRoot()) {
                int got = pipe.extract(ItemResource.of(new ItemStack(BuckshotItem.NETHERITE.get())), 64, tx);
                h.assertTrue(got == 8, "漏斗取走八发，取到 " + got);
                tx.commit();
            }
            // 出货格里放着别的弹：放不下这一批，不压。
            bench.output().set(0, ItemResource.of(new ItemStack(RifleRoundItem.IRON.get())), 1);
        });
        h.runAfterDelay(120, () -> {
            h.assertTrue(bench.output().getAmountAsInt(0) == 1 && bench.inputs().getAmountAsInt(0) == 3, "出货格被别的弹占着就不压");
            h.succeed();
        });
    }

    private static ServerPlayer shooter(GameTestHelper h, BlockPos at, ItemStack round) {
        var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "firearm-test"), false);
        var p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, p, cookie);
        p.connection.markClientLoaded();
        p.setGameMode(GameType.SURVIVAL);
        p.getInventory().clearContent();
        // 朝 +X 看
        p.snapTo(at.getX() + .5, at.getY(), at.getZ() + .5, -90, 0);
        p.setYHeadRot(-90);
        var gun = new ItemStack(GunaxeItem.ITEM.get());
        gun.set(Firearms.LOAD.get(), new GunLoad(round.copyWithCount(1), round.copyWithCount(1), 6));
        p.setItemInHand(InteractionHand.MAIN_HAND, gun);
        return p;
    }

    /** 一条石头地面的靶道，往 +X 方向 {@code length} 格。 */
    private static BlockPos range(GameTestHelper h, int length) {
        var level = h.getLevel();
        var o = h.absolutePos(new BlockPos(2, 1, 4));
        for (int x = -2; x <= length; x++) for (int z = -2; z <= 2; z++) {
            level.setBlockAndUpdate(o.offset(x, -1, z), Blocks.STONE.defaultBlockState());
            for (int y = 0; y <= 4; y++) level.setBlockAndUpdate(o.offset(x, y, z), Blocks.AIR.defaultBlockState());
        }
        return o;
    }

    private static IronGolem golem(GameTestHelper h, BlockPos o, double x) {
        var golem = EntityType.IRON_GOLEM.create(h.getLevel(), EntitySpawnReason.COMMAND);
        golem.setNoAi(true);
        golem.snapTo(o.getX() + x + .5, o.getY(), o.getZ() + .5, 90, 0);
        h.getLevel().addFreshEntity(golem);
        return golem;
    }

    /** 三十格外的木桩：瞄准打霰弹够不着，打铁步枪弹掉 12 × 铳斧威力 0.85 点。 */
    private static void reach(GameTestHelper h) {
        var o = range(h, 34);
        var golem = golem(h, o, 30);
        float full = golem.getHealth();
        var buck = shooter(h, o, new ItemStack(BuckshotItem.ITEM.get()));
        h.assertTrue(Firearms.fire(buck, buck.getMainHandItem(), true, 1, 1), "霰弹开得了枪");
        h.assertTrue(golem.getHealth() == full, "霰弹够不着三十格");
        buck.discard();
        var rifle = shooter(h, o, new ItemStack(RifleRoundItem.IRON.get()));
        h.assertTrue(Firearms.fire(rifle, rifle.getMainHandItem(), true, 1, 1), "步枪弹开得了枪");
        float lost = full - golem.getHealth();
        h.assertTrue(Math.abs(lost - 12 * .85) < .5, "铁步枪弹在三十格满伤，掉了 " + lost);
        h.assertTrue(Firearms.load(rifle.getMainHandItem()).rounds() == 5, "少一发");
        rifle.discard();
        h.succeed();
    }

    /** 两个木桩前后站成一线：铁步枪弹只打前面那个，下界合金弹头连后面的一起打穿。 */
    private static void pierce(GameTestHelper h) {
        var o = range(h, 14);
        var front = golem(h, o, 6);
        var back = golem(h, o, 10);
        float full = front.getHealth();
        var iron = shooter(h, o, new ItemStack(RifleRoundItem.IRON.get()));
        Firearms.fire(iron, iron.getMainHandItem(), true, 1, 1);
        h.assertTrue(front.getHealth() < full && back.getHealth() == full, "铁弹头停在第一个");
        iron.discard();
        float frontLeft = front.getHealth();
        var netherite = shooter(h, o, new ItemStack(RifleRoundItem.NETHERITE.get()));
        Firearms.fire(netherite, netherite.getMainHandItem(), true, 1, 1);
        float frontLost = frontLeft - front.getHealth(), backLost = full - back.getHealth();
        h.assertTrue(Math.abs(frontLost - 12 * 1.4 * .85) < .5, "下界合金弹头 ×1.4，前面掉了 " + frontLost);
        h.assertTrue(Math.abs(backLost - 12 * 1.4 * .85) < .5, "穿过去打中后面那个，掉了 " + backLost);
        netherite.discard();
        h.succeed();
    }

    /** 空弹头贴脸开枪：照样扣一发，但木桩一点血不掉。 */
    private static void blank(GameTestHelper h) {
        var o = range(h, 6);
        var golem = golem(h, o, 3);
        float full = golem.getHealth();
        var p = shooter(h, o, new ItemStack(BlankRoundItem.ITEM.get()));
        h.assertTrue(Firearms.fire(p, p.getMainHandItem(), true, 2, 1), "空弹头也能开枪");
        h.assertTrue(golem.getHealth() == full, "空弹头不伤人");
        h.assertTrue(Firearms.load(p.getMainHandItem()).rounds() == 5, "照样少一发");
        p.discard();
        h.succeed();
    }
}
