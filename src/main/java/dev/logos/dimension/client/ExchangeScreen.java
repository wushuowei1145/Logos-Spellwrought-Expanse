package dev.logos.dimension.client;

import dev.logos.dimension.economy.Currency;
import dev.logos.dimension.economy.EconomyActionPayload;
import dev.logos.dimension.economy.FxMarket;
import dev.logos.dimension.economy.Money;
import dev.logos.dimension.economy.Purse;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * 兑换台面板：左边是所选货币对帝国币的 K 线（红涨绿跌，按游戏小时一根，可切换看近五天或近二十天），
 * 右边下单（市价立即成交，或挂单等价格到了再成交），底部是最近的告示与传闻。价格一律写成「一枚最大面额值多少银元」。
 * 挂单只能一边是帝国币；钱现在就冻结，成交所得和撤单退回的钱都存入钱庄（撤单在钱囊或钱庄里）。
 */
public final class ExchangeScreen extends Screen {
    private static final int W = 360, H = 236, CHART_W = 222, CHART_H = 100;
    private static final int PANEL = 0xE0161218, EDGE = 0xFFB89A5A, DIM = CandleChart.DIM;
    private final BlockPos pos;
    private Currency shown = Currency.FOX, from = Currency.IMPERIAL, to = Currency.FOX;
    private boolean wide, limit;
    private EditBox amount, price;
    private Button fromButton, toButton, tradeButton, rangeButton, typeButton;
    private final List<Button> tabs = new ArrayList<>();
    private int x0, y0;

    public ExchangeScreen(BlockPos pos) {
        super(Component.translatable("block.logos_dimension.exchange_counter"));
        this.pos = pos;
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    protected void init() {
        x0 = (width - W) / 2; y0 = (height - H) / 2;
        tabs.clear();
        for (int i = 0; i < Currency.TRADED.size(); i++) {
            var c = Currency.TRADED.get(i);
            tabs.add(addRenderableWidget(Button.builder(Component.translatable(c.key()), b -> shown = c)
                    .bounds(x0 + 8 + i * 54, y0 + 20, 52, 16).build()));
        }
        rangeButton = addRenderableWidget(Button.builder(Component.empty(), b -> wide = !wide).bounds(x0 + 8 + CHART_W - 54, y0 + 20, 54, 16).build());
        int rx = x0 + 238, rw = W - 246;
        fromButton = addRenderableWidget(Button.builder(Component.empty(), b -> from = next(from, to)).bounds(rx, y0 + 40, rw, 14).build());
        toButton = addRenderableWidget(Button.builder(Component.empty(), b -> to = next(to, from)).bounds(rx, y0 + 56, rw, 14).build());
        amount = addRenderableWidget(new EditBox(font, rx, y0 + 72, rw, 16, Component.translatable("screen.logos_dimension.fx.amount")));
        amount.setFilter(s -> s.matches("\\d{0,9}(\\.\\d{0,3})?"));
        amount.setHint(Component.translatable("screen.logos_dimension.fx.amount"));
        int qw = (rw - 4) / 3;
        Component[] labels = {Component.literal("25%"), Component.literal("50%"), Component.translatable("screen.logos_dimension.fx.all")};
        double[] shares = {.25, .5, 1};
        for (int i = 0; i < 3; i++) {
            double share = shares[i];
            addRenderableWidget(Button.builder(labels[i], b -> fill(share)).bounds(rx + i * (qw + 2), y0 + 90, qw, 14).build());
        }
        typeButton = addRenderableWidget(Button.builder(Component.empty(), b -> limit = !limit).bounds(rx, y0 + 106, 40, 14).build());
        price = addRenderableWidget(new EditBox(font, rx + 42, y0 + 106, rw - 42, 14, Component.translatable("screen.logos_dimension.bank.limit_price")));
        price.setFilter(s -> s.matches("\\d{0,9}(\\.\\d{0,4})?"));
        price.setHint(Component.translatable("screen.logos_dimension.bank.limit_price"));
        tradeButton = addRenderableWidget(Button.builder(Component.translatable("screen.logos_dimension.fx.trade"), b -> trade())
                .bounds(rx, y0 + 144, rw, 18).build());
    }

    /** 轮换到下一种货币，跳过另一边已选的。 */
    private static Currency next(Currency current, Currency other) {
        var all = Currency.values();
        var c = current;
        do c = all[(c.ordinal() + 1) % all.length]; while (c == other);
        return c;
    }

    private long held(Currency c) { return minecraft.player == null ? 0 : Purse.available(minecraft.player, c); }

    /** 输入框按最大面额写，例如 1.5 小判；换算成最小单位。 */
    private long typed() {
        try {
            String s = amount.getValue();
            if (s.isEmpty() || s.equals(".")) return 0;
            return Math.round(Double.parseDouble(s) * from.top().value());
        } catch (NumberFormatException e) { return 0; }
    }

    private void fill(double share) {
        long v = (long) Math.floor(held(from) * share);
        amount.setValue(trim(v / (double) from.top().value()));
    }

    private static String trim(double v) {
        String s = String.format(java.util.Locale.ROOT, "%.3f", Math.floor(v * 1000) / 1000);
        return s.replaceAll("\\.?0+$", "");
    }

    /** 挂单针对的那种货币（另一边必须是帝国币），不能挂时为 null。 */
    private Currency limitTarget() {
        if (from == Currency.IMPERIAL && to != Currency.IMPERIAL) return to;
        if (to == Currency.IMPERIAL && from != Currency.IMPERIAL) return from;
        return null;
    }

    /** 挂单价换成中间价（铜分每最小单位）；空着为 0。 */
    private double limitRate(Currency c) {
        try { return price.getValue().isEmpty() ? 0 : Double.parseDouble(price.getValue()) * 100 / c.top().value(); } catch (NumberFormatException e) { return 0; }
    }

    private void trade() {
        long a = typed();
        if (a <= 0) return;
        var c = limitTarget();
        if (limit && c != null && limitRate(c) > 0)
            AccountViews.order(dev.logos.dimension.economy.BankOrderPayload.LIMIT_SPOT, c.ordinal(), from == Currency.IMPERIAL ? 1 : -1, 1,
                    Math.min(a, held(from)), 0, limitRate(c), 0, 0, 0);
        else if (!limit) EconomyClient.send(EconomyActionPayload.TRADE, 0, from.ordinal(), to.ordinal(), Math.min(a, held(from)));
        else return;
        amount.setValue("");
    }

    @Override
    public void removed() { EconomyClient.send(EconomyActionPayload.CLOSE, 0, 0, 0, 0); }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        var data = EconomyClient.latest;
        g.fill(x0, y0, x0 + W, y0 + H, PANEL);
        g.outline(x0, y0, W, H, EDGE);
        long clock = data == null ? 0 : data.clock();
        boolean open = FxMarket.open(clock);
        g.text(font, title, x0 + 8, y0 + 7, 0xFFE8C77A);
        var status = EconomyClient.clockLine(clock).append(" · ")
                .append(Component.translatable(open ? "screen.logos_dimension.fx.open" : "screen.logos_dimension.fx.closed"));
        g.text(font, status, x0 + W - 8 - font.width(status), y0 + 7, open ? 0xFFB8E0A0 : 0xFFE09070);

        for (int i = 0; i < tabs.size(); i++) tabs.get(i).active = Currency.TRADED.get(i) != shown;
        rangeButton.setMessage(Component.translatable(wide ? "screen.logos_dimension.fx.range_wide" : "screen.logos_dimension.fx.range_near"));
        fromButton.setMessage(Component.translatable("screen.logos_dimension.fx.from", Component.translatable(from.key())));
        toButton.setMessage(Component.translatable("screen.logos_dimension.fx.to", Component.translatable(to.key())));
        long typed = typed();
        typeButton.setMessage(Component.translatable(limit ? "screen.logos_dimension.bank.limit" : "screen.logos_dimension.bank.market"));
        price.visible = limit;
        tradeButton.setMessage(Component.translatable(limit ? "screen.logos_dimension.bank.place" : "screen.logos_dimension.fx.trade"));
        var target = limitTarget();
        tradeButton.active = typed > 0 && typed <= held(from) && (limit ? target != null && limitRate(target) > 0 : open)
                && !FxMarket.tooLarge(from, to, typed, EconomyClient.rate(from), EconomyClient.rate(to));
        super.extractRenderState(g, mouseX, mouseY, a);

        chart(g, data, mouseX, mouseY);
        order(g, typed);
        news(g, data, mouseX, mouseY);
    }

    // ---------------------------------------------------------------- K 线

    private void chart(GuiGraphicsExtractor g, dev.logos.dimension.economy.MarketPayload data, int mouseX, int mouseY) {
        int cx = x0 + 8, cy = y0 + 40;
        var marks = new ArrayList<CandleChart.Mark>();
        if (EconomyClient.bank != null) for (var o : EconomyClient.bank.orders())
            if (o.currency() == shown && o.kind() != dev.logos.dimension.economy.Orders.OPEN)
                marks.add(new CandleChart.Mark(o.price(), 0xC0A0A0A0, Component.literal("#" + o.id())));
        CandleChart.draw(g, font, data, shown, wide, cx, cy, CHART_W, CHART_H, mouseX, mouseY, marks);
        CandleChart.quote(g, font, data, shown, cx, cy + CHART_H + 5);
    }

    // ---------------------------------------------------------------- 下单预览

    private void order(GuiGraphicsExtractor g, long typed) {
        int rx = x0 + 238, rw = W - 246;
        int y = y0 + 123;
        var held = Component.translatable("screen.logos_dimension.fx.held").append(Money.format(from, held(from)));
        for (var line : font.split(held, rw)) { g.text(font, line, rx, y, DIM); y += 9; if (y > y0 + 140) break; }
        if (typed <= 0) return;
        var target = limitTarget();
        if (limit && target == null) { g.text(font, Component.translatable("screen.logos_dimension.fx.limit_imperial"), rx, y0 + 166, 0xFFE09070); return; }
        // 挂单按挂价估算能得多少；实际按成交那一刻的市价
        double rf = EconomyClient.rate(from), rt = EconomyClient.rate(to);
        if (limit && limitRate(target) > 0) { if (target == from) rf = limitRate(target); else rt = limitRate(target); }
        var q = FxMarket.quote(from, to, typed, rf, rt);
        var get = Component.translatable("screen.logos_dimension.fx.get").append(Money.format(to, q.received()));
        int gy = y0 + 166;
        for (var line : font.split(get, rw)) { g.text(font, line, rx, gy, 0xFFE8E0D0); gy += 9; }
        g.text(font, Component.translatable("screen.logos_dimension.fx.cost").append(Money.top(Currency.IMPERIAL, Math.max(0, q.cost()))), rx, gy, DIM);
        if (typed > held(from)) g.text(font, Component.translatable("screen.logos_dimension.fx.short"), rx, gy + 9, 0xFFE09070);
        else if (FxMarket.tooLarge(from, to, typed, EconomyClient.rate(from), EconomyClient.rate(to)))
            g.text(font, Component.translatable("message.logos_dimension.bank.too_deep"), rx, gy + 9, 0xFFE09070);
        else if (limit) g.text(font, Component.translatable("screen.logos_dimension.fx.limit_note"), rx, gy + 9, DIM);
    }

    // ---------------------------------------------------------------- 告示与传闻

    private void news(GuiGraphicsExtractor g, dev.logos.dimension.economy.MarketPayload data, int mouseX, int mouseY) {
        int nx = x0 + 8, ny = y0 + 166, nw = 222;
        g.text(font, Component.translatable("screen.logos_dimension.fx.news"), nx, ny - 10 + 4, 0xFFE8C77A);
        ny += 4;
        if (data == null) return;
        var items = data.news();
        int shown = 0;
        for (int i = items.size() - 1; i >= 0 && shown < 6; i--, shown++) {
            var n = items.get(i);
            var line = EconomyClient.when(n.time(), true).copy().append(" ").append(EconomyClient.news(n));
            int ly = ny + shown * 10;
            // 中文没有空格，按词换行会把整句挤到下一行；这里按字截断，写不下的悬停看全文
            boolean clipped = font.width(line) > nw;
            var shownText = clipped ? net.minecraft.network.chat.FormattedText.composite(font.substrByWidth(line, nw - font.width("…")), net.minecraft.network.chat.FormattedText.of("…")) : line;
            g.text(font, net.minecraft.locale.Language.getInstance().getVisualOrder(shownText), nx, ly, 0xFFD8D0C0);
            if (clipped && mouseX >= nx && mouseX < nx + nw && mouseY >= ly - 1 && mouseY < ly + 9)
                g.setTooltipForNextFrame(font, font.split(line, 220), mouseX, mouseY);
        }
    }
}
