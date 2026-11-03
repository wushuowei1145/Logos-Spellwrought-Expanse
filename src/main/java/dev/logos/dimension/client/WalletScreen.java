package dev.logos.dimension.client;

import dev.logos.dimension.economy.BankOrderPayload;
import dev.logos.dimension.economy.Currency;
import dev.logos.dimension.economy.EconomyActionPayload;
import dev.logos.dimension.economy.Margin;
import dev.logos.dimension.economy.Money;
import dev.logos.dimension.economy.Orders;
import dev.logos.dimension.economy.Purse;
import dev.logos.dimension.economy.WalletContents;
import dev.logos.dimension.economy.WalletItem;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;

/**
 * 万国钱囊面板，分三页：
 * <ul>
 *   <li>钱囊：每种货币一行，写余额和按当前汇率折合的金冠；可选面额按一枚或一组取出，🔒 锁住的货币结账时不会被自动换汇。</li>
 *   <li>行情：随身看 K 线、报价和传闻，设价格提醒。</li>
 *   <li>持仓：随身看钱庄账户总览、持仓盈亏和爆仓价、挂单，可以撤单；开平仓还是要回钱庄。</li>
 * </ul>
 * 后两页底下可以开关屏幕角落的行情条。
 */
public final class WalletScreen extends Screen {
    private static final int W = 350, ROW = 30, TOP = 22, H = 200, ROWS = 9, ROW_H = 12;
    private static final int PURSE = 0, MARKET = 1, POSITIONS = 2;
    private final int hand;
    private final int[] denomination = new int[Currency.values().length];
    private final List<Button> denomButtons = new ArrayList<>(), lockButtons = new ArrayList<>(), pageButtons = new ArrayList<>(),
            tabs = new ArrayList<>(), cancelButtons = new ArrayList<>();
    private Button autoButton, tickerButton;
    private EditBox alertPrice;
    private int page, scroll;
    private Currency shown = Currency.FOX;
    private int x0, y0;

    public WalletScreen(int hand) {
        super(Component.translatable("item.logos_dimension.wallet"));
        this.hand = hand;
        for (var c : Currency.values()) denomination[c.ordinal()] = c.denominations.size() - 1;
    }

    @Override public boolean isPauseScreen() { return false; }

    private WalletContents contents() {
        if (minecraft.player == null) return WalletContents.EMPTY;
        var stack = minecraft.player.getItemInHand(InteractionHand.values()[hand]);
        return stack.getItem() instanceof WalletItem ? Purse.contents(stack) : WalletContents.EMPTY;
    }

    @Override
    protected void init() {
        x0 = (width - W) / 2; y0 = (height - H) / 2;
        denomButtons.clear(); lockButtons.clear(); pageButtons.clear(); tabs.clear(); cancelButtons.clear();
        String[] names = {"screen.logos_dimension.wallet.page_purse", "screen.logos_dimension.wallet.page_market", "screen.logos_dimension.bank.positions_page"};
        for (int i = 0; i < 3; i++) {
            int p = i;
            pageButtons.add(addRenderableWidget(Button.builder(Component.translatable(names[i]), b -> { page = p; rebuildWidgets(); })
                    .bounds(x0 + W - 140 + i * 45, y0 + 3, 43, 14).build()));
        }
        switch (page) {
            case MARKET -> initMarket();
            case POSITIONS -> initPositions();
            default -> initPurse();
        }
        if (page != PURSE) tickerButton = addRenderableWidget(Button.builder(Component.empty(), b -> TickerHud.toggle())
                .bounds(x0 + 8, y0 + H - 24, 120, 16).build());
    }

    private void initPurse() {
        for (var c : Currency.values()) {
            int y = y0 + TOP + c.ordinal() * ROW + 6;
            int i = c.ordinal();
            denomButtons.add(addRenderableWidget(Button.builder(Component.empty(),
                    b -> denomination[i] = (denomination[i] + 1) % c.denominations.size()).bounds(x0 + W - 150, y, 54, 18).build()));
            addRenderableWidget(Button.builder(Component.literal("×1"),
                    b -> EconomyClient.send(EconomyActionPayload.WITHDRAW, hand, i, denomination[i], 1)).bounds(x0 + W - 94, y, 24, 18).build());
            addRenderableWidget(Button.builder(Component.literal("×64"),
                    b -> EconomyClient.send(EconomyActionPayload.WITHDRAW, hand, i, denomination[i], 64)).bounds(x0 + W - 68, y, 30, 18).build());
            lockButtons.add(addRenderableWidget(Button.builder(Component.empty(),
                    b -> EconomyClient.send(EconomyActionPayload.LOCK, hand, i, 0, 0)).bounds(x0 + W - 34, y, 26, 18).build()));
        }
        int by = y0 + H - 26;
        addRenderableWidget(Button.builder(Component.translatable("screen.logos_dimension.wallet.deposit"),
                b -> EconomyClient.send(EconomyActionPayload.DEPOSIT, hand, 0, 0, 0)).bounds(x0 + 8, by, 110, 18).build());
        autoButton = addRenderableWidget(Button.builder(Component.empty(),
                b -> EconomyClient.send(EconomyActionPayload.AUTO, hand, 0, 0, 0)).bounds(x0 + 122, by, 110, 18).build());
    }

    private void initMarket() {
        for (int i = 0; i < Currency.TRADED.size(); i++) {
            var c = Currency.TRADED.get(i);
            tabs.add(addRenderableWidget(Button.builder(Component.translatable(c.key()), b -> shown = c).bounds(x0 + 8 + i * 56, y0 + 22, 54, 14).build()));
        }
        int y = y0 + 148;
        alertPrice = addRenderableWidget(new EditBox(font, x0 + 8, y, 110, 14, Component.translatable("screen.logos_dimension.wallet.alert_price")));
        alertPrice.setFilter(s -> s.matches("\\d{0,9}(\\.\\d{0,4})?"));
        alertPrice.setHint(Component.translatable("screen.logos_dimension.wallet.alert_price"));
        addRenderableWidget(Button.builder(Component.translatable("screen.logos_dimension.wallet.alert"), b -> {
            double v;
            try { v = Double.parseDouble(alertPrice.getValue()); } catch (NumberFormatException e) { return; }
            if (v <= 0) return;
            AccountViews.order(BankOrderPayload.ALERT, shown.ordinal(), 0, 0, 0, 0, v * 100 / shown.top().value(), 0, 0, 0);
            alertPrice.setValue("");
        }).bounds(x0 + 122, y, 64, 14).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.logos_dimension.wallet.clear_alerts"), b -> {
            if (EconomyClient.bank != null) for (var al : EconomyClient.bank.alerts()) AccountViews.order(BankOrderPayload.UNALERT, 0, 0, 0, 0, al.id(), 0, 0, 0, 0);
        }).bounds(x0 + 190, y, 64, 14).build());
    }

    private void initPositions() {
        for (int i = 0; i < ROWS; i++) {
            int row = i;
            cancelButtons.add(addRenderableWidget(Button.builder(Component.translatable("screen.logos_dimension.wallet.cancel_short"), b -> {
                var list = rows();
                if (scroll + row < list.size() && list.get(scroll + row) instanceof Orders.Pending o)
                    AccountViews.order(BankOrderPayload.CANCEL, 0, 0, 0, 0, o.id(), 0, 0, 0, 0);
            }).bounds(x0 + W - 30, y0 + 34 + i * ROW_H, 22, 11).build()));
        }
    }

    /** 场景测试用：切页。 */
    void page(int page) { this.page = page; rebuildWidgets(); }

    /** 持仓页的行：先持仓、再挂单。 */
    private List<Object> rows() {
        var out = new ArrayList<Object>();
        var b = EconomyClient.bank;
        if (b == null) return out;
        out.addAll(b.positions());
        out.addAll(b.orders());
        return out;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (page == POSITIONS && scrollY != 0) scroll = Math.clamp(scroll + (scrollY < 0 ? 1 : -1), 0, Math.max(0, rows().size() - ROWS));
        return true;
    }

    @Override
    public void removed() { EconomyClient.send(EconomyActionPayload.CLOSE, hand, 0, 0, 0); }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        g.fill(x0, y0, x0 + W, y0 + H, 0xE0161218);
        g.outline(x0, y0, W, H, 0xFFB89A5A);
        g.text(font, title, x0 + 8, y0 + 7, AccountViews.GOLD);
        for (int i = 0; i < pageButtons.size(); i++) pageButtons.get(i).active = i != page;
        if (tickerButton != null && page != PURSE)
            tickerButton.setMessage(Component.translatable(TickerHud.enabled() ? "screen.logos_dimension.wallet.ticker_on" : "screen.logos_dimension.wallet.ticker_off"));
        switch (page) {
            case MARKET -> { for (int i = 0; i < tabs.size(); i++) tabs.get(i).active = Currency.TRADED.get(i) != shown; }
            case POSITIONS -> {
                var list = rows();
                scroll = Math.clamp(scroll, 0, Math.max(0, list.size() - ROWS));
                for (int i = 0; i < ROWS; i++) cancelButtons.get(i).visible = scroll + i < list.size() && list.get(scroll + i) instanceof Orders.Pending;
            }
            default -> purseWidgets();
        }
        super.extractRenderState(g, mouseX, mouseY, a);
        switch (page) {
            case MARKET -> marketPage(g, mouseX, mouseY);
            case POSITIONS -> positionsPage(g, mouseX, mouseY);
            default -> pursePage(g, mouseX, mouseY);
        }
    }

    private void purseWidgets() {
        var contents = contents();
        for (var c : Currency.values()) {
            int i = c.ordinal();
            denomButtons.get(i).setMessage(Component.translatable(c.coinKey(c.denominations.get(denomination[i]))));
            boolean locked = contents.locked().contains(c);
            lockButtons.get(i).setMessage(Component.literal(locked ? "🔒" : "🔓"));
            lockButtons.get(i).setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.translatable(locked ? "screen.logos_dimension.wallet.locked" : "screen.logos_dimension.wallet.unlocked")));
        }
        autoButton.setMessage(Component.translatable(contents.autoCollect() ? "screen.logos_dimension.wallet.auto_on" : "screen.logos_dimension.wallet.auto_off"));
    }

    private void pursePage(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        var contents = contents();
        double total = 0;
        for (var c : Currency.values()) {
            int y = y0 + TOP + c.ordinal() * ROW;
            if (c.ordinal() > 0) g.horizontalLine(x0 + 6, x0 + W - 7, y, 0x40B89A5A);
            long amount = contents.get(c);
            double cents = amount * EconomyClient.rate(c);
            total += cents;
            g.text(font, Component.translatable(c.key()), x0 + 8, y + 5, AccountViews.GOLD);
            var full = Money.format(c, amount);
            var balance = font.split(full, W - 166);
            g.text(font, balance.getFirst(), x0 + 8, y + 16, AccountViews.TEXT);
            if (balance.size() > 1 && mouseX >= x0 + 8 && mouseX < x0 + W - 158 && mouseY >= y + 15 && mouseY < y + 25)
                g.setTooltipForNextFrame(font, full, mouseX, mouseY);
            if (c != Currency.IMPERIAL && amount > 0) {
                var worth = Component.literal("≈ ").append(Money.top(Currency.IMPERIAL, cents));
                g.text(font, worth, x0 + W - 156 - font.width(worth), y + 5, AccountViews.DIM);
            }
        }
        var sum = Component.translatable("screen.logos_dimension.wallet.total").append(Money.top(Currency.IMPERIAL, total));
        g.text(font, sum, x0 + 8, y0 + H - 38, AccountViews.TEXT);
    }

    private void marketPage(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        var data = EconomyClient.latest;
        int cx = x0 + 8, cw = W - 16;
        var marks = new ArrayList<CandleChart.Mark>();
        var b = EconomyClient.bank;
        if (b != null) {
            for (var p : b.positions()) if (p.currency() == shown) marks.add(new CandleChart.Mark(Margin.liquidationRate(p), 0xFFB070E0, Component.literal("#" + p.id())));
            for (var al : b.alerts()) if (al.currency() == shown) marks.add(new CandleChart.Mark(al.price(), 0xFF6FD3E0, Component.translatable("screen.logos_dimension.wallet.alert_mark")));
        }
        CandleChart.draw(g, font, data, shown, false, cx, y0 + 40, cw, 58, mouseX, mouseY, marks);
        CandleChart.quote(g, font, data, shown, cx, y0 + 101);
        if (data != null) {
            var news = data.news();
            for (int i = 0; i < 3 && i < news.size(); i++) {
                var n = news.get(news.size() - 1 - i);
                var line = EconomyClient.when(n.time(), true).copy().append(" ").append(EconomyClient.news(n));
                AccountViews.clipped(g, font, line, cx, y0 + 114 + i * 10, cw, AccountViews.TEXT, mouseX, mouseY);
            }
        }
        var alerts = Component.translatable("screen.logos_dimension.wallet.alerts");
        if (b == null || b.alerts().isEmpty()) alerts.append(Component.translatable("screen.logos_dimension.wallet.no_alerts"));
        else for (var al : b.alerts())
            alerts.append(" ").append(Component.translatable(al.currency().key())).append((al.above() ? "≥" : "≤") + AccountViews.silver(al.currency(), al.price()));
        AccountViews.clipped(g, font, alerts, cx, y0 + 165, cw, AccountViews.DIM, mouseX, mouseY);
    }

    private void positionsPage(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        var b = EconomyClient.bank;
        if (b == null) return;
        AccountViews.clipped(g, font, AccountViews.summaryLine(b), x0 + 8, y0 + 22, W - 16, AccountViews.TEXT, mouseX, mouseY);
        var list = rows();
        if (list.isEmpty()) g.text(font, Component.translatable("screen.logos_dimension.bank.no_positions"), x0 + 8, y0 + 36, AccountViews.DIM);
        for (int i = 0; i < ROWS && scroll + i < list.size(); i++) {
            var row = list.get(scroll + i);
            int y = y0 + 35 + i * ROW_H;
            if (row instanceof Margin.Position p) {
                boolean danger = Margin.equity(p, EconomyClient.rate(p.currency())) <= p.margin() * Margin.WARNING;
                AccountViews.clipped(g, font, AccountViews.position(p), x0 + 8, y, W - 42, danger ? AccountViews.RED : AccountViews.TEXT, mouseX, mouseY);
            } else if (row instanceof Orders.Pending o) {
                AccountViews.clipped(g, font, AccountViews.order(o), x0 + 8, y, W - 42, AccountViews.TEXT, mouseX, mouseY);
            }
        }
        g.text(font, Component.translatable("screen.logos_dimension.wallet.positions_note"), x0 + 136, y0 + H - 20, AccountViews.DIM);
    }
}
