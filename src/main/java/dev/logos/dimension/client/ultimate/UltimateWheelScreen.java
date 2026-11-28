package dev.logos.dimension.client.ultimate;

import dev.logos.dimension.client.companion.CompanionStyle;
import dev.logos.dimension.client.companion.CompanionUi;
import dev.logos.dimension.ultimate.UltimatePayloads;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * 号令轮盘（规范 2.1，docs/ULTIMATES.md「按键」）：按住 V 打开，在场的同伴一位一格（两位左右各一、三位三等分、四位上下左右），
 * 鼠标甩到谁、松开 V（或点一下）就令她放；指针在中间的圈里松手是取消。不暂停世界。
 * <ul>
 *   <li>能放：外圈亮着她的主色；指着的那一格放大，下面写奥义的名字和要花的魔力（魔力不够时是红的）；</li>
 *   <li>这一轮放过了：外圈暖金、右下角一枚金印，锁着；</li>
 *   <li>冷却中：整格压暗，盖一块随冷却转走的扇形。</li>
 * </ul>
 */
public final class UltimateWheelScreen extends Screen {
    /** 中间这一圈里松手是取消（界面像素）。 */
    static final float DEAD = 18;
    private int hovered = -1;

    public UltimateWheelScreen() {
        super(Component.translatable("gui.logos_dimension.ultimate_wheel.title"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        g.fill(0, 0, width, height, 0x38000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        List<UltimatePayloads.Member> members = UltimateClient.status().members();
        int n = members.size();
        if (n == 0) return;
        float cx = width / 2f, cy = height / 2f;
        float radius = Math.max(52, Math.min(width, height) / 5f);
        hovered = index(mouseX - cx, mouseY - cy, n);
        float since = UltimateClient.since(a);
        CompanionUi.ring(g, cx, cy, DEAD, 1, 0x60FFFFFF);
        for (int k = 0; k < n; k++) {
            UltimatePayloads.Member m = members.get(k);
            CompanionStyle st = CompanionStyle.of(m.id());
            double ang = start(n) + Math.PI * 2 * k / n;
            float x = cx + (float) Math.cos(ang) * radius, y = cy + (float) Math.sin(ang) * radius;
            boolean on = k == hovered;
            float r = on ? 26 : 22;
            float left = Math.max(0, m.left() - since);
            boolean cooling = left > 0;
            if (on) CompanionUi.glow(g, x, y, r + 8, CompanionUi.alpha(m.released() ? UltimateHud.GOLD : st.accent(), .07f), 5);
            CompanionUi.disc(g, x, y, r, CompanionUi.alpha(st.night0(), .9f));
            if (m.released()) CompanionUi.ring(g, x, y, r, 2, UltimateHud.GOLD);
            else CompanionUi.ring(g, x, y, r, on ? 2.5f : 2, cooling ? CompanionUi.alpha(st.accent(), .35f) : st.accent());
            st.crest().draw(g, x, y, r * .6f, cooling || m.released() ? CompanionUi.alpha(st.accent(), .4f) : st.accent());
            if (cooling && m.cooldown() > 0) UltimateHud.pie(g, x, y, r - 2, left / m.cooldown(), 0x99000000);
            if (m.released()) CompanionUi.diamond(g, Math.round(x + r * .62f), Math.round(y + r * .62f), 5, UltimateHud.GOLD);
        }
        if (hovered >= 0) {
            UltimatePayloads.Member m = members.get(hovered);
            CompanionStyle st = CompanionStyle.of(m.id());
            boolean usable = m.ready() && m.left() - since <= 0;
            int below = Math.round(cy + radius + 34);
            g.centeredText(font, Component.translatable("ultimate.logos_dimension." + m.id() + ".name"), Math.round(cx), below,
                    usable ? st.accent() : CompanionUi.alpha(st.accent(), .5f));
            if (!m.released()) g.centeredText(font, Component.translatable("gui.logos_dimension.ultimate_wheel.cost", m.cost()), Math.round(cx), below + 12,
                    m.affordable() ? 0xFFCFCFCF : UltimateHud.RED);
        }
    }

    /** 第一格在哪：两位时左右各一（从左边起），其余从正上方起顺时针。 */
    static double start(int n) {
        return n == 2 ? Math.PI : -Math.PI / 2;
    }

    /** 指着哪一格：离开中间那一圈以后按角度。 */
    static int index(double dx, double dy, int n) {
        if (n == 0 || dx * dx + dy * dy < DEAD * DEAD) return -1;
        double step = Math.PI * 2 / n;
        return Math.floorMod(Math.round((Math.atan2(dy, dx) - start(n)) / step), n);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        confirm();
        return true;
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (UltimateClient.KEY.matches(event)) {
            confirm();
            return true;
        }
        return super.keyReleased(event);
    }

    /** 松手：指着谁就令谁放，在中间就算了。 */
    void confirm() {
        List<UltimatePayloads.Member> members = UltimateClient.status().members();
        if (hovered >= 0 && hovered < members.size()) UltimateClient.order(members.get(hovered).id());
        onClose();
    }

    /** 测试用：指着第几格松手。 */
    public void pick(int index) {
        hovered = index;
        confirm();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }
}
