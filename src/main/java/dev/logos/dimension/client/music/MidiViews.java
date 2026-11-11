package dev.logos.dimension.client.music;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.dimension.ClientConfig;
import dev.logos.dimension.music.InstrumentBlockEntity;
import dev.logos.dimension.music.MidiInput;
import dev.logos.dimension.music.MusicRoll;
import java.util.List;
import net.minecraft.client.Minecraft;

/**
 * 电子琴输入的客户端场景：这台电脑没有真的电子琴，所以把 MIDI 消息直接塞进接收器（和设备送来的走同一条路），
 * 核对演奏页的琴键亮起、声音引擎在响、录下的纸带带着力度与踏板，力度曲线生效，没有打开乐器时消息被丢掉；
 * 最后截一张电子琴设置页。
 */
public abstract class MidiViews extends InstrumentViews {
    private static MusicClient.Recorder recorder;

    private static InstrumentScreen screen() {
        return Minecraft.getInstance().screen instanceof InstrumentScreen s ? s : null;
    }

    @LDLRegisterClient(name = "midi_keyboard_ui", group = "instrument", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Keyboard extends MidiViews {
        @Override
        public void define(ScenarioBuilder s) {
            stage(s, 0.0, 1.0, 3.5, 180f, 10f, false)
                .step("normal touch", ctx -> {
                    ClientConfig.MIDI_VELOCITY_CURVE.set(MidiInput.NORMAL);
                    ClientConfig.MIDI_CHANNEL.set(0);
                    ClientConfig.MIDI_TRANSPOSE.set(0);
                })
                .step("nobody listening: messages are dropped", ctx -> MidiKeyboard.inject(0x90, 72, 100))
                .ticks(2)
                .openScreen("open piano", ctx -> new InstrumentScreen(PIANO))
                .waitUntilServer("player claims the piano", ctx -> ctx.server().overworld().getBlockEntity(PIANO) instanceof InstrumentBlockEntity be
                    && ctx.player().getUUID().equals(be.performer()))
                .check("the dropped note did not leak in", ctx -> screen() != null && screen().midiHeld() == 0)
                .step("record and play a chord with the pedal", ctx -> {
                    recorder = new MusicClient.Recorder();
                    MusicClient.recorder = recorder;
                    MidiKeyboard.inject(0xB0, 64, 127);
                    MidiKeyboard.inject(0x90, 60, 100);
                    MidiKeyboard.inject(0x90, 64, 80);
                    MidiKeyboard.inject(0x90, 67, 60);
                    MidiKeyboard.inject(0xF8, 0, 0);   // 时钟，应当被忽略
                })
                .ticks(3)
                .check("three keys held from the keyboard", ctx -> screen() != null && screen().midiHeld() == 3)
                .check("the chord is sounding", ctx -> VoiceBank.sounding(MusicClient.liveKey(PIANO)) >= 3)
                .screenshot("midi_keyboard_ui")
                .step("release with velocity-zero note-ons and a note-off", ctx -> {
                    MidiKeyboard.inject(0x90, 60, 0);
                    MidiKeyboard.inject(0x80, 64, 0);
                    MidiKeyboard.inject(0x90, 67, 0);
                    MidiKeyboard.inject(0xB0, 64, 0);
                })
                .ticks(3)
                .check("all released", ctx -> screen() != null && screen().midiHeld() == 0)
                .step("light touch", ctx -> {
                    ClientConfig.MIDI_VELOCITY_CURVE.set(MidiInput.LIGHT);
                    MidiKeyboard.inject(0x90, 72, 40);
                })
                .ticks(3)
                .step("let go", ctx -> MidiKeyboard.inject(0x80, 72, 0))
                .ticks(2)
                .check("the roll kept the velocities and the pedal", ctx -> {
                    List<MusicRoll.Event> ev = recorder.events();
                    List<MusicRoll.Event> notes = ev.stream().filter(e -> e.type() == MusicRoll.NOTE).toList();
                    long pedals = ev.stream().filter(e -> e.type() != MusicRoll.NOTE).count();
                    if (notes.size() != 4 || pedals != 2) return false;
                    var byPitch = new java.util.HashMap<Integer, Integer>();
                    for (MusicRoll.Event e : notes) byPitch.put(e.pitch(), e.velocity());
                    return byPitch.get(60) == 100 && byPitch.get(64) == 80 && byPitch.get(67) == 60
                        && byPitch.get(72) == MidiInput.curve(40, MidiInput.LIGHT);
                })
                .step("settings page", ctx -> {
                    MusicClient.recorder = null;
                    ClientConfig.MIDI_VELOCITY_CURVE.set(MidiInput.NORMAL);
                    Minecraft.getInstance().setScreen(new MidiDeviceScreen(screen()));
                })
                .ticks(10)
                .check("settings page shows what it received", ctx -> Minecraft.getInstance().screen instanceof MidiDeviceScreen
                    && MidiKeyboard.lastPitch() == 72 && MidiKeyboard.received() >= 10)
                .screenshot("midi_device_ui")
                .teardown("close", ctx -> {
                    MusicClient.recorder = null;
                    Minecraft.getInstance().setScreen(null);
                });
        }
    }
}
