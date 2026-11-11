package dev.logos.dimension.music.orchestra;

import dev.logos.dimension.music.InstrumentKind;
import dev.logos.dimension.music.MusicRoll;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrchestraArrangerTest {
    /** 每个声部一串半音阶：{音色号, 最低音, 最高音}。 */
    private static MusicRoll score(int[]... parts) {
        List<MusicRoll.Part> names = new ArrayList<>();
        List<MusicRoll.Event> events = new ArrayList<>();
        for (int p = 0; p < parts.length; p++) {
            names.add(new MusicRoll.Part("p" + p, parts[p][0]));
            for (int pitch = parts[p][1], t = 0; pitch <= parts[p][2]; pitch++, t += 100) {
                events.add(MusicRoll.Event.note(t, p, pitch, 90, 90));
            }
        }
        return new MusicRoll("", "", 12000, 4, 4, names, events);
    }

    private static OrchestraArranger.Member m(InstrumentKind kind, double seat) {
        return new OrchestraArranger.Member(kind, seat, -1);
    }

    @Test void twoViolinsTakeFirstAndSecondViolinByTheirSeats() {
        MusicRoll s = score(new int[]{40, 72, 88}, new int[]{40, 62, 76}, new int[]{42, 40, 60}, new int[]{73, 74, 90});
        // 成员的顺序故意打乱：座次靠左的那把拉第一小提琴
        int[] masks = OrchestraArranger.assign(s, List.of(
            m(InstrumentKind.VIOLIN, 3), m(InstrumentKind.CELLO, 5), m(InstrumentKind.VIOLIN, 1), m(InstrumentKind.FLUTE, 7)));
        assertEquals(1 << 1, masks[0], "the right violin plays the second violin part");
        assertEquals(1 << 2, masks[1]);
        assertEquals(1, masks[2], "the left violin plays the first violin part");
        assertEquals(1 << 3, masks[3]);
    }

    @Test void extraInstrumentsDoubleTheirBestPart() {
        MusicRoll s = score(new int[]{40, 72, 88}, new int[]{42, 40, 60});
        int[] masks = OrchestraArranger.assign(s, List.of(
            m(InstrumentKind.CELLO, 0), m(InstrumentKind.VIOLIN, 1), m(InstrumentKind.CELLO, 2)));
        assertEquals(1 << 1, masks[0]);
        assertEquals(1, masks[1]);
        assertEquals(1 << 1, masks[2]);
    }

    @Test void uncoveredPartsGoToKeyboardsThatAlreadyPlay() {
        // 双簧管声部没人演奏：叠给已经在弹钢琴声部的钢琴，小提琴不兼
        MusicRoll s = score(new int[]{40, 72, 88}, new int[]{68, 65, 80}, new int[]{0, 48, 72});
        int[] masks = OrchestraArranger.assign(s, List.of(m(InstrumentKind.VIOLIN, 0), m(InstrumentKind.GRAND_PIANO, 1)));
        assertEquals(1, masks[0]);
        assertEquals(1 << 2 | 1 << 1, masks[1]);
    }

    @Test void lockedMembersKeepTheirParts() {
        MusicRoll s = score(new int[]{40, 72, 88}, new int[]{42, 40, 60});
        int[] masks = OrchestraArranger.assign(s, List.of(
            new OrchestraArranger.Member(InstrumentKind.VIOLIN, 0, 1 << 1), m(InstrumentKind.VIOLIN, 1)));
        assertEquals(1 << 1, masks[0], "locked to the cello part");
        assertEquals(1, masks[1]);
    }

    @Test void instrumentsThatCannotReachAPartRest() {
        // 钟琴只有两个半八度：跨五个八度的声部怎么移都弹不到六成
        MusicRoll s = score(new int[]{43, 28, 88});
        int[] masks = OrchestraArranger.assign(s, List.of(m(InstrumentKind.GLOCKENSPIEL, 0)));
        assertEquals(0, masks[0]);
    }

    @Test void programsMapToKinds() {
        assertEquals(InstrumentKind.GRAND_PIANO, OrchestraArranger.ofProgram(0));
        assertEquals(InstrumentKind.CELLO, OrchestraArranger.ofProgram(42));
        assertEquals(InstrumentKind.TIMPANI, OrchestraArranger.ofProgram(47));
        assertNull(OrchestraArranger.ofProgram(30));
        assertArrayEquals(new int[]{0, 3, 15}, OrchestraArranger.parts(1 | 1 << 3 | 1 << 15));
    }
}
