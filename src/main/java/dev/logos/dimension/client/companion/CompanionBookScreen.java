package dev.logos.dimension.client.companion;

import dev.logos.dimension.companion.CompanionBook.Page;
import dev.logos.dimension.companion.CompanionSheet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 友人帐（{@link dev.logos.dimension.companion.CompanionBook}）：所有同伴一人一页排成一排（放不下就换行）。立过契的那一页画她本人的半身
 * （穿着你给她换的那一套，身后是她自己的纹，配色照她的对话和面板），下面是名字、羁绊段，最底下一个按钮：叫她出来、收回、或者她还在歇着；
 * 还没立契的是一扇黑窗，名字是「？？？」。叫出来、收回以后合上，好看她出来。
 */
public final class CompanionBookScreen extends Screen {
    static final int CARD_W = 112, CARD_H = 178, GAP = 12, WINDOW = 108, BUTTON_H = 16;
    /** 半身像：整个人是窗口高度的几倍（窗口里只看得到头和肩）、窗口正中对着身高的几成（脸）、镜头俯仰。 */
    static final float BUST = 1.95f, FOCUS = .8f, PITCH = -6;
    private static final int NIGHT0 = 0xFF1B1622, NIGHT1 = 0xFF0B0910, INK = 0xFFF2ECE2, DIM = 0xFFA79C8E, GOLD = 0xFFE3C27A;

    private List<Page> pages;
    private int sinceUpdate;
    private final Map<String, @Nullable LivingEntity> models = new HashMap<>();

    CompanionBookScreen(List<Page> pages) {
        super(Component.translatable("gui.logos_dimension.companion_book.title"));
        this.pages = pages;
    }

    /** 测试用：开着的友人帐。 */
    public static @Nullable CompanionBookScreen current() {
        return net.minecraft.client.Minecraft.getInstance().screen instanceof CompanionBookScreen b ? b : null;
    }

    public List<Page> pages() {
        return pages;
    }

    void update(List<Page> pages) {
        this.pages = pages;
        sinceUpdate = 0;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        sinceUpdate++;
        for (LivingEntity m : models.values()) if (m != null) m.tickCount++;
    }

    /** 还要歇多少秒（服务端给的是那一刻的，之后在这边倒数）。 */
    int rest(Page p) {
        return Math.max(0, p.rest() - sinceUpdate / 20);
    }

    // ---------------------------------------------------------------- 排版

    private int perRow() {
        return Math.max(1, Math.min(pages.size(), (width - 40 + GAP) / (CARD_W + GAP)));
    }

    private int top() {
        int rows = (pages.size() + perRow() - 1) / perRow();
        return Math.max(56, (height - rows * CARD_H - (rows - 1) * GAP) / 2 + 16);
    }

    private int cardX(int i) {
        int per = perRow(), row = i / per, inRow = Math.min(per, pages.size() - row * per);
        return (width - inRow * CARD_W - (inRow - 1) * GAP) / 2 + (i % per) * (CARD_W + GAP);
    }

    private int cardY(int i) {
        return top() + (i / perRow()) * (CARD_H + GAP);
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private boolean onButton(int i, double mx, double my) {
        return inside(mx, my, cardX(i) + 12, cardY(i) + CARD_H - 12 - BUTTON_H, CARD_W - 24, BUTTON_H);
    }

    // ---------------------------------------------------------------- 画

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        extractBlurredBackground(g);
        g.fillGradient(0, 0, width, height, CompanionUi.alpha(NIGHT0, .92f), CompanionUi.alpha(NIGHT1, .96f));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // 书名
        int ty = top() - 42;
        int tw = font.width(title) * 2;
        CompanionUi.bigText(g, font, title, (width - tw) / 2, ty, INK, 2, true);
        CompanionUi.fadeLine(g, width / 2 - 70, width / 2 + 70, ty + 22, CompanionUi.alpha(GOLD, .7f));
        for (int i = 0; i < pages.size(); i++) drawPage(g, i, mouseX, mouseY, partialTick);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void drawPage(GuiGraphicsExtractor g, int i, int mouseX, int mouseY, float partialTick) {
        Page p = pages.get(i);
        int x = cardX(i), y = cardY(i), wx0 = x + 8, wy0 = y + 8, wx1 = x + CARD_W - 8, wy1 = wy0 + WINDOW;
        if (!p.pact()) {
            // 还没立契：一扇黑窗
            g.fill(x, y, x + CARD_W, y + CARD_H, 0xB4000000);
            frame(g, x, y, x + CARD_W, y + CARD_H, CompanionUi.alpha(DIM, .18f));
            g.fill(wx0, wy0, wx1, wy1, 0xFF000000);
            frame(g, wx0, wy0, wx1, wy1, CompanionUi.alpha(DIM, .22f));
            centered(g, Component.translatable("gui.logos_dimension.companion_book.unknown"), x + CARD_W / 2, wy1 + 10, CompanionUi.alpha(DIM, .6f));
            return;
        }
        CompanionStyle style = CompanionStyle.of(p.id());
        boolean hover = inside(mouseX, mouseY, x, y, CARD_W, CARD_H);
        g.fill(x, y, x + CARD_W, y + CARD_H, style.card());
        frame(g, x, y, x + CARD_W, y + CARD_H, CompanionUi.alpha(style.accent(), hover ? .7f : .25f));
        g.fill(x, y, x + CARD_W, y + 1, CompanionUi.alpha(style.accent(), .6f));
        // 窗里：她的配色、身后她的纹，半身像
        g.fillGradient(wx0, wy0, wx1, wy1, style.night0(), style.night1());
        float cx = (wx0 + wx1) / 2f, cy = wy0 + WINDOW * .45f;
        CompanionUi.glow(g, cx, cy, WINDOW * .5f, CompanionUi.alpha(style.accent(), .05f), 8);
        style.crest().draw(g, cx, cy, WINDOW * .36f, CompanionUi.alpha(style.accent(), .16f));
        drawBust(g, p, wx0, wy0, wx1, wy1);
        frame(g, wx0, wy0, wx1, wy1, CompanionUi.alpha(style.gold(), .35f));
        // 名字、羁绊段（在外面就写「在你身边」）
        Component name = Component.translatable("talk.logos_dimension." + p.id() + ".name");
        centered(g, name, x + CARD_W / 2, wy1 + 8, style.ink());
        Component sub = p.out() ? Component.translatable("gui.logos_dimension.companion_book.with_you")
                : Component.translatable("gui.logos_dimension.companion.stage." + p.stage().toLowerCase(java.util.Locale.ROOT));
        centered(g, sub, x + CARD_W / 2, wy1 + 21, p.out() ? style.accent() : style.dim());
        // 按钮
        int bx = x + 12, by = y + CARD_H - 12 - BUTTON_H, bw = CARD_W - 24;
        int rest = rest(p);
        boolean on = onButton(i, mouseX, mouseY) && rest == 0;
        Component label = rest > 0 ? Component.translatable("gui.logos_dimension.companion_book.resting", rest)
                : Component.translatable("gui.logos_dimension.companion_book." + (p.out() ? "dismiss" : "call"));
        int bg = rest > 0 ? CompanionUi.alpha(style.dim(), .12f) : CompanionUi.alpha(on ? style.accent() : style.accentDeep(), on ? .55f : .32f);
        g.fill(bx, by, bx + bw, by + BUTTON_H, bg);
        frame(g, bx, by, bx + bw, by + BUTTON_H, CompanionUi.alpha(rest > 0 ? style.dim() : style.accent(), on ? .9f : .45f));
        centered(g, label, bx + bw / 2, by + 4, rest > 0 ? style.dim() : style.ink());
    }

    /** 她的半身像：同角色面板的展示（{@link CompanionStyle.Showcase}），放大到窗里只看得到头和肩。 */
    private void drawBust(GuiGraphicsExtractor g, Page p, int x0, int y0, int x1, int y1) {
        if (minecraft.level == null) return;
        String key = p.id() + "|" + p.outfit() + "|" + p.seals();
        LivingEntity model = models.computeIfAbsent(key, k -> CompanionStyle.of(p.id()).showcase().make().apply(minecraft.level, sheet(p)));
        if (model == null) return;
        CompanionStyle.Showcase show = CompanionStyle.of(p.id()).showcase();
        float body = 180 + show.yaw(), head = body + show.head(show.yaw());
        model.yBodyRot = model.yBodyRotO = body;
        model.setYRot(body);
        model.yRotO = body;
        model.setYHeadRot(head);
        model.yHeadRotO = head;
        model.setXRot(0);
        model.xRotO = 0;
        var renderer = minecraft.getEntityRenderDispatcher().getRenderer(model);
        EntityRenderState state = renderer.createRenderState(model, minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        state.shadowPieces.clear();
        state.outlineColor = 0;
        if (state instanceof LivingEntityRenderState l) {
            l.boundingBoxWidth /= l.scale;
            l.boundingBoxHeight /= l.scale;
            l.scale = 1;
        }
        float h = Math.max(.5f, state.boundingBoxHeight);
        float scale = (y1 - y0) * BUST / h;
        Quaternionf cam = new Quaternionf().rotateX(PITCH * Mth.DEG_TO_RAD);
        Quaternionf rot = new Quaternionf().rotateZ((float) Math.PI).mul(cam);
        g.entity(state, scale, new Vector3f(0, h * FOCUS, 0), rot, cam, x0, y0, x1, y1);
    }

    /** 画像用的档案：只有画她时要的（哪一位、穿哪一套、几枚契印）。 */
    private static CompanionSheet sheet(Page p) {
        return new CompanionSheet(-1, p.id(), p.outfit(), List.of(), List.of(), p.seals(), 0, p.stage(), 0, 0, 0, false, "adaptive", false, 0, 0, 0);
    }

    private void centered(GuiGraphicsExtractor g, Component text, int cx, int y, int color) {
        g.text(font, text, cx - font.width(text) / 2, y, color, false);
    }

    private static void frame(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
        g.fill(x0, y0, x1, y0 + 1, color);
        g.fill(x0, y1 - 1, x1, y1, color);
        g.fill(x0, y0, x0 + 1, y1, color);
        g.fill(x1 - 1, y0, x1, y1, color);
    }

    // ---------------------------------------------------------------- 操作

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            for (int i = 0; i < pages.size(); i++) {
                if (onButton(i, event.x(), event.y())) {
                    press(pages.get(i).id());
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** 按下 {@code companion} 那一页的按钮（测试也用）：立过契、没在歇着才算。 */
    public void press(String companion) {
        for (Page p : pages) {
            if (p.id().equals(companion) && p.pact() && rest(p) == 0) {
                CompanionBookClient.call(companion);
                return;
            }
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (CompanionBookClient.KEY.matches(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }
}
