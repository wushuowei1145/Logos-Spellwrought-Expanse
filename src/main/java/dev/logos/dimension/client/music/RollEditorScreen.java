package dev.logos.dimension.client.music;

import dev.logos.dimension.music.InstrumentBlockEntity;
import dev.logos.dimension.music.InstrumentKind;
import dev.logos.dimension.music.Instruments;
import dev.logos.dimension.music.MusicPayloads;
import dev.logos.dimension.music.MusicRoll;
import dev.logos.dimension.music.RollInfo;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * 打孔编辑器：钢琴卷帘式网格。横向是时间（按 BPM 画拍线，新音符和拖动吸附到十六分音符），纵向是音高。
 * <ul>
 *   <li>左键空格子打孔（拖动定长短），拖音符移动，拖音符右缘改长短；右键删除。</li>
 *   <li>Shift + 拖空白处框选，Shift + 点音符加选；Delete 删除选中，Ctrl+D 把选中的复制到后面，Ctrl+A 全选。</li>
 *   <li>滚轮上下翻音高，Shift + 滚轮左右翻时间，Ctrl + 滚轮缩放；空格从当前视图开头试听。</li>
 * </ul>
 * 保存时生成新纸带（新 ID），乐器的纸带槽里要有一卷空白纸带，或者就是正在编辑的这卷。
 * 时间在内部按毫秒存，导入的 MIDI 不在网格上也不会被挪动；延音踏板原样保留，编辑器里不改。
 */
public class RollEditorScreen extends Screen {
    private static final int W = 400, H = 226, PAD = 26, KEYS = 26, ROW = 6;
    private static final int KEY_DELETE = 261, KEY_BACKSPACE = 259, KEY_SPACE = 32, KEY_D = 68, KEY_A = 65;
    private static final int[] PART_COLORS = {0xFFB8432E, 0xFF3D6FB5, 0xFF4E8F3A, 0xFFB5872A, 0xFF8A4BA8, 0xFF2E9A9A,
        0xFFB54E7E, 0xFF6A6A6A};

    private static final class Note {
        int start, dur, pitch, vel, part;

        Note(int start, int dur, int pitch, int vel, int part) {
            this.start = start;
            this.dur = dur;
            this.pitch = pitch;
            this.vel = vel;
            this.part = part;
        }

        Note copy() {
            return new Note(start, dur, pitch, vel, part);
        }
    }

    private final BlockPos pos;
    private final Screen parent;
    private final @Nullable RollInfo source;
    private boolean loaded;
    private final List<Note> notes = new ArrayList<>();
    private final List<MusicRoll.Event> pedals = new ArrayList<>();
    private final List<MusicRoll.Part> parts = new ArrayList<>();
    private final Set<Note> selected = new HashSet<>();
    private String title = "";
    private String author = "";
    private int bpm = 120;
    private int beatsPerBar = 4;
    private int beatUnit = 4;
    private int part;

    private int x0, y0, gridLeft, gridTop, gridW, gridH;
    private int viewStart;
    private float msPerPx = 8f;
    private int topPitch = 79;
    private int lastDur = -1;
    private @Nullable EditBox titleBox, bpmBox;
    private @Nullable Button partButton, playButton;
    private @Nullable String notice;
    private long noticeUntil;

    // 拖动状态
    private enum Drag { NONE, CREATE, MOVE, RESIZE, BOX }
    private Drag drag = Drag.NONE;
    private @Nullable Note dragNote;
    private double dragX, dragY;
    private final List<int[]> dragOrigin = new ArrayList<>();
    private final List<Note> dragSet = new ArrayList<>();

    public RollEditorScreen(BlockPos pos, Screen parent, @Nullable RollInfo source) {
        super(Component.translatable("screen.logos_dimension.instrument.editor"));
        this.pos = pos.immutable();
        this.parent = parent;
        this.source = source;
        this.loaded = source == null;
        if (source == null) parts.add(new MusicRoll.Part(kindName(), 0));
        InstrumentKind kind = kind();
        topPitch = Math.min(kind.high(), 79);
    }

    private InstrumentKind kind() {
        InstrumentKind k = MusicClient.kindAt(pos);
        return k == null ? InstrumentKind.GRAND_PIANO : k;
    }

    private String kindName() {
        return Component.translatable("block.logos_dimension." + switch (kind()) {
            case GRAND_PIANO -> "starbloom_grand_piano";
            case CONCERT_HARP -> "hexen_concert_harp";
            case PIPE_ORGAN -> "hexen_organ_console";
        }).getString();
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("screen.logos_dimension.instrument." + key, args);
    }

    private void load(MusicRoll roll) {
        notes.clear();
        pedals.clear();
        parts.clear();
        parts.addAll(roll.parts());
        for (MusicRoll.Event e : roll.events()) {
            if (e.type() == MusicRoll.NOTE) notes.add(new Note(e.timeMs(), e.durationMs(), e.pitch(), e.velocity(), e.part()));
            else pedals.add(e);
        }
        title = roll.title();
        author = roll.author();
        bpm = Math.max(20, Math.min(400, Math.round(roll.bpmX100() / 100f)));
        beatsPerBar = roll.beatsPerBar();
        beatUnit = roll.beatUnit();
        loaded = true;
        if (!notes.isEmpty()) {
            int lowest = notes.stream().mapToInt(n -> n.pitch).min().orElse(60);
            int highest = notes.stream().mapToInt(n -> n.pitch).max().orElse(72);
            topPitch = Math.min(kind().high(), Math.max(highest + 2, lowest + gridH / ROW - 2));
        }
        rebuildWidgets();
    }

    private int stepMs() {
        return Math.max(1, Math.round(60000f / bpm / 4f));
    }

    private int snap(int ms) {
        int s = stepMs();
        return Math.max(0, Math.round(ms / (float) s) * s);
    }

    // —— 布局 ——

    @Override
    protected void init() {
        x0 = (width - W) / 2;
        y0 = (height - H) / 2;
        gridLeft = x0 + PAD + KEYS;
        gridTop = y0 + 60;
        gridW = W - PAD * 2 - KEYS;
        gridH = (H - PAD - 14 - 60) / ROW * ROW;
        int x = x0 + PAD;
        titleBox = addRenderableWidget(new EditBox(font, x, y0 + 20, 140, 16, tr("title_hint")));
        titleBox.setMaxLength(MusicRoll.MAX_TITLE);
        titleBox.setValue(title);
        titleBox.setResponder(s -> title = s);
        bpmBox = addRenderableWidget(new EditBox(font, x + 172, y0 + 20, 36, 16, tr("bpm")));
        bpmBox.setMaxLength(3);
        bpmBox.setFilter(s -> s.chars().allMatch(Character::isDigit));
        bpmBox.setValue(Integer.toString(bpm));
        bpmBox.setResponder(s -> {
            try {
                bpm = Math.max(20, Math.min(400, Integer.parseInt(s)));
            } catch (NumberFormatException ignored) {
                // 空着时保持原值
            }
        });
        partButton = addRenderableWidget(Button.builder(partLabel(), b -> cyclePart()).bounds(x + 214, y0 + 20, 132, 16).build());
        int y = y0 + 40;
        playButton = addRenderableWidget(Button.builder(tr("preview"), b -> togglePreview()).bounds(x, y, 64, 16).build());
        addRenderableWidget(Button.builder(tr("delete_selected"), b -> deleteSelected()).bounds(x + 68, y, 80, 16).build());
        addRenderableWidget(Button.builder(tr("save"), b -> save()).bounds(x + 218, y, 64, 16).build());
        addRenderableWidget(Button.builder(tr("back"), b -> onClose()).bounds(x + 286, y, 60, 16).build());
    }

    private Component partLabel() {
        String name = part < parts.size() ? parts.get(part).name() : "";
        return tr("part", part + 1, name.isEmpty() ? "—" : name);
    }

    private void cyclePart() {
        setFocused(null);
        if (part + 1 < parts.size()) {
            part++;
        } else if (parts.size() < MusicRoll.MAX_PARTS && Minecraft.getInstance().hasShiftDown()) {
            parts.add(new MusicRoll.Part(tr("new_part", parts.size() + 1).getString(), 0));
            part = parts.size() - 1;
        } else {
            part = 0;
        }
        if (partButton != null) partButton.setMessage(partLabel());
    }

    @Override
    public void tick() {
        if (!loaded && source != null) {
            MusicRoll roll = MusicClient.roll(source.id());
            if (roll != null) load(roll);
            else if (MusicClient.missing(source.id())) {
                loaded = true;
                parts.add(new MusicRoll.Part(kindName(), 0));
                say(tr("state_missing").getString());
            }
        }
        if (playButton != null) playButton.setMessage(MusicClient.previewPosition() >= 0 ? tr("preview_stop") : tr("preview"));
    }

    @Override
    public void onClose() {
        MusicClient.stopPreview();
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void say(String text) {
        notice = text;
        noticeUntil = System.currentTimeMillis() + 5000;
    }

    // —— 纸带 ——

    boolean loaded() {
        return loaded;
    }

    int noteCount() {
        return notes.size();
    }

    private MusicRoll build() {
        List<MusicRoll.Event> events = new ArrayList<>(notes.size() + pedals.size());
        for (Note n : notes) events.add(MusicRoll.Event.note(n.start, n.part, n.pitch, n.vel, n.dur));
        events.addAll(pedals);
        return new MusicRoll(title, author, bpm * 100, beatsPerBar, beatUnit, parts, events);
    }

    private void togglePreview() {
        setFocused(null);
        if (MusicClient.previewPosition() >= 0) {
            MusicClient.stopPreview();
        } else {
            MusicClient.preview(pos, build(), viewStart);
        }
    }

    private void save() {
        setFocused(null);
        var level = Minecraft.getInstance().level;
        if (level == null || !(level.getBlockEntity(pos) instanceof InstrumentBlockEntity be) || !Instruments.isRoll(be.roll())) {
            say(tr("need_blank").getString());
            return;
        }
        if (notes.isEmpty()) {
            say(tr("record_empty").getString());
            return;
        }
        try {
            MusicClient.upload(pos, MusicPayloads.Upload.WRITE, build().encode());
            onClose();
        } catch (MusicRoll.FormatException e) {
            say(e.getMessage());
        }
    }

    private void deleteSelected() {
        setFocused(null);
        notes.removeAll(selected);
        selected.clear();
    }

    // —— 坐标 ——

    private int xOf(int ms) {
        return gridLeft + Math.round((ms - viewStart) / msPerPx);
    }

    private int msAt(double x) {
        return Math.max(0, viewStart + Math.round((float) ((x - gridLeft) * msPerPx)));
    }

    private int pitchAt(double y) {
        return topPitch - (int) Math.floor((y - gridTop) / ROW);
    }

    private int yOf(int pitch) {
        return gridTop + (topPitch - pitch) * ROW;
    }

    private boolean inGrid(double x, double y) {
        return x >= gridLeft && x < gridLeft + gridW && y >= gridTop && y < gridTop + gridH;
    }

    private @Nullable Note noteAt(double x, double y) {
        int pitch = pitchAt(y);
        int ms = msAt(x);
        Note hit = null;
        for (Note n : notes) {
            if (n.pitch == pitch && ms >= n.start && ms <= n.start + Math.max(n.dur, (int) (3 * msPerPx))) hit = n;
        }
        return hit;
    }

    // —— 鼠标 ——

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (super.mouseClicked(e, doubleClick)) return true;
        setFocused(null);
        double x = e.x(), y = e.y();
        // 左侧键盘：试听
        if (x >= x0 + PAD && x < gridLeft && y >= gridTop && y < gridTop + gridH) {
            MusicClient.audition(pos, pitchAt(y));
            return true;
        }
        if (!inGrid(x, y) || !loaded) return false;
        Note hit = noteAt(x, y);
        if (e.button() == 1) {
            if (hit != null) {
                if (selected.contains(hit)) deleteSelected();
                else notes.remove(hit);
            }
            return true;
        }
        if (e.button() != 0) return false;
        dragX = x;
        dragY = y;
        if (hit == null) {
            if (e.hasShiftDown()) {
                drag = Drag.BOX;
                return true;
            }
            selected.clear();
            int pitch = pitchAt(y);
            if (pitch < kind().low() || pitch > kind().high()) return true;
            int start = snap(msAt(x) - stepMs() / 2);
            Note n = new Note(start, lastDur > 0 ? lastDur : stepMs() * 4, pitch, 96, Math.min(part, parts.size() - 1));
            notes.add(n);
            dragNote = n;
            drag = Drag.CREATE;
            MusicClient.audition(pos, pitch);
            return true;
        }
        if (e.hasShiftDown()) {
            if (!selected.remove(hit)) selected.add(hit);
            return true;
        }
        dragNote = hit;
        int right = xOf(hit.start + hit.dur);
        drag = x >= right - 3 ? Drag.RESIZE : Drag.MOVE;
        dragSet.clear();
        dragOrigin.clear();
        if (selected.contains(hit)) dragSet.addAll(selected);
        else {
            selected.clear();
            dragSet.add(hit);
        }
        for (Note n : dragSet) dragOrigin.add(new int[]{n.start, n.pitch, n.dur});
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (drag == Drag.NONE) return super.mouseDragged(e, dx, dy);
        double x = e.x(), y = e.y();
        switch (drag) {
            case CREATE -> {
                if (dragNote != null) {
                    int end = snap(msAt(x));
                    dragNote.dur = Math.max(stepMs(), Math.min(MusicRoll.MAX_NOTE_MS, end - dragNote.start));
                }
            }
            case RESIZE -> {
                int delta = snap(msAt(x)) - snap(msAt(dragX));
                for (int i = 0; i < dragSet.size(); i++) {
                    dragSet.get(i).dur = Math.max(stepMs(), Math.min(MusicRoll.MAX_NOTE_MS, dragOrigin.get(i)[2] + delta));
                }
            }
            case MOVE -> {
                int dms = Math.round((float) ((x - dragX) * msPerPx));
                int step = stepMs();
                dms = Math.round(dms / (float) step) * step;
                int dp = -(int) Math.round((y - dragY) / ROW);
                for (int i = 0; i < dragSet.size(); i++) {
                    Note n = dragSet.get(i);
                    int[] o = dragOrigin.get(i);
                    n.start = Math.max(0, o[0] + dms);
                    n.pitch = Math.max(kind().low(), Math.min(kind().high(), o[1] + dp));
                }
            }
            default -> { }
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        if (drag == Drag.BOX) {
            int a = msAt(Math.min(dragX, e.x())), b = msAt(Math.max(dragX, e.x()));
            int hi = pitchAt(Math.min(dragY, e.y())), lo = pitchAt(Math.max(dragY, e.y()));
            for (Note n : notes) {
                if (n.pitch >= lo && n.pitch <= hi && n.start + n.dur >= a && n.start <= b) selected.add(n);
            }
        }
        if ((drag == Drag.CREATE || drag == Drag.RESIZE) && dragNote != null) lastDur = dragNote.dur;
        drag = Drag.NONE;
        dragNote = null;
        return super.mouseReleased(e);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        var mc = Minecraft.getInstance();
        if (mc.hasControlDown()) {
            msPerPx = Math.max(1f, Math.min(80f, msPerPx * (scrollY > 0 ? 0.8f : 1.25f)));
        } else if (mc.hasShiftDown() || scrollX != 0) {
            double d = scrollX != 0 ? scrollX : scrollY;
            viewStart = Math.max(0, viewStart - (int) (d * 40 * msPerPx));
        } else {
            int rows = gridH / ROW;
            topPitch = Math.max(kind().low() + rows - 1, Math.min(kind().high(), topPitch + (int) Math.signum(scrollY) * 2));
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (getFocused() instanceof EditBox box && box.isFocused() && !e.isEscape()) return super.keyPressed(e);
        int key = e.key();
        if (key == KEY_DELETE || key == KEY_BACKSPACE) {
            deleteSelected();
            return true;
        }
        if (key == KEY_SPACE) {
            togglePreview();
            return true;
        }
        if (e.hasControlDown() && key == KEY_A) {
            selected.addAll(notes);
            return true;
        }
        if (e.hasControlDown() && key == KEY_D && !selected.isEmpty()) {
            int first = selected.stream().mapToInt(n -> n.start).min().orElse(0);
            int last = selected.stream().mapToInt(n -> n.start + n.dur).max().orElse(0);
            int shift = snap(last - first);
            List<Note> copies = new ArrayList<>();
            for (Note n : selected) {
                Note c = n.copy();
                c.start += shift;
                copies.add(c);
            }
            notes.addAll(copies);
            selected.clear();
            selected.addAll(copies);
            return true;
        }
        return super.keyPressed(e);
    }

    // —— 绘制 ——

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        Parchment.draw(g, x0, y0, W, H);
        g.text(font, tr("bpm"), x0 + PAD + 146, y0 + 24, Parchment.INK, false);
        int keysLeft = x0 + PAD;
        int rows = gridH / ROW;
        // 网格底色：黑键行深一点
        for (int r = 0; r < rows; r++) {
            int pitch = topPitch - r;
            int y = gridTop + r * ROW;
            boolean out = pitch < kind().low() || pitch > kind().high();
            int bg = out ? 0xFFB8AA90 : KeyLayout.isBlack(pitch) ? 0xFFE2D6BC : 0xFFF2EAD6;
            g.fill(gridLeft, y, gridLeft + gridW, y + ROW, bg);
            g.fill(keysLeft, y, gridLeft - 1, y + ROW, KeyLayout.isBlack(pitch) ? 0xFF2A1C14 : 0xFFF8F2E4);
            if (Math.floorMod(pitch, 12) == 0) {
                g.fill(gridLeft, y + ROW - 1, gridLeft + gridW, y + ROW, 0xFFC8B898);
                g.text(font, Component.literal(KeyLayout.noteName(pitch)), keysLeft + 1, y - 2, Parchment.INK_HINT, false);
            }
        }
        // 拍线与小节线
        int beatMs = Math.round(60000f / bpm);
        int step = stepMs();
        int firstStep = viewStart / step;
        for (int s = firstStep; ; s++) {
            int ms = s * step;
            int x = xOf(ms);
            if (x >= gridLeft + gridW) break;
            if (x < gridLeft) continue;
            boolean beat = ms % beatMs < step / 2 || beatMs - ms % beatMs < step / 2;
            boolean bar = beat && Math.round(ms / (float) beatMs) % beatsPerBar == 0;
            if (!beat && step / msPerPx < 4) continue;
            g.fill(x, gridTop, x + 1, gridTop + gridH, bar ? 0xFF8C7458 : beat ? 0xFFC0AE8C : 0xFFE0D4BA);
        }
        // 音符
        g.enableScissor(gridLeft, gridTop, gridLeft + gridW, gridTop + gridH);
        for (Note n : notes) {
            int xa = xOf(n.start), xb = Math.max(xa + 2, xOf(n.start + n.dur));
            if (xb < gridLeft || xa > gridLeft + gridW || n.pitch > topPitch || n.pitch <= topPitch - rows) continue;
            int y = yOf(n.pitch);
            int color = PART_COLORS[n.part % PART_COLORS.length];
            if (n.part != part) color = (color & 0x00FFFFFF) | 0x88000000;
            g.fill(xa, y, xb, y + ROW - 1, color);
            if (selected.contains(n)) g.outline(xa, y, xb - xa, ROW - 1, Parchment.GOLD);
        }
        int play = MusicClient.previewPosition();
        if (play >= 0) {
            int x = xOf(play);
            g.fill(x, gridTop, x + 1, gridTop + gridH, 0xFFD02020);
            if (x > gridLeft + gridW - 10) viewStart = play;
        }
        if (drag == Drag.BOX) {
            int ax = (int) Math.min(dragX, mouseX), ay = (int) Math.min(dragY, mouseY);
            g.outline(ax, ay, (int) Math.abs(mouseX - dragX), (int) Math.abs(mouseY - dragY), Parchment.BRASS);
        }
        g.disableScissor();
        g.outline(gridLeft - 1, gridTop - 1, gridW + 2, gridH + 2, Parchment.INK);
        // 底部：位置、音符数、提示
        String where = RollInfo.clock(viewStart) + " · " + tr("notes_count", notes.size()).getString();
        if (!loaded) where = tr("loading").getString();
        g.text(font, Component.literal(where), x0 + PAD, y0 + H - PAD - 8, Parchment.INK, false);
        if (notice != null && System.currentTimeMillis() < noticeUntil) {
            g.text(font, Component.literal(font.plainSubstrByWidth(notice, W - PAD * 2 - 110)), x0 + PAD + 110, y0 + H - PAD - 8, 0xFF8A2A1A, false);
        } else {
            String hint = font.plainSubstrByWidth(tr("editor_hint").getString(), W - PAD * 2 - 110);
            g.text(font, Component.literal(hint), x0 + PAD + 110, y0 + H - PAD - 8, Parchment.INK_HINT, false);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }
}
