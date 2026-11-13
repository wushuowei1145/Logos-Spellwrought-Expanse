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

    // —— 认乐器、管弦乐 MIDI、钢琴曲 ——

    @Test void trackNamesBeatGenericOrWrongPrograms() {
        assertEquals(41, OrchestraArranger.programOfName("Viola"));
        assertEquals(42, OrchestraArranger.programOfName("Violoncello(s)"));
        assertEquals(43, OrchestraArranger.programOfName("Contrabass"));
        assertEquals(70, OrchestraArranger.programOfName("Fagotte"));
        assertEquals(40, OrchestraArranger.programOfName("Violin2"));
        assertEquals(56, OrchestraArranger.programOfName("Trumept"));
        assertEquals(69, OrchestraArranger.programOfName("English Horn"));
        assertEquals(60, OrchestraArranger.programOfName("Horn"));
        assertEquals(45, OrchestraArranger.programOfName("StrPizz1"));
        assertEquals(42, OrchestraArranger.programOfName("大提琴"));
        assertEquals(0, OrchestraArranger.programOfName("Stage Grand #2"));
        assertNull(OrchestraArranger.programOfName("p3"));
        assertNull(OrchestraArranger.programOfName("MIDI Out 1"));
        // 音色号写成颤音琴的「Viola」照样是中提琴
        assertEquals(41, OrchestraArranger.program(new MusicRoll.Part("Viola", 11)));
    }

    /** 声部：{名字, 音色号, 最低音, 最高音, 音数}。音在最低、最高音之间来回，中位音在中间。 */
    private static MusicRoll orchestral(Object[]... parts) {
        List<MusicRoll.Part> names = new ArrayList<>();
        List<MusicRoll.Event> events = new ArrayList<>();
        for (int p = 0; p < parts.length; p++) {
            names.add(new MusicRoll.Part((String) parts[p][0], (Integer) parts[p][1]));
            int lo = (Integer) parts[p][2], hi = (Integer) parts[p][3], n = (Integer) parts[p][4];
            for (int i = 0; i < n; i++) events.add(MusicRoll.Event.note(i * 50, p, lo + i % (hi - lo + 1), 90, 40));
        }
        return new MusicRoll("", "", 12000, 4, 4, names, events);
    }

    /** sym1_1.mid（贝多芬第一交响曲）的轨道：弦乐都写成 48 号弦乐合奏，乐器写在名字里；有单簧管、圆号；两个零星的拨弦声部。 */
    private static final MusicRoll BEETHOVEN = orchestral(
        new Object[]{"Flute", 73, 67, 91, 1153}, new Object[]{"Oboe", 68, 60, 86, 1288}, new Object[]{"Clarinet", 71, 55, 86, 944},
        new Object[]{"Fagotte", 70, 36, 67, 1460}, new Object[]{"Horn", 60, 55, 84, 608}, new Object[]{"Trumpet", 56, 55, 79, 375},
        new Object[]{"Timpani", 47, 43, 48, 248}, new Object[]{"Violin1", 48, 55, 91, 2351}, new Object[]{"Violin2", 48, 55, 86, 2935},
        new Object[]{"Viola", 48, 48, 77, 1979}, new Object[]{"Cello", 48, 36, 67, 1399}, new Object[]{"Contrabass", 48, 24, 55, 1398},
        new Object[]{"StrPizz1", 45, 55, 78, 25}, new Object[]{"StrPizz2", 45, 38, 62, 17});

    /** 音乐厅里那样的乐团：座次从左到右大致是小提琴、键盘、木管、铜管、中提琴、大提琴、低音提琴、打击乐。 */
    private static List<OrchestraArranger.Member> hall() {
        List<OrchestraArranger.Member> out = new ArrayList<>();
        InstrumentKind[] kinds = {
            InstrumentKind.VIOLIN, InstrumentKind.VIOLIN, InstrumentKind.VIOLIN, InstrumentKind.VIOLIN, InstrumentKind.VIOLIN, InstrumentKind.VIOLIN,
            InstrumentKind.GRAND_PIANO, InstrumentKind.HARPSICHORD, InstrumentKind.CELESTA, InstrumentKind.CONCERT_HARP, InstrumentKind.CONCERT_HARP,
            InstrumentKind.FLUTE, InstrumentKind.FLUTE, InstrumentKind.OBOE, InstrumentKind.OBOE, InstrumentKind.BASSOON, InstrumentKind.BASSOON,
            InstrumentKind.TRUMPET, InstrumentKind.TRUMPET, InstrumentKind.PIPE_ORGAN, InstrumentKind.VIOLA, InstrumentKind.VIOLA, InstrumentKind.VIOLA,
            InstrumentKind.CELLO, InstrumentKind.CELLO, InstrumentKind.CELLO, InstrumentKind.DOUBLE_BASS, InstrumentKind.GLOCKENSPIEL,
            InstrumentKind.TUBULAR_BELLS, InstrumentKind.TUBULAR_BELLS, InstrumentKind.TIMPANI, InstrumentKind.TIMPANI};
        for (int i = 0; i < kinds.length; i++) out.add(m(kinds[i], i));
        return out;
    }

    private static int part(MusicRoll roll, String name) {
        for (int p = 0; p < roll.parts().size(); p++) if (roll.parts().get(p).name().equals(name)) return p;
        throw new AssertionError(name);
    }

    @Test void orchestralMidiGoesToTheRightSections() {
        List<OrchestraArranger.Member> members = hall();
        int[] masks = OrchestraArranger.assign(BEETHOVEN, members);
        int pizz = 1 << part(BEETHOVEN, "StrPizz1") | 1 << part(BEETHOVEN, "StrPizz2");
        java.util.Map<InstrumentKind, Integer> strings = java.util.Map.of(
            InstrumentKind.VIOLIN, 1 << part(BEETHOVEN, "Violin1") | 1 << part(BEETHOVEN, "Violin2"),
            InstrumentKind.VIOLA, 1 << part(BEETHOVEN, "Viola"),
            InstrumentKind.CELLO, 1 << part(BEETHOVEN, "Cello"),
            InstrumentKind.DOUBLE_BASS, 1 << part(BEETHOVEN, "Contrabass"));
        int v1 = 0, v2 = 0;
        for (int i = 0; i < members.size(); i++) {
            InstrumentKind kind = members.get(i).kind();
            int own = masks[i] & ~pizz;
            String who = kind + " #" + i + " plays " + java.util.Arrays.toString(OrchestraArranger.parts(masks[i]));
            if (strings.containsKey(kind)) {
                assertTrue(own != 0 && (own & ~strings.get(kind)) == 0, who);
            }
            if (kind == InstrumentKind.VIOLIN) {
                if ((own & 1 << part(BEETHOVEN, "Violin1")) != 0) v1++;
                if ((own & 1 << part(BEETHOVEN, "Violin2")) != 0) v2++;
            }
            if (OrchestraArranger.polyphonic(kind) || kind == InstrumentKind.GLOCKENSPIEL || kind == InstrumentKind.TUBULAR_BELLS) {
                assertEquals(0, masks[i], who + ": nothing is written for it, so it rests");
            }
            if (kind == InstrumentKind.TIMPANI) assertEquals(1 << part(BEETHOVEN, "Timpani"), masks[i], who);
            // 拨弦的几个音叠在已经在拉的人身上，不单独占一个人
            if ((masks[i] & pizz) != 0) assertTrue(own != 0, who + ": pizzicato is layered on someone who already plays");
        }
        assertTrue(v1 >= 2 && v2 >= 2, "six violins split between the first and second violins: " + v1 + "/" + v2);
        // 没有单簧管、圆号：单簧管由双簧管或长笛接，圆号由小号或巴松管接；拨弦也都有人
        boolean clarinet = false, horn = false, pizz1 = false, pizz2 = false;
        for (int i = 0; i < members.size(); i++) {
            InstrumentKind kind = members.get(i).kind();
            if ((masks[i] & 1 << part(BEETHOVEN, "Clarinet")) != 0) clarinet = kind == InstrumentKind.OBOE || kind == InstrumentKind.FLUTE;
            if ((masks[i] & 1 << part(BEETHOVEN, "Horn")) != 0) horn = kind == InstrumentKind.TRUMPET || kind == InstrumentKind.BASSOON;
            pizz1 |= (masks[i] & 1 << part(BEETHOVEN, "StrPizz1")) != 0;
            pizz2 |= (masks[i] & 1 << part(BEETHOVEN, "StrPizz2")) != 0;
        }
        assertTrue(clarinet && horn && pizz1 && pizz2);
    }

    @Test void restingByHandStaysResting() {
        List<OrchestraArranger.Member> members = new ArrayList<>(hall());
        members.set(0, new OrchestraArranger.Member(InstrumentKind.VIOLIN, 0, 0));
        int[] masks = OrchestraArranger.assign(BEETHOVEN, members);
        assertEquals(0, masks[0], "a violin set to rest by hand stays resting");
    }

    /** 一首单通道的钢琴曲：右手单音旋律在高音区，左手低音加中间的和弦。 */
    private static MusicRoll pianoPiece() {
        List<MusicRoll.Event> events = new ArrayList<>();
        int[] melody = {76, 79, 81, 79, 76, 74, 72, 74};
        for (int i = 0; i < 32; i++) {
            int t = i * 500;
            events.add(MusicRoll.Event.note(t, 0, melody[i % melody.length], 90, 450));
            if (i % 2 == 0) {
                events.add(MusicRoll.Event.note(t, 0, 36 + (i / 2) % 5, 80, 900));
                events.add(MusicRoll.Event.note(t + 10, 0, 55, 70, 900));
                events.add(MusicRoll.Event.note(t + 10, 0, 59, 70, 900));
            }
        }
        return new MusicRoll("", "", 12000, 4, 4, List.of(new MusicRoll.Part("", 0)), events);
    }

    @Test void pianoPiecesAreSplitIntoMelodyHarmonyAndBass() {
        MusicRoll piece = pianoPiece();
        OrchestraScore.Score s = OrchestraScore.of(piece);
        assertTrue(s.split());
        assertEquals(1, s.voiceStart());
        assertEquals(List.of(OrchestraScore.MELODY, OrchestraScore.HARMONY, OrchestraScore.BASS), s.roles());
        int[] lowest = new int[4], highest = new int[4];
        java.util.Arrays.fill(lowest, 999);
        for (MusicRoll.Event e : s.roll().events()) {
            lowest[e.part()] = Math.min(lowest[e.part()], e.pitch());
            highest[e.part()] = Math.max(highest[e.part()], e.pitch());
        }
        assertEquals(72, lowest[1], "the melody is the right hand's line");
        assertEquals(40, highest[3], "the bass is the left hand's lowest notes");
        assertTrue(lowest[2] >= 55 && highest[2] <= 59, "the chord tones in between are the harmony");
        // 键盘、竖琴弹原曲，提琴分旋律、和声、低音，打击乐休息
        int[] masks = OrchestraArranger.assign(piece, List.of(m(InstrumentKind.GRAND_PIANO, 0), m(InstrumentKind.VIOLIN, 1),
            m(InstrumentKind.VIOLA, 2), m(InstrumentKind.CELLO, 3), m(InstrumentKind.TIMPANI, 4), m(InstrumentKind.CONCERT_HARP, 5)));
        assertArrayEquals(new int[]{1, 1 << 1, 1 << 2, 1 << 3, 0, 1}, masks);
    }

    @Test void orchestralScoresAreNotSplit() {
        assertFalse(OrchestraScore.of(BEETHOVEN).split());
        assertEquals(BEETHOVEN.parts().size(), OrchestraScore.of(BEETHOVEN).voiceStart());
    }
}
