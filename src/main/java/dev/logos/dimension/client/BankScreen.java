package dev.logos.dimension.client;

import dev.logos.dimension.economy.Bank;
import dev.logos.dimension.economy.BankOrderPayload;
import dev.logos.dimension.economy.BankPayload;
import dev.logos.dimension.economy.Currency;
import dev.logos.dimension.economy.EconomyActionPayload;
import dev.logos.dimension.economy.FxMarket;
import dev.logos.dimension.economy.Margin;
import dev.logos.dimension.economy.Money;
import dev.logos.dimension.economy.Purse;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * 钱庄面板，分两页：
 * <ul>
 *   <li>账户：四种货币的存款（帝国币为负表示借款）、授信额度、存取，以及强平止损等通知。</li>
 *   <li>杠杆：K 线上画出持仓的开仓价、爆仓价、止损止盈；右边下单并预览爆仓价和隔夜利息；底部是持仓列表，可逐笔平仓。</li>
 * </ul>
 * 价格输入与显示都按「一枚最大面额值多少银元」，和兑换台一致。
 */
public final class BankScreen extends Screen {
    private static final int W = 400, H = 236, CHART_W = 220, CHART_H = 84, ROWS = 5;
    private static final int PANEL = 0xE0161218, EDGE = 0xFFB89A5A, GOLD = 0xFFE8C77A, TEXT = 0xFFE8E0D0, DIM = CandleChart.DIM;
    private static final int RED = 0xFFE07060, LIQ = 0xFFB070E0, STOP = 0xFF6FA8DC, TAKE = 0xFFE0C060;
    private final BlockPos pos;
    private boolean leverageMode, wide;
    private Currency account = Currency.IMPERIAL, shown = Currency.FOX;
    private int side = 1, scroll;
    private EditBox amount, margin, leverage, stop, take;
    private Button accountButton, leverageButton, currencyButton, sideButton, openButton, rangeButton;
    private final List<Button> tabs = new ArrayList<>(), closes = new ArrayList<>();
    private int x0, y0;

    public BankScreen(BlockPos pos) {
        super(Component.translatable("block.logos_dimension.bank_counter"));
        this.pos = pos;
    }

    @Override public boolean isPauseScreen() { return false; }

    private static BankPayload bank() { return EconomyClient.bank; }

    private void order(int action, int currency, int side, int leverage, long amount, long id, double stop, double take) {
        ClientPacketDistributor.sendToServer(new BankOrderPayload(action, currency, side, leverage, amount, id, stop, take));
    }

    @Override
    protected void init() {
        x0 = (width - W) / 2; y0 = (height - H) / 2;
        tabs.clear(); closes.clear();
        accountButton = addRenderableWidget(Button.builder(Component.translatable("screen.logos_dimension.bank.account"), b -> mode(false))
                .bounds(x0 + W - 108, y0 + 3, 50, 14).build());
        leverageButton = addRenderableWidget(Button.builder(Component.translatable("screen.logos_dimension.bank.leverage"), b -> mode(true))
                .bounds(x0 + W - 56, y0 + 3, 50, 14).build());
        if (leverageMode) initLeverage(); else initAccount();
    }

    void mode(boolean leverage) { leverageMode = leverage; rebuildWidgets(); }

    private void initAccount() {
        int y = y0 + 130;
        currencyButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            account = Currency.values()[(account.ordinal() + 1) % Currency.values().length];
        }).bounds(x0 + 8, y, 70, 16).build());
        amount = addRenderableWidget(new EditBox(font, x0 + 82, y, 100, 16, Component.translatable("screen.logos_dimension.fx.amount")));
        amount.setFilter(s -> s.matches("\\d{0,9}(\\.\\d{0,3})?"));
        amount.setHint(Component.translatable("screen.logos_dimension.fx.amount"));
        addRenderableWidget(Button.builder(Component.translatable("screen.logos_dimension.bank.deposit"),
                b -> { long a = typed(amount, account); if (a > 0) order(BankOrderPayload.DEPOSIT, account.ordinal(), 0, 0, a, 0, 0, 0); })
                .bounds(x0 + 186, y, 48, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.logos_dimension.bank.withdraw"),
                b -> { long a = typed(amount, account); if (a > 0) order(BankOrderPayload.WITHDRAW, account.ordinal(), 0, 0, a, 0, 0, 0); })
                .bounds(x0 + 238, y, 48, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.logos_dimension.bank.deposit_all"),
                b -> { for (var c : Currency.values()) order(BankOrderPayload.DEPOSIT, c.ordinal(), 0, 0, 0, 0, 0, 0); })
                .bounds(x0 + 290, y, W - 298, 16).build());
    }

    private void initLeverage() {
        for (int i = 0; i < Currency.TRADED.size(); i++) {
            var c = Currency.TRADED.get(i);
            tabs.add(addRenderableWidget(Button.builder(Component.translatable(c.key()), b -> shown = c).bounds(x0 + 8 + i * 54, y0 + 20, 52, 16).build()));
        }
        rangeButton = addRenderableWidget(Button.builder(Component.empty(), b -> wide = !wide).bounds(x0 + 8 + CHART_W - 54, y0 + 20, 54, 16).build());
        int rx = x0 + 236, rw = W - 244;
        sideButton = addRenderableWidget(Button.builder(Component.empty(), b -> side = -side).bounds(rx, y0 + 20, rw, 16).build());
        margin = box(rx, y0 + 40, rw, "screen.logos_dimension.bank.margin", "\\d{0,9}(\\.\\d{0,3})?");
        addRenderableWidget(Button.builder(Component.literal("-"), b -> bump(-1)).bounds(rx, y0 + 58, 18, 16).build());
        leverage = box(rx + 20, y0 + 58, rw - 40, "screen.logos_dimension.bank.leverage_hint", "\\d{0,3}");
        addRenderableWidget(Button.builder(Component.literal("+"), b -> bump(1)).bounds(rx + rw - 18, y0 + 58, 18, 16).build());
        stop = box(rx, y0 + 76, (rw - 4) / 2, "screen.logos_dimension.bank.stop", "\\d{0,9}(\\.\\d{0,4})?");
        take = box(rx + (rw - 4) / 2 + 4, y0 + 76, (rw - 4) / 2, "screen.logos_dimension.bank.take", "\\d{0,9}(\\.\\d{0,4})?");
        openButton = addRenderableWidget(Button.builder(Component.translatable("screen.logos_dimension.bank.open"), b -> submit())
                .bounds(rx, y0 + 124, rw, 16).build());
        for (int i = 0; i < ROWS; i++) {
            int row = i;
            closes.add(addRenderableWidget(Button.builder(Component.translatable("screen.logos_dimension.bank.close"), b -> {
                var list = positions();
                if (scroll + row < list.size()) order(BankOrderPayload.CLOSE, 0, 0, 0, 0, list.get(scroll + row).id(), 0, 0);
            }).bounds(x0 + W - 46, y0 + 154 + i * 16, 38, 14).build()));
        }
    }

    private EditBox box(int x, int y, int w, String hint, String pattern) {
        var box = addRenderableWidget(new EditBox(font, x, y, w, 16, Component.translatable(hint)));
        box.setFilter(s -> s.matches(pattern));
        box.setHint(Component.translatable(hint));
        return box;
    }

    private int playerMax() { return bank() == null ? Margin.PLAYER_MAX : bank().playerMax(); }

    private void bump(int delta) {
        int now = lev();
        leverage.setValue(String.valueOf(Math.clamp(now + delta, 1, playerMax())));
    }

    private int lev() {
        try { return leverage.getValue().isEmpty() ? 1 : Integer.parseInt(leverage.getValue()); } catch (NumberFormatException e) { return 1; }
    }

    /** 输入按最大面额写，换算成最小单位。 */
    private static long typed(EditBox box, Currency c) { return Math.round(number(box) * c.top().value()); }

    private static double number(EditBox box) {
        try { String s = box.getValue(); return s.isEmpty() || s.equals(".") ? 0 : Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
    }

    /** 价格框（银元每枚最大面额）换成中间价（铜分每最小单位）；空着为 0。 */
    private double price(EditBox box) { return number(box) * 100 / shown.top().value(); }

    private void submit() {
        long m = typed(margin, Currency.IMPERIAL);
        int l = lev();
        if (m <= 0 || l < 1 || l > playerMax()) return;
        order(BankOrderPayload.OPEN, shown.ordinal(), side, l, m, 0, price(stop), price(take));
    }

    private List<Margin.Position> positions() { return bank() == null ? List.of() : bank().positions(); }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (leverageMode && scrollY != 0) scroll = Math.clamp(scroll + (scrollY < 0 ? 1 : -1), 0, Math.max(0, positions().size() - ROWS));
        return true;
    }

    @Override
    public void removed() { EconomyClient.send(EconomyActionPayload.CLOSE, 0, 0, 0, 0); }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        var data = EconomyClient.latest;
        long clock = data == null ? 0 : data.clock();
        boolean open = FxMarket.open(clock);
        g.fill(x0, y0, x0 + W, y0 + H, PANEL);
        g.outline(x0, y0, W, H, EDGE);
        var head = Component.empty().append(title).append("  ").append(EconomyClient.when(clock, false)).append(" · ")
                .append(Component.translatable(open ? "screen.logos_dimension.fx.open" : "screen.logos_dimension.fx.closed"));
        g.text(font, head, x0 + 8, y0 + 7, GOLD);
        accountButton.active = leverageMode;
        leverageButton.active = !leverageMode;
        if (leverageMode) leverageWidgets(open); else currencyButton.setMessage(Component.translatable(account.key()));
        super.extractRenderState(g, mouseX, mouseY, a);
        if (leverageMode) leveragePage(g, data, mouseX, mouseY); else accountPage(g, mouseX, mouseY);
    }

    // ---------------------------------------------------------------- 账户页

    private void accountPage(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        var b = bank();
        if (b == null) return;
        double net = 0;
        for (var c : Currency.values()) {
            int y = y0 + 26 + c.ordinal() * 22;
            long v = b.balance(c);
            double cents = v * EconomyClient.rate(c);
            net += cents;
            g.text(font, Component.translatable(c.key()), x0 + 8, y, GOLD);
            MutableComponent text = v < 0
                    ? Component.literal("−").append(Money.format(c, -v)).append(" ").append(Component.translatable("screen.logos_dimension.bank.loan"))
                    : Money.format(c, v);
            g.text(font, text, x0 + 70, y, v < 0 ? RED : TEXT);
            if (c != Currency.IMPERIAL && v != 0) {
                var worth = Component.literal("≈ ").append(Money.top(Currency.IMPERIAL, cents));
                g.text(font, worth, x0 + W - 8 - font.width(worth), y, DIM);
            }
            if (c.ordinal() < 3) g.horizontalLine(x0 + 6, x0 + W - 7, y + 14, 0x40B89A5A);
        }
        // 净值连同持仓按现价能拿回的钱一起算，否则刚开仓就像亏光了
        for (var p : b.positions()) net += Margin.payout(p, EconomyClient.rate(p.currency()));
        long debt = Math.max(0, -b.balance(Currency.IMPERIAL));
        var credit = Component.translatable("screen.logos_dimension.bank.credit", Money.top(Currency.IMPERIAL, b.credit()),
                Money.top(Currency.IMPERIAL, debt), Money.top(Currency.IMPERIAL, net));
        g.text(font, credit, x0 + 8, y0 + 116, debt > 0 ? RED : DIM);
        if (minecraft.player != null) {
            var held = Component.translatable("screen.logos_dimension.fx.held").append(Money.format(account, Purse.available(minecraft.player, account)));
            g.text(font, held, x0 + 8, y0 + 150, DIM);
        }
        notices(g, b, x0 + 8, y0 + 166, mouseX, mouseY);
    }

    private void notices(GuiGraphicsExtractor g, BankPayload b, int x, int y, int mouseX, int mouseY) {
        g.text(font, Component.translatable("screen.logos_dimension.bank.notices"), x, y, GOLD);
        var list = b.notices();
        if (list.isEmpty()) { g.text(font, Component.translatable("screen.logos_dimension.bank.no_notices"), x, y + 11, DIM); return; }
        for (int i = 0; i < 5 && i < list.size(); i++) {
            var n = list.get(list.size() - 1 - i);
            var line = EconomyClient.when(n.time(), true).copy().append(" ").append(Bank.text(n.key(), n.args()));
            clipped(g, line, x, y + 11 + i * 10, W - 16, n.key().endsWith("liquidate") ? RED : TEXT, mouseX, mouseY);
        }
    }

    /** 一行写不下就按字截断加省略号，悬停看全文。 */
    private void clipped(GuiGraphicsExtractor g, Component line, int x, int y, int w, int color, int mouseX, int mouseY) {
        boolean cut = font.width(line) > w;
        FormattedText text = cut ? FormattedText.composite(font.substrByWidth(line, w - font.width("…")), FormattedText.of("…")) : line;
        g.text(font, Language.getInstance().getVisualOrder(text), x, y, color);
        if (cut && mouseX >= x && mouseX < x + w && mouseY >= y - 1 && mouseY < y + 9) g.setTooltipForNextFrame(font, font.split(line, 260), mouseX, mouseY);
    }

    // ---------------------------------------------------------------- 杠杆页

    private void leverageWidgets(boolean open) {
        for (int i = 0; i < tabs.size(); i++) tabs.get(i).active = Currency.TRADED.get(i) != shown;
        rangeButton.setMessage(Component.translatable(wide ? "screen.logos_dimension.fx.range_wide" : "screen.logos_dimension.fx.range_near"));
        sideButton.setMessage(Component.translatable(side > 0 ? "screen.logos_dimension.bank.long" : "screen.logos_dimension.bank.short")
                .withStyle(side > 0 ? ChatFormatting.RED : ChatFormatting.GREEN));
        int l = lev();
        openButton.active = open && typed(margin, Currency.IMPERIAL) > 0 && l >= 1 && l <= playerMax();
        var list = positions();
        scroll = Math.clamp(scroll, 0, Math.max(0, list.size() - ROWS));
        for (int i = 0; i < ROWS; i++) { closes.get(i).visible = scroll + i < list.size(); closes.get(i).active = open; }
    }

    private void leveragePage(GuiGraphicsExtractor g, dev.logos.dimension.economy.MarketPayload data, int mouseX, int mouseY) {
        int cx = x0 + 8, cy = y0 + 40;
        double rate = EconomyClient.rate(shown);
        var marks = new ArrayList<CandleChart.Mark>();
        for (var p : positions()) {
            if (p.currency() != shown) continue;
            marks.add(new CandleChart.Mark(p.entry(), GOLD, Component.literal("#" + p.id())));
            marks.add(new CandleChart.Mark(Margin.liquidationRate(p), LIQ, Component.translatable("screen.logos_dimension.bank.liq_short")));
            if (p.stop() > 0) marks.add(new CandleChart.Mark(p.stop(), STOP, Component.translatable("screen.logos_dimension.bank.stop")));
            if (p.take() > 0) marks.add(new CandleChart.Mark(p.take(), TAKE, Component.translatable("screen.logos_dimension.bank.take")));
        }
        // 下单预览：开仓价、爆仓价、隔夜利息
        long m = typed(margin, Currency.IMPERIAL);
        int l = lev();
        int rx = x0 + 236, py = y0 + 96;
        if (m > 0 && l >= 1 && l <= Margin.SUPPORTED_MAX) {
            double notional = (double) m * l;
            double entry = Margin.entryPrice(shown, side, notional, rate);
            var preview = new Margin.Position(0, shown, side, m, l, entry, notional / entry, 0, price(stop), price(take), 0);
            double liq = Margin.liquidationRate(preview);
            marks.add(new CandleChart.Mark(liq, 0xA0B070E0, Component.translatable("screen.logos_dimension.bank.liq_short")));
            double unit = shown.top().value() / 100.0;
            g.text(font, Component.translatable("screen.logos_dimension.bank.notional").append(Money.top(Currency.IMPERIAL, notional)), rx, py, DIM);
            g.text(font, Component.translatable("screen.logos_dimension.bank.entry_liq", CandleChart.fmt(entry * unit), CandleChart.fmt(liq * unit)), rx, py + 9, DIM);
            double night = Margin.overnight(preview);
            g.text(font, Component.translatable("screen.logos_dimension.bank.overnight", Bank.crownsText(night)), rx, py + 18, night >= 0 ? CandleChart.UP : CandleChart.DOWN);
            if (l > playerMax()) g.text(font, Component.translatable("message.logos_dimension.bank.leverage", playerMax()), rx, py + 27, RED);
            if (bank() != null && bank().balance(Currency.IMPERIAL) - m < -bank().credit())
                g.text(font, Component.translatable("message.logos_dimension.bank.credit"), rx, py + 27, RED);
            else if (bank() != null && bank().balance(Currency.IMPERIAL) < m)
                g.text(font, Component.translatable("screen.logos_dimension.bank.will_borrow"), rx, py + 27, 0xFFE0A060);
        }
        CandleChart.draw(g, font, data, shown, wide, cx, cy, CHART_W, CHART_H, mouseX, mouseY, marks);
        CandleChart.quote(g, font, data, shown, cx, cy + CHART_H + 4);
        positionsList(g, mouseX, mouseY);
    }

    private void positionsList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        var list = positions();
        double total = 0;
        for (var p : list) total += Margin.pnl(p, EconomyClient.rate(p.currency())) + p.accrued();
        var head = Component.translatable("screen.logos_dimension.bank.positions", list.size()).append("  ")
                .append(Component.translatable("screen.logos_dimension.bank.pnl")).append(Bank.crownsText(total));
        g.text(font, head, x0 + 8, y0 + 144, GOLD);
        if (list.isEmpty()) { g.text(font, Component.translatable("screen.logos_dimension.bank.no_positions"), x0 + 8, y0 + 158, DIM); return; }
        for (int i = 0; i < ROWS && scroll + i < list.size(); i++) {
            var p = list.get(scroll + i);
            double rate = EconomyClient.rate(p.currency()), unit = p.currency().top().value() / 100.0;
            double pnl = Margin.pnl(p, rate) + p.accrued();
            var line = Component.literal("#" + p.id() + " ").append(Component.translatable(p.currency().key())).append(" ")
                    .append(Component.translatable(p.side() > 0 ? "bank.side.long" : "bank.side.short")
                            .withStyle(p.side() > 0 ? ChatFormatting.RED : ChatFormatting.GREEN))
                    .append(" " + p.leverage() + "× ")
                    .append(Component.translatable("screen.logos_dimension.bank.row", Money.top(Currency.IMPERIAL, p.margin()),
                            CandleChart.fmt(p.entry() * unit), CandleChart.fmt(Margin.liquidationRate(p) * unit)))
                    .append(" ").append(Bank.crownsText(pnl).withStyle(pnl >= 0 ? ChatFormatting.RED : ChatFormatting.GREEN));
            clipped(g, line, x0 + 8, y0 + 157 + i * 16, W - 60, TEXT, mouseX, mouseY);
        }
    }
}
