package dev.logos.dimension.client.firearm;

import dev.logos.dimension.firearm.FirearmPayloads;
import dev.logos.dimension.firearm.Firearms;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * 弹药轮盘：拿着铳长按 R 打开，背包里每种弹药一格（图标与总数），指向一种、松开 R（或点一下）就选中它并换弹。
 * 版式照 Logos 的术式轮盘：一圈至多 {@link #PER_PAGE} 格，多了用滚轮翻页；中间写指着的那种叫什么、背包里有几发。
 */
public final class AmmoWheelScreen extends Screen {
    static final int PER_PAGE = 10;
    private static final int BOX = 24;
    private final List<Firearms.Kind> kinds;
    private final ItemStack current;
    private int page, hovered = -1;

    public AmmoWheelScreen(List<Firearms.Kind> kinds, ItemStack current) {
        super(Component.translatable("gui.logos_dimension.ammo_wheel.title"));
        this.kinds = kinds;
        this.current = current;
        for (int i = 0; i < kinds.size(); i++) if (ItemStack.isSameItemSameComponents(kinds.get(i).ammo(), current)) page = i / PER_PAGE;
    }

    private int pages() { return Math.max(1, (kinds.size() + PER_PAGE - 1) / PER_PAGE); }
    private int onPage() { return Math.min(PER_PAGE, kinds.size() - page * PER_PAGE); }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        int cx = width / 2, cy = height / 2, n = onPage();
        int radius = Math.max(60, Math.min(width, height) / 3);
        hovered = hoveredIndex(mouseX - cx, mouseY - cy, n);
        for (int k = 0; k < n; k++) {
            var kind = kinds.get(page * PER_PAGE + k);
            double ang = -Math.PI / 2 + Math.PI * 2 * k / n;
            int x = cx + (int) (Math.cos(ang) * radius), y = cy + (int) (Math.sin(ang) * radius);
            boolean chosen = ItemStack.isSameItemSameComponents(kind.ammo(), current);
            g.fill(x - BOX / 2, y - BOX / 2, x + BOX / 2, y + BOX / 2, k == hovered ? 0xE0B07A3A : chosen ? 0xC0E8B84A : 0xB0181822);
            g.outline(x - BOX / 2, y - BOX / 2, BOX, BOX, chosen ? 0xFFF0D080 : 0xFFE6E2D3);
            g.item(kind.ammo(), x - 8, y - 8);
            g.centeredText(font, "×" + kind.count(), x, y + BOX / 2 + 3, k == hovered ? 0xFFFFFFFF : 0xFFCFCFCF);
        }
        int show = hovered >= 0 ? page * PER_PAGE + hovered : -1;
        if (show >= 0) {
            var kind = kinds.get(show);
            g.fill(cx - 70, cy - 16, cx + 70, cy + 16, 0xC0101018);
            g.outline(cx - 70, cy - 16, 140, 32, 0xFFE0A050);
            g.centeredText(font, kind.ammo().getHoverName().getString(), cx, cy - 10, 0xFFE8B84A);
            g.centeredText(font, "×" + kind.count(), cx, cy + 2, 0xFFCFCFCF);
        }
        if (pages() > 1) g.centeredText(font, (page + 1) + " / " + pages(), cx, cy + radius + BOX, 0xFF9F9FAF);
    }

    /** 指着哪一格：离开中心以后按角度（照术式轮盘）。 */
    private static int hoveredIndex(double dx, double dy, int n) {
        if (n == 0 || dx * dx + dy * dy < 30 * 30) return -1;
        double ang = Math.atan2(dy, dx) + Math.PI / 2;
        double step = Math.PI * 2 / n;
        return (int) Math.floorMod(Math.round(ang / step), n);
    }

    @Override public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (pages() > 1 && scrollY != 0) page = Math.floorMod(page + (scrollY < 0 ? 1 : -1), pages());
        return true;
    }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) { confirm(); return true; }
    @Override public boolean keyReleased(KeyEvent event) {
        if (FirearmClient.RELOAD.matches(event)) { confirm(); return true; }
        return super.keyReleased(event);
    }

    private void confirm() {
        if (hovered >= 0) ClientPacketDistributor.sendToServer(new FirearmPayloads.Select(kinds.get(page * PER_PAGE + hovered).ammo()));
        onClose();
    }
}
