package dev.logos.dimension.client;

import dev.logos.dimension.economy.Bank;
import dev.logos.dimension.economy.BankOrderPayload;
import dev.logos.dimension.economy.BankPayload;
import dev.logos.dimension.economy.Currency;
import dev.logos.dimension.economy.EconomyActionPayload;
import dev.logos.dimension.economy.FxMarket;
import dev.logos.dimension.economy.Margin;
import dev.logos.dimension.economy.Money;
import dev.logos.dimension.economy.Orders;
import dev.logos.dimension.economy.Purse;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * 钱庄面板，分三页，顶上一行是账户总览（总权益、可用、浮动盈亏、已用保证金、借款）：
 * <ul>
 *   <li>账户：四种货币的存款（帝国币为负表示借款）、授信额度、存取，以及通知。</li>
 *   <li>杠杆：K 线上画出持仓的开仓价、爆仓价、止损止盈；右边市价开仓或挂单，带移动止损，
 *       预览爆仓价、约可承受的反向波动和隔夜利息；左下是告示与传闻。</li>
 *   <li>持仓：持仓和挂单列表，点一行管理：追加保证金、加仓、平四分之一 / 一半 / 全部、改止损止盈和移动止损、撤单；一键全平。</li>
 * </ul>
 * 价格输入与显示都按「一枚最大面额值多少银元」，和兑换台一致。
 */
public final class BankScreen extends Screen {
    private static final int W = 400, H = 236, CHART_W = 220, CHART_H = 90, ROWS = 9, ROW_H = 12;
    private static final int PANEL = 0xE0161218, EDGE = 0xFFB89A5A, GOLD = AccountViews.GOLD, TEXT = AccountViews.TEXT, DIM = AccountViews.DIM;
    private static final int RED = AccountViews.RED, LIQ = 0xFFB070E0, STOP = 0xFF6FA8DC, TAKE = 0xFFE0C060;
    private static final int ACCOUNT = 0, LEVERAGE = 1, POSITIONS = 2;
    private final BlockPos pos;
    private int page, side = 1, scroll;
    /** 持仓页右边按钮是按哪种行建的：0 无、1 持仓、2 挂单。 */
    private int built;
    private boolean wide, limit;
    private long selected = -1;
    private Currency account = Currency.IMPERIAL, shown = Currency.FOX;
    private EditBox amount, margin, leverage, stop, take, trail, price;
    private Button currencyButton, sideButton, typeButton, openButton, rangeButton;
    private final List<Button> pageButtons = new ArrayList<>(), tabs = new ArrayList<>();
    private int x0, y0;

    public BankScreen(BlockPos pos) {
        super(Component.translatable("block.logos_dimension.bank_counter"));
        this.pos = pos;
    }

    @Override public boolean isPauseScreen() { return false; }

    private static BankPayload bank() { return EconomyClient.bank; }

    private static void order(int action, int currency, int side, int leverage, long amount, long id, double price, double stop, double take, double trail) {
        AccountViews.order(action, currency, side, leverage, amount, id, price, stop, take, trail);
    }

    @Override
    protected void init() {
        x0 = (width - W) / 2; y0 = (height - H) / 2;
        pageButtons.clear(); tabs.clear();
        String[] names = {"account", "leverage", "positions_page"};
        for (int i = 0; i < 3; i++) {
            int p = i;
            pageButtons.add(addRenderableWidget(Button.builder(Component.translatable("screen.logos_dimension.bank." + names[i]), b -> page(p))
                    .bounds(x0 + W - 140 + i * 45, y0 + 3, 43, 14).build()));
        }
        switch (page) {
            case LEVERAGE -> initLeverage();
            case POSITIONS -> initPositions();
            default -> initAccount();
        }
    }

    void page(int page) { this.page = page; rebuildWidgets(); }
    /** 场景测试用：在持仓页选中一行。 */
    void select(long id) { selected = id; rebuildWidgets(); }
    /** 场景测试用：切到杠杆页或账户页。 */
    void mode(boolean leverage) { page(leverage ? LEVERAGE : ACCOUNT); }

    private EditBox box(int x, int y, int w, int h, String hint, String pattern) {
        var box = addRenderableWidget(new EditBox(font, x, y, w, h, Component.translatable(hint)));
        box.setFilter(s -> s.matches(pattern));
        box.setHint(Component.translatable(hint));
        return box;
    }
    private static final String MONEY = "\\d{0,9}(\\.\\d{0,3})?", PRICE = "\\d{0,9}(\\.\\d{0,4})?", PERCENT = "\\d{0,2}(\\.\\d{0,2})?";

    private void initAccount() {
        int y = y0 + 132;
        currencyButton = addRenderableWidget(Button.builder(Component.empty(), b -> account = Currency.values()[(account.ordinal() + 1) % Currency.values().length])
                .bounds(x0 + 8, y, 70, 16).build());
        amount = box(x0 + 82, y, 100, 16, "screen.logos_dimension.fx.amount", MONEY);
        addRenderableWidget(Button.builder(Component.translatable("screen.logos_dimension.bank.deposit"),
                b -> { long a = typed(amount, account); if (a > 0) order(BankOrderPayload.DEPOSIT, account.ordinal(), 0, 0, a, 0, 0, 0, 0, 0); })
                .bounds(x0 + 186, y, 48, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.logos_dimension.bank.withdraw"),
                b -> { long a = typed(amount, account); if (a > 0) order(BankOrderPayload.WITHDRAW, account.ordinal(), 0, 0, a, 0, 0, 0, 0, 0); })
                .bounds(x0 + 238, y, 48, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.logos_dimension.bank.deposit_all"),
                b -> { for (var c : Currency.values()) order(BankOrderPayload.DEPOSIT, c.ordinal(), 0, 0, 0, 0, 0, 0, 0, 0); })
                .bounds(x0 + 290, y, W - 298, 16).build());
    }

    private void initLeverage() {
        for (int i = 0; i < Currency.TRADED.size(); i++) {
            var c = Currency.TRADED.get(i);
            tabs.add(addRenderableWidget(Button.builder(Component.translatable(c.key()), b -> shown = c).bounds(x0 + 8 + i * 54, y0 + 32, 52, 14).build()));
        }
        rangeButton = addRenderableWidget(Button.builder(Component.empty(), b -> wide = !wide).bounds(x0 + 8 + CHART_W - 54, y0 + 32, 54, 14).build());
        int rx = x0 + 236, rw = W - 244;
        sideButton = addRenderableWidget(Button.builder(Component.empty(), b -> side = -side).bounds(rx, y0 + 32, rw, 14).build());
        typeButton = addRenderableWidget(Button.builder(Component.empty(), b -> limit = !limit).bounds(rx, y0 + 50, 50, 14).build());
        price = box(rx + 52, y0 + 50, rw - 52, 14, "screen.logos_dimension.bank.limit_price", PRICE);
        margin = box(rx, y0 + 66, rw, 14, "screen.logos_dimension.bank.margin", MONEY);
        addRenderableWidget(Button.builder(Component.literal("-"), b -> bump(-1)).bounds(rx, y0 + 82, 18, 14).build());
        leverage = box(rx + 20, y0 + 82, rw - 40, 14, "screen.logos_dimension.bank.leverage_hint", "\\d{0,3}");
        addRenderableWidget(Button.builder(Component.literal("+"), b -> bump(1)).bounds(rx + rw - 18, y0 + 82, 18, 14).build());
        stop = box(rx, y0 + 98, (rw - 2) / 2, 14, "screen.logos_dimension.bank.stop", PRICE);
        take = box(rx + (rw - 2) / 2 + 2, y0 + 98, (rw - 2) / 2, 14, "screen.logos_dimension.bank.take", PRICE);
        trail = box(rx, y0 + 114, rw, 14, "screen.logos_dimension.bank.trail", PERCENT);
        openButton = addRenderableWidget(Button.builder(Component.empty(), b -> submit()).bounds(rx, y0 + 186, rw, 16).build());
    }

    private void initPositions() {
        addRenderableWidget(Button.builder(Component.translatable("screen.logos_dimension.bank.close_all"),
                b -> order(BankOrderPayload.CLOSE_ALL, 0, 0, 0, 0, 0, 0, 0, 0, 0)).bounds(x0 + 8, y0 + 146, 80, 14).build());
        int rx = x0 + 258, rw = W - 266;
        var p = selectedPosition();
        var o = selectedOrder();
        built = p != null ? 1 : o != null ? 2 : 0;
        if (p != null) {
            amount = box(rx, y0 + 84, rw, 14, "screen.logos_dimension.bank.amount_crowns", MONEY);
            int half = (rw - 2) / 2;
            addRenderableWidget(Button.builder(Component.translatable("screen.logos_dimension.bank.add_margin"),
                    b -> { long a = typed(amount, Currency.IMPERIAL); if (a > 0) order(BankOrderPayload.ADD_MARGIN, 0, 0, 0, a, p.id(), 0, 0, 0, 0); })
                    .bounds(rx, y0 + 100, half, 14).build());
            addRenderableWidget(Button.builder(Component.translatable("screen.logos_dimension.bank.increase"),
                    b -> { long a = typed(amount, Currency.IMPERIAL); if (a > 0) order(BankOrderPayload.INCREASE, 0, 0, Math.min(p.leverage(), playerMax()), a, p.id(), 0, 0, 0, 0); })
                    .bounds(rx + half + 2, y0 + 100, half, 14).build());
            int third = (rw - 4) / 3;
            int[] permille = {250, 500, 1000};
            String[] labels = {"screen.logos_dimension.bank.close_quarter", "screen.logos_dimension.bank.close_half", "screen.logos_dimension.bank.close"};
            for (int i = 0; i < 3; i++) {
                int pm = permille[i];
                addRenderableWidget(Button.builder(Component.translatable(labels[i]), b -> order(BankOrderPayload.REDUCE, 0, 0, 0, pm, p.id(), 0, 0, 0, 0))
                        .bounds(rx + i * (third + 2), y0 + 116, third, 14).build());
            }
            stop = box(rx, y0 + 132, half, 14, "screen.logos_dimension.bank.stop", PRICE);
            take = box(rx + half + 2, y0 + 132, half, 14, "screen.logos_dimension.bank.take", PRICE);
            trail = box(rx, y0 + 148, half, 14, "screen.logos_dimension.bank.trail", PERCENT);
            if (p.stop() > 0) stop.setValue(String.format(java.util.Locale.ROOT, "%.2f", p.stop() * p.currency().top().value() / 100));
            if (p.take() > 0) take.setValue(String.format(java.util.Locale.ROOT, "%.2f", p.take() * p.currency().top().value() / 100));
            if (p.trail() > 0) trail.setValue(String.format(java.util.Locale.ROOT, "%.1f", p.trail() * 100));
            addRenderableWidget(Button.builder(Component.translatable("screen.logos_dimension.bank.modify"),
                    b -> order(BankOrderPayload.MODIFY, 0, 0, 0, 0, p.id(), 0, price(stop, p.currency()), price(take, p.currency()), number(trail) / 100))
                    .bounds(rx + half + 2, y0 + 148, half, 14).build());
        } else if (o != null) {
            addRenderableWidget(Button.builder(Component.translatable("screen.logos_dimension.bank.cancel"),
                    b -> order(BankOrderPayload.CANCEL, 0, 0, 0, 0, o.id(), 0, 0, 0, 0)).bounds(rx, y0 + 84, rw, 14).build());
        }
    }

    private int playerMax() { return bank() == null ? Margin.PLAYER_MAX : bank().playerMax(); }

    private void bump(int delta) { leverage.setValue(String.valueOf(Math.clamp(lev() + delta, 1, playerMax()))); }

    private int lev() {
        try { return leverage.getValue().isEmpty() ? 1 : Integer.parseInt(leverage.getValue()); } catch (NumberFormatException e) { return 1; }
    }

    /** 输入按最大面额写，换算成最小单位。 */
    private static long typed(EditBox box, Currency c) { return Math.round(number(box) * c.top().value()); }

    private static double number(EditBox box) {
        if (box == null) return 0;
        try { String s = box.getValue(); return s.isEmpty() || s.equals(".") ? 0 : Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
    }

    /** 价格框（银元每枚最大面额）换成中间价（铜分每最小单位）；空着为 0。 */
    private static double price(EditBox box, Currency c) { return number(box) * 100 / c.top().value(); }

    private void submit() {
        long m = typed(margin, Currency.IMPERIAL);
        int l = lev();
        if (m <= 0 || l < 1 || l > playerMax()) return;
        double t = number(trail) / 100;
        if (limit) order(BankOrderPayload.LIMIT_OPEN, shown.ordinal(), side, l, m, 0, price(price, shown), price(stop, shown), price(take, shown), t);
        else order(BankOrderPayload.OPEN, shown.ordinal(), side, l, m, 0, 0, price(stop, shown), price(take, shown), t);
    }

    // ---------------------------------------------------------------- 持仓与挂单列表

    /** 列表里的一行：持仓或挂单。 */
    private record Row(Margin.Position position, Orders.Pending order) {
        long id() { return position != null ? position.id() : order.id(); }
    }

    private List<Row> rows() {
        var b = bank();
        var out = new ArrayList<Row>();
        if (b == null) return out;
        for (var p : b.positions()) out.add(new Row(p, null));
        for (var o : b.orders()) out.add(new Row(null, o));
        return out;
    }

    private Margin.Position selectedPosition() {
        if (bank() == null) return null;
        for (var p : bank().positions()) if (p.id() == selected) return p;
        return null;
    }

    private Orders.Pending selectedOrder() {
        if (bank() == null) return null;
        for (var o : bank().orders()) if (o.id() == selected) return o;
        return null;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (page == POSITIONS && event.x() >= x0 + 8 && event.x() < x0 + 252 && event.y() >= y0 + 34 && event.y() < y0 + 34 + ROWS * ROW_H) {
            int i = scroll + (int) ((event.y() - y0 - 34) / ROW_H);
            var list = rows();
            if (i < list.size()) { selected = list.get(i).id(); rebuildWidgets(); return true; }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (page == POSITIONS && scrollY != 0) scroll = Math.clamp(scroll + (scrollY < 0 ? 1 : -1), 0, Math.max(0, rows().size() - ROWS));
        return true;
    }

    @Override
    public void removed() { EconomyClient.send(EconomyActionPayload.CLOSE, 0, 0, 0, 0); }

    // ---------------------------------------------------------------- 绘制

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        var data = EconomyClient.latest;
        long clock = data == null ? 0 : data.clock();
        boolean open = FxMarket.open(clock);
        g.fill(x0, y0, x0 + W, y0 + H, PANEL);
        g.outline(x0, y0, W, H, EDGE);
        var head = Component.empty().append(title).append("  ").append(EconomyClient.when(clock, false)).append(" · ")
                .append(Component.translatable(open ? "screen.logos_dimension.fx.open" : "screen.logos_dimension.fx.closed"));
        AccountViews.clipped(g, font, head, x0 + 8, y0 + 7, W - 156, GOLD, mouseX, mouseY);
        for (int i = 0; i < pageButtons.size(); i++) pageButtons.get(i).active = i != page;
        // 所选那笔平掉了、或挂单成交变成了持仓：按钮要跟着换
        if (page == POSITIONS) {
            int kind = selectedPosition() != null ? 1 : selectedOrder() != null ? 2 : 0;
            if (kind == 0) selected = -1;
            if (kind != built) rebuildWidgets();
        }
        switch (page) {
            case LEVERAGE -> leverageWidgets(open);
            case ACCOUNT -> currencyButton.setMessage(Component.translatable(account.key()));
            default -> {}
        }
        super.extractRenderState(g, mouseX, mouseY, a);
        if (bank() != null) AccountViews.clipped(g, font, AccountViews.summaryLine(bank()), x0 + 8, y0 + 21, W - 16, TEXT, mouseX, mouseY);
        switch (page) {
            case LEVERAGE -> leveragePage(g, data, mouseX, mouseY);
            case POSITIONS -> positionsPage(g, mouseX, mouseY);
            default -> accountPage(g, mouseX, mouseY);
        }
    }

    private void accountPage(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        var b = bank();
        if (b == null) return;
        for (var c : Currency.values()) {
            int y = y0 + 36 + c.ordinal() * 20;
            long v = b.balance(c);
            g.text(font, Component.translatable(c.key()), x0 + 8, y, GOLD);
            MutableComponent text = v < 0
                    ? Component.literal("−").append(Money.format(c, -v)).append(" ").append(Component.translatable("screen.logos_dimension.bank.loan"))
                    : Money.format(c, v);
            g.text(font, text, x0 + 70, y, v < 0 ? RED : TEXT);
            if (c != Currency.IMPERIAL && v != 0) {
                var worth = Component.literal("≈ ").append(Money.top(Currency.IMPERIAL, v * EconomyClient.rate(c)));
                g.text(font, worth, x0 + W - 8 - font.width(worth), y, DIM);
            }
            if (c.ordinal() < 3) g.horizontalLine(x0 + 6, x0 + W - 7, y + 13, 0x40B89A5A);
        }
        long debt = Math.max(0, -b.balance(Currency.IMPERIAL));
        var credit = Component.translatable("screen.logos_dimension.bank.credit", Money.top(Currency.IMPERIAL, b.credit()),
                Money.top(Currency.IMPERIAL, debt), Money.top(Currency.IMPERIAL, AccountViews.summary(b).equity()));
        g.text(font, credit, x0 + 8, y0 + 119, debt > 0 ? RED : DIM);
        if (minecraft.player != null) {
            var held = Component.translatable("screen.logos_dimension.fx.held").append(Money.format(account, Purse.available(minecraft.player, account)));
            g.text(font, held, x0 + 8, y0 + 152, DIM);
        }
        notices(g, b, x0 + 8, y0 + 166, 5, mouseX, mouseY);
    }

    private void notices(GuiGraphicsExtractor g, BankPayload b, int x, int y, int lines, int mouseX, int mouseY) {
        g.text(font, Component.translatable("screen.logos_dimension.bank.notices"), x, y, GOLD);
        var list = b.notices();
        if (list.isEmpty()) { g.text(font, Component.translatable("screen.logos_dimension.bank.no_notices"), x, y + 11, DIM); return; }
        for (int i = 0; i < lines && i < list.size(); i++) {
            var n = list.get(list.size() - 1 - i);
            var line = EconomyClient.when(n.time(), true).copy().append(" ").append(Bank.text(n.key(), n.args()));
            boolean bad = n.key().endsWith("liquidate") || n.key().endsWith("warning");
            AccountViews.clipped(g, font, line, x, y + 11 + i * 10, W - 16, bad ? RED : TEXT, mouseX, mouseY);
        }
    }

    private void leverageWidgets(boolean open) {
        for (int i = 0; i < tabs.size(); i++) tabs.get(i).active = Currency.TRADED.get(i) != shown;
        rangeButton.setMessage(Component.translatable(wide ? "screen.logos_dimension.fx.range_wide" : "screen.logos_dimension.fx.range_near"));
        sideButton.setMessage(Component.translatable(side > 0 ? "screen.logos_dimension.bank.long" : "screen.logos_dimension.bank.short")
                .withStyle(side > 0 ? ChatFormatting.RED : ChatFormatting.GREEN));
        typeButton.setMessage(Component.translatable(limit ? "screen.logos_dimension.bank.limit" : "screen.logos_dimension.bank.market"));
        price.visible = limit;
        openButton.setMessage(Component.translatable(limit ? "screen.logos_dimension.bank.place" : "screen.logos_dimension.bank.open"));
        int l = lev();
        long m = typed(margin, Currency.IMPERIAL);
        double at = limit && price(price, shown) > 0 ? price(price, shown) : EconomyClient.rate(shown);
        boolean ready = m > 0 && l >= 1 && l <= playerMax() && !Margin.tooDeep(shown, (double) m * l, at);
        openButton.active = ready && (limit ? price(price, shown) > 0 : open);
    }

    private void leveragePage(GuiGraphicsExtractor g, dev.logos.dimension.economy.MarketPayload data, int mouseX, int mouseY) {
        int cx = x0 + 8, cy = y0 + 50;
        double rate = EconomyClient.rate(shown);
        var marks = new ArrayList<CandleChart.Mark>();
        for (var p : bank() == null ? List.<Margin.Position>of() : bank().positions()) {
            if (p.currency() != shown) continue;
            marks.add(new CandleChart.Mark(p.entry(), GOLD, Component.literal("#" + p.id())));
            marks.add(new CandleChart.Mark(Margin.liquidationRate(p), LIQ, Component.translatable("screen.logos_dimension.bank.liq_short")));
            if (p.stop() > 0) marks.add(new CandleChart.Mark(p.stop(), STOP, Component.translatable("screen.logos_dimension.bank.stop")));
            if (p.take() > 0) marks.add(new CandleChart.Mark(p.take(), TAKE, Component.translatable("screen.logos_dimension.bank.take")));
        }
        for (var o : bank() == null ? List.<Orders.Pending>of() : bank().orders())
            if (o.currency() == shown) marks.add(new CandleChart.Mark(o.price(), 0xC0A0A0A0, Component.literal("#" + o.id())));
        // 下单预览：名义金额、开仓价与爆仓价、约可承受反向波动、隔夜利息
        long m = typed(margin, Currency.IMPERIAL);
        int l = lev();
        int rx = x0 + 236, py = y0 + 132;
        if (m > 0 && l >= 1 && l <= Margin.SUPPORTED_MAX) {
            double at = limit && price(price, shown) > 0 ? price(price, shown) : rate;
            double notional = (double) m * l;
            double entry = Margin.entryPrice(shown, side, notional, at);
            var preview = new Margin.Position(0, shown, side, m, l, entry, notional / entry, 0, price(stop, shown), price(take, shown), 0,
                    number(trail) / 100, at, false);
            double liq = Margin.liquidationRate(preview);
            marks.add(new CandleChart.Mark(liq, 0xA0B070E0, Component.translatable("screen.logos_dimension.bank.liq_short")));
            if (limit && at != rate) marks.add(new CandleChart.Mark(at, 0xC0E0E0E0, Component.translatable("screen.logos_dimension.bank.limit")));
            g.text(font, Component.translatable("screen.logos_dimension.bank.notional").append(Money.top(Currency.IMPERIAL, notional)), rx, py, DIM);
            g.text(font, Component.translatable("screen.logos_dimension.bank.entry_liq", AccountViews.silver(shown, entry), AccountViews.silver(shown, liq)), rx, py + 9, DIM);
            g.text(font, Component.translatable("screen.logos_dimension.bank.room", String.format("%.2f%%", Margin.room(preview, at) * 100)), rx, py + 18, DIM);
            double night = Margin.overnight(preview);
            g.text(font, Component.translatable("screen.logos_dimension.bank.overnight", Bank.crownsText(night)), rx, py + 27, night >= 0 ? CandleChart.UP : CandleChart.DOWN);
            var b = bank();
            if (l > playerMax()) g.text(font, Component.translatable("message.logos_dimension.bank.leverage", playerMax()), rx, py + 36, RED);
            else if (Margin.tooDeep(shown, notional, at)) g.text(font, Component.translatable("message.logos_dimension.bank.too_deep"), rx, py + 36, RED);
            else if (b != null && b.balance(Currency.IMPERIAL) - m < -b.credit()) g.text(font, Component.translatable("message.logos_dimension.bank.credit"), rx, py + 36, RED);
            else if (b != null && b.balance(Currency.IMPERIAL) < m) g.text(font, Component.translatable("screen.logos_dimension.bank.will_borrow"), rx, py + 36, 0xFFE0A060);
            else if (limit) g.text(font, Component.translatable("screen.logos_dimension.bank.limit_note"), rx, py + 36, DIM);
        }
        CandleChart.draw(g, font, data, shown, wide, cx, cy, CHART_W, CHART_H, mouseX, mouseY, marks);
        CandleChart.quote(g, font, data, shown, cx, cy + CHART_H + 4);
        // 左下：告示与传闻，杠杆主要靠它
        int ny = y0 + 158;
        g.text(font, Component.translatable("screen.logos_dimension.fx.news"), cx, ny, GOLD);
        if (data == null) return;
        var news = data.news();
        for (int i = 0; i < 6 && i < news.size(); i++) {
            var n = news.get(news.size() - 1 - i);
            var line = EconomyClient.when(n.time(), true).copy().append(" ").append(EconomyClient.news(n));
            AccountViews.clipped(g, font, line, cx, ny + 11 + i * 10, CHART_W, TEXT, mouseX, mouseY);
        }
    }

    private void positionsPage(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        var list = rows();
        scroll = Math.clamp(scroll, 0, Math.max(0, list.size() - ROWS));
        int lx = x0 + 8, lw = 244;
        g.fill(lx - 1, y0 + 33, lx + lw + 1, y0 + 35 + ROWS * ROW_H, 0x60080608);
        if (list.isEmpty()) g.text(font, Component.translatable("screen.logos_dimension.bank.no_positions"), lx + 2, y0 + 37, DIM);
        for (int i = 0; i < ROWS && scroll + i < list.size(); i++) {
            var row = list.get(scroll + i);
            int y = y0 + 34 + i * ROW_H;
            if (row.id() == selected) g.fill(lx - 1, y - 1, lx + lw + 1, y + ROW_H - 1, 0x50B89A5A);
            boolean danger = row.position != null && Margin.equity(row.position, EconomyClient.rate(row.position.currency())) <= row.position.margin() * Margin.WARNING;
            var line = row.position != null ? AccountViews.position(row.position) : AccountViews.order(row.order);
            AccountViews.clipped(g, font, line, lx + 1, y + 1, lw - 2, danger ? RED : TEXT, mouseX, mouseY);
        }
        if (list.size() > ROWS) g.text(font, (scroll + 1) + "–" + Math.min(list.size(), scroll + ROWS) + " / " + list.size(), lx + 92, y0 + 149, DIM);
        // 右边：所选那一行的详情
        int rx = x0 + 258, rw = W - 266, y = y0 + 34;
        var p = selectedPosition();
        var o = selectedOrder();
        if (p != null) {
            double rate = EconomyClient.rate(p.currency());
            var c = p.currency();
            g.text(font, Component.literal("#" + p.id() + " ").append(Component.translatable(c.key())).append(" ").append(AccountViews.side(p.side())), rx, y, GOLD);
            g.text(font, Component.translatable("screen.logos_dimension.bank.detail_margin", Money.top(Currency.IMPERIAL, p.margin()),
                    String.format("%.1f", p.effectiveLeverage())), rx, y + 10, TEXT);
            g.text(font, Component.translatable("screen.logos_dimension.bank.detail_price", AccountViews.silver(c, p.entry()), AccountViews.silver(c, rate)), rx, y + 20, TEXT);
            g.text(font, Component.translatable("screen.logos_dimension.bank.detail_liq", AccountViews.silver(c, Margin.liquidationRate(p)),
                    String.format("%.1f%%", Margin.room(p, rate) * 100)), rx, y + 30, LIQ);
            AccountViews.clipped(g, font, Component.translatable("screen.logos_dimension.bank.detail_interest", Bank.crownsText(p.accrued()),
                    Bank.crownsText(Margin.overnight(p))), rx, y + 40, rw, DIM, mouseX, mouseY);
        } else if (o != null) {
            AccountViews.clipped(g, font, AccountViews.order(o), rx, y, rw, TEXT, mouseX, mouseY);
            g.text(font, Component.translatable("screen.logos_dimension.bank.order_note"), rx, y + 12, DIM);
        } else {
            for (var line : font.split(Component.translatable("screen.logos_dimension.bank.pick"), rw)) { g.text(font, line, rx, y, DIM); y += 10; }
        }
        if (bank() != null) notices(g, bank(), x0 + 8, y0 + 166, 5, mouseX, mouseY);
    }
}
