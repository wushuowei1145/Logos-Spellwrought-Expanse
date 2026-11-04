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
                "economy_counter_trades_by_day", EconomyGameTests::counter,
                "economy_bank_loan_stays_in_the_bank", EconomyGameTests::bankLoan,
                "economy_bank_liquidates_and_stops", EconomyGameTests::bankLiquidation,
                "economy_bank_orders_fill_and_refund", EconomyGameTests::bankOrders,
                "economy_bank_manage_position", EconomyGameTests::bankManage);
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

    private static void bankLoan(GameTestHelper h) {
        var p = player(h);
        try {
            setClock(h, 30 * FxMarket.DAY + 3_000);
            var server = h.getLevel().getServer();
            var wallet = giveWallet(p);
            Purse.receive(p, Currency.IMPERIAL, 20_000);
            Purse.receive(p, Currency.FOX, 8_000);
            h.assertTrue(Bank.deposit(p, Currency.IMPERIAL, 0) && Bank.deposit(p, Currency.FOX, 0), "存入全部");
            var account = Bank.of(server).account(p.getUUID());
            h.assertTrue(account.balance(Currency.IMPERIAL) == 20_000 && Purse.contents(wallet).get(Currency.IMPERIAL) == 0, "存款进钱庄，钱囊清空");
            h.assertTrue(!Bank.open(p, Currency.WOLF, 1, 10_000, Margin.PLAYER_MAX + 1, 0, 0), "玩家倍数不能超过上限");
            // 保证金十金冠，存款只有两金冠：差额借来，帝国币变成负数
            h.assertTrue(Bank.open(p, Currency.WOLF, 1, 100_000, 25, 0, 0), "保证金不够时应向钱庄借");
            h.assertTrue(account.balance(Currency.IMPERIAL) == -80_000 && account.positions().size() == 1, "帝国币应欠八金冠");
            h.assertTrue(!Bank.withdraw(p, Currency.FOX, 8_000), "欠着钱时不能把存款取光");
            h.assertTrue(!Bank.open(p, Currency.FOX, 1, BankAccount.CREDIT_LIMIT, 2, 0, 0), "借款不能超过授信额度");
            long id = account.positions().getFirst().id();
            h.assertTrue(Bank.close(p, id) && account.positions().isEmpty(), "手动平仓");
            h.assertTrue(account.balance(Currency.IMPERIAL) > -80_000 && account.balance(Currency.IMPERIAL) < 20_000, "平仓后拿回保证金扣去成本");
            h.succeed();
        } finally { p.discard(); }
    }

    private static void bankLiquidation(GameTestHelper h) {
        var p = player(h);
        try {
            setClock(h, 40 * FxMarket.DAY + 3_000);
            var server = h.getLevel().getServer();
            var market = FxMarketData.market(server);
            market.advance(Economy.clock(server));
            var account = Bank.of(server).account(p.getUUID());
            Purse.receive(p, Currency.IMPERIAL, 200_000);
            Bank.deposit(p, Currency.IMPERIAL, 0);
            double rate = market.rate(Currency.CAT);
            h.assertTrue(Bank.open(p, Currency.WOLF, 1, 50_000, 25, 0, 0), "开 25 倍狼币多单");
            h.assertTrue(Bank.open(p, Currency.CAT, -1, 50_000, 2, rate * 1.05, 0), "开猫币空单带止损");
            market.shock(Currency.WOLF, Math.log(.9));
            market.shock(Currency.CAT, Math.log(1.08));
            Bank.update(server, market, Economy.clock(server));
            h.assertTrue(account.positions().isEmpty(), "两笔都应被自动平掉");
            var keys = account.notices().stream().map(FxMarket.News::key).toList();
            h.assertTrue(keys.contains("bank.notice.liquidate") && keys.contains("bank.notice.stop"), "应有强平和止损通知：" + keys);
            // 狼币爆仓最多亏光保证金；猫币止损亏一些
            long balance = account.balance(Currency.IMPERIAL);
            h.assertTrue(balance >= 100_000 && balance < 160_000, "结算后余额不对：" + balance);
            h.succeed();
        } finally { p.discard(); }
    }

    private static void bankOrders(GameTestHelper h) {
        var p = player(h);
        try {
            setClock(h, 50 * FxMarket.DAY + 3_000);
            var server = h.getLevel().getServer();
            var market = FxMarketData.market(server);
            market.advance(Economy.clock(server));
            var wallet = giveWallet(p);
            Purse.receive(p, Currency.IMPERIAL, 300_000);
            Purse.receive(p, Currency.FOX, 8_000);
            var account = Bank.of(server).account(p.getUUID());
            double fox = market.rate(Currency.FOX), wolf = market.rate(Currency.WOLF), cat = market.rate(Currency.CAT);
            h.assertTrue(Bank.placeSpot(p, Currency.FOX, 1, 20_000, fox * .95), "现货买单");
            h.assertTrue(Bank.placeSpot(p, Currency.FOX, -1, 4_000, fox * 1.1), "现货卖单");
            h.assertTrue(Purse.contents(wallet).get(Currency.IMPERIAL) == 280_000 && Purse.contents(wallet).get(Currency.FOX) == 4_000, "挂单时钱就冻结");
            h.assertTrue(Bank.placeOpen(p, Currency.WOLF, 1, 50_000, 10, wolf * .97, 0, 0, 0), "杠杆挂单");
            h.assertTrue(account.balance(Currency.IMPERIAL) == -50_000, "挂单保证金从钱庄扣（可以借）");
            Bank.update(server, market, Economy.clock(server));
            h.assertTrue(account.orders().size() == 3, "没到价不能成交");
            market.shock(Currency.FOX, Math.log(.9));
            Bank.update(server, market, Economy.clock(server));
            h.assertTrue(account.orders().size() == 2 && account.balance(Currency.FOX) > 0, "狐钱跌到挂价，买单成交进钱庄");
            market.shock(Currency.FOX, Math.log(1.25));
            Bank.update(server, market, Economy.clock(server));
            h.assertTrue(account.orders().size() == 1 && account.balance(Currency.IMPERIAL) > -50_000, "狐钱涨到挂价，卖单成交");
            // 夜里不撮合
            setClock(h, 50 * FxMarket.DAY + 15_000);
            market.shock(Currency.WOLF, Math.log(.95));
            Bank.update(server, market, Economy.clock(server));
            h.assertTrue(account.orders().size() == 1, "夜里兑换商关门，挂单不成交");
            long before = account.balance(Currency.IMPERIAL);
            h.assertTrue(Bank.cancel(p, account.orders().getFirst().id()) && account.balance(Currency.IMPERIAL) == before + 50_000, "撤单退回保证金");
            h.assertTrue(Bank.addAlert(p, Currency.CAT, cat * 1.03), "设提醒");
            market.shock(Currency.CAT, Math.log(1.06));
            Bank.update(server, market, Economy.clock(server));
            var keys = account.notices().stream().map(FxMarket.News::key).toList();
            h.assertTrue(account.alerts().isEmpty() && keys.contains("bank.notice.alert"), "夜里提醒照样触发：" + keys);
            h.assertTrue(keys.contains("bank.notice.buy") && keys.contains("bank.notice.sell"), "成交通知：" + keys);
            h.succeed();
        } finally { p.discard(); }
    }

    private static void bankManage(GameTestHelper h) {
        var p = player(h);
        try {
            setClock(h, 60 * FxMarket.DAY + 3_000);
            var server = h.getLevel().getServer();
            var market = FxMarketData.market(server);
            market.advance(Economy.clock(server));
            // 别的测试把时钟拨来拨去；先让钱庄结掉跨过的夜，免得下面这笔仓位白拿几夜利息
            Bank.update(server, market, Economy.clock(server));
            var account = Bank.of(server).account(p.getUUID());
            Purse.receive(p, Currency.IMPERIAL, 300_000);
            Bank.deposit(p, Currency.IMPERIAL, 0);
            h.assertTrue(Bank.open(p, Currency.WOLF, 1, 50_000, 25, 0, 0), "开 25 倍");
            long id = account.positions().getFirst().id();
            h.assertTrue(Bank.addMargin(p, id, 50_000) && account.positions().getFirst().margin() == 100_000, "追加保证金");
            h.assertTrue(account.balance(Currency.IMPERIAL) == 200_000, "追加的钱从存款扣");
            // 市场是各个测试共用的，按这笔仓位自己算出让净值落到保证金 35% 的跌幅
            var held = account.positions().getFirst();
            double now = market.rate(Currency.WOLF), lo = .5, hi = 1;
            for (int i = 0; i < 60; i++) { double mid = (lo + hi) / 2; if (Margin.equity(held, now * mid) > held.margin() * .35) hi = mid; else lo = mid; }
            market.shock(Currency.WOLF, Math.log((lo + hi) / 2));
            Bank.update(server, market, Economy.clock(server));
            h.assertTrue(account.positions().size() == 1 && account.positions().getFirst().warned(), "跌到一半保证金时预警，不强平");
            h.assertTrue(account.notices().stream().anyMatch(n -> n.key().equals("bank.notice.warning")), "预警通知");
            double rate = market.rate(Currency.WOLF);
            h.assertTrue(Bank.modify(p, id, 0, 0, .02), "设移动止损");
            double stop = account.positions().getFirst().stop();
            h.assertTrue(stop < rate && stop > rate * .97, "移动止损从现价起算：" + stop / rate);
            Bank.update(server, market, Economy.clock(server));
            h.assertTrue(account.positions().size() == 1, "刚设的移动止损不该立刻触发");
            long cash = account.balance(Currency.IMPERIAL);
            h.assertTrue(Bank.reduce(p, id, .5) && account.positions().getFirst().margin() == 50_000, "平一半");
            h.assertTrue(account.balance(Currency.IMPERIAL) > cash, "平掉的部分拿回钱");
            h.assertTrue(Bank.increase(p, id, 20_000, 5) && account.positions().getFirst().margin() == 70_000, "加仓");
            h.assertTrue(Bank.open(p, Currency.FOX, -1, 10_000, 3, 0, 0) && account.positions().size() == 2, "再开一笔");
            h.assertTrue(Bank.closeAll(p) && account.positions().isEmpty(), "一键全平");
            h.succeed();
        } finally { p.discard(); }
    }

    private EconomyGameTests() {}
}
