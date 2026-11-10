package dev.logos.dimension.client.music;

import com.mojang.logging.LogUtils;
import dev.logos.dimension.ClientConfig;
import dev.logos.dimension.music.MidiInput;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequencer;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Synthesizer;
import javax.sound.midi.Transmitter;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * 电子琴（MIDI 键盘）输入：列出电脑上的 MIDI 输入设备，连上玩家选的那一个，把它发来的消息
 * 交给正打开演奏页的乐器（{@link Target}）。
 *
 * <p>MIDI 消息在设备自己的线程上到达，这里只放进队列；每帧由 {@link MusicClient} 在主线程取出、翻译
 * （{@link MidiInput}）并交给乐器，所以比电脑键盘只多约一帧的延迟。每条消息带着到达的时刻，
 * 录制和实时演奏的批内偏移都按这个时刻算。只读玩家选的设备，不读文件，服务端不用知道。
 */
public final class MidiKeyboard {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 正在接收 MIDI 的乐器界面。 */
    interface Target {
        void midiNote(int pitch, int velocity, long nanos);

        void midiOff(int pitch, long nanos);

        void midiPedal(boolean down, long nanos);

        void midiAllOff();

        void midiProgram(int program);
    }

    /** 一个输入设备。name 用来记在设置里，下次按名字自动连。 */
    record Device(String name, String description, MidiDevice.Info info) {
        String label() {
            return description.isEmpty() || description.equals(name) ? name : name + "（" + description + "）";
        }
    }

    private record Raw(int status, int data1, int data2, long nanos) {}

    private static final ConcurrentLinkedQueue<Raw> QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger RECEIVED = new AtomicInteger();
    private static @Nullable MidiDevice device;
    private static @Nullable Transmitter transmitter;
    private static @Nullable String connected;
    private static @Nullable String problem;
    private static boolean triedAuto;
    private static @Nullable Target target;
    /** 最近一个按下的音（给设置页的「试一下」显示），-1 表示还没有。 */
    private static volatile int lastPitch = -1;
    private static volatile int lastVelocity;

    private MidiKeyboard() {}

    private static final Receiver RECEIVER = new Receiver() {
        @Override
        public void send(MidiMessage message, long timeStamp) {
            if (message instanceof ShortMessage m) push(m.getStatus(), m.getData1(), m.getData2());
        }

        @Override
        public void close() {}
    };

    private static void push(int status, int data1, int data2) {
        // 时钟、主动感知之类的系统实时消息每秒几十条，不进队列
        if (status >= 0xF0) return;
        RECEIVED.incrementAndGet();
        if ((status & 0xF0) == 0x90 && data2 > 0) {
            lastPitch = data1;
            lastVelocity = data2;
        }
        QUEUE.add(new Raw(status, data1, data2, System.nanoTime()));
    }

    /** 测试与调试用：当作设备发来了一条消息。 */
    static void inject(int status, int data1, int data2) {
        push(status, data1, data2);
    }

    // —— 设备 ——

    /** 电脑上的 MIDI 输入设备（排除软件合成器、音序器和只能输出的设备）。 */
    static List<Device> inputs() {
        List<Device> out = new ArrayList<>();
        for (MidiDevice.Info info : MidiSystem.getMidiDeviceInfo()) {
            try {
                MidiDevice d = MidiSystem.getMidiDevice(info);
                if (d instanceof Sequencer || d instanceof Synthesizer || d.getMaxTransmitters() == 0) continue;
                out.add(new Device(info.getName(), info.getDescription() == null ? "" : info.getDescription(), info));
            } catch (MidiUnavailableException | RuntimeException e) {
                LOGGER.debug("Skipping MIDI device {}", info.getName(), e);
            }
        }
        return out;
    }

    static synchronized boolean connect(Device d) {
        disconnect(false);
        try {
            MidiDevice dev = MidiSystem.getMidiDevice(d.info());
            dev.open();
            Transmitter t = dev.getTransmitter();
            t.setReceiver(RECEIVER);
            device = dev;
            transmitter = t;
            connected = d.name();
            problem = null;
            ClientConfig.MIDI_DEVICE.set(d.name());
            ClientConfig.MIDI_DEVICE.save();
            LOGGER.info("MIDI keyboard connected: {}", d.label());
            return true;
        } catch (MidiUnavailableException | RuntimeException e) {
            problem = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            LOGGER.warn("Could not open MIDI keyboard {}", d.label(), e);
            return false;
        }
    }

    /** 断开；forget 为真时也从设置里删掉，下次不再自动连。 */
    static synchronized void disconnect(boolean forget) {
        if (transmitter != null) transmitter.close();
        if (device != null && device.isOpen()) device.close();
        transmitter = null;
        device = null;
        connected = null;
        if (forget) {
            ClientConfig.MIDI_DEVICE.set("");
            ClientConfig.MIDI_DEVICE.save();
        }
    }

    /** 第一次打开乐器界面时：设置里记着设备名就按名字连上（连不上不报错，设置页里看得到原因）。 */
    static void autoConnect() {
        if (triedAuto || connected != null) return;
        triedAuto = true;
        String name = ClientConfig.MIDI_DEVICE.get();
        if (name.isEmpty()) return;
        for (Device d : inputs()) {
            if (d.name().equals(name)) {
                connect(d);
                return;
            }
        }
        problem = "not found";
    }

    /** 刷新设备列表时：连着的设备已经不在了（拔掉了）就断开。 */
    static List<Device> refresh() {
        List<Device> list = inputs();
        if (connected != null && list.stream().noneMatch(d -> d.name().equals(connected))) {
            disconnect(false);
            problem = "unplugged";
        }
        return list;
    }

    static @Nullable String connected() {
        return connected;
    }

    static @Nullable String problem() {
        return problem;
    }

    static int received() {
        return RECEIVED.get();
    }

    static int lastPitch() {
        return lastPitch;
    }

    static int lastVelocity() {
        return lastVelocity;
    }

    static void setTarget(@Nullable Target t) {
        if (target != null && target != t) target.midiAllOff();
        target = t;
    }

    static void clearTarget(Target t) {
        if (target == t) target = null;
    }

    static MidiInput.Settings settings() {
        return new MidiInput.Settings(ClientConfig.MIDI_CHANNEL.get(), ClientConfig.MIDI_TRANSPOSE.get(), ClientConfig.MIDI_VELOCITY_CURVE.get());
    }

    /** 每帧在主线程：把队列里的消息翻译后交给乐器；没有乐器在接收时丢掉（计数照记）。 */
    static void drain() {
        if (QUEUE.isEmpty()) return;
        MidiInput.Settings s = settings();
        Raw r;
        while ((r = QUEUE.poll()) != null) {
            Target t = target;
            if (t == null) continue;
            MidiInput.Event e = MidiInput.translate(r.status, r.data1, r.data2, s);
            if (e == null) continue;
            switch (e.type()) {
                case NOTE_ON -> t.midiNote(e.value(), e.velocity(), r.nanos);
                case NOTE_OFF -> t.midiOff(e.value(), r.nanos);
                case PEDAL -> t.midiPedal(e.value() != 0, r.nanos);
                case ALL_OFF -> t.midiAllOff();
                case PROGRAM -> t.midiProgram(e.value());
            }
        }
    }
}
