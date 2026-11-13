package dev.logos.dimension.client.music;

import dev.logos.dimension.music.InstrumentKind;
import dev.logos.dimension.music.Instruments;
import dev.logos.dimension.music.MusicRoll;
import dev.logos.dimension.music.RollInfo;
import dev.logos.dimension.music.orchestra.BeatMap;
import dev.logos.dimension.music.orchestra.EnsemblePayloads;
import dev.logos.dimension.music.orchestra.OrchestraArranger;
import dev.logos.dimension.music.orchestra.OrchestraScore;
import dev.logos.dimension.music.orchestra.PodiumBlockEntity;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * 指挥台界面（docs/ORCHESTRA.md 第 9 节）：左边是总谱的声部（颜色、谁在演奏、静音 / 独奏 / 响度），右边是乐团成员
 * （演奏哪个声部、锁定）。点声部选中它（画面里描边）；选中声部后左键点成员让它改演奏这个声部（锁定），右键成员解锁、
 * 潜行左键成员让它退出。底下是召集附近、重新分派、速度、从第几小节、开始 / 停止、取出总谱。
 */
final class PodiumScreen extends Screen {
    /** 羊皮纸的四边有撕边和金角：内容离边 PAD，标题离上边 TOP；成员的乐器名一栏宽 NAME_W。 */
    private static final int W = 470, H = 268, PAD = 18, TOP = 24, ROW = 11, NAME_W = 100;
    private final BlockPos pos;
    private int x0, y0;
    private int fromBar;
    private int scroll;
    /** 选中了声部列表最后的「休息」：再点乐器就让它休息。 */
    private boolean restPicked;
    /** 底下两行文字的位置（按按钮实际的宽度排出来）。 */
    private int tempoX, fromX, gainX;
    /** 只有选中声部时才能按的：响度、静音、独奏。 */
    private final List<Button> partButtons = new java.util.ArrayList<>();

    PodiumScreen(BlockPos pos) {
        super(Component.translatable("screen.logos_dimension.podium.title"));
        this.pos = pos.immutable();
    }

    BlockPos podium() {
        return pos;
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("screen.logos_dimension.podium." + key, args);
    }

    private @Nullable PodiumBlockEntity entity() {
        var level = Minecraft.getInstance().level;
        return level != null && level.getBlockEntity(pos) instanceof PodiumBlockEntity be ? be : null;
    }

    /** 合奏用的总谱（钢琴曲另有拆出的旋律、和声、低音，{@link OrchestraScore}）。 */
    private static OrchestraScore.@Nullable Score prepared(PodiumBlockEntity be) {
        RollInfo info = be.score().get(Instruments.MUSIC_ROLL.get());
        MusicRoll raw = info == null ? null : MusicClient.roll(info.id());
        return raw == null ? null : OrchestraScore.of(raw);
    }

    private @Nullable MusicRoll score(PodiumBlockEntity be) {
        OrchestraScore.Score s = prepared(be);
        return s == null ? null : s.roll();
    }

    /** 声部的名字：纸带里写的，没写时「声部 n」；钢琴曲拆出来的是「旋律」「和声」「低音」。 */
    static Component partName(PodiumBlockEntity be, int part) {
        OrchestraScore.Score s = prepared(be);
        if (s != null && s.voice(part)) {
            return Component.translatable("screen.logos_dimension.podium.voice." + switch (s.role(part)) {
                case OrchestraScore.MELODY -> "melody";
                case OrchestraScore.HARMONY -> "harmony";
                default -> "bass";
            });
        }
        RollInfo info = be.score().get(Instruments.MUSIC_ROLL.get());
        if (info != null && part < info.parts().size() && !info.parts().get(part).isBlank()) return Component.literal(info.parts().get(part));
        return Component.translatable("screen.logos_dimension.podium.part", part + 1);
    }

    private boolean playing() {
        return Conducting.position(pos) != null;
    }

    private void send(int action, int a, int b) {
        ClientPacketDistributor.sendToServer(new EnsemblePayloads.PodiumControl(pos, action, a, b));
    }

    /** 按文字宽度放一个按钮，返回它右边（留 2 像素）的位置。 */
    private Button button(Component label, int x, int y, Button.OnPress press) {
        Button b = Button.builder(label, press).bounds(x, y, Math.max(16, font.width(label) + 12), 16).build();
        addRenderableWidget(b);
        return b;
    }

    private int bottomRow() {
        return y0 + H - 34;
    }

    @Override
    protected void init() {
        x0 = (width - W) / 2;
        y0 = (height - H) / 2;
        int y = bottomRow();
        int x = x0 + PAD, right = x0 + W - PAD;
        // 左：召集、重新分派；右：开始 / 停止、取出；中间：速度、从第几小节
        x = button(tr("gather"), x, y, b -> send(EnsemblePayloads.PodiumControl.GATHER, 0, 0)).getRight() + 2;
        x = button(tr("reassign"), x, y, b -> send(EnsemblePayloads.PodiumControl.REASSIGN, 0, 0)).getRight() + 8;
        x = button(Component.literal("−"), x, y, b -> tempo(-5)).getRight() + 3;
        tempoX = x;
        x = button(Component.literal("+"), x + font.width(tr("tempo", 150)) + 3, y, b -> tempo(5)).getRight() + 8;
        x = button(Component.literal("◀"), x, y, b -> fromBar = Math.max(0, fromBar - 1)).getRight() + 3;
        fromX = x;
        button(Component.literal("▶"), x + font.width(tr("from_bar", 999)) + 3, y, b -> fromBar++);
        Component eject = tr("eject");
        int ejectX = right - font.width(eject) - 12;
        button(eject, ejectX, y, b -> send(EnsemblePayloads.PodiumControl.EJECT, 0, 0));
        Component startStop = tr("start_stop");
        button(startStop, ejectX - 2 - font.width(startStop) - 12, y, b -> {
            if (playing()) send(EnsemblePayloads.PodiumControl.STOP, 0, 0);
            else send(EnsemblePayloads.PodiumControl.START, fromBar, 0);
        });
        // 选中声部的响度、静音、独奏（没选中时按不了）
        int y2 = y - 20;
        partButtons.clear();
        x = x0 + PAD + 104;
        Button minus = button(Component.literal("−"), x, y2, b -> gain(-3));
        gainX = minus.getRight() + 3;
        Button plus = button(Component.literal("+"), gainX + font.width("+6 dB") + 3, y2, b -> gain(3));
        Button mute = button(tr("mute"), plus.getRight() + 6, y2, b -> partAction(EnsemblePayloads.PodiumControl.MUTE));
        Button solo = button(tr("solo"), mute.getRight() + 2, y2, b -> partAction(EnsemblePayloads.PodiumControl.SOLO));
        partButtons.addAll(List.of(minus, plus, mute, solo));
    }

    private void tempo(int d) {
        PodiumBlockEntity be = entity();
        if (be != null) send(EnsemblePayloads.PodiumControl.TEMPO, be.tempo() + d, 0);
    }

    private void gain(int d) {
        PodiumBlockEntity be = entity();
        int part = Conducting.selectedPart(pos);
        if (be != null && part >= 0) send(EnsemblePayloads.PodiumControl.GAIN, part, be.gain(part) + d);
    }

    private void partAction(int action) {
        int part = Conducting.selectedPart(pos);
        if (part >= 0) send(action, part, 0);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 不模糊、只稍微压暗：选中声部时要看得见画面里乐器的描边。 */
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        g.fillGradient(0, 0, width, height, 0x50101010, 0x70101010);
        minecraft.gui.extractDeferredSubtitles();
    }

    // —— 画 ——

    private int listTop() {
        return y0 + TOP + 26;
    }

    private int listBottom() {
        return bottomRow() - 24;
    }

    private int splitX() {
        return x0 + W / 2 - 10;
    }

    /** 测试用：在声部列表（members 为 false）或成员列表的第 row 行上点一下（button 0 左键、1 右键）。 */
    boolean clickRow(boolean members, int row, int button) {
        double x = members ? splitX() + 20 : x0 + PAD + 12, y = listTop() + row * ROW + ROW / 2.0;
        return mouseClicked(new net.minecraft.client.input.MouseButtonEvent(x, y, new net.minecraft.client.input.MouseButtonInfo(button, 0)), false);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        Parchment.draw(g, x0, y0, W, H);
        PodiumBlockEntity be = entity();
        if (be == null) {
            onClose();
            return;
        }
        MusicRoll roll = score(be);
        RollInfo info = be.score().get(Instruments.MUSIC_ROLL.get());
        int x = x0 + PAD, top = y0 + TOP;
        Component title = info == null ? tr("no_score")
            : Component.literal(font.plainSubstrByWidth(info.title().isBlank() ? "—" : info.title(), 200));
        g.text(font, title, x, top, Parchment.INK_TITLE, false);
        Integer position = Conducting.position(pos);
        if (roll != null) {
            String meter = roll.beatsPerBar() + "/" + roll.beatUnit() + "  ♩=" + Math.round(roll.bpmX100() / 100f);
            g.text(font, Component.literal(meter), x + 210, top, Parchment.INK_HINT, false);
            if (position != null) {
                BeatMap.Beat b = BeatMap.of(roll).at(Math.max(0, position));
                Component bar = tr("bar_now", b.bar() + 1);
                g.text(font, bar, x0 + W - PAD - font.width(bar), top, Parchment.INK, false);
            }
        }
        g.text(font, tr("parts"), x, top + 13, Parchment.INK_HINT, false);
        g.text(font, tr("members", be.members().size(), PodiumBlockEntity.MAX_MEMBERS), splitX() + 8, top + 13, Parchment.INK_HINT, false);
        g.fill(splitX(), listTop() - 2, splitX() + 1, listBottom(), 0x55402810);
        drawParts(g, be, roll, mouseX, mouseY);
        drawMembers(g, be, mouseX, mouseY);
        // 选中的声部、速度、从第几小节
        int sel = Conducting.selectedPart(pos);
        int y = bottomRow(), y2 = y - 20;
        Component selected = sel < 0 ? tr("none_selected") : Component.literal(font.plainSubstrByWidth(partName(be, sel).getString(), 98));
        g.text(font, selected, x, y2 + 4, sel < 0 ? Parchment.INK_HINT : Conducting.PART_COLORS[sel] | 0xFF000000, false);
        if (sel >= 0) g.text(font, Component.literal(String.format("%+d dB", be.gain(sel))), gainX, y2 + 4, Parchment.INK, false);
        for (Button b : partButtons) b.active = sel >= 0;
        g.text(font, tr("tempo", be.tempo()), tempoX, y + 4, Parchment.INK, false);
        g.text(font, tr("from_bar", fromBar + 1), fromX, y + 4, Parchment.INK, false);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void drawParts(GuiGraphicsExtractor g, PodiumBlockEntity be, @Nullable MusicRoll roll, int mouseX, int mouseY) {
        int x = x0 + PAD, y = listTop();
        if (roll == null) {
            g.text(font, tr(be.score().isEmpty() ? "insert_score" : "loading"), x, y, Parchment.INK_HINT, false);
            return;
        }
        int sel = Conducting.selectedPart(pos);
        int used = 0;
        for (int p = 0; p < roll.parts().size(); p++) {
            if (y + ROW > listBottom()) break;
            int players = 0;
            for (BlockPos m : be.members()) if ((be.mask(m) & (1 << p)) != 0) players++;
            used |= players > 0 ? 1 << p : 0;
            if (p == sel) g.fill(x - 2, y - 1, splitX() - 4, y + ROW - 1, 0x33E0B84A);
            g.fill(x, y + 1, x + 7, y + 8, Conducting.PART_COLORS[p]);
            Component name = partName(be, p);
            g.text(font, Component.literal((p + 1) + " ").append(name), x + 10, y, Parchment.INK, false);
            Component who = players == 0 ? tr("nobody") : tr("players", players);
            int color = players == 0 ? 0xFFB0283F : Parchment.INK_HINT;
            if ((be.muteMask() & (1 << p)) != 0) who = tr("muted");
            else if (be.soloMask() != 0 && (be.soloMask() & (1 << p)) != 0) who = tr("solo_on");
            g.text(font, who, splitX() - 6 - font.width(who), y, color, false);
            y += ROW;
        }
        // 最后一行「休息」：选中它再点乐器，让那件乐器休息（同手动改派，锁定）
        if (y + ROW <= listBottom()) {
            if (restPicked) g.fill(x - 2, y - 1, splitX() - 4, y + ROW - 1, 0x33E0B84A);
            g.text(font, tr("rest_row"), x + 10, y, restPicked ? Parchment.INK : Parchment.INK_HINT, false);
        }
    }

    private void drawMembers(GuiGraphicsExtractor g, PodiumBlockEntity be, int mouseX, int mouseY) {
        int x = splitX() + 8, y = listTop();
        List<BlockPos> members = be.members();
        if (members.isEmpty()) {
            g.text(font, tr("no_members"), x, y, Parchment.INK_HINT, false);
            return;
        }
        int rows = (listBottom() - listTop()) / ROW;
        scroll = Math.max(0, Math.min(scroll, members.size() - rows));
        for (int i = scroll; i < members.size() && i < scroll + rows; i++) {
            BlockPos m = members.get(i);
            InstrumentKind kind = Conducting.kindOf(m);
            boolean bench = Minecraft.getInstance().level != null
                && Minecraft.getInstance().level.getBlockState(m).getBlock() instanceof dev.logos.dimension.music.StarbloomBenchBlock;
            String name = (kind == null ? tr(bench ? "empty_bench" : "missing") : Component.translatable(kind.nameKey())).getString();
            String shown = font.plainSubstrByWidth(name, NAME_W - 6);
            if (shown.length() < name.length()) shown = font.plainSubstrByWidth(name, NAME_W - 6 - font.width("…")) + "…";
            g.text(font, Component.literal(shown), x, y, Parchment.INK, false);
            int mask = be.mask(m);
            int px = x + NAME_W;
            if (mask == 0) {
                g.text(font, tr("resting"), px, y, Parchment.INK_HINT, false);
            } else {
                for (int p : OrchestraArranger.parts(mask)) {
                    g.fill(px, y + 1, px + 7, y + 8, Conducting.PART_COLORS[p]);
                    Component pn = partName(be, p);
                    g.text(font, pn, px + 9, y, Parchment.INK, false);
                    px += 12 + font.width(pn);
                    if (px > x0 + W - PAD - 30) break;
                }
            }
            if (be.locked(m)) g.text(font, Component.literal("🔒"), x0 + W - PAD - 10, y, Parchment.BRASS, false);
            y += ROW;
        }
    }

    // —— 点 ——

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        PodiumBlockEntity be = entity();
        double mx = event.x(), my = event.y();
        if (be != null && my >= listTop() && my < listBottom()) {
            int row = (int) ((my - listTop()) / ROW);
            if (mx < splitX()) {
                MusicRoll roll = score(be);
                if (roll != null && row < roll.parts().size()) {
                    restPicked = false;
                    Conducting.select(pos, Conducting.selectedPart(pos) == row ? -1 : row);
                    return true;
                }
                if (roll != null && row == roll.parts().size()) {
                    restPicked = !restPicked;
                    Conducting.select(pos, -1);
                    return true;
                }
            } else {
                int index = scroll + row;
                if (index < be.members().size()) {
                    if (event.button() == 1) {
                        send(EnsemblePayloads.PodiumControl.UNLOCK, index, 0);
                    } else if (event.hasShiftDown()) {
                        send(EnsemblePayloads.PodiumControl.REMOVE, index, 0);
                    } else if (restPicked) {
                        send(EnsemblePayloads.PodiumControl.ASSIGN, index, 0);
                    } else {
                        int sel = Conducting.selectedPart(pos);
                        if (sel >= 0) send(EnsemblePayloads.PodiumControl.ASSIGN, index, 1 << sel);
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (x >= splitX()) {
            scroll = Math.max(0, scroll - (int) Math.signum(scrollY));
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }
}
