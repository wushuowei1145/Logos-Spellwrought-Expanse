package dev.logos.dimension.music.orchestra;

import dev.logos.dimension.music.InstrumentKind;
import dev.logos.dimension.music.MusicRoll;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 乐团演奏用的总谱（docs/ORCHESTRA.md 第 5 节）：纸带原样，钢琴曲另外拆出旋律、和声、低音三个声部，让键盘以外的乐器也有得演奏。
 * 纯逻辑；服务端分派和客户端合奏都从这里拿同一份，所以声部的编号两边一致。
 *
 * <p>「钢琴曲」：所有有音的声部都是键盘音色（钢琴、大键琴；没写音色号的通道读出来也是 0 号钢琴），轨道名也没说是别的乐器。
 * 拆法（按时间从前往后、几乎同时按下的音算一组）：一组里最高的音归旋律、最低的归低音，中间的归和声；
 * 单独一个音看它离刚才的旋律近还是离刚才的低音近。一组的最高音离低音更近（右手停着、左手在弹和弦）时不算旋律，最低音同理。
 */
public final class OrchestraScore {
    public static final int MELODY = 0, HARMONY = 1, BASS = 2;
    /** 几乎同时按下：这么多毫秒以内的算一组。 */
    private static final int CHORD_MS = 35;
    private static final Map<MusicRoll, Score> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * @param roll 演奏用的纸带：原来的声部在前，拆出的声部（如果有）接在后面
     * @param voiceStart 拆出的第一个声部的编号；没拆时等于声部数
     * @param roles 拆出的各声部是 {@link #MELODY}、{@link #HARMONY} 还是 {@link #BASS}
     */
    public record Score(MusicRoll roll, int voiceStart, List<Integer> roles) {
        public boolean voice(int part) {
            return part >= voiceStart && part < voiceStart + roles.size();
        }

        /** 拆出的声部是哪一种；不是拆出的声部时 -1。 */
        public int role(int part) {
            return voice(part) ? roles.get(part - voiceStart) : -1;
        }

        /** 拆过（这是一首钢琴曲）。 */
        public boolean split() {
            return !roles.isEmpty();
        }
    }

    private OrchestraScore() {}

    /** 演奏用的总谱（同一卷纸带只算一次）。 */
    public static Score of(MusicRoll raw) {
        return CACHE.computeIfAbsent(raw, OrchestraScore::prepare);
    }

    static Score prepare(MusicRoll raw) {
        int parts = raw.parts().size();
        boolean[] used = new boolean[parts];
        for (MusicRoll.Event e : raw.events()) if (e.type() == MusicRoll.NOTE && e.part() >= 0 && e.part() < parts) used[e.part()] = true;
        boolean any = false;
        for (int p = 0; p < parts; p++) {
            if (!used[p]) continue;
            any = true;
            if (!keyboard(raw.parts().get(p))) return new Score(raw, parts, List.of());
        }
        if (!any || parts + 3 > MusicRoll.MAX_PARTS) return new Score(raw, parts, List.of());
        List<MusicRoll.Event> notes = new ArrayList<>();
        for (MusicRoll.Event e : raw.events()) if (e.type() == MusicRoll.NOTE) notes.add(e);
        notes.sort((a, b) -> a.timeMs() != b.timeMs() ? Integer.compare(a.timeMs(), b.timeMs()) : Integer.compare(b.pitch(), a.pitch()));
        List<List<MusicRoll.Event>> voices = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        split(notes, voices);
        List<MusicRoll.Part> outParts = new ArrayList<>(raw.parts());
        List<MusicRoll.Event> outEvents = new ArrayList<>(raw.events());
        List<Integer> roles = new ArrayList<>();
        for (int role : new int[]{MELODY, HARMONY, BASS}) {
            if (voices.get(role).isEmpty()) continue;
            int index = outParts.size();
            // 名字只是占位，界面按 role 显示「旋律」「和声」「低音」；音色号 48（弦乐合奏）：给提琴家族、管乐
            outParts.add(new MusicRoll.Part("", 48));
            for (MusicRoll.Event e : voices.get(role)) outEvents.add(MusicRoll.Event.note(e.timeMs(), index, e.pitch(), e.velocity(), e.durationMs()));
            roles.add(role);
        }
        MusicRoll roll = new MusicRoll(raw.title(), raw.author(), raw.bpmX100(), raw.beatsPerBar(), raw.beatUnit(), outParts, outEvents, raw.tempos());
        return new Score(roll, parts, List.copyOf(roles));
    }

    /** 键盘音色、轨道名没说是别的乐器的声部。 */
    private static boolean keyboard(MusicRoll.Part part) {
        Integer named = OrchestraArranger.programOfName(part.name());
        int program = named != null ? named : part.program();
        InstrumentKind kind = OrchestraArranger.ofProgram(program);
        return kind == InstrumentKind.GRAND_PIANO || kind == InstrumentKind.HARPSICHORD;
    }

    /** 按时间分组，每组分给旋律、和声、低音（notes 已按时间、音高从高到低排好）。 */
    static void split(List<MusicRoll.Event> notes, List<List<MusicRoll.Event>> voices) {
        int lastMelody = Integer.MIN_VALUE, lastBass = Integer.MIN_VALUE;
        int i = 0;
        while (i < notes.size()) {
            int start = notes.get(i).timeMs();
            int j = i;
            while (j < notes.size() && notes.get(j).timeMs() - start <= CHORD_MS) j++;
            List<MusicRoll.Event> group = new ArrayList<>(notes.subList(i, j));
            group.sort((a, b) -> Integer.compare(b.pitch(), a.pitch()));
            MusicRoll.Event top = group.get(0), bottom = group.get(group.size() - 1);
            if (lastMelody == Integer.MIN_VALUE) {
                // 第一组：最高的是旋律、最低的是低音（只有一个音时先当旋律，低音从它下面一个半八度算起）
                lastMelody = top.pitch();
                lastBass = group.size() > 1 ? bottom.pitch() : top.pitch() - 19;
            }
            if (group.size() == 1) {
                if (Math.abs(top.pitch() - lastMelody) <= Math.abs(top.pitch() - lastBass)) {
                    voices.get(MELODY).add(top);
                    lastMelody = top.pitch();
                } else {
                    voices.get(BASS).add(top);
                    lastBass = top.pitch();
                }
            } else {
                boolean melody = Math.abs(top.pitch() - lastMelody) <= Math.abs(top.pitch() - lastBass) + 5;
                boolean bass = Math.abs(bottom.pitch() - lastBass) <= Math.abs(bottom.pitch() - lastMelody) + 5;
                for (MusicRoll.Event e : group) {
                    if (e == top && melody) {
                        voices.get(MELODY).add(e);
                        lastMelody = e.pitch();
                    } else if (e == bottom && bass) {
                        voices.get(BASS).add(e);
                        lastBass = e.pitch();
                    } else {
                        voices.get(HARMONY).add(e);
                    }
                }
            }
            i = j;
        }
    }
}
