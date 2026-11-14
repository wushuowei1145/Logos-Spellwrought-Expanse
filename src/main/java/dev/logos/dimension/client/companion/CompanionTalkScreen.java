package dev.logos.dimension.client.companion;

import dev.logos.dimension.companion.CompanionSheet;
import dev.logos.dimension.companion.CompanionTalk;
import dev.logos.dimension.companion.TalkMenu;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * 和同伴说话（docs/SHIRAUME_COMPANION.md 第 8.13 节）：画面下方一条渐暗的底，左边她的名字牌（纹 + 名字 + 一道渐隐的金线）和她说的话
 * （一个字一个字出来），右边一列选项（{@link TalkMenu}）。不暂停、不模糊，看得见她站在面前；状态栏先藏起来。
 *
 * <p>点一下：字还没出完就一下出完，还有下一句就翻到下一句，说完了才出选项。数字键 1–9 直接选；Esc 在下一层时回上一层，在最上面时关掉。
 * 通用的：配色和纹按同伴找（{@link CompanionStyle}），话和选项的文字在语言文件里。
 */
public final class CompanionTalkScreen extends Screen {
    /** 一秒出几个字；选项淡入多久（毫秒）；一行选项多高。 */
    static final float CPS = 38;
    static final int OPTIONS_FADE = 160, ROW = 15;

    private CompanionSheet sheet;
    private final CompanionStyle style;
    private final Deque<CompanionTalk.Line> pending = new ArrayDeque<>();
    /** 这一句（排好行的）、一共几个字、什么时候开始出字。 */
    private List<String> wrapped = List.of();
    private int chars;
    private long shownAt;
    private int wrapWidth = -1;
    private CompanionTalk.@Nullable Line line;
    private final List<List<TalkMenu.Option>> path = new ArrayList<>();
    /** 自己换到面板、包袱、或者是服务端让关的：关掉时不再告诉服务端「关了」。 */
    private boolean keep;
    /** 换到了角色面板（状态栏接着藏着，面板关上回到这里）。 */
    private boolean toPanel;
    private long optionsAt = -1;
    /** 选了一项、等她回话（等不到也不能一直藏着选项：{@link #WAIT_MAX} 毫秒后照样出）。 */
    private boolean waiting;
    private long waitingSince;
    static final int WAIT_MAX = 1500;

    CompanionTalkScreen(CompanionSheet sheet, List<CompanionTalk.Line> lines) {
        super(Component.translatable("talk.logos_dimension." + sheet.companion() + ".name"));
        this.sheet = sheet;
        this.style = CompanionStyle.of(sheet.companion());
        path.add(TalkMenu.of(sheet.companion()));
        pending.addAll(lines);
        next();
    }

    public CompanionSheet sheet() {
        return sheet;
    }

    // ---------------------------------------------------------------- 服务端来的

    /** 档案更新、她又说了话。 */
    void receive(CompanionSheet sheet, List<CompanionTalk.Line> lines) {
        this.sheet = sheet;
        if (lines.isEmpty()) return;
        waiting = false;
        boolean idle = line == null || done() && pending.isEmpty();
        pending.addAll(lines);
        if (idle) next();
    }

    /** 服务端让关（走远了、来了敌人、她走了）。 */
    void end() {
        keep = true;
        if (minecraft != null && minecraft.screen == this) onClose();
    }

    // ---------------------------------------------------------------- 一句一句

    private void next() {
        line = pending.poll();
        wrapWidth = -1;
        shownAt = Util.getMillis();
        optionsAt = -1;
    }

    private void wrap() {
        int width = textWidth();
        if (width == wrapWidth || line == null) return;
        wrapWidth = width;
        List<String> out = CompanionUi.wrap(font, text(line), width);
        wrapped = out;
        chars = 0;
        for (String s : out) chars += s.codePointCount(0, s.length());
    }

    private int shown() {
        return Math.min(chars, (int) ((Util.getMillis() - shownAt) * CPS / 1000));
    }

    private boolean done() {
        return line == null || shown() >= chars && wrapWidth > 0;
    }

    private void finish() {
        shownAt = Util.getMillis() - (long) (chars * 1000 / CPS) - 1;
    }

    /** 这一句的文字：参数以 # 开头的是语言键。 */
    static Component text(CompanionTalk.Line line) {
        Object[] args = new Object[line.args().size()];
        for (int i = 0; i < args.length; i++) {
            String a = line.args().get(i);
            args[i] = a.startsWith("#") ? Component.translatable(a.substring(1)) : a;
        }
        return Component.translatable(line.key(), args);
    }

    // ---------------------------------------------------------------- 选项

    private List<TalkMenu.Option> menu() {
        return path.get(path.size() - 1);
    }

    private boolean showOptions() {
        if (waiting && Util.getMillis() - waitingSince > WAIT_MAX) waiting = false;
        return done() && pending.isEmpty() && !waiting;
    }

    /** 选项上的字：先找这位同伴自己的，没有用通用的；「作战策略」带上现在的策略。 */
    Component label(TalkMenu.Option o) {
        String own = "talk.logos_dimension." + sheet.companion() + ".option." + o.id();
        String key = I18n.exists(own) ? own : "talk.logos_dimension.option." + o.id();
        Component moving = Component.translatable("talk.logos_dimension.state." + (sheet.staying() ? "stay" : "follow"));
        Component tactic = Component.translatable("talk.logos_dimension.option.tactic." + sheet.tactic());
        return Component.translatable(key, moving, tactic);
    }

    /** 选一项（测试也用）：按 ID 在这一层找。 */
    public boolean choose(String id) {
        for (TalkMenu.Option o : menu()) {
            if (o.id().equals(id)) {
                choose(o);
                return true;
            }
        }
        return false;
    }

    private void choose(TalkMenu.Option o) {
        switch (o.kind()) {
            case MENU -> {
                path.add(o.children());
                optionsAt = Util.getMillis();
            }
            case BACK -> back();
            case SERVER -> {
                if (TalkMenu.POUCH.equals(o.id())) {
                    keep = true;
                } else {
                    waiting = true;
                    waitingSince = Util.getMillis();
                }
                send(o.id());
            }
            case PANEL -> {
                keep = true;
                toPanel = true;
                minecraft.setScreen(new CompanionPanelScreen(this));
            }
            case CLOSE -> {
                keep = true;
                send(o.id());
                onClose();
            }
        }
    }

    private void back() {
        if (path.size() > 1) {
            path.remove(path.size() - 1);
            optionsAt = Util.getMillis();
        }
    }

    private void send(String option) {
        ClientPacketDistributor.sendToServer(new CompanionTalk.Pick(sheet.entity(), option));
    }

    /** 服务端又开了一次对话（看完包袱回来）：换下这一个时不告诉服务端「关了」。 */
    void replaced() {
        keep = true;
    }

    /** 从面板回来：之后关掉照常告诉服务端。 */
    void returned() {
        keep = false;
        toPanel = false;
    }

    // ---------------------------------------------------------------- 排版

    private int margin() {
        return Math.max(14, Math.round(width * .14f));
    }

    private int textWidth() {
        return Math.min(width - margin() * 2, 360);
    }

    private int textTop() {
        return height - 42;
    }

    private int optionWidth() {
        int w = 0;
        for (TalkMenu.Option o : menu()) w = Math.max(w, font.width(label(o)));
        return Mth.clamp(w + 34, 110, Math.round(width * .46f));
    }

    private int optionX() {
        return width - margin() - optionWidth() + 8;
    }

    private int optionY(int i) {
        return textTop() - 26 - (menu().size() - i) * (ROW + 2);
    }

    private int optionAt(double mx, double my) {
        if (!showOptions()) return -1;
        int x = optionX(), w = optionWidth();
        for (int i = 0; i < menu().size(); i++) {
            int y = optionY(i);
            if (mx >= x && mx < x + w && my >= y && my < y + ROW) return i;
        }
        return -1;
    }

    // ---------------------------------------------------------------- 画

    @Override
    protected void init() {
        CompanionTalkClient.hideGui(true);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 不模糊：下面四成一条渐暗的底，字和选项浮在上面。 */
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        g.fillGradient(0, Math.round(height * .5f), width, height, 0x00000000, CompanionUi.alpha(style.night1(), .9f));
        minecraft.gui.extractDeferredSubtitles();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        wrap();
        int x = margin(), y = textTop();
        // 名字牌：纹、名字、一道往右渐隐的金线
        style.crest().draw(g, x + 5, y - 13.5f, 5.5f, style.accent());
        Component name = Component.translatable("talk.logos_dimension." + sheet.companion() + ".name");
        g.text(font, name, x + 15, y - 17, style.accent(), true);
        Component epithet = Component.translatable("talk.logos_dimension." + sheet.companion() + ".epithet");
        g.text(font, epithet, x + 19 + font.width(name), y - 17, CompanionUi.alpha(style.dim(), .9f), true);
        CompanionUi.fadeLine(g, x, x + Math.min(textWidth(), 260), y - 5, CompanionUi.alpha(style.gold(), .8f));
        // 她说的话
        int left = shown();
        int ly = y + 2;
        for (String s : wrapped) {
            int n = s.codePointCount(0, s.length());
            String part = left >= n ? s : s.substring(0, s.offsetByCodePoints(0, Math.max(0, left)));
            g.text(font, Component.literal(part), x, ly, style.ink(), true);
            left -= n;
            ly += 11;
            if (left <= 0) break;
        }
        // 还有下一句：一个一闪一闪的小三角
        if (done() && !pending.isEmpty() && (Util.getMillis() / 400) % 2 == 0) {
            int tx = x + textWidth() - 6, ty = y + 26;
            for (int i = 0; i < 3; i++) g.fill(tx + i, ty + i, tx + 6 - i, ty + i + 1, style.accent());
        }
        if (showOptions()) {
            if (optionsAt < 0) optionsAt = Util.getMillis();
            drawOptions(g, mouseX, mouseY);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void drawOptions(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        float fade = Mth.clamp((Util.getMillis() - optionsAt) / (float) OPTIONS_FADE, 0, 1);
        int x = optionX(), w = optionWidth(), hover = optionAt(mouseX, mouseY);
        List<TalkMenu.Option> menu = menu();
        for (int i = 0; i < menu.size(); i++) {
            TalkMenu.Option o = menu.get(i);
            int y = optionY(i) + Math.round((1 - fade) * 4);
            boolean on = i == hover, current = TalkMenu.current(o, sheet);
            int bg = on ? CompanionUi.mix(style.card(), CompanionUi.alpha(style.accentDeep(), .55f), .5f) : style.card();
            g.fill(x, y, x + w, y + ROW, CompanionUi.alpha(bg, fade));
            g.fill(x, y, x + 2, y + ROW, CompanionUi.alpha(on ? style.accent() : CompanionUi.alpha(style.accentDeep(), .6f), fade));
            if (on) g.fill(x + 2, y, x + w, y + 1, CompanionUi.alpha(style.accent(), .35f * fade));
            int bullet = o.kind() == TalkMenu.Kind.BACK || o.kind() == TalkMenu.Kind.CLOSE ? style.dim() : style.accent();
            CompanionUi.diamond(g, x + 9, y + ROW / 2, 2, CompanionUi.alpha(bullet, fade));
            int color = o.kind() == TalkMenu.Kind.BACK ? style.dim() : current ? style.gold() : style.ink();
            g.text(font, label(o), x + 16, y + 4, CompanionUi.alpha(color, fade), true);
            if (current) g.text(font, Component.literal("✔"), x + w - 11, y + 4, CompanionUi.alpha(style.gold(), fade), true);
            else if (o.kind() == TalkMenu.Kind.MENU) g.text(font, Component.literal("›"), x + w - 9, y + 4, CompanionUi.alpha(style.dim(), fade), true);
        }
    }

    // ---------------------------------------------------------------- 操作

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != 0) return super.mouseClicked(event, doubleClick);
        int i = optionAt(event.x(), event.y());
        if (i >= 0) {
            choose(menu().get(i));
            return true;
        }
        advance();
        return true;
    }

    /** 测试用：跳到最后一句、出完。 */
    public void skip() {
        while (!pending.isEmpty()) next();
        wrap();
        finish();
    }

    /** 点一下：字出完、翻下一句（测试也用）。 */
    public void advance() {
        if (!done()) finish();
        else if (!pending.isEmpty()) next();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape()) {
            if (path.size() > 1) back();
            else onClose();
            return true;
        }
        int k = event.key();
        if (k >= 49 && k <= 57 && showOptions()) {
            int i = k - 49;
            if (i < menu().size()) choose(menu().get(i));
            return true;
        }
        if (k == 32 || k == 257 || k == 335) {
            advance();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void removed() {
        if (!keep) send(CompanionTalk.CLOSED);
        if (!toPanel) CompanionTalkClient.hideGui(false);
    }

    /** 测试用：这一层有哪些选项、正在说的那一句（语言键）、选项出来了没有。 */
    public List<String> options() {
        List<String> out = new ArrayList<>();
        for (TalkMenu.Option o : menu()) out.add(o.id());
        return out;
    }

    public @Nullable String lineKey() {
        return line == null ? null : line.key();
    }

    public boolean ready() {
        return showOptions();
    }
}
