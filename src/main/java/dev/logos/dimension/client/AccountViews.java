package dev.logos.dimension.client;

import dev.logos.dimension.economy.Bank;
import dev.logos.dimension.economy.BankAccount;
import dev.logos.dimension.economy.BankOrderPayload;
import dev.logos.dimension.economy.BankPayload;
import dev.logos.dimension.economy.Currency;
import dev.logos.dimension.economy.Margin;
import dev.logos.dimension.economy.Money;
import dev.logos.dimension.economy.Orders;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** 钱庄和钱囊共用的账户文字：总览一行、持仓一行、挂单一行，以及按字截断的绘制。 */
final class AccountViews {
    static final int TEXT = 0xFFE8E0D0, DIM = CandleChart.DIM, GOLD = 0xFFE8C77A, RED = 0xFFE07060;

    static void order(int action, int currency, int side, int leverage, long amount, long id, double price, double stop, double take, double trail) {
        ClientPacketDistributor.sendToServer(new BankOrderPayload(action, currency, side, leverage, amount, id, price, stop, take, trail));
    }

    static BankAccount.Summary summary(BankPayload b) {
        return BankAccount.summary(b.balances(), b.positions(), b.orders(), EconomyClient::rate);
    }

    /** 「总权益 · 可用 · 浮动盈亏 · 已用保证金 · 借款」。 */
    static MutableComponent summaryLine(BankPayload b) {
        var s = summary(b);
        long imperial = b.balance(Currency.IMPERIAL);
        var line = Component.translatable("screen.logos_dimension.bank.summary",
                Money.top(Currency.IMPERIAL, s.equity()), Money.top(Currency.IMPERIAL, Math.max(0, imperial)));
        line.append(" · ").append(Component.translatable("screen.logos_dimension.bank.pnl"))
                .append(Bank.crownsText(s.pnl()).withStyle(s.pnl() >= 0 ? ChatFormatting.RED : ChatFormatting.GREEN));
        line.append(" · ").append(Component.translatable("screen.logos_dimension.bank.used", Money.top(Currency.IMPERIAL, s.margin())));
        if (imperial < 0) line.append(" · ").append(Component.translatable("screen.logos_dimension.bank.borrowed",
                Money.top(Currency.IMPERIAL, -imperial)).withStyle(ChatFormatting.RED));
        return line;
    }

    static String silver(Currency c, double rate) { return CandleChart.fmt(rate * c.top().value() / 100.0); }

    static MutableComponent side(int side) {
        return Component.translatable(side > 0 ? "bank.side.long" : "bank.side.short").withStyle(side > 0 ? ChatFormatting.RED : ChatFormatting.GREEN);
    }

    /** 「#3 狼币 多 25× 盈亏 +1.23 金冠 · 爆 108.18 · 可承受 2.3%」，快爆仓时整行标红。 */
    static MutableComponent position(Margin.Position p) {
        double rate = EconomyClient.rate(p.currency());
        double pnl = Margin.pnl(p, rate) + p.accrued();
        var line = Component.literal("#" + p.id() + " ").append(Component.translatable(p.currency().key())).append(" ").append(side(p.side()))
                .append(String.format(" %.1f× ", p.effectiveLeverage()))
                .append(Bank.crownsText(pnl).withStyle(pnl >= 0 ? ChatFormatting.RED : ChatFormatting.GREEN))
                .append(Component.translatable("screen.logos_dimension.bank.row_tail", silver(p.currency(), Margin.liquidationRate(p)),
                        String.format("%.1f%%", Margin.room(p, rate) * 100)));
        if (p.stop() > 0) line.append(Component.translatable("screen.logos_dimension.bank.row_stop", silver(p.currency(), p.stop())));
        return line;
    }

    /** 「挂单 #7 买入狐钱 ≤ 98.20 · 冻结 10.00 金冠」。 */
    static MutableComponent order(Orders.Pending o) {
        var c = o.currency();
        String kind = switch (o.kind()) { case Orders.BUY -> "buy"; case Orders.SELL -> "sell"; default -> o.side() > 0 ? "long" : "short"; };
        boolean below = o.kind() == Orders.BUY || o.kind() == Orders.OPEN && o.side() > 0;
        var frozen = o.kind() == Orders.SELL ? Money.top(c, o.amount()) : Money.top(Currency.IMPERIAL, o.amount());
        var line = Component.translatable("screen.logos_dimension.bank.order_row", o.id(),
                Component.translatable("screen.logos_dimension.bank.order." + kind, Component.translatable(c.key())),
                (below ? "≤ " : "≥ ") + silver(c, o.price()), frozen);
        if (o.kind() == Orders.OPEN) line.append(" · " + o.leverage() + "×");
        return line.withStyle(ChatFormatting.GRAY);
    }

    /** 一行写不下就按字截断加省略号（中文没有空格，按词换行会整句挤走），悬停看全文。 */
    static void clipped(GuiGraphicsExtractor g, Font font, Component line, int x, int y, int w, int color, int mouseX, int mouseY) {
        boolean cut = font.width(line) > w;
        FormattedText text = cut ? FormattedText.composite(font.substrByWidth(line, w - font.width("…")), FormattedText.of("…")) : line;
        g.text(font, Language.getInstance().getVisualOrder(text), x, y, color);
        if (cut && mouseX >= x && mouseX < x + w && mouseY >= y - 1 && mouseY < y + 9) g.setTooltipForNextFrame(font, font.split(line, 280), mouseX, mouseY);
    }

    private AccountViews() {}
}
