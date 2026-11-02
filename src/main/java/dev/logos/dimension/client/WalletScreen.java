package dev.logos.dimension.client;

import dev.logos.dimension.economy.Currency;
import dev.logos.dimension.economy.EconomyActionPayload;
import dev.logos.dimension.economy.Money;
import dev.logos.dimension.economy.Purse;
import dev.logos.dimension.economy.WalletContents;
import dev.logos.dimension.economy.WalletItem;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;

/**
 * 万国钱囊面板：每种货币一行，写余额和按当前汇率折合的金冠；可选面额按一枚或一组取出，
 * 🔒 锁住的货币结账时不会被自动换汇。底部存入背包散币、开关捡钱自动入囊。
 */
public final class WalletScreen extends Screen {
    private static final int W = 350, ROW = 30, TOP = 22;
    private static final int H = TOP + ROW * 4 + 40;
    private final int hand;
    private final int[] denomination = new int[Currency.values().length];
    private final List<Button> denomButtons = new ArrayList<>(), lockButtons = new ArrayList<>();
    private Button autoButton;
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
        denomButtons.clear(); lockButtons.clear();
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

    @Override
    public void removed() { EconomyClient.send(EconomyActionPayload.CLOSE, hand, 0, 0, 0); }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        var contents = contents();
        g.fill(x0, y0, x0 + W, y0 + H, 0xE0161218);
        g.outline(x0, y0, W, H, 0xFFB89A5A);
        g.text(font, title, x0 + 8, y0 + 7, 0xFFE8C77A);
        for (var c : Currency.values()) {
            int i = c.ordinal();
            var d = c.denominations.get(denomination[i]);
            denomButtons.get(i).setMessage(Component.translatable(c.coinKey(d)));
            boolean locked = contents.locked().contains(c);
            lockButtons.get(i).setMessage(Component.literal(locked ? "🔒" : "🔓"));
            lockButtons.get(i).setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.translatable(locked ? "screen.logos_dimension.wallet.locked" : "screen.logos_dimension.wallet.unlocked")));
        }
        autoButton.setMessage(Component.translatable(contents.autoCollect() ? "screen.logos_dimension.wallet.auto_on" : "screen.logos_dimension.wallet.auto_off"));
        super.extractRenderState(g, mouseX, mouseY, a);

        double total = 0;
        for (var c : Currency.values()) {
            int y = y0 + TOP + c.ordinal() * ROW;
            if (c.ordinal() > 0) g.horizontalLine(x0 + 6, x0 + W - 7, y, 0x40B89A5A);
            long amount = contents.get(c);
            double cents = amount * EconomyClient.rate(c);
            total += cents;
            g.text(font, Component.translatable(c.key()), x0 + 8, y + 5, 0xFFE8C77A);
            var full = Money.format(c, amount);
            var balance = font.split(full, W - 166);
            g.text(font, balance.getFirst(), x0 + 8, y + 16, 0xFFE8E0D0);
            if (balance.size() > 1 && mouseX >= x0 + 8 && mouseX < x0 + W - 158 && mouseY >= y + 15 && mouseY < y + 25)
                g.setTooltipForNextFrame(font, full, mouseX, mouseY);
            if (c != Currency.IMPERIAL && amount > 0) {
                var worth = Component.literal("≈ ").append(Money.top(Currency.IMPERIAL, cents));
                g.text(font, worth, x0 + W - 156 - font.width(worth), y + 5, 0xFF8C8478);
            }
        }
        var sum = Component.translatable("screen.logos_dimension.wallet.total").append(Money.top(Currency.IMPERIAL, total));
        g.text(font, sum, x0 + 8, y0 + H - 38, 0xFFE8E0D0);
    }
}
