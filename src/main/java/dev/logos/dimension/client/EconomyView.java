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

/** 兑换台与万国钱囊的实际客户端检查：攒几天行情和传闻，打开兑换台、经网络下单、打开钱囊，各截一张图。 */
@LDLRegisterClient(name="economy_views", group="economy", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
public class EconomyView implements UIScenario {
    private static final BlockPos COUNTER = new BlockPos(0, 100, 2);
    @Override public void configure(ScenarioOptions o) { o.tags("economy").defaultTimeoutMs(60000).scenarioTimeoutMs(180000); }
    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SURVIVAL).runCommand("time set 0").runCommand("weather clear")
                .step("设置视口", ctx -> {
                    var mc = Minecraft.getInstance(); mc.options.hideGui = false;
                    var w = mc.getWindow(); w.setWindowed(1280,720); w.setWidth(1280); w.setHeight(720);
                    mc.getMainRenderTarget().resize(1280,720); mc.gameRenderer.resize(1280,720); mc.resizeGui();
                }).step("切换中文", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.getLanguageManager().setSelected("zh_cn"); mc.options.languageCode = "zh_cn";
                    mc.reloadResourcePacks();
                }).ticks(5).waitUntil("语言加载完成", ctx -> Minecraft.getInstance().getOverlay() == null).server("摆兑换台、发钱囊和散币", ctx -> {
                    var level = ctx.server().overworld();
                    for (int x=-4;x<=4;x++) for (int z=-4;z<=6;z++) {
                        level.setBlockAndUpdate(new BlockPos(x,99,z), Blocks.SMOOTH_STONE.defaultBlockState());
                        for (int y=100;y<104;y++) level.removeBlock(new BlockPos(x,y,z),false);
                    }
                    level.setBlockAndUpdate(COUNTER, Economy.EXCHANGE.get().defaultBlockState());
                    DimensionTravel.teleport(ctx.player(),level,new Vec3(.5,100,.5),0,20);
                    var p = ctx.player();
                    p.getInventory().clearContent(); p.getInventory().setSelectedSlot(0);
                    p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Economy.WALLET_ITEM.get()));
                    Purse.receive(p, Currency.IMPERIAL, 1_234_567);
                    Purse.receive(p, Currency.FOX, 9_876);
                    Purse.receive(p, Currency.CAT, 3 * 240 + 8 * 12 + 7);
                    Purse.receive(p, Currency.WOLF, 2 * 400 + 5 * 16 + 3);
                    p.getInventory().add(new ItemStack(Economy.coin(Currency.FOX, 2), 5));
                    p.getInventory().add(new ItemStack(Economy.coin(Currency.WOLF, 3), 2));
                    FxMarketData.reset(ctx.server(), Economy.clock(ctx.server()));
                })
                // 走过五个游戏日，攒下 K 线和几条传闻
                .runCommand("time add 24000").ticks(3).runCommand("time add 24000").ticks(3)
                .server("放出几条传闻", ctx -> {
                    var market = FxMarketData.market(ctx.server());
                    long clock = Economy.clock(ctx.server());
                    market.force("war", clock, 30_000); market.force("council", clock, 8_000); market.force("calamity", clock, 20_000);
                })
                .runCommand("time add 24000").ticks(3).runCommand("time add 24000").ticks(3).runCommand("time add 27000").ticks(110)
                .server("打开兑换台", ctx -> Economy.openExchange(ctx.player(), COUNTER))
                .waitUntil("兑换台面板", ctx -> Minecraft.getInstance().screen instanceof ExchangeScreen).ticks(5)
                .check("收到 K 线", ctx -> EconomyClient.latest != null && EconomyClient.latest.candles(Currency.FOX).size() > 30)
                .check("收到传闻", ctx -> EconomyClient.latest.news().size() >= 3)
                .screenshot("exchange_fox")
                .step("经网络下单：一万铜分换狼币", ctx -> EconomyClient.send(EconomyActionPayload.TRADE, 0, Currency.IMPERIAL.ordinal(), Currency.WOLF.ordinal(), 1_000_000))
                .ticks(10)
                .checkServer("钱囊里多了狼币", ctx -> Purse.contents(ctx.player().getMainHandItem()).get(Currency.WOLF) > 2 * 400 + 5 * 16 + 3)
                .step("关闭兑换台", ctx -> Minecraft.getInstance().setScreen(null)).ticks(3)
                .server("打开钱囊", ctx -> Economy.openWallet(ctx.player(), InteractionHand.MAIN_HAND))
                .waitUntil("钱囊面板", ctx -> Minecraft.getInstance().screen instanceof WalletScreen).ticks(5)
                .screenshot("wallet")
                .step("存入散币", ctx -> EconomyClient.send(EconomyActionPayload.DEPOSIT, 0, 0, 0, 0)).ticks(10)
                .checkServer("散币已入囊", ctx -> Purse.loose(ctx.player(), Currency.FOX) == 0 && Purse.loose(ctx.player(), Currency.WOLF) == 0)
                .screenshot("wallet_deposited")
                .step("关闭钱囊", ctx -> Minecraft.getInstance().setScreen(null));
    }
}
