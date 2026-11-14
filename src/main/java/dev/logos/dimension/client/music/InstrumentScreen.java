package dev.logos.dimension.client.music;

import dev.logos.dimension.music.InstrumentBlockEntity;
import dev.logos.dimension.music.InstrumentKind;
import dev.logos.dimension.music.Instruments;
import dev.logos.dimension.music.MusicPayloads;
import dev.logos.dimension.music.MusicRoll;
import dev.logos.dimension.music.RollInfo;
import dev.logos.dimension.music.organ.OrganStop;
import dev.logos.dimension.music.organ.RoomAcoustics;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * 乐器界面，两页：
 * <ul>
 *   <li>演奏：屏幕键盘 + 键盘实时弹奏（键位见 {@link KeyLayout}），←→ 换八度、↑↓ 改力度、空格延音；
 *       可以录制成纸带。打开这一页时向服务端「认领」演奏者，同一台乐器同时只能一个人弹。</li>
 *   <li>纸带：纸带槽里的纸带信息、播放/暂停/停止/取出、选择演奏哪些声部、红石模式、打孔编辑器、导入 MIDI。</li>
 * </ul>
 */
public class InstrumentScreen extends Screen implements MidiKeyboard.Target {
    private static final int W = 400, H = 226, PAD = 28;
    private static final int KEY_LEFT = 263, KEY_RIGHT = 262, KEY_UP = 265, KEY_DOWN = 264, KEY_SPACE = 32;

    private final BlockPos pos;
    private static final int PLAY = 0, ROLL = 1, STOPS = 2;
    private int page = PLAY;
    private boolean diatonic;
    private int base;
    /** 键盘起点只在第一次打开时按乐器的音域选（之后换八度、重建控件时保留）。 */
    private boolean baseChosen;
    private int velocity = 96;
    private boolean pedal;
    private final boolean[] sharp = new boolean[7];
    /** 按住的键：键码 → 发出的音高。松开时按这里的音高止音，换八度不会卡音。 */
    private final Map<Integer, Integer> held = new HashMap<>();
    /** 电子琴上按住的音。 */
    private final java.util.Set<Integer> midiHeld = new java.util.HashSet<>();
    private int mousePitch = -1;
    private int x0, y0;
    private @Nullable Button recordButton;
    private @Nullable String notice;
    private long noticeUntil;

    public InstrumentScreen(BlockPos pos) {
        super(Component.translatable("screen.logos_dimension.instrument.title"));
        this.pos = pos.immutable();
        this.base = 48;
    }

    /** 这个界面演奏的乐器（主块）。 */
    BlockPos pos() {
        return pos;
    }

    private @Nullable InstrumentBlockEntity entity() {
        var level = Minecraft.getInstance().level;
        return level != null && level.getBlockEntity(pos) instanceof InstrumentBlockEntity be ? be : null;
    }

    private InstrumentKind kind() {
        InstrumentKind k = MusicClient.kindAt(pos);
        return k == null ? InstrumentKind.GRAND_PIANO : k;
    }

    private boolean busy() {
        InstrumentBlockEntity be = entity();
        var me = Minecraft.getInstance().player;
        return be != null && me != null && be.performer() != null && !be.performer().equals(me.getUUID());
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("screen.logos_dimension.instrument." + key, args);
    }

    // —— 布局 ——

    @Override
    protected void init() {
        x0 = (width - W) / 2;
        y0 = (height - H) / 2;
        recordButton = null;
        int right = x0 + W - PAD;
        boolean organ = kind() == InstrumentKind.PIPE_ORGAN;
        if (!baseChosen) {
            // 键盘从和这件乐器音域重合最多的那个 C 开始（以前一律 C3：钟琴、管钟在默认位置弹音阶会忽高忽低）
            base = KeyLayout.defaultBase(kind(), KeyLayout.chromatic().length(), 48);
            baseChosen = true;
        }
        // 先把当前力度那一层的采样解码好（钢琴一层 30 个），第一下不慢半拍
        if (!organ) SampleMaps.prewarm(kind(), velocity);
        int tx = right - (organ ? 156 : 104);
        addRenderableWidget(Button.builder(tr("tab_play"), b -> page(PLAY)).bounds(tx, y0 + 20, 50, 16).build()).active = page != PLAY;
        if (organ) {
            addRenderableWidget(Button.builder(tr("tab_stops"), b -> page(STOPS)).bounds(tx + 52, y0 + 20, 50, 16).build()).active = page != STOPS;
        }
        addRenderableWidget(Button.builder(tr("tab_roll"), b -> page(ROLL)).bounds(right - 50, y0 + 20, 50, 16).build()).active = page != ROLL;
        // 只有演奏页接收电子琴
        if (page == PLAY) MidiKeyboard.setTarget(this);
        else MidiKeyboard.clearTarget(this);
        switch (page) {
            case ROLL -> initRollPage();
            case STOPS -> initStopsPage();
            default -> initPlayPage();
        }
    }

    /** 场景测试用：切到纸带页。 */
    void showRollPage() {
        page(ROLL);
    }

    /** 场景测试用：切到音栓页。 */
    void showStopsPage() {
        page(STOPS);
    }

    private void page(int to) {
        if (to == page) return;
        releaseAll();
        if (page == PLAY) MusicClient.control(pos, MusicPayloads.Control.RELEASE, 0);
        if (to == PLAY) MusicClient.control(pos, MusicPayloads.Control.CLAIM, 0);
        page = to;
        rebuildWidgets();
    }

    private String nameKey() {
        return kind().nameKey();
    }

    // —— 音栓页（管风琴）——

    private void initStopsPage() {
        InstrumentBlockEntity be = entity();
        if (be == null) return;
        var organ = be.organ();
        int available = organ == null ? OrganStop.ALWAYS : organ.availableStops();
        int stops = be.stops();
        int x = x0 + PAD, y = y0 + 58;
        OrganStop[] all = OrganStop.values();
        for (int i = 0; i < all.length; i++) {
            OrganStop s = all[i];
            boolean on = s.in(stops);
            Component label = Component.literal((on ? "● " : "○ ")).append(tr("stop." + s.id()));
            Button b = Button.builder(label, btn -> {
                InstrumentBlockEntity e = entity();
                if (e != null) MusicClient.control(pos, MusicPayloads.Control.STOPS, e.stops() ^ s.bit());
            }).bounds(x + (i % 4) * 86, y + (i / 4) * 15, 84, 14).build();
            b.active = s.in(available);
            addRenderableWidget(b);
        }
        int py = y0 + 118;
        OrganStop.Preset[] presets = OrganStop.Preset.values();
        for (int i = 0; i < presets.length; i++) {
            OrganStop.Preset p = presets[i];
            Button b = Button.builder(tr("preset." + p.id()), btn -> MusicClient.control(pos, MusicPayloads.Control.STOPS, p.mask()))
                .bounds(x + (i % 3) * 115, py + (i / 3) * 15, 112, 14).build();
            // 预设里有音管不够的音栓时也能按：服务端只保留能用的那些
            b.active = (p.mask() & available) != 0;
            addRenderableWidget(b);
        }
        int mode = be.reverbMode();
        addRenderableWidget(Button.builder(tr("reverb", tr("reverb." + mode)), b -> {
            InstrumentBlockEntity e = entity();
            if (e != null) MusicClient.control(pos, MusicPayloads.Control.REVERB, (e.reverbMode() + 1) % 4);
        }).bounds(x, y0 + H - PAD - 16, 170, 16).build());
        MusicClient.prewarmOrgan(pos);
    }

    private void drawStopsPage(GuiGraphicsExtractor g) {
        InstrumentBlockEntity be = entity();
        if (be == null) return;
        var organ = be.organ();
        int x = x0 + PAD;
        if (organ != null) {
            g.text(font, tr("pipes", organ.tin(), organ.wood(), organ.reed(), organ.chests(), organ.range()), x, y0 + 44, Parchment.INK, false);
        }
        g.text(font, tr("presets"), x, y0 + 107, Parchment.INK_HINT, false);
        RoomAcoustics.Reverb auto = OrganRoom.estimate(pos, be);
        Component room;
        if (OrganRoom.soundPhysics() && be.reverbMode() == RoomAcoustics.AUTO) room = tr("room_sound_physics");
        else if (!auto.on()) room = tr("room_open");
        else room = tr("room_inside", String.format("%.1f", auto.seconds()));
        g.text(font, room, x + 176, y0 + H - PAD - 12, Parchment.INK_HINT, false);
    }

    private void initPlayPage() {
        int y = y0 + 166;
        int x = x0 + PAD;
        addRenderableWidget(Button.builder(Component.literal("◀"), b -> shiftOctave(-1)).bounds(x, y, 20, 16).build());
        addRenderableWidget(Button.builder(Component.literal("▶"), b -> shiftOctave(1)).bounds(x + 22, y, 20, 16).build());
        recordButton = addRenderableWidget(Button.builder(recordLabel(), b -> toggleRecording()).bounds(x + 48, y, 92, 16).build());
        if (kind() == InstrumentKind.CONCERT_HARP) {
            addRenderableWidget(Button.builder(diatonic ? tr("mode_diatonic") : tr("mode_chromatic"), b -> {
                releaseAll();
                diatonic = !diatonic;
                base = diatonic ? 36 : 48;
                rebuildWidgets();
            }).bounds(x + 146, y, 92, 16).build());
        }
        addRenderableWidget(Button.builder(midiLabel(), b -> minecraft.setScreen(new MidiDeviceScreen(this)))
            .bounds(x0 + W - PAD - 96, y, 96, 16).build());
    }

    private Component midiLabel() {
        String name = MidiKeyboard.connected();
        return name == null ? tr("midi_button") : tr("midi_button_on");
    }

    private void initRollPage() {
        int x = x0 + PAD, y = y0 + 92;
        addRenderableWidget(Button.builder(tr("play"), b -> MusicClient.control(pos, MusicPayloads.Control.PLAY, 0)).bounds(x, y, 60, 16).build());
        addRenderableWidget(Button.builder(tr("pause"), b -> MusicClient.control(pos, MusicPayloads.Control.PAUSE, 0)).bounds(x + 64, y, 60, 16).build());
        addRenderableWidget(Button.builder(tr("stop"), b -> MusicClient.control(pos, MusicPayloads.Control.STOP, 0)).bounds(x + 128, y, 60, 16).build());
        addRenderableWidget(Button.builder(tr("eject"), b -> MusicClient.control(pos, MusicPayloads.Control.EJECT, 0)).bounds(x + 192, y, 60, 16).build());
        // 声部
        InstrumentBlockEntity be = entity();
        RollInfo info = be == null ? null : be.roll().get(Instruments.MUSIC_ROLL.get());
        if (info != null && info.parts().size() > 1) {
            int mask = be.partMask();
            for (int i = 0; i < info.parts().size() && i < MusicRoll.MAX_PARTS; i++) {
                int bit = 1 << i;
                String name = info.parts().get(i);
                String shown = (mask & bit) != 0 ? "✔ " + name : "  " + name;
                if (font.width(shown) > 80) shown = font.plainSubstrByWidth(shown, 76) + "…";
                int col = i % 4, row = i / 4;
                addRenderableWidget(Button.builder(Component.literal(shown), b -> {
                    InstrumentBlockEntity e = entity();
                    if (e != null) MusicClient.control(pos, MusicPayloads.Control.PART_MASK, e.partMask() ^ bit);
                }).bounds(x + col * 86, y0 + 126 + row * 12, 84, 11).build());
            }
        }
        int by = y0 + H - PAD - 16;
        addRenderableWidget(Button.builder(tr("editor"), b -> openEditor()).bounds(x, by, 84, 16).build());
        addRenderableWidget(Button.builder(tr("import"), b -> minecraft.setScreen(new MidiImportScreen(pos, this))).bounds(x + 88, by, 84, 16).build());
        boolean stopMode = be != null && be.stopWithSignal();
        addRenderableWidget(Button.builder(stopMode ? tr("signal_stop") : tr("signal_start"), b -> {
            InstrumentBlockEntity e = entity();
            if (e != null) MusicClient.control(pos, MusicPayloads.Control.SIGNAL_MODE, e.stopWithSignal() ? 0 : 1);
        }).bounds(x + 176, by, 168, 16).build());
    }

    /** 纸带槽里的东西变了（服务端同步过来）就重排按钮。 */
    private String lastSlot = "";

    @Override
    public void tick() {
        InstrumentBlockEntity be = entity();
        var me = Minecraft.getInstance().player;
        // 离开太远、这里不再是一件乐器（谱台前的乐手站起来、乐器拿走了）时关掉
        if (be == null || me == null || me.distanceToSqr(be.soundCenter()) > Instruments.REACH * Instruments.REACH
                || MusicClient.kindAt(pos) == null) {
            onClose();
            return;
        }
        String slot = "";
        if (page == ROLL) slot = be.roll().getComponents().toString() + be.partMask() + be.stopWithSignal();
        if (page == STOPS) {
            var o = be.organ();
            slot = be.stops() + "/" + be.reverbMode() + "/" + (o == null ? "" : o.availableStops());
        }
        if (page != PLAY && !slot.equals(lastSlot)) {
            lastSlot = slot;
            rebuildWidgets();
        }
        if (recordButton != null) {
            recordButton.setMessage(recordLabel());
            recordButton.active = MusicClient.recorder != null || Instruments.isRoll(be.roll());
        }
    }

    @Override
    public void added() {
        super.added();
        if (page == PLAY) MusicClient.control(pos, MusicPayloads.Control.CLAIM, 0);
        MidiKeyboard.autoConnect();
        MusicClient.prewarmOrgan(pos);
    }

    @Override
    public void removed() {
        MidiKeyboard.clearTarget(this);
        releaseAll();
        if (MusicClient.recorder != null) MusicClient.recorder = null;
        MusicClient.control(pos, MusicPayloads.Control.RELEASE, 0);
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void say(String text) {
        notice = text;
        noticeUntil = System.currentTimeMillis() + 4000;
    }

    // —— 弹奏 ——

    private void shiftOctave(int d) {
        releaseAll();
        base = diatonic ? Math.max(24, Math.min(72, base + 12 * d)) : KeyLayout.clampBase(kind(), base + 12 * d, KeyLayout.chromatic().length());
    }

    /** 不在这件乐器音域里的键不出声（不按八度折叠，界面上画暗）。 */
    private boolean playable(int pitch) {
        InstrumentKind k = kind();
        return pitch >= k.low() && pitch <= k.high();
    }

    private void press(int key, int pitch) {
        if (busy() || held.containsKey(key) || !playable(pitch)) return;
        held.put(key, pitch);
        MusicClient.localNote(pos, pitch, velocity);
    }

    private void lift(int key) {
        Integer pitch = held.remove(key);
        if (pitch != null) MusicClient.localNote(pos, pitch, 0);
    }

    private void releaseAll() {
        for (int key : new ArrayList<>(held.keySet())) lift(key);
        midiAllOff();
        if (mousePitch >= 0) {
            MusicClient.localNote(pos, mousePitch, 0);
            mousePitch = -1;
        }
        if (pedal) {
            pedal = false;
            MusicClient.localPedal(pos, false);
        }
    }

    // —— 电子琴 ——

    @Override
    public void midiNote(int pitch, int velocity, long nanos) {
        if (!playable(pitch)) return;
        if (page != PLAY || busy()) return;
        // 同一个键还按着又来一次「按下」（有的琴会这样）：先松开
        if (!midiHeld.add(pitch)) MusicClient.localNote(pos, pitch, 0, nanos);
        MusicClient.localNote(pos, pitch, velocity, nanos);
    }

    @Override
    public void midiOff(int pitch, long nanos) {
        if (midiHeld.remove(pitch)) MusicClient.localNote(pos, pitch, 0, nanos);
    }

    @Override
    public void midiPedal(boolean down, long nanos) {
        if (page != PLAY || busy() || down == pedal) return;
        pedal = down;
        MusicClient.localPedal(pos, down, nanos);
    }

    @Override
    public void midiAllOff() {
        long now = System.nanoTime();
        for (int p : new ArrayList<>(midiHeld)) MusicClient.localNote(pos, p, 0, now);
        midiHeld.clear();
    }

    @Override
    public void midiProgram(int program) {
        // 管风琴：琴上的音色切换键轮流切六个预设
        if (kind() != InstrumentKind.PIPE_ORGAN) return;
        OrganStop.Preset[] presets = OrganStop.Preset.values();
        MusicClient.control(pos, MusicPayloads.Control.STOPS, presets[Math.floorMod(program, presets.length)].mask());
    }

    /** 测试用：电子琴上正按着几个音。 */
    int midiHeld() {
        return midiHeld.size();
    }

    /** 这个键码此刻对应的音高；没有映射时 -1。 */
    private int pitchFor(int key) {
        if (diatonic) {
            int i = KeyLayout.whiteIndex(key);
            return i < 0 ? -1 : KeyLayout.whitePitch(base, i, sharp);
        }
        int off = KeyLayout.chromaticOffset(KeyLayout.chromatic(), key);
        return off < 0 ? -1 : base + off;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (page != PLAY || event.isEscape()) return super.keyPressed(event);
        int key = event.key();
        switch (key) {
            case KEY_LEFT -> shiftOctave(-1);
            case KEY_RIGHT -> shiftOctave(1);
            case KEY_UP -> velocity = Math.min(127, velocity + 16);
            case KEY_DOWN -> velocity = Math.max(16, velocity - 16);
            case KEY_SPACE -> {
                if (!pedal && !busy()) {
                    pedal = true;
                    MusicClient.localPedal(pos, true);
                }
            }
            default -> {
                int degree = diatonic ? KeyLayout.pedalDegree(key) : -1;
                if (degree >= 0) {
                    sharp[degree] = !sharp[degree];
                } else {
                    int pitch = pitchFor(key);
                    if (pitch >= 0) press(key, pitch);
                }
            }
        }
        // 演奏页的键都归乐器，不交给按钮（空格不会按下有焦点的按钮）
        setFocused(null);
        return true;
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (page != PLAY) return super.keyReleased(event);
        if (event.key() == KEY_SPACE) {
            if (pedal) {
                pedal = false;
                MusicClient.localPedal(pos, false);
            }
            return true;
        }
        lift(event.key());
        return true;
    }

    // —— 录制 ——

    private Component recordLabel() {
        var r = MusicClient.recorder;
        return r == null ? tr("record") : tr("record_stop", RollInfo.clock(r.elapsedMs()));
    }

    private void toggleRecording() {
        setFocused(null);
        var r = MusicClient.recorder;
        if (r == null) {
            InstrumentBlockEntity be = entity();
            if (be == null || !Instruments.isRoll(be.roll())) {
                say(tr("need_blank").getString());
                return;
            }
            MusicClient.recorder = new MusicClient.Recorder();
            return;
        }
        MusicClient.recorder = null;
        if (r.count() == 0) {
            say(tr("record_empty").getString());
            return;
        }
        String title = tr("record_title", LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))).getString();
        MusicRoll roll = r.finish(Component.translatable(nameKey()).getString()).withMeta(title, "");
        try {
            MusicClient.upload(pos, MusicPayloads.Upload.WRITE, roll.encode());
        } catch (MusicRoll.FormatException e) {
            say(e.getMessage());
        }
    }

    private void openEditor() {
        InstrumentBlockEntity be = entity();
        if (be == null) return;
        minecraft.setScreen(new RollEditorScreen(pos, this, be.roll().get(Instruments.MUSIC_ROLL.get())));
    }

    // —— 屏幕键盘 ——

    private record KeyRect(int pitch, int x, int y, int w, int h, boolean black, String label) {}

    private List<KeyRect> keyboard() {
        List<KeyRect> keys = new ArrayList<>();
        int left = x0 + PAD, top = y0 + 64, area = W - PAD * 2, height = 92;
        if (diatonic) {
            int n = KeyLayout.DIATONIC.length();
            int kw = area / n;
            for (int i = 0; i < n; i++) {
                keys.add(new KeyRect(KeyLayout.whitePitch(base, i, sharp), left + i * kw, top, kw - 1, height, false,
                    KeyLayout.label(KeyLayout.DIATONIC.charAt(i))));
            }
            return keys;
        }
        String layout = KeyLayout.chromatic();
        int whites = 0;
        for (int i = 0; i < layout.length(); i++) if (!KeyLayout.isBlack(base + i)) whites++;
        int kw = Math.min(22, area / Math.max(1, whites));
        int wi = 0;
        List<KeyRect> blacks = new ArrayList<>();
        for (int i = 0; i < layout.length(); i++) {
            int p = base + i;
            String label = KeyLayout.label(layout.charAt(i));
            if (KeyLayout.isBlack(p)) {
                int bw = kw * 3 / 5;
                blacks.add(new KeyRect(p, left + wi * kw - bw / 2, top, bw, height * 3 / 5, true, label));
            } else {
                keys.add(new KeyRect(p, left + wi * kw, top, kw - 1, height, false, label));
                wi++;
            }
        }
        keys.addAll(blacks);
        return keys;
    }

    private boolean sounding(int pitch) {
        return held.containsValue(pitch) || mousePitch == pitch || midiHeld.contains(pitch);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (page == PLAY && event.button() == 0 && !busy()) {
            List<KeyRect> keys = keyboard();
            for (int i = keys.size() - 1; i >= 0; i--) {
                KeyRect k = keys.get(i);
                if (event.x() >= k.x && event.x() < k.x + k.w && event.y() >= k.y && event.y() < k.y + k.h) {
                    if (!playable(k.pitch)) return true;
                    mousePitch = k.pitch;
                    MusicClient.localNote(pos, k.pitch, velocity);
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (mousePitch >= 0) {
            MusicClient.localNote(pos, mousePitch, 0);
            mousePitch = -1;
        }
        return super.mouseReleased(event);
    }

    // —— 绘制 ——

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        Parchment.draw(g, x0, y0, W, H);
        Component name = Component.translatable(nameKey());
        g.text(font, name, x0 + PAD, y0 + 24, Parchment.INK_TITLE, false);
        // 音源：合成（模组本体）或音乐厅录音（资源包）
        Component source = SampleMaps.sourceLabel(kind());
        if (source != null) g.text(font, source, x0 + PAD, y0 + 36, Parchment.INK_HINT, false);
        switch (page) {
            case ROLL -> drawRollPage(g);
            case STOPS -> drawStopsPage(g);
            default -> drawPlayPage(g);
        }
        if (notice != null && System.currentTimeMillis() < noticeUntil) {
            g.text(font, Component.literal(notice), x0 + PAD, y0 + H - PAD - 30, 0xFF8A2A1A, false);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void drawPlayPage(GuiGraphicsExtractor g) {
        InstrumentBlockEntity be = entity();
        Component status;
        if (busy() && be != null) {
            var other = Minecraft.getInstance().level.getPlayerByUUID(be.performer());
            status = tr("busy", other == null ? "?" : other.getName().getString());
        } else {
            if (kind() == InstrumentKind.PIPE_ORGAN) {
                // 管风琴不分力度、没有延音踏板：显示开着几个音栓
                status = tr("status_organ", KeyLayout.noteName(base), be == null ? 0 : Integer.bitCount(be.effectiveStops()));
            } else {
                status = tr("status", KeyLayout.noteName(base), velocity, pedal ? "●" : "○");
            }
        }
        g.text(font, status, x0 + PAD, y0 + 44, Parchment.INK, false);
        var r = MusicClient.recorder;
        if (r != null) {
            g.text(font, tr("recording", RollInfo.clock(r.elapsedMs()), r.count()), x0 + PAD + 200, y0 + 44, 0xFFA02020, false);
        }
        for (KeyRect k : keyboard()) {
            boolean on = sounding(k.pitch);
            boolean out = !playable(k.pitch);
            int fill = k.black ? (on ? Parchment.GOLD : out ? 0xFF5A4A40 : 0xFF241812) : (on ? 0xFFF4D27A : out ? 0xFFD8CCB4 : 0xFFF6F0E2);
            g.fill(k.x, k.y, k.x + k.w, k.y + k.h, fill);
            g.outline(k.x, k.y, k.w, k.h, k.black ? 0xFF120A06 : 0xFF8C7A60);
            int labelColor = k.black ? 0xFFE8DCC0 : Parchment.INK_HINT;
            int lx = k.x + (k.w - font.width(k.label)) / 2;
            g.text(font, Component.literal(k.label), lx, k.y + k.h - 11, labelColor, false);
            if (!k.black && Math.floorMod(k.pitch, 12) == 0) {
                g.text(font, Component.literal(KeyLayout.noteName(k.pitch)), k.x + 1, k.y + 2, 0xFFB09A78, false);
            }
        }
        if (diatonic) {
            StringBuilder sb = new StringBuilder();
            String[] names = {"C", "D", "E", "F", "G", "A", "B"};
            for (int i = 0; i < 7; i++) sb.append(i + 1).append(':').append(names[i]).append(sharp[i] ? "♯ " : "♮ ");
            g.text(font, Component.literal(sb.toString()), x0 + PAD, y0 + 158 - 2, Parchment.INK_HINT, false);
        }
        Component hint = kind() == InstrumentKind.PIPE_ORGAN ? tr("hint_organ") : diatonic ? tr("hint_diatonic") : tr("hint");
        g.text(font, hint, x0 + PAD, y0 + H - PAD - 10, Parchment.INK_HINT, false);
    }

    private void drawRollPage(GuiGraphicsExtractor g) {
        InstrumentBlockEntity be = entity();
        int x = x0 + PAD, y = y0 + 44;
        ItemStack slot = be == null ? ItemStack.EMPTY : be.roll();
        RollInfo info = slot.get(Instruments.MUSIC_ROLL.get());
        if (slot.isEmpty()) {
            g.text(font, tr("slot_empty"), x, y, Parchment.INK, false);
        } else if (info == null) {
            g.text(font, tr("slot_blank"), x, y, Parchment.INK, false);
        } else {
            g.text(font, Component.literal(info.title().isEmpty() ? "—" : info.title()), x, y, Parchment.INK_TITLE, false);
            g.text(font, tr("slot_info", info.author().isEmpty() ? "—" : info.author(), RollInfo.clock(info.durationMs()), info.noteCount()),
                x, y + 12, Parchment.INK, false);
        }
        MusicClient.Track t = MusicClient.track(pos);
        Component state;
        if (t == null) {
            state = tr("state_stopped");
        } else {
            int now = t.positionMs(System.nanoTime());
            int total = t.roll == null ? 0 : t.roll.durationMs();
            state = tr(t.playing ? "state_playing" : "state_paused", RollInfo.clock(now), RollInfo.clock(total));
            if (t.roll == null && t.rollId != null && MusicClient.missing(t.rollId)) state = tr("state_missing");
        }
        g.text(font, state, x, y + 28, Parchment.INK_HINT, false);
        if (info != null && info.parts().size() > 1) g.text(font, tr("parts"), x, y0 + 114, Parchment.INK_HINT, false);
    }
}
