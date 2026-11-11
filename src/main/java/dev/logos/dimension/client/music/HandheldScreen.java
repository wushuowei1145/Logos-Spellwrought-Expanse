package dev.logos.dimension.client.music;

import dev.logos.dimension.music.InstrumentKind;
import dev.logos.dimension.music.Instruments;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;

/**
 * 拿在手上的乐器的演奏条：贴在屏幕底部的一条羊皮纸，上面是屏幕键盘，世界照常显示（不模糊、不暂停），
 * 自己的演奏姿势别人也看得到。键位同乐器界面的演奏页（{@link KeyLayout}），←→ 换八度、↑↓ 改力度；
 * 电子琴也能弹（{@link MidiKeyboard}）。乐器离手或关掉演奏条时全部松开，附近的人那里也一起松开。
 */
public class HandheldScreen extends Screen implements MidiKeyboard.Target {
    private static final int W = 400, H = 104;
    private static final int KEY_LEFT = 263, KEY_RIGHT = 262, KEY_UP = 265, KEY_DOWN = 264;

    private final InstrumentKind kind;
    private final InteractionHand hand;
    private int base = 60;
    private int velocity = 96;
    private final Map<Integer, Integer> held = new HashMap<>();
    private final Set<Integer> midiHeld = new HashSet<>();
    private int mousePitch = -1;
    private int x0, y0;

    public HandheldScreen(InstrumentKind kind, InteractionHand hand) {
        super(Component.translatable(kind.nameKey()));
        this.kind = kind;
        this.hand = hand;
        // 键盘从和音域重合最多的那个 C 开始（以前固定从 C4，巴松管的低音一半弹不到）
        this.base = KeyLayout.defaultBase(kind, KeyLayout.chromatic().length(), 60);
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("screen.logos_dimension.instrument." + key, args);
    }

    InstrumentKind kind() {
        return kind;
    }

    @Override
    protected void init() {
        x0 = (width - W) / 2;
        y0 = height - H - 6;
        MidiKeyboard.setTarget(this);
    }

    @Override
    public void added() {
        super.added();
        MidiKeyboard.autoConnect();
    }

    @Override
    public void removed() {
        MidiKeyboard.clearTarget(this);
        releaseAll();
        MusicClient.localHeldOff(kind);
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 不画模糊的背景：演奏时看得到世界和别的乐手。 */
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
    }

    @Override
    public void tick() {
        var me = Minecraft.getInstance().player;
        // 乐器离手（被换掉、丢掉）就收起
        if (me == null || Instruments.heldKind(me.getItemInHand(hand)) != kind) onClose();
    }

    // —— 弹奏 ——

    private void note(int pitch, int vel, long nanos) {
        MusicClient.localHeld(kind, pitch, vel, nanos);
    }

    private void shiftOctave(int d) {
        releaseAll();
        base = KeyLayout.clampBase(kind, base + 12 * d, KeyLayout.chromatic().length());
    }

    /** 不在这件乐器音域里的键不出声（不按八度折叠，界面上画暗）。 */
    private boolean playable(int pitch) {
        return pitch >= kind.low() && pitch <= kind.high();
    }

    private void releaseAll() {
        long now = System.nanoTime();
        for (int p : new ArrayList<>(held.values())) note(p, 0, now);
        held.clear();
        for (int p : new ArrayList<>(midiHeld)) note(p, 0, now);
        midiHeld.clear();
        if (mousePitch >= 0) note(mousePitch, 0, now);
        mousePitch = -1;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape()) return super.keyPressed(event);
        int key = event.key();
        switch (key) {
            case KEY_LEFT -> shiftOctave(-1);
            case KEY_RIGHT -> shiftOctave(1);
            case KEY_UP -> velocity = Math.min(127, velocity + 16);
            case KEY_DOWN -> velocity = Math.max(16, velocity - 16);
            default -> {
                int off = KeyLayout.chromaticOffset(KeyLayout.chromatic(), key);
                if (off >= 0 && !held.containsKey(key) && playable(base + off)) {
                    held.put(key, base + off);
                    note(base + off, velocity, System.nanoTime());
                }
            }
        }
        setFocused(null);
        return true;
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        Integer p = held.remove(event.key());
        if (p != null) note(p, 0, System.nanoTime());
        return true;
    }

    @Override
    public void midiNote(int pitch, int vel, long nanos) {
        if (!playable(pitch)) return;
        if (!midiHeld.add(pitch)) note(pitch, 0, nanos);
        note(pitch, vel, nanos);
    }

    @Override
    public void midiOff(int pitch, long nanos) {
        if (midiHeld.remove(pitch)) note(pitch, 0, nanos);
    }

    @Override
    public void midiPedal(boolean down, long nanos) {
        // 手持乐器没有踏板
    }

    @Override
    public void midiAllOff() {
        long now = System.nanoTime();
        for (int p : new ArrayList<>(midiHeld)) note(p, 0, now);
        midiHeld.clear();
    }

    @Override
    public void midiProgram(int program) {
    }

    /** 测试用：键盘和电子琴上正按着几个音。 */
    int holding() {
        return held.size() + midiHeld.size() + (mousePitch >= 0 ? 1 : 0);
    }

    // —— 屏幕键盘 ——

    private record KeyRect(int pitch, int x, int y, int w, int h, boolean black, String label) {}

    private List<KeyRect> keyboard() {
        List<KeyRect> keys = new ArrayList<>();
        String layout = KeyLayout.chromatic();
        int left = x0 + 20, top = y0 + 30, area = W - 40, height = 44;
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
        return held.containsValue(pitch) || midiHeld.contains(pitch) || mousePitch == pitch;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (e.button() == 0) {
            List<KeyRect> keys = keyboard();
            for (int i = keys.size() - 1; i >= 0; i--) {
                KeyRect k = keys.get(i);
                if (e.x() >= k.x && e.x() < k.x + k.w && e.y() >= k.y && e.y() < k.y + k.h) {
                    if (!playable(k.pitch)) return true;
                    mousePitch = k.pitch;
                    note(k.pitch, velocity, System.nanoTime());
                    return true;
                }
            }
        }
        return super.mouseClicked(e, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        if (mousePitch >= 0) {
            note(mousePitch, 0, System.nanoTime());
            mousePitch = -1;
        }
        return super.mouseReleased(e);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        Parchment.draw(g, x0, y0, W, H, 20);
        g.text(font, title, x0 + 20, y0 + 16, Parchment.INK_TITLE, false);
        Component status = tr("held_status", KeyLayout.noteName(base), velocity,
            Component.translatable(KeyLayout.noteName(kind.low()) + "–" + KeyLayout.noteName(kind.high())));
        int statusX = x0 + 20 + font.width(title) + 12;
        g.text(font, status, statusX, y0 + 16, Parchment.INK, false);
        // 音源写在右上角；名字、状态太长挤不下时挪到底下提示行的右边，那里也挤不下就不写
        Component source = SampleMaps.sourceLabel(kind);
        Component hint = tr("held_hint");
        if (source != null) {
            int sx = x0 + W - 20 - font.width(source);
            if (sx >= statusX + font.width(status) + 8) g.text(font, source, sx, y0 + 16, Parchment.INK_HINT, false);
            else if (sx >= x0 + 20 + font.width(hint) + 8) g.text(font, source, sx, y0 + H - 23, Parchment.INK_HINT, false);
        }
        for (KeyRect k : keyboard()) {
            boolean on = sounding(k.pitch);
            boolean out = k.pitch < kind.low() || k.pitch > kind.high();
            int fill = k.black ? (on ? Parchment.GOLD : out ? 0xFF5A4A40 : 0xFF241812)
                : (on ? 0xFFF4D27A : out ? 0xFFD8CCB4 : 0xFFF6F0E2);
            g.fill(k.x, k.y, k.x + k.w, k.y + k.h, fill);
            g.outline(k.x, k.y, k.w, k.h, k.black ? 0xFF120A06 : 0xFF8C7A60);
            int lx = k.x + (k.w - font.width(k.label)) / 2;
            g.text(font, Component.literal(k.label), lx, k.y + k.h - 10, k.black ? 0xFFE8DCC0 : Parchment.INK_HINT, false);
        }
        g.text(font, hint, x0 + 20, y0 + H - 23, Parchment.INK_HINT, false);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }
}
