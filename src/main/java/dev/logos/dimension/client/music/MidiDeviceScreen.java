package dev.logos.dimension.client.music;

import dev.logos.dimension.ClientConfig;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * 电子琴设置：列出电脑上的 MIDI 输入设备，点一下连上（名字记进客户端设置，下次打开乐器界面自动连）；
 * 刷新、断开；力度曲线、只听哪个通道、整体移调。下面的「试一下」显示收到了多少条消息和最近按下的音，
 * 没有打开乐器时也能看出琴有没有连上。
 */
public class MidiDeviceScreen extends Screen {
    private static final int W = 400, H = 226, PAD = 28, ROW = 12, VISIBLE = 6;

    private final Screen parent;
    private List<MidiKeyboard.Device> devices = List.of();
    private int x0, y0;

    public MidiDeviceScreen(Screen parent) {
        super(Component.translatable("screen.logos_dimension.instrument.midi_title"));
        this.parent = parent;
        devices = MidiKeyboard.refresh();
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("screen.logos_dimension.instrument." + key, args);
    }

    @Override
    protected void init() {
        x0 = (width - W) / 2;
        y0 = (height - H) / 2;
        int x = x0 + PAD, y = y0 + 40 + VISIBLE * ROW + 8;
        addRenderableWidget(Button.builder(tr("refresh"), b -> {
            devices = MidiKeyboard.refresh();
            rebuildWidgets();
        }).bounds(x, y, 70, 16).build());
        Button off = addRenderableWidget(Button.builder(tr("midi_disconnect"), b -> {
            MidiKeyboard.disconnect(true);
            rebuildWidgets();
        }).bounds(x + 74, y, 70, 16).build());
        off.active = MidiKeyboard.connected() != null;
        int y2 = y + 20;
        int curve = ClientConfig.MIDI_VELOCITY_CURVE.get();
        addRenderableWidget(Button.builder(tr("midi_curve", tr("midi_curve." + curve)), b -> {
            set(ClientConfig.MIDI_VELOCITY_CURVE, (ClientConfig.MIDI_VELOCITY_CURVE.get() + 1) % 3);
        }).bounds(x, y2, 112, 16).build());
        int ch = ClientConfig.MIDI_CHANNEL.get();
        addRenderableWidget(Button.builder(ch == 0 ? tr("midi_channel_all") : tr("midi_channel", ch), b -> {
            int step = Minecraft.getInstance().hasShiftDown() ? 16 : 1;
            set(ClientConfig.MIDI_CHANNEL, (ClientConfig.MIDI_CHANNEL.get() + step) % 17);
        }).bounds(x + 116, y2, 100, 16).build());
        int tp = ClientConfig.MIDI_TRANSPOSE.get();
        addRenderableWidget(Button.builder(Component.literal("−"), b -> transpose(-1)).bounds(x + 220, y2, 18, 16).build());
        addRenderableWidget(Button.builder(tr("midi_transpose", tp > 0 ? "+" + tp : Integer.toString(tp)), b -> set(ClientConfig.MIDI_TRANSPOSE, 0))
            .bounds(x + 240, y2, 84, 16).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> transpose(1)).bounds(x + 326, y2, 18, 16).build());
        addRenderableWidget(Button.builder(tr("back"), b -> onClose()).bounds(x0 + W - PAD - 60, y0 + H - PAD - 16, 60, 16).build());
    }

    private void transpose(int d) {
        int step = Minecraft.getInstance().hasShiftDown() ? 12 : 1;
        set(ClientConfig.MIDI_TRANSPOSE, Math.max(-36, Math.min(36, ClientConfig.MIDI_TRANSPOSE.get() + d * step)));
    }

    private void set(net.neoforged.neoforge.common.ModConfigSpec.IntValue value, int v) {
        value.set(v);
        value.save();
        rebuildWidgets();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (super.mouseClicked(e, doubleClick)) return true;
        int lx = x0 + PAD, ly = y0 + 40;
        if (e.button() == 0 && e.x() >= lx && e.x() < x0 + W - PAD && e.y() >= ly && e.y() < ly + VISIBLE * ROW) {
            int i = (int) ((e.y() - ly) / ROW);
            if (i < devices.size()) {
                MidiKeyboard.connect(devices.get(i));
                rebuildWidgets();
            }
            return true;
        }
        return false;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        Parchment.draw(g, x0, y0, W, H);
        int x = x0 + PAD;
        g.text(font, title, x, y0 + 24, Parchment.INK_TITLE, false);
        String name = MidiKeyboard.connected();
        String problem = MidiKeyboard.problem();
        Component status = name != null ? tr("midi_connected", name)
            : problem == null ? tr("midi_none") : tr("midi_problem." + (problem.equals("unplugged") || problem.equals("not found") ? problem.replace(' ', '_') : "other"), problem);
        g.text(font, status, x + 90, y0 + 24, name != null ? 0xFF2E6A2E : Parchment.INK_HINT, false);
        int ly = y0 + 40;
        g.fill(x, ly, x0 + W - PAD, ly + VISIBLE * ROW, 0x30FFFFFF);
        g.outline(x - 1, ly - 1, W - PAD * 2 + 2, VISIBLE * ROW + 2, Parchment.INK);
        if (devices.isEmpty()) {
            g.text(font, tr("midi_no_devices"), x + 4, ly + 3, Parchment.INK_HINT, false);
        }
        for (int i = 0; i < Math.min(VISIBLE, devices.size()); i++) {
            MidiKeyboard.Device d = devices.get(i);
            int y = ly + i * ROW;
            boolean on = d.name().equals(name);
            if (on) g.fill(x, y, x0 + W - PAD, y + ROW, 0x6080C070);
            String label = font.plainSubstrByWidth((on ? "● " : "○ ") + d.label(), W - PAD * 2 - 6);
            g.text(font, Component.literal(label), x + 3, y + 2, Parchment.INK, false);
        }
        // 试一下：收到的消息数与最近按下的音
        int ty = y0 + H - PAD - 12;
        int last = MidiKeyboard.lastPitch();
        Component test = last < 0 ? tr("midi_test_none", MidiKeyboard.received())
            : tr("midi_test", MidiKeyboard.received(), KeyLayout.noteName(last), MidiKeyboard.lastVelocity());
        String testText = font.plainSubstrByWidth(test.getString(), W - PAD * 2 - 66);
        g.text(font, Component.literal(testText), x, ty, Parchment.INK, false);
        // 提示按面板宽度折行，从「试一下」那一行往上排
        var lines = font.split(tr("midi_hint"), W - PAD * 2);
        for (int i = 0; i < lines.size(); i++) {
            g.text(font, lines.get(i), x, ty - 11 * (lines.size() - i), Parchment.INK_HINT, false);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }
}
