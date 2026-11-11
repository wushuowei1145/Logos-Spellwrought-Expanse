package dev.logos.dimension.client.music;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.logos.dimension.music.InstrumentKind;
import org.junit.jupiter.api.Test;

/** #243：演奏界面的键盘按每件乐器的音域摆，不再一律从 C3（方块）、C4（手持）开始。 */
class KeyLayoutTest {
    private static final int SPAN = 29;

    private static int overlap(InstrumentKind kind, int base) {
        return Math.max(0, Math.min(base + SPAN - 1, kind.high()) - Math.max(base, kind.low()) + 1);
    }

    @Test void defaultKeyboardCoversAsMuchOfEachRangeAsPossible() {
        for (InstrumentKind kind : InstrumentKind.values()) {
            for (int preferred : new int[]{48, 60}) {
                int base = KeyLayout.defaultBase(kind, SPAN, preferred);
                assertEquals(0, Math.floorMod(base, 12), kind + " keyboard starts on a C");
                int best = 0;
                for (int c = 0; c <= 120; c += 12) best = Math.max(best, overlap(kind, c));
                assertEquals(best, overlap(kind, base), kind + " keyboard covers as much of its range as any C start can");
                if (overlap(kind, preferred) == best) assertEquals(preferred, base, kind + " keeps the old start when it already fits");
            }
        }
        assertEquals(84, KeyLayout.defaultBase(InstrumentKind.GLOCKENSPIEL, SPAN, 48), "glockenspiel opens at C6");
        assertEquals(60, KeyLayout.defaultBase(InstrumentKind.TUBULAR_BELLS, SPAN, 48), "tubular bells open at C4");
        assertEquals(36, KeyLayout.defaultBase(InstrumentKind.DOUBLE_BASS, SPAN, 48), "double bass opens at C2");
        assertEquals(48, KeyLayout.defaultBase(InstrumentKind.BASSOON, SPAN, 60), "handheld bassoon opens at C3");
        assertEquals(48, KeyLayout.defaultBase(InstrumentKind.GRAND_PIANO, SPAN, 48), "the piano keeps C3");
    }

    @Test void octaveButtonsStopWhereTheInstrumentEnds() {
        for (InstrumentKind kind : InstrumentKind.values()) {
            int lo = KeyLayout.clampBase(kind, -120, SPAN), hi = KeyLayout.clampBase(kind, 240, SPAN);
            assertTrue(overlap(kind, lo) > 0 && overlap(kind, hi) > 0, kind + " can always reach some of its range");
            assertTrue(overlap(kind, lo - 12) == 0 || lo == 0, kind + " stops one octave before the keyboard leaves the range");
            assertTrue(overlap(kind, hi + 12) == 0 || hi == 120, kind + " stops at the top too");
        }
    }
}
