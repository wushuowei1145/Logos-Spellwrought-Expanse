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

    @Test void foldsNotesIntoEachInstrument() {
        assertEquals(24, InstrumentKind.CONCERT_HARP.fold(12));
        assertEquals(103 - 12 + 1, InstrumentKind.CONCERT_HARP.fold(104));
        assertEquals(21, InstrumentKind.GRAND_PIANO.fold(21));
        assertEquals(108, InstrumentKind.GRAND_PIANO.fold(108));
        for (int p = 21; p <= 108; p++) {
            int s = InstrumentKind.GRAND_PIANO.sampleFor(p);
            assertTrue(Math.abs(p - s) <= 1, "piano " + p + " -> " + s);
            assertEquals(0, (s - 21) % 3);
        }
        for (int p = 24; p <= 103; p++) {
            int s = InstrumentKind.CONCERT_HARP.sampleFor(p);
            assertTrue(Math.abs(p - s) <= 1 && s <= 102, "harp " + p + " -> " + s);
        }
    }
}
