package dev.logos.dimension.client;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.economy.Bank;
import dev.logos.dimension.economy.Currency;
import dev.logos.dimension.economy.EconomyActionPayload;
import dev.logos.dimension.economy.Margin;
import dev.logos.dimension.economy.TickerPayload;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * 屏幕左上角的行情条：三种货币的报价和一日涨跌（红涨绿跌），有持仓时加上浮动盈亏，
 * 最危险那笔净值跌破保证金一半时闪红提示。在钱囊面板里开关，开关状态记在 config/logos_dimension-fx.txt；
 * 服务端只在身上带着钱囊时推送，超过 15 秒没有新数据就不画。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class TickerHud {
    private static final Identifier ID = LogosDimension.id("fx_ticker");
    private static Boolean enabled;
    private static TickerPayload latest;
    private static long received;

    private static Path file() { return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("logos_dimension-fx.txt"); }

    static boolean enabled() {
        if (enabled == null) {
            try { enabled = Files.exists(file()) && Files.readString(file()).trim().equals("ticker=on"); } catch (Exception e) { enabled = false; }
        }
        return enabled;
    }

    static void toggle() {
        enabled = !enabled();
        try { Files.createDirectories(file().getParent()); Files.writeString(file(), enabled ? "ticker=on\n" : "ticker=off\n"); } catch (Exception ignored) {}
        EconomyClient.send(EconomyActionPayload.TICKER, 0, 0, 0, enabled ? 1 : 0);
        if (!enabled) latest = null;
    }

    static void receive(TickerPayload p) { latest = p; received = System.currentTimeMillis(); }
    static boolean hasData() { return latest != null; }

    @SubscribeEvent static void login(ClientPlayerNetworkEvent.LoggingIn event) {
        latest = null;
        if (enabled()) EconomyClient.send(EconomyActionPayload.TICKER, 0, 0, 0, 1);
    }

    @SubscribeEvent static void layers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CHAT, ID, TickerHud::render);
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        var mc = Minecraft.getInstance();
        var p = latest;
        if (!enabled() || p == null || mc.options.hideGui || mc.screen != null || System.currentTimeMillis() - received > 15_000) return;
        var font = mc.font;
        MutableComponent line = Component.empty();
        for (int i = 0; i < 3; i++) {
            var c = Currency.TRADED.get(i);
            double change = p.dayAgo()[i] == 0 ? 0 : (p.rates()[i] / p.dayAgo()[i] - 1) * 100;
            if (i > 0) line.append("  ");
            line.append(Component.translatable(c.key()).withStyle(ChatFormatting.GOLD)).append(" ")
                    .append(AccountViews.silver(c, p.rates()[i]))
                    .append(Component.literal(String.format(" %+.1f%%", change)).withStyle(change >= 0 ? ChatFormatting.RED : ChatFormatting.GREEN));
        }
        int w = font.width(line) + 8, h = 13;
        MutableComponent second = null;
        if (p.positions() > 0) {
            second = Component.translatable("screen.logos_dimension.bank.positions", p.positions()).append("  ")
                    .append(Bank.crownsText(p.pnl()).withStyle(p.pnl() >= 0 ? ChatFormatting.RED : ChatFormatting.GREEN));
            boolean danger = p.worst() >= 0 && p.worst() <= Margin.WARNING;
            if (danger && (System.currentTimeMillis() / 400) % 2 == 0)
                second.append("  ").append(Component.translatable("screen.logos_dimension.wallet.ticker_danger").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            w = Math.max(w, font.width(second) + 8);
            h += 10;
        }
        g.fill(2, 2, 2 + w, 2 + h, 0x90101014);
        g.text(font, line, 6, 5, 0xFFE8E0D0);
        if (second != null) g.text(font, second, 6, 15, 0xFFE8E0D0);
    }

    private TickerHud() {}
}
