package dev.logos.dimension.client.companion;

import dev.logos.dimension.companion.CompanionSheet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 同伴的角色面板（docs/SHIRAUME_COMPANION.md 第 8.13 节），从对话里的「详细数据」打开，Esc 回到对话。
 * 左边是她本人：名字、称号，身后一枚大大的纹和一圈光，脚下一方台子，摆着她自己的姿势（{@link CompanionStyle.Showcase}），拖动可以转身。
 * 右边一张卡片，三页：
 * <ul>
 *   <li>属性：生命、攻击、护甲、韧性（本身 + 继承），继承比例，羁绊、饱腹，现在的行动；
 *   <li>契印 · 能力：契印（白梅的九条尾尖）；普通攻击、特殊、被动，点一项看说明，没解锁的写着要第几枚契印；
 *   <li>往事：每枚契印想起的一段，点开重读。
 * </ul>
 * 数是活的：说着话时服务端每秒重发一次档案。
 */
public final class CompanionPanelScreen extends Screen {
    public enum Tab { STATS, SKILLS, MEMORIES }

    static final int PAD = 10, ENTRY = 13, HEADER = 11;
    private final CompanionTalkScreen parent;
    private final CompanionStyle style;
    private Tab tab = Tab.STATS;
    private @Nullable LivingEntity model;
    private String modelOutfit = "";
    /** 拖动转过的角度（度）。 */
    private float turn;
    private boolean dragging, returning;
    private int skill, memory = -1;

    CompanionPanelScreen(CompanionTalkScreen parent) {
        super(Component.translatable("talk.logos_dimension." + parent.sheet().companion() + ".name"));
        this.parent = parent;
        this.style = CompanionStyle.of(parent.sheet().companion());
    }

    public CompanionTalkScreen parent() {
        return parent;
    }

    private CompanionSheet sheet() {
        return parent.sheet();
    }

    private String id() {
        return sheet().companion();
    }

    private Component tr(String key, Object... args) {
        return Component.translatable("gui.logos_dimension.companion." + key, args);
    }

    /** 这位同伴自己的键（talk.logos_dimension.<id>.<key>）。 */
    private Component own(String key, Object... args) {
        return Component.translatable("talk.logos_dimension." + id() + "." + key, args);
    }

    /** 服务端让关（说话结束了）：连对话一起关掉。 */
    void end() {
        minecraft.setScreen(null);
    }

    /** 切页（测试也用）。 */
    public void tab(Tab t) {
        tab = t;
    }

    public void selectSkill(int i) {
        skill = i;
    }

    public void selectMemory(int i) {
        memory = i;
    }

    @Override
    public void onClose() {
        returning = true;
        parent.returned();
        minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        // 不是回到对话（服务端让关、死了换了界面……）：放回状态栏，对话也算结束
        if (!returning) {
            CompanionTalkClient.hideGui(false);
            net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(
                    new dev.logos.dimension.companion.CompanionTalk.Pick(sheet().entity(), dev.logos.dimension.companion.CompanionTalk.CLOSED));
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        if (model != null) model.tickCount++;
    }

    // ---------------------------------------------------------------- 排版

    private int charW() {
        return Math.round(width * .42f);
    }

    private int cardX() {
        return charW() + 4;
    }

    private int cardY() {
        return PAD + 4;
    }

    private int cardR() {
        return width - PAD;
    }

    private int cardB() {
        return height - PAD;
    }

    private int left() {
        return cardX() + 10;
    }

    private int right() {
        return cardR() - 10;
    }

    private int top() {
        return cardY() + 26;
    }

    private int tabX(int i) {
        int x = left();
        for (int k = 0; k < i; k++) x += font.width(tr("tab." + Tab.values()[k].name().toLowerCase())) + 14;
        return x;
    }

    // ---------------------------------------------------------------- 画

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        extractBlurredBackground(g);
        g.fillGradient(0, 0, width, height, CompanionUi.alpha(style.night0(), .93f), CompanionUi.alpha(style.night1(), .96f));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        drawCharacter(g, partialTick);
        // 卡片
        int x0 = cardX(), y0 = cardY(), x1 = cardR(), y1 = cardB();
        g.fill(x0, y0, x1, y1, style.card());
        g.fill(x0, y0, x1, y0 + 1, CompanionUi.alpha(style.accent(), .55f));
        g.fill(x0, y1 - 1, x1, y1, CompanionUi.alpha(style.accent(), .18f));
        g.fill(x0, y0, x0 + 1, y1, CompanionUi.alpha(style.accent(), .18f));
        g.fill(x1 - 1, y0, x1, y1, CompanionUi.alpha(style.accent(), .18f));
        drawTabs(g, mouseX, mouseY);
        switch (tab) {
            case STATS -> drawStats(g);
            case SKILLS -> drawSkills(g, mouseX, mouseY);
            case MEMORIES -> drawMemories(g, mouseX, mouseY);
        }
        Component esc = tr("esc");
        g.text(font, esc, x1 - 6 - font.width(esc), y1 - 12, CompanionUi.alpha(style.dim(), .7f), false);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void drawCharacter(GuiGraphicsExtractor g, float partialTick) {
        int w = charW();
        float cx = w / 2f, cy = height * .55f;
        float r = Math.min(w * .44f, height * .38f);
        // 身后：一圈淡淡的光，一枚大大的纹
        CompanionUi.glow(g, cx, cy, r * 1.15f, CompanionUi.alpha(style.accent(), .035f), 10);
        style.crest().draw(g, cx, cy, r, CompanionUi.alpha(style.accent(), .12f));
        CompanionUi.ring(g, cx, cy, r * 1.08f, 1, CompanionUi.alpha(style.gold(), .22f));
        // 脚下的台子
        float feet = height - PAD - 10;
        CompanionUi.ellipse(g, cx, feet, w * .28f, 5, CompanionUi.alpha(style.accentDeep(), .22f));
        CompanionUi.ellipse(g, cx, feet, w * .18f, 3, CompanionUi.alpha(style.accent(), .22f));
        int top = PAD + 30;
        int frame = Math.round((feet - top) / ((1 + style.showcase().height()) / 2));
        drawModel(g, 0, top, w, top + frame, partialTick);
        // 名字、称号
        int x = PAD + 6, y = PAD + 6;
        CompanionUi.bigText(g, font, own("name"), x, y, style.ink(), 2, true);
        int nx = x + font.width(own("name")) * 2 + 6;
        g.text(font, own("kana"), nx, y + 8, style.dim(), false);
        g.text(font, own("epithet"), x, y + 20, style.accent(), true);
        CompanionUi.fadeLine(g, x, x + Math.round(w * .62f), y + 31, CompanionUi.alpha(style.gold(), .75f));
        g.text(font, tr("drag"), PAD + 6, height - PAD - 9, CompanionUi.alpha(style.dim(), .55f), false);
    }

    /** 不放进世界的她：照档案的装束摆姿势，身子侧一点、脸朝着你；拖动转身。 */
    private void drawModel(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, float partialTick) {
        if (minecraft.level == null) return;
        if (model == null || !modelOutfit.equals(sheet().outfit())) {
            modelOutfit = sheet().outfit();
            model = style.showcase().make().apply(minecraft.level, sheet());
        }
        if (model == null) return;
        CompanionStyle.Showcase show = style.showcase();
        float side = show.yaw() + turn;
        float body = 180 + side, head = body + side * show.look();
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
        // 脚落在框底往上 (1 - height) / 2 的地方：框的下沿按这个往下放，脚正好踩在台子上
        float scale = (y1 - y0) * show.height() / Math.max(.5f, state.boundingBoxHeight);
        Quaternionf cam = new Quaternionf().rotateX(show.pitch() * Mth.DEG_TO_RAD);
        Quaternionf rot = new Quaternionf().rotateZ((float) Math.PI).mul(cam);
        Vector3f t = new Vector3f(0, state.boundingBoxHeight / 2 + show.lift(), 0);
        g.entity(state, scale, t, rot, cam, x0, y0, x1, y1);
    }

    private void drawTabs(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int y = cardY() + 9;
        for (Tab t : Tab.values()) {
            Component label = tr("tab." + t.name().toLowerCase());
            int x = tabX(t.ordinal()), w = font.width(label);
            boolean on = t == tab, hover = mouseX >= x - 3 && mouseX < x + w + 3 && mouseY >= y - 3 && mouseY < y + 12;
            g.text(font, label, x, y, on ? style.ink() : hover ? CompanionUi.mix(style.dim(), style.ink(), .5f) : style.dim(), false);
            if (on) g.fill(x - 1, y + 11, x + w + 1, y + 13, style.accent());
        }
        g.fill(left(), cardY() + 23, right(), cardY() + 24, CompanionUi.alpha(style.accent(), .16f));
    }

    // ---------------------------------------------------------------- 属性

    private static final String[] STATS = {"health", "attack", "armor", "toughness"};

    private void drawStats(GuiGraphicsExtractor g) {
        CompanionSheet s = sheet();
        int x = left(), r = right(), y = top() + 2;
        int valueX = x + 58;
        for (String id : STATS) {
            CompanionSheet.Stat st = s.stat(id);
            g.text(font, tr("stat." + id), x, y, style.dim(), false);
            String value = st.current() >= 0 ? num(st.current()) + " / " + num(st.value()) : num(st.value());
            CompanionUi.bigText(g, font, Component.literal(value), valueX, y - 1, style.ink(), 1.25f, false);
            // 本身 + 继承
            Component bonus = Component.literal("+" + num(st.bonus()));
            Component base = Component.literal(num(st.base()) + " ");
            int bw = font.width(bonus);
            g.text(font, bonus, r - bw, y, style.accent(), false);
            g.text(font, base, r - bw - font.width(base), y, CompanionUi.alpha(style.dim(), .8f), false);
            if (st.current() >= 0) CompanionUi.bar(g, valueX, y + 11, r - valueX, 2, st.current() / Math.max(1, st.value()),
                    style.accent(), CompanionUi.alpha(style.dim(), .2f));
            y += st.current() >= 0 ? 19 : 15;
        }
        g.text(font, tr("base_bonus"), r - font.width(tr("base_bonus")), y - 2, CompanionUi.alpha(style.dim(), .55f), false);
        y += 10;
        CompanionUi.fadeLine(g, x, r, y, CompanionUi.alpha(style.gold(), .5f));
        y += 6;
        y = wrapped(g, tr("inherit", num(s.ratio() * 100)), x, y, r - x, style.ink());
        y = wrapped(g, tr("inherit_hint"), x, y, r - x, style.dim()) + 6;
        CompanionUi.fadeLine(g, x, r, y, CompanionUi.alpha(style.gold(), .5f));
        y += 7;
        // 羁绊、饱腹、现在
        int barX = x + 58, barW = Math.max(30, r - barX - 40);
        g.text(font, tr("bond"), x, y, style.dim(), false);
        Component stage = tr("stage." + s.stage().toLowerCase());
        g.text(font, stage, barX, y, style.accent(), false);
        CompanionUi.bar(g, barX + font.width(stage) + 6, y + 3, Math.max(20, r - barX - font.width(stage) - 6), 3, s.stageProgress(),
                style.accentDeep(), CompanionUi.alpha(style.dim(), .2f));
        y += 14;
        g.text(font, tr("satiety"), x, y, style.dim(), false);
        CompanionUi.bar(g, barX, y + 3, barW, 3, s.satiety() / Math.max(1, s.satietyMax()), style.gold(), CompanionUi.alpha(style.dim(), .2f));
        Component sat = Component.literal(num(s.satiety()) + " / " + num(s.satietyMax()));
        g.text(font, sat, r - font.width(sat), y, style.ink(), false);
        y += 14;
        g.text(font, tr("now"), x, y, style.dim(), false);
        Component now = Component.translatable("talk.logos_dimension.state." + (s.staying() ? "stay" : "follow")).append(" · ")
                .append(Component.translatable("talk.logos_dimension.option.tactic." + s.tactic()));
        g.text(font, now, barX, y, style.ink(), false);
        y += 14;
        g.text(font, tr("outfit"), x, y, style.dim(), false);
        g.text(font, own("option.outfit." + s.outfit()), barX, y, style.ink(), false);
        // 最下面：她这时候会怎么说你（按羁绊），像角色卡上的一句话
        Component quote = Component.translatable("line.logos_dimension." + id() + ".talk.us_" + s.stage().toLowerCase());
        List<String> lines = CompanionUi.wrap(font, quote, r - x - 24);
        int qy = cardB() - 26 - lines.size() * 10;
        CompanionUi.fadeLine(g, x, r, qy - 8, CompanionUi.alpha(style.gold(), .35f));
        style.crest().draw(g, x + 5, qy + 4, 5, CompanionUi.alpha(style.accent(), .7f));
        for (String l : lines) {
            g.text(font, l, x + 16, qy, CompanionUi.mix(style.ink(), style.accent(), .25f), false);
            qy += 10;
        }
    }

    // ---------------------------------------------------------------- 契印 · 能力

    /** 列表里一行：一项能力，或者一个分类的小标题（{@code skill} 为 -1）。 */
    private record Row(int y, int skill, int kind) {}

    private List<Row> skillRows() {
        List<Row> rows = new ArrayList<>();
        int y = top() + 22;
        List<CompanionSheet.Skill> skills = sheet().skills();
        for (int kind = 0; kind < 3; kind++) {
            boolean any = false;
            for (CompanionSheet.Skill s : skills) any |= s.kind() == kind;
            if (!any) continue;
            rows.add(new Row(y, -1, kind));
            y += HEADER;
            for (int i = 0; i < skills.size(); i++) {
                if (skills.get(i).kind() != kind) continue;
                rows.add(new Row(y, i, kind));
                y += ENTRY;
            }
            y += 2;
        }
        return rows;
    }

    private int listW() {
        return Math.min(104, Math.round((right() - left()) * .44f));
    }

    private void drawSkills(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        CompanionSheet s = sheet();
        int x = left(), r = right(), y = top();
        // 契印：白梅的是九条尾尖
        Component name = own("seal_name");
        g.text(font, name, x, y + 3, style.gold(), false);
        int fx = x + font.width(name) + 10;
        int step = Math.min(15, Math.max(9, (r - fx - 30) / Math.max(1, s.maxSeals())));
        for (int i = 0; i < s.maxSeals(); i++) CompanionUi.flame(g, fx + i * step + 4, y + 8, 3.3f, style.accent(), i < s.seals());
        Component count = tr("seals", s.seals(), s.maxSeals());
        g.text(font, count, r - font.width(count), y + 3, style.ink(), false);
        List<CompanionSheet.Skill> skills = s.skills();
        if (skills.isEmpty()) return;
        skill = Mth.clamp(skill, 0, skills.size() - 1);
        int lw = listW();
        for (Row row : skillRows()) {
            if (row.skill() < 0) {
                g.text(font, tr("kind." + KINDS[row.kind()]), x, row.y() + 1, CompanionUi.alpha(style.dim(), .8f), false);
                continue;
            }
            CompanionSheet.Skill sk = skills.get(row.skill());
            boolean on = row.skill() == skill, hover = inside(mouseX, mouseY, x - 2, row.y() - 1, lw, ENTRY);
            if (on || hover) g.fill(x - 2, row.y() - 1, x - 2 + lw, row.y() + ENTRY - 1, CompanionUi.alpha(style.accent(), on ? .18f : .08f));
            if (on) g.fill(x - 2, row.y() - 1, x - 1, row.y() + ENTRY - 1, style.accent());
            CompanionUi.glyph(g, font, x + 6, row.y() + 5, 6.5f, skillText(sk, "icon"), kindColor(sk.kind()), !sk.unlocked());
            Component label = sk.unlocked() ? skillText(sk, "name") : tr("locked_skill");
            g.text(font, fit(label, lw - 20), x + 17, row.y() + 1, sk.unlocked() ? style.ink() : style.dim(), false);
        }
        // 右边：选中那一项的说明
        CompanionSheet.Skill sk = skills.get(skill);
        int dx = x + lw + 8, dy = top() + 22;
        g.fill(dx - 5, dy - 2, dx - 4, cardB() - 16, CompanionUi.alpha(style.accent(), .14f));
        CompanionUi.glyph(g, font, dx + 9, dy + 9, 9, skillText(sk, "icon"), kindColor(sk.kind()), !sk.unlocked());
        g.text(font, sk.unlocked() ? skillText(sk, "name") : tr("locked_skill"), dx + 23, dy + 1, style.ink(), false);
        Component where = tr("kind." + KINDS[sk.kind()]).copy().append(" · ").append(sk.seal() == 0 ? tr("skill_base") : tr("skill_seal", sk.seal()));
        g.text(font, where, dx + 23, dy + 11, CompanionUi.alpha(style.dim(), .9f), false);
        Component desc = sk.unlocked() ? skillDesc(sk) : tr("locked_skill_desc", sk.seal());
        wrapped(g, desc, dx, dy + 25, r - dx, CompanionUi.mix(style.ink(), style.dim(), .35f));
    }

    private static final String[] KINDS = {"normal", "special", "passive"};

    private int kindColor(int kind) {
        return kind == CompanionSheet.NORMAL ? style.accent() : kind == CompanionSheet.SPECIAL ? style.gold() : style.cool();
    }

    private Component skillText(CompanionSheet.Skill sk, String part) {
        return own("skill." + sk.id() + "." + part);
    }

    private Component skillDesc(CompanionSheet.Skill sk) {
        Object[] args = new Object[sk.args().size()];
        for (int i = 0; i < args.length; i++) {
            String a = sk.args().get(i);
            args[i] = a.startsWith("#") ? Component.translatable(a.substring(1)) : a;
        }
        return own("skill." + sk.id() + ".desc", args);
    }

    // ---------------------------------------------------------------- 往事

    private void drawMemories(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        CompanionSheet s = sheet();
        int x = left(), r = right(), y = top() + 2, lw = listW();
        if (memory < 0) memory = Math.max(0, s.memories() - 1);
        memory = Mth.clamp(memory, 0, Math.max(0, s.memoryTotal() - 1));
        g.text(font, tr("memories", s.memories(), s.memoryTotal()), x, y, style.gold(), false);
        y += 14;
        for (int i = 0; i < s.memoryTotal(); i++) {
            int ry = y + i * ENTRY;
            boolean open = i < s.memories(), on = i == memory, hover = inside(mouseX, mouseY, x - 2, ry - 1, lw, ENTRY);
            if (on || hover) g.fill(x - 2, ry - 1, x - 2 + lw, ry + ENTRY - 1, CompanionUi.alpha(style.accent(), on ? .18f : .08f));
            if (on) g.fill(x - 2, ry - 1, x - 1, ry + ENTRY - 1, style.accent());
            CompanionUi.diamond(g, x + 4, ry + 5, 2, open ? style.accent() : CompanionUi.alpha(style.dim(), .45f));
            Component title = open ? own("memory." + i + ".title") : tr("locked_skill");
            g.text(font, fit(title, lw - 14), x + 11, ry + 1, open ? style.ink() : CompanionUi.alpha(style.dim(), .7f), false);
        }
        int dx = x + lw + 8, dy = top() + 16;
        g.fill(dx - 5, dy - 2, dx - 4, cardB() - 16, CompanionUi.alpha(style.accent(), .14f));
        if (memory >= s.memories()) {
            g.text(font, tr("locked_skill"), dx, dy, style.dim(), false);
            wrapped(g, tr("memory_locked", memory), dx, dy + 14, r - dx, style.dim());
            return;
        }
        g.text(font, own("memory." + memory + ".title"), dx, dy, style.accent(), false);
        int ty = dy + 14;
        for (int i = 1; I18n.exists("line.logos_dimension." + id() + ".talk.past_" + memory + "_" + i); i++) {
            ty = wrapped(g, Component.translatable("line.logos_dimension." + id() + ".talk.past_" + memory + "_" + i), dx, ty, r - dx,
                    CompanionUi.mix(style.ink(), style.dim(), .25f)) + 4;
        }
    }

    // ---------------------------------------------------------------- 小工具

    private int wrapped(GuiGraphicsExtractor g, Component text, int x, int y, int width, int color) {
        for (String line : CompanionUi.wrap(font, text, width)) {
            g.text(font, line, x, y, color, false);
            y += 10;
        }
        return y;
    }

    /** 放不下就截短，末尾加省略号。 */
    private Component fit(Component text, int width) {
        String s = text.getString();
        if (font.width(s) <= width) return text;
        return Component.literal(font.plainSubstrByWidth(s, width - font.width("…")) + "…");
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    static String num(float v) {
        float r = Math.round(v * 10) / 10f;
        return r == Math.round(r) ? String.valueOf(Math.round(r)) : String.valueOf(r);
    }

    // ---------------------------------------------------------------- 操作

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        if (event.button() == 0) {
            int ty = cardY() + 9;
            for (Tab t : Tab.values()) {
                int x = tabX(t.ordinal()), w = font.width(tr("tab." + t.name().toLowerCase()));
                if (inside(mx, my, x - 3, ty - 3, w + 6, 15)) {
                    tab = t;
                    return true;
                }
            }
            if (tab == Tab.SKILLS) {
                for (Row row : skillRows()) {
                    if (row.skill() >= 0 && inside(mx, my, left() - 2, row.y() - 1, listW(), ENTRY)) {
                        skill = row.skill();
                        return true;
                    }
                }
            }
            if (tab == Tab.MEMORIES) {
                int y = top() + 16;
                for (int i = 0; i < sheet().memoryTotal(); i++) {
                    if (inside(mx, my, left() - 2, y + i * ENTRY - 1, listW(), ENTRY)) {
                        memory = i;
                        return true;
                    }
                }
            }
            if (mx < charW()) {
                dragging = true;
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging) {
            turn += (float) dx * 1.6f;
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        dragging = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int k = event.key();
        if (k == 258) {
            tab = Tab.values()[(tab.ordinal() + 1) % Tab.values().length];
            return true;
        }
        if (k >= 49 && k <= 51) {
            tab = Tab.values()[k - 49];
            return true;
        }
        return super.keyPressed(event);
    }
}
