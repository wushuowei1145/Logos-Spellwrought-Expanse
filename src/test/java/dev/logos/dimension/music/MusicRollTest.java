package dev.logos.dimension.music;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MusicRollTest {
    private static MusicRoll sample() {
        List<MusicRoll.Event> ev = new ArrayList<>();
        ev.add(MusicRoll.Event.note(0, 0, 60, 100, 500));
        ev.add(MusicRoll.Event.pedal(250, 0, true));
        ev.add(MusicRoll.Event.note(500, 1, 64, 80, 1200));
        ev.add(MusicRoll.Event.pedal(1800, 0, false));
        return new MusicRoll("星辰花圆舞曲", "灵猫", 12000, 3, 4,
            List.of(new MusicRoll.Part("钢琴", 0), new MusicRoll.Part("竖琴", 46)), ev);
    }

    @Test void roundTripsEverything() throws Exception {
        MusicRoll roll = sample();
        byte[] bytes = roll.encode();
        MusicRoll back = MusicRoll.decode(bytes);
        assertEquals(roll.title(), back.title());
        assertEquals(roll.author(), back.author());
        assertEquals(12000, back.bpmX100());
        assertEquals(3, back.beatsPerBar());
        assertEquals(roll.parts(), back.parts());
        assertEquals(roll.events(), back.events());
        assertEquals(1800, back.durationMs());
        assertEquals(2, back.noteCount());
        assertArrayEquals(bytes, back.encode());
        assertEquals(MusicRoll.hash(bytes), MusicRoll.hash(back.encode()));
    }

    private static MusicRoll withTempos(MusicRoll base, List<MusicRoll.Tempo> tempos) {
        return new MusicRoll(base.title(), base.author(), base.bpmX100(), base.beatsPerBar(), base.beatUnit(), base.parts(), base.events(), tempos);
    }

    @Test void tempoAndMeterChangesRoundTrip() throws Exception {
        MusicRoll roll = withTempos(sample(), List.of(new MusicRoll.Tempo(4000, 9000, 3, 4), new MusicRoll.Tempo(1000, 6000, 6, 8)));
        byte[] bytes = roll.encode();
        MusicRoll back = MusicRoll.decode(bytes);
        assertEquals(List.of(new MusicRoll.Tempo(1000, 6000, 6, 8), new MusicRoll.Tempo(4000, 9000, 3, 4)), back.tempos(), "sorted by time");
        assertEquals(roll.events(), back.events());
        assertArrayEquals(bytes, back.encode());
        assertEquals(back.tempos(), back.withMeta("新标题", "").tempos());
    }

    @Test void rollsWithoutTempoChangesKeepTheOldBytes() throws Exception {
        byte[] old = sample().encode();
        assertArrayEquals(old, withTempos(sample(), List.of()).encode());
        assertTrue(MusicRoll.decode(old).tempos().isEmpty());
        byte[] longer = withTempos(sample(), List.of(new MusicRoll.Tempo(1000, 6000, 3, 4))).encode();
        assertArrayEquals(old, java.util.Arrays.copyOf(longer, old.length), "the tempo section is only appended");
        assertEquals('T', longer[old.length]);
    }

    @Test void sortsEventsByTime() {
        MusicRoll roll = new MusicRoll("", "", 12000, 4, 4, List.of(),
            List.of(MusicRoll.Event.note(900, 0, 62, 90, 10), MusicRoll.Event.note(100, 0, 60, 90, 10)));
        assertEquals(100, roll.events().get(0).timeMs());
        assertEquals(1, roll.parts().size(), "an empty part list gets one unnamed part");
    }

    @Test void rejectsBadValuesBeforeEncoding() {
        var tooLong = new MusicRoll("", "", 12000, 4, 4, List.of(),
            List.of(MusicRoll.Event.note(0, 0, 60, 90, MusicRoll.MAX_NOTE_MS + 1)));
        assertThrows(MusicRoll.FormatException.class, tooLong::encode);
        var badPart = new MusicRoll("", "", 12000, 4, 4, List.of(), List.of(MusicRoll.Event.note(0, 3, 60, 90, 10)));
        assertThrows(MusicRoll.FormatException.class, badPart::encode);
        var late = new MusicRoll("", "", 12000, 4, 4, List.of(),
            List.of(MusicRoll.Event.note(MusicRoll.MAX_DURATION_MS + 1, 0, 60, 90, 10)));
        assertThrows(MusicRoll.FormatException.class, late::encode);
        List<MusicRoll.Event> many = new ArrayList<>();
        for (int i = 0; i <= MusicRoll.MAX_EVENTS; i++) many.add(MusicRoll.Event.note(i, 0, 60, 90, 10));
        assertThrows(MusicRoll.FormatException.class, new MusicRoll("", "", 12000, 4, 4, List.of(), many)::encode);
    }

    @Test void rejectsTruncatedAndForgedData() throws Exception {
        byte[] good = sample().encode();
        for (int cut = 0; cut < good.length; cut++) {
            byte[] part = java.util.Arrays.copyOf(good, cut);
            assertThrows(MusicRoll.FormatException.class, () -> MusicRoll.decode(part), "cut at " + cut);
        }
        byte[] trailing = java.util.Arrays.copyOf(good, good.length + 1);
        assertThrows(MusicRoll.FormatException.class, () -> MusicRoll.decode(trailing));
        byte[] magic = good.clone();
        magic[0] = 'X';
        assertThrows(MusicRoll.FormatException.class, () -> MusicRoll.decode(magic));
        assertThrows(MusicRoll.FormatException.class, () -> MusicRoll.decode(new byte[MusicRoll.MAX_BYTES + 1]));
    }

    @Test void rejectsHugeLengthFields() {
        // 魔数 + 声称有 2^31 字节的标题
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(MusicRoll.MAGIC);
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x07});
        assertThrows(MusicRoll.FormatException.class, () -> MusicRoll.decode(out.toByteArray()));
        ByteArrayOutputStream events = new ByteArrayOutputStream();
        events.writeBytes(MusicRoll.MAGIC);
        events.writeBytes(new byte[]{0, 0, (byte) 0xE0, 0x5D, 4, 4, 1, 0, 0});
        MusicRoll.writeVarInt(events, MusicRoll.MAX_EVENTS + 1);
        assertThrows(MusicRoll.FormatException.class, () -> MusicRoll.decode(events.toByteArray()));
    }

    /** 每种用采样的乐器：音域内每个音取到的采样都在 ±1 半音内，而且采样文件和声音事件都在。 */
    @Test void foldsNotesIntoEachInstrument() {
        assertEquals(24, InstrumentKind.CONCERT_HARP.fold(12));
        assertEquals(103 - 12 + 1, InstrumentKind.CONCERT_HARP.fold(104));
        assertEquals(21, InstrumentKind.GRAND_PIANO.fold(21));
        assertEquals(108, InstrumentKind.GRAND_PIANO.fold(108));
        // 管钟只有 B3–G5：低一个八度的音挪上来
        assertEquals(60, InstrumentKind.TUBULAR_BELLS.fold(48));
        // 小提琴到 E7 为止：G7 往下挪一个八度
        assertEquals(91, InstrumentKind.VIOLIN.fold(103));
    }

    /**
     * #243：纸带按声部整体移八度、保留音程，不逐音折叠——钟琴放 C3 的大调音阶，七个音仍然一个比一个高
     * （以前 C–F 被抬到 C6–F6、G–B 留在 G5–B5，fa→sol 往下跳了十度）。
     */
    @Test void wholePartsMoveByOctavesToFitTheInstrument() {
        int[] scale = {48, 50, 52, 53, 55, 57, 59};
        List<MusicRoll.Event> ev = new ArrayList<>();
        for (int i = 0; i < scale.length; i++) ev.add(MusicRoll.Event.note(i * 250, 0, scale[i], 90, 240));
        for (int i = 0; i < 4; i++) ev.add(MusicRoll.Event.note(i * 250, 1, 84 + i, 90, 240));
        MusicRoll roll = new MusicRoll("音阶", "测试", 12000, 4, 4,
            List.of(new MusicRoll.Part("一", 0), new MusicRoll.Part("二", 0)), ev);
        InstrumentKind glock = InstrumentKind.GLOCKENSPIEL;
        int[] shifts = roll.octaveShifts(glock.low(), glock.high());
        assertEquals(36, shifts[0], "the C3 scale moves up three octaves as a whole");
        assertEquals(0, shifts[1], "a part that already fits stays where it is");
        int last = -1;
        for (int p : scale) {
            int sounding = glock.fold(p + shifts[0]);
            assertEquals(p + 36, sounding, "no note is folded on its own");
            assertTrue(sounding > last, "the scale keeps rising");
            last = sounding;
        }
        // 声部比乐器音域还宽（定音鼓只有 D2–A3）：取能弹到的音最多的那一挪，一样多时挪得少的
        List<MusicRoll.Event> wide = new ArrayList<>();
        for (int p = 40; p <= 90; p++) wide.add(MusicRoll.Event.note((p - 40) * 100, 0, p, 90, 90));
        MusicRoll w = new MusicRoll("宽", "测试", 12000, 4, 4, List.of(new MusicRoll.Part("一", 0)), wide);
        assertEquals(-12, w.octaveShifts(InstrumentKind.TIMPANI.low(), InstrumentKind.TIMPANI.high())[0]);
    }
}
