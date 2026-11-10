package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.economy.*;
import dev.logos.dimension.economy.Currency;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** 钱庄的实际客户端检查：经网络存款、借钱开三笔杠杆单（一笔带止损），切到杠杆页截图，再砸盘让一笔爆仓，看通知。 */
@LDLRegisterClient(name="bank_views", group="economy", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
public class BankView implements UIScenario {
    private static final BlockPos BANK = new BlockPos(0, 100, 2);
    private static void order(int action, Currency c, int side, int leverage, long amount, double stop) {
        ClientPacketDistributor.sendToServer(new BankOrderPayload(action, c.ordinal(), side, leverage, amount, 0, 0, stop, 0, 0));
    }
    @Override public void configure(ScenarioOptions o) { o.tags("economy").defaultTimeoutMs(60000).scenarioTimeoutMs(180000); }
    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SURVIVAL).runCommand("time set 0").runCommand("time of logos_dimension:cloudsea set 0").runCommand("weather clear")
                .step("设置视口与中文", ctx -> {
                    var mc = Minecraft.getInstance(); mc.options.hideGui = false;
                    var w = mc.getWindow(); w.setWindowed(1280,720); w.setWidth(1280); w.setHeight(720);
                    mc.getMainRenderTarget().resize(1280,720); mc.gameRenderer.resize(1280,720); mc.resizeGui();
                    mc.getLanguageManager().setSelected("zh_cn"); mc.options.languageCode = "zh_cn"; mc.reloadResourcePacks();
                }).ticks(5).waitUntil("语言加载完成", ctx -> Minecraft.getInstance().getOverlay() == null)
                .server("摆钱庄、发钱", ctx -> {
                    var level = ctx.server().overworld();
                    for (int x=-4;x<=4;x++) for (int z=-4;z<=6;z++) {
                        level.setBlockAndUpdate(new BlockPos(x,99,z), Blocks.SMOOTH_STONE.defaultBlockState());
                        for (int y=100;y<104;y++) level.removeBlock(new BlockPos(x,y,z),false);
                    }
                    level.setBlockAndUpdate(BANK, Economy.BANK.get().defaultBlockState());
                    DimensionTravel.teleport(ctx.player(),level,new Vec3(.5,100,.5),0,20);
                    var p = ctx.player();
                    p.getInventory().clearContent();
                    p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Economy.WALLET_ITEM.get()));
                    Purse.receive(p, Currency.IMPERIAL, 300_000);
                    Purse.receive(p, Currency.CAT, 2 * 240 + 5);
                    FxMarketData.reset(ctx.server(), Economy.clock(ctx.server()));
                })
                .runCommand("time of logos_dimension:cloudsea add 24000").ticks(3).runCommand("time of logos_dimension:cloudsea add 24000").ticks(3).runCommand("time of logos_dimension:cloudsea add 27000").ticks(110)
                .server("打开钱庄", ctx -> Economy.openBank(ctx.player(), BANK))
                .waitUntil("钱庄面板", ctx -> Minecraft.getInstance().screen instanceof BankScreen).ticks(3)
                .step("全部存入", ctx -> { for (var c : Currency.values()) order(BankOrderPayload.DEPOSIT, c, 0, 0, 0, 0); }).ticks(10)
                .checkServer("钱进了钱庄", ctx -> Bank.of(ctx.server()).account(ctx.player().getUUID()).balance(Currency.IMPERIAL) == 300_000)
                .step("开三笔：狼币 25 倍多、猫币 5 倍空带止损、狐钱 10 倍多（借钱）", ctx -> {
                    order(BankOrderPayload.OPEN, Currency.WOLF, 1, 25, 100_000, 0);
                    order(BankOrderPayload.OPEN, Currency.CAT, -1, 5, 100_000, EconomyClient.rate(Currency.CAT) * 1.04);
                    order(BankOrderPayload.OPEN, Currency.FOX, 1, 10, 250_000, 0);
                }).ticks(10)
                .checkServer("三笔都开了，帝国币借成负数", ctx -> {
                    var a = Bank.of(ctx.server()).account(ctx.player().getUUID());
                    return a.positions().size() == 3 && a.balance(Currency.IMPERIAL) == -150_000;
                })
                .screenshot("bank_account_loan")
                .step("切到杠杆页", ctx -> ((BankScreen) Minecraft.getInstance().screen).mode(true)).ticks(3).screenshot("bank_leverage_fox")
                .server("狼币暴跌 8%", ctx -> {
                    var market = FxMarketData.market(ctx.server());
                    market.shock(Currency.WOLF, Math.log(.92));
                    Bank.update(ctx.server(), market, Economy.clock(ctx.server()));
                }).ticks(110)
                .checkServer("狼币多单被强平", ctx -> Bank.of(ctx.server()).account(ctx.player().getUUID()).positions().stream().noneMatch(p -> p.currency() == Currency.WOLF))
                .screenshot("bank_leverage_after_liquidation")
                .step("回到账户页", ctx -> ((BankScreen) Minecraft.getInstance().screen).mode(false)).ticks(3)
                .screenshot("bank_account_notices")
                .step("挂一张狼币多单、给狐钱单追加保证金、猫币单设移动止损", ctx -> {
                    AccountViews.order(BankOrderPayload.LIMIT_OPEN, Currency.WOLF.ordinal(), 1, 10, 20_000, 0, EconomyClient.rate(Currency.WOLF) * .95, 0, 0, 0);
                    for (var p : EconomyClient.bank.positions()) {
                        if (p.currency() == Currency.FOX) AccountViews.order(BankOrderPayload.ADD_MARGIN, 0, 0, 0, 50_000, p.id(), 0, 0, 0, 0);
                        if (p.currency() == Currency.CAT) AccountViews.order(BankOrderPayload.MODIFY, 0, 0, 0, 0, p.id(), 0, 0, 0, .03);
                    }
                }).ticks(10)
                .checkServer("挂单、追加保证金、移动止损都生效", ctx -> {
                    var a = Bank.of(ctx.server()).account(ctx.player().getUUID());
                    return a.orders().size() == 1 && a.positions().stream().anyMatch(p -> p.currency() == Currency.FOX && p.margin() == 300_000)
                            && a.positions().stream().anyMatch(p -> p.currency() == Currency.CAT && p.trail() > 0);
                })
                .step("持仓页，选中狐钱单", ctx -> {
                    var screen = (BankScreen) Minecraft.getInstance().screen;
                    screen.page(2);
                    for (var p : EconomyClient.bank.positions()) if (p.currency() == Currency.FOX) screen.select(p.id());
                }).ticks(3).screenshot("bank_positions_selected")
                .step("关闭钱庄", ctx -> Minecraft.getInstance().setScreen(null)).ticks(3)
                .server("打开钱囊", ctx -> Economy.openWallet(ctx.player(), InteractionHand.MAIN_HAND))
                .waitUntil("钱囊面板", ctx -> Minecraft.getInstance().screen instanceof WalletScreen).ticks(3)
                .step("钱囊行情页、设提醒", ctx -> {
                    ((WalletScreen) Minecraft.getInstance().screen).page(1);
                    AccountViews.order(BankOrderPayload.ALERT, Currency.FOX.ordinal(), 0, 0, 0, 0, EconomyClient.rate(Currency.FOX) * 1.05, 0, 0, 0);
                }).ticks(10).screenshot("wallet_market")
                .checkServer("提醒已设", ctx -> Bank.of(ctx.server()).account(ctx.player().getUUID()).alerts().size() == 1)
                .step("钱囊持仓页", ctx -> ((WalletScreen) Minecraft.getInstance().screen).page(2)).ticks(3).screenshot("wallet_positions")
                .step("关闭钱囊、打开行情条", ctx -> { Minecraft.getInstance().setScreen(null); TickerHud.toggle(); }).ticks(115)
                .check("行情条收到数据", ctx -> TickerHud.enabled() && TickerHud.hasData())
                .screenshot("ticker_hud")
                .step("关掉行情条", ctx -> TickerHud.toggle());
    }
}
