package dev.logos.dimension.music;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MidiReaderTest {
    /** 拼一个 MIDI 文件：format、division 与若干轨道的原始字节（不含 MTrk 头）。 */
    private static byte[] midi(int format, int division, byte[]... tracks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("MThd".getBytes(StandardCharsets.ISO_8859_1));
        u32(out, 6);
        u16(out, format);
        u16(out, tracks.length);
        u16(out, division);
        for (byte[] t : tracks) {
            out.writeBytes("MTrk".getBytes(StandardCharsets.ISO_8859_1));
            u32(out, t.length);
            out.writeBytes(t);
        }
        return out.toByteArray();
    }

    private static void u32(ByteArrayOutputStream o, int v) {
        o.write(v >>> 24); o.write(v >>> 16); o.write(v >>> 8); o.write(v);
    }

    private static void u16(ByteArrayOutputStream o, int v) {
        o.write(v >>> 8); o.write(v);
    }

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    @Test void readsTempoNotesRunningStatusAndPedal() throws Exception {
        // 速度 120 BPM（500000 µs/拍），480 tick/拍；之后在 960 tick 处改成 60 BPM
        byte[] conductor = bytes(
            0x00, 0xFF, 0x51, 0x03, 0x07, 0xA1, 0x20,
            0x00, 0xFF, 0x58, 0x04, 0x03, 0x02, 0x18, 0x08,
            0x87, 0x40, 0xFF, 0x51, 0x03, 0x0F, 0x42, 0x40,
            0x00, 0xFF, 0x2F, 0x00);
        byte[] piano = bytes(
            0x00, 0xFF, 0x03, 0x05, 'P', 'i', 'a', 'n', 'o',
            0x00, 0x90, 60, 100,          // C4 开
            0x00, 64, 90,                 // 运行状态：E4 开
            0x83, 0x60, 0x80, 60, 0,      // 480 tick（500 ms）后 C4 关
            0x00, 0x90, 64, 0,            // 力度 0 = E4 关
            0x00, 0xB0, 64, 127,          // 踩下踏板
            0x83, 0x60, 0x90, 67, 80,     // 960 tick：G4 开（之后速度减半）
            0x83, 0x60, 0x80, 67, 0,      // 再 480 tick（1000 ms）关
            0x00, 0xB0, 64, 0,            // 松开踏板
            0x00, 0xFF, 0x2F, 0x00);
        byte[] drums = bytes(0x00, 0x99, 36, 100, 0x60, 0x89, 36, 0, 0x00, 0xFF, 0x2F, 0x00);
        MidiReader.Result r = MidiReader.read(midi(1, 480, conductor, piano, drums), "测试");
        MusicRoll roll = r.roll();
        assertEquals("测试", roll.title());
        assertEquals(12000, roll.bpmX100());
        assertEquals(3, roll.beatsPerBar());
        assertEquals(4, roll.beatUnit());
        assertEquals(java.util.List.of(new MusicRoll.Tempo(1000, 6000, 3, 4)), roll.tempos(), "the tempo change at tick 960 is kept for the conductor");
        assertEquals(1, roll.parts().size());
        assertEquals("Piano", roll.parts().get(0).name());
        var notes = roll.events().stream().filter(e -> e.type() == MusicRoll.NOTE).toList();
        assertEquals(3, notes.size());
        assertEquals(60, notes.get(0).pitch());
        assertEquals(500, notes.get(0).durationMs());
        assertEquals(500, notes.get(1).durationMs());
        assertEquals(1000, notes.get(2).timeMs());
        assertEquals(1000, notes.get(2).durationMs(), "tempo halves at tick 960");
        var pedals = roll.events().stream().filter(e -> e.type() != MusicRoll.NOTE).toList();
        assertEquals(2, pedals.size());
        assertEquals(MusicRoll.PEDAL_DOWN, pedals.get(0).type());
        assertEquals(500, pedals.get(0).timeMs());
        assertEquals(2000, pedals.get(1).timeMs());
        assertTrue(r.notes().stream().anyMatch(s -> s.contains("打击乐")));
        assertFalse(r.truncated());
        // 能编码，且限制之内
        MusicRoll.decode(roll.encode());
    }

    @Test void reportsTrackAndEventOnBrokenData() {
        byte[] noStatus = bytes(0x00, 60, 100, 0x00, 0xFF, 0x2F, 0x00);
        var e = assertThrows(MusicRoll.FormatException.class, () -> MidiReader.read(midi(0, 96, noStatus), ""));
        assertTrue(e.getMessage().contains("第 1 轨第 1 个事件"), e.getMessage());
        byte[] cut = bytes(0x00, 0x90, 60);
        assertThrows(MusicRoll.FormatException.class, () -> MidiReader.read(midi(0, 96, cut), ""));
    }

    @Test void rejectsForgedLengthsAndFormats() {
        byte[] good = midi(0, 96, bytes(0x00, 0x90, 60, 100, 0x60, 0x80, 60, 0, 0x00, 0xFF, 0x2F, 0x00));
        // 轨道长度改成远大于文件
        byte[] forged = good.clone();
        forged[18] = 0x7F;
        assertThrows(MusicRoll.FormatException.class, () -> MidiReader.read(forged, ""));
        // 元事件长度超出轨道
        byte[] meta = midi(0, 96, bytes(0x00, 0xFF, 0x03, 0x7F, 'x'));
        assertThrows(MusicRoll.FormatException.class, () -> MidiReader.read(meta, ""));
        // 格式 2 不支持
        assertThrows(MusicRoll.FormatException.class, () -> MidiReader.read(midi(2, 96, bytes(0x00, 0xFF, 0x2F, 0x00)), ""));
        // 不是 MIDI
        assertThrows(MusicRoll.FormatException.class, () -> MidiReader.read("hello world, not midi".getBytes(), ""));
        // 超过 1 MiB
        assertThrows(MusicRoll.FormatException.class, () -> MidiReader.read(new byte[MidiReader.MAX_FILE_BYTES + 1], ""));
        // 每一种截断都只抛格式错误
        for (int i = 0; i < good.length; i++) {
            byte[] part = java.util.Arrays.copyOf(good, i);
            try {
                MidiReader.read(part, "");
            } catch (MusicRoll.FormatException expected) {
                // 正常
            }
        }
    }

    @Test void truncatesAtTheEventLimit() throws Exception {
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        int notes = MusicRoll.MAX_EVENTS + 500;
        for (int i = 0; i < notes; i++) {
            t.writeBytes(bytes(0x00, 0x90, 60 + i % 12, 90, 0x01, 0x80, 60 + i % 12, 0));
        }
        t.writeBytes(bytes(0x00, 0xFF, 0x2F, 0x00));
        MidiReader.Result r = MidiReader.read(midi(0, 960, t.toByteArray()), "");
        assertTrue(r.truncated());
        assertTrue(r.roll().events().size() <= MusicRoll.MAX_EVENTS);
        assertTrue(r.roll().encode().length <= MusicRoll.MAX_BYTES);
    }

    @Test void smpteDivision() throws Exception {
        // 25 帧/秒、每帧 40 tick → 1 tick = 1 ms
        int division = ((-25) & 0xFF) << 8 | 40;
        byte[] t = bytes(0x00, 0x90, 60, 100, 0x83, 0x68, 0x80, 60, 0, 0x00, 0xFF, 0x2F, 0x00);
        var roll = MidiReader.read(midi(0, division, t), "").roll();
        assertEquals(488, roll.events().get(0).durationMs());
    }
}
