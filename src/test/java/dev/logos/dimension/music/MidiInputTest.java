package dev.logos.dimension.music;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MidiInputTest {
    private static final MidiInput.Settings ALL = MidiInput.Settings.DEFAULT;

    @Test void notesAndVelocityZero() {
        var on = MidiInput.translate(0x90, 60, 100, ALL);
        assertEquals(new MidiInput.Event(MidiInput.Type.NOTE_ON, 60, 100), on);
        assertEquals(MidiInput.Type.NOTE_OFF, MidiInput.translate(0x90, 60, 0, ALL).type(), "note-on with velocity 0 is a release");
        assertEquals(new MidiInput.Event(MidiInput.Type.NOTE_OFF, 60, 0), MidiInput.translate(0x80, 60, 64, ALL));
        // 任何通道都收
        assertEquals(MidiInput.Type.NOTE_ON, MidiInput.translate(0x9F, 21, 1, ALL).type());
    }

    @Test void pedalAllOffAndProgram() {
        assertEquals(new MidiInput.Event(MidiInput.Type.PEDAL, 1, 0), MidiInput.translate(0xB0, 64, 127, ALL));
        assertEquals(new MidiInput.Event(MidiInput.Type.PEDAL, 0, 0), MidiInput.translate(0xB0, 64, 10, ALL));
        assertEquals(MidiInput.Type.ALL_OFF, MidiInput.translate(0xB3, 123, 0, ALL).type());
        assertEquals(MidiInput.Type.ALL_OFF, MidiInput.translate(0xB0, 120, 0, ALL).type());
        assertNull(MidiInput.translate(0xB0, 7, 100, ALL), "volume knob is ignored");
        assertEquals(new MidiInput.Event(MidiInput.Type.PROGRAM, 19, 0), MidiInput.translate(0xC0, 19, 0, ALL));
    }

    @Test void ignoresClockSysexAndAftertouch() {
        assertNull(MidiInput.translate(0xF8, 0, 0, ALL), "clock");
        assertNull(MidiInput.translate(0xFE, 0, 0, ALL), "active sensing");
        assertNull(MidiInput.translate(0xF0, 0, 0, ALL), "sysex");
        assertNull(MidiInput.translate(0xD0, 40, 0, ALL), "channel pressure");
        assertNull(MidiInput.translate(0xE0, 0, 64, ALL), "pitch bend (later)");
        assertNull(MidiInput.translate(0x40, 60, 100, ALL), "a data byte is not a status");
    }

    @Test void channelFilterAndTranspose() {
        var two = new MidiInput.Settings(2, 0, MidiInput.NORMAL);
        assertNull(MidiInput.translate(0x90, 60, 100, two), "channel 1 is filtered out");
        assertNotNull(MidiInput.translate(0x91, 60, 100, two), "channel 2 passes");
        var up = new MidiInput.Settings(0, 12, MidiInput.NORMAL);
        assertEquals(72, MidiInput.translate(0x90, 60, 100, up).value());
        assertNull(MidiInput.translate(0x90, 120, 100, up), "transposed past 127 is dropped");
        var down = new MidiInput.Settings(0, -24, MidiInput.NORMAL);
        assertNull(MidiInput.translate(0x80, 10, 0, down), "transposed below 0 is dropped");
    }

    @Test void velocityCurves() {
        for (int v = 1; v <= 127; v++) {
            int light = MidiInput.curve(v, MidiInput.LIGHT), normal = MidiInput.curve(v, MidiInput.NORMAL), heavy = MidiInput.curve(v, MidiInput.HEAVY);
            assertTrue(light >= normal && normal >= heavy, "order at " + v);
            assertTrue(heavy >= 1 && light <= 127);
        }
        assertEquals(127, MidiInput.curve(127, MidiInput.HEAVY));
        assertEquals(64, MidiInput.curve(64, MidiInput.NORMAL));
        assertEquals(MidiInput.curve(40, MidiInput.LIGHT), MidiInput.translate(0x90, 60, 40, new MidiInput.Settings(0, 0, MidiInput.LIGHT)).velocity());
    }
}
