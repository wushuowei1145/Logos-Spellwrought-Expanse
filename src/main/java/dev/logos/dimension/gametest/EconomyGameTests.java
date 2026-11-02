package dev.logos.dimension.gametest;

import com.mojang.authlib.GameProfile;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.economy.*;
import dev.logos.dimension.economy.Currency;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 钱囊存取、散币找零、结账自动换汇和兑换台成交。市场本身的数学在单元测试 FxMarketTest 里。 */
public final class EconomyGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, LogosDimension.ID);
    public static void register(IEventBus bus) {
        var tests = Map.<String, Consumer<GameTestHelper>>of(
                "economy_wallet_deposit_withdraw", EconomyGameTests::wallet,
                "economy_loose_coins_get_change", EconomyGameTests::change,
                "economy_checkout_auto_exchange", EconomyGameTests::checkout,
                "economy_counter_trades_by_day", EconomyGameTests::counter);
        tests.forEach((name, test) -> FUNCTIONS.register(name, () -> test)); FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event) -> {
            var env = event.registerEnvironment(LogosDimension.id("economy"), new TestEnvironmentDefinition.AllOf());
            for (String name : tests.keySet()) event.registerTest(LogosDimension.id(name), new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, LogosDimension.id(name)),
                    new TestData<>(env, Identifier.withDefaultNamespace("empty"), 200, 0, true, Rotation.NONE, false, 1, 1, false, 16)));
        });
    }

    private static ServerPlayer player(GameTestHelper h) {
        var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "fx-test"), false);
        var p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND); new EmbeddedChannel(connection);
        h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, p, cookie); p.connection.markClientLoaded(); p.setGameMode(GameType.SURVIVAL);
        p.getInventory().clearContent();
        return p;
    }

    private static int count(ServerPlayer p, Currency c, int denomination) {
        var item = Economy.coin(c, denomination); int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (p.getInventory().getItem(i).is(item)) n += p.getInventory().getItem(i).getCount();
        return n;
    }

    private static ItemStack giveWallet(ServerPlayer p) {
        var wallet = new ItemStack(Economy.WALLET_ITEM.get());
        p.setItemInHand(InteractionHand.OFF_HAND, wallet);
        return p.getOffhandItem();
    }

    private static void setClock(GameTestHelper h, long ticks) {
        var server = h.getLevel().getServer();
        var clock = h.getLevel().registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(WorldClocks.OVERWORLD);
        server.clockManager().setTotalTicks(clock, ticks);
    }

    private static void wallet(GameTestHelper h) {
        var p = player(h);
        try {
            p.getInventory().add(new ItemStack(Economy.coin(Currency.FOX, 2), 3));
            p.getInventory().add(new ItemStack(Economy.coin(Currency.CAT, 1), 25));
            var wallet = giveWallet(p);
            h.assertTrue(Purse.depositAll(p, wallet) == 2, "背包两叠散币应全部存入");
            h.assertTrue(Purse.contents(wallet).get(Currency.FOX) == 12_000 && Purse.contents(wallet).get(Currency.CAT) == 300, "余额按最小单位记账");
            h.assertTrue(count(p, Currency.FOX, 2) == 0, "存入后背包不再有小判");
            h.assertTrue(Purse.withdraw(p, wallet, Currency.CAT, 2, 64) == 1, "300 便士只够取一枚金镑");
            h.assertTrue(Purse.contents(wallet).get(Currency.CAT) == 60 && count(p, Currency.CAT, 2) == 1, "取出后余额与钱币对得上");
            // 物品栏里拿着钱币点钱囊
            var coins = new ItemStack(Economy.coin(Currency.WOLF, 0), 40);
            h.assertTrue(Purse.deposit(wallet, coins) && coins.isEmpty() && Purse.contents(wallet).get(Currency.WOLF) == 40, "单叠存入");
            h.succeed();
        } finally { p.discard(); }
    }

    private static void change(GameTestHelper h) {
        var p = player(h);
        try {
            p.getInventory().add(new ItemStack(Economy.coin(Currency.FOX, 2), 1));
            h.assertTrue(Purse.spend(p, Currency.FOX, 1_500), "一枚小判付得起一千五百文");
            // 找零 2500 文：两枚一分银加五百文
            h.assertTrue(count(p, Currency.FOX, 2) == 0 && count(p, Currency.FOX, 1) == 2 && count(p, Currency.FOX, 0) == 500, "找零不对");
            h.assertTrue(!Purse.spend(p, Currency.FOX, 10_000) && Purse.loose(p, Currency.FOX) == 2_500, "付不起时不能动钱");
            h.succeed();
        } finally { p.discard(); }
    }

    private static void checkout(GameTestHelper h) {
        var p = player(h);
        try {
            var wallet = giveWallet(p);
            Purse.receive(p, Currency.IMPERIAL, 50_000);
            Purse.receive(p, Currency.FOX, 1_000);
            var preview = Purse.pay(p, Currency.FOX, 4_000, false);
            h.assertTrue(preview.ok() && preview.sold().containsKey(Currency.IMPERIAL), "狐钱不够时应自动卖帝国币");
            h.assertTrue(Purse.contents(wallet).get(Currency.IMPERIAL) == 50_000, "预览不能动钱");
            long cost = preview.sold().get(Currency.IMPERIAL);
            h.assertTrue(cost > 3_000 * Currency.FOX.anchor * .5 && cost < 3_000 * Currency.FOX.anchor * 2, "换汇数额离谱：" + cost);
            var paid = Purse.pay(p, Currency.FOX, 4_000, true);
            h.assertTrue(paid.ok() && Purse.contents(wallet).get(Currency.IMPERIAL) == 50_000 - cost, "结账后帝国币按预览扣");
            h.assertTrue(Purse.contents(wallet).get(Currency.FOX) < 50, "狐钱应几乎用光，只剩换汇零头");
            // 锁住帝国币后不再自动换
            wallet.set(Economy.WALLET.get(), Purse.contents(wallet).toggleLock(Currency.IMPERIAL));
            h.assertTrue(!Purse.pay(p, Currency.FOX, 4_000, false).ok(), "锁住的货币不能被自动换掉");
            h.succeed();
        } finally { p.discard(); }
    }

    private static void counter(GameTestHelper h) {
        var p = player(h);
        try {
            var pos = h.absolutePos(new BlockPos(2, 1, 2));
            h.getLevel().setBlockAndUpdate(pos, Economy.EXCHANGE.get().defaultBlockState());
            p.snapTo(pos.getX() + .5, pos.getY() + 1, pos.getZ() + 2.5);
            var wallet = giveWallet(p);
            Purse.receive(p, Currency.IMPERIAL, 100_000);
            setClock(h, 20 * FxMarket.DAY + 18_000);
            h.assertTrue(!Economy.trade(p, pos, Currency.IMPERIAL.ordinal(), Currency.WOLF.ordinal(), 10_000), "夜里兑换商不营业");
            setClock(h, 21 * FxMarket.DAY + 2_000);
            h.assertTrue(Economy.trade(p, pos, Currency.IMPERIAL.ordinal(), Currency.WOLF.ordinal(), 10_000), "白天应能兑换");
            long wolf = Purse.contents(wallet).get(Currency.WOLF);
            h.assertTrue(wolf > 0 && Purse.contents(wallet).get(Currency.IMPERIAL) == 90_000, "兑换后余额不对");
            p.snapTo(pos.getX() + 30.5, pos.getY() + 1, pos.getZ() + .5);
            h.assertTrue(!Economy.trade(p, pos, Currency.WOLF.ordinal(), Currency.IMPERIAL.ordinal(), wolf), "离开兑换台不能远程交易");
            h.succeed();
        } finally { p.discard(); }
    }

    private EconomyGameTests() {}
}
