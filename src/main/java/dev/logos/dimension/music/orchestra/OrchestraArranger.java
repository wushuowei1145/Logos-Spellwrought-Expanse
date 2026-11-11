package dev.logos.dimension.music.orchestra;

import dev.logos.dimension.music.InstrumentKind;
import dev.logos.dimension.music.MusicRoll;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * 乐团的声部分派（docs/ORCHESTRA.md 第 5 节）：总谱的每个声部分给一件或几件乐器。纯逻辑，不碰世界，方便单元测试。
 *
 * <p>对每个「声部 × 乐器」打分：MIDI 音色号对上乐器种类加大分；整体移八度后能弹到的音少于六成不考虑；
 * 声部的中位音高离乐器音域中心越近越好。先每个声部配一件（同分时声部按顺序、乐器按座次从左到右，所以两把小提琴分到
 * 第一、第二小提琴）；多出来的乐器去齐奏它最合适的声部；还没人演奏的声部再叠给已经有声部的键盘、竖琴、管风琴（「兼」）。
 * 锁定的乐器（玩家手动改派过）保持原样。
 */
public final class OrchestraArranger {
    /** 至少能弹到这么多比例的音，才考虑让它演奏这个声部。 */
    static final double MIN_COVERAGE = 0.6;

    /**
     * @param kind 乐器种类
     * @param seat 从指挥台看过去从左到右的座次（越小越靠左）
     * @param lockedMask 玩家手动改派后锁定的声部掩码；没锁时为 -1
     */
    public record Member(InstrumentKind kind, double seat, int lockedMask) {}

    private OrchestraArranger() {}

    /** GM 音色号（0–127）对应的乐器；没有对应的为 null。 */
    public static @Nullable InstrumentKind ofProgram(int program) {
        if (program >= 0 && program <= 5) return InstrumentKind.GRAND_PIANO;
        return switch (program) {
            case 6, 7 -> InstrumentKind.HARPSICHORD;
            case 8, 10 -> InstrumentKind.CELESTA;
            case 9, 11, 12, 13 -> InstrumentKind.GLOCKENSPIEL;
            case 14 -> InstrumentKind.TUBULAR_BELLS;
            case 16, 17, 18, 19, 20 -> InstrumentKind.PIPE_ORGAN;
            case 24, 25, 46 -> InstrumentKind.CONCERT_HARP;
            case 40 -> InstrumentKind.VIOLIN;
            case 41 -> InstrumentKind.VIOLA;
            case 42 -> InstrumentKind.CELLO;
            case 43 -> InstrumentKind.DOUBLE_BASS;
            case 47 -> InstrumentKind.TIMPANI;
            case 56 -> InstrumentKind.TRUMPET;
            case 68, 69 -> InstrumentKind.OBOE;
            case 70 -> InstrumentKind.BASSOON;
            case 72, 73, 74, 75 -> InstrumentKind.FLUTE;
            default -> null;
        };
    }

    /** 弦乐合奏、颤音弦乐、拨弦等音色：按音区分给提琴家族。 */
    static boolean stringSection(int program) {
        return program == 44 || program == 45 || (program >= 48 && program <= 51);
    }

    static boolean stringFamily(InstrumentKind kind) {
        return kind == InstrumentKind.VIOLIN || kind == InstrumentKind.VIOLA || kind == InstrumentKind.CELLO || kind == InstrumentKind.DOUBLE_BASS;
    }

    /** 能同时弹几个声部的乐器（键盘、竖琴、管风琴）：没人演奏的声部可以叠给它。 */
    public static boolean polyphonic(InstrumentKind kind) {
        return kind == InstrumentKind.GRAND_PIANO || kind == InstrumentKind.CONCERT_HARP || kind == InstrumentKind.PIPE_ORGAN
            || kind == InstrumentKind.HARPSICHORD || kind == InstrumentKind.CELESTA;
    }

    /** 一个声部的统计：音符数、移调前的中位音高、音色号。 */
    record PartStats(int index, int notes, int median, int program) {}

    static List<PartStats> stats(MusicRoll score) {
        List<List<Integer>> pitches = new ArrayList<>();
        for (int i = 0; i < MusicRoll.MAX_PARTS; i++) pitches.add(new ArrayList<>());
        for (MusicRoll.Event e : score.events()) {
            if (e.type() == MusicRoll.NOTE && e.part() >= 0 && e.part() < MusicRoll.MAX_PARTS) pitches.get(e.part()).add(e.pitch());
        }
        List<PartStats> out = new ArrayList<>();
        for (int i = 0; i < MusicRoll.MAX_PARTS; i++) {
            List<Integer> p = pitches.get(i);
            if (p.isEmpty()) continue;
            p.sort(Integer::compare);
            int program = i < score.parts().size() ? score.parts().get(i).program() : 0;
            out.add(new PartStats(i, p.size(), p.get(p.size() / 2), program));
        }
        return out;
    }

    /** 每个成员演奏的声部掩码（与 members 一一对应，0 表示休息）。 */
    public static int[] assign(MusicRoll score, List<Member> members) {
        int[] masks = new int[members.size()];
        List<PartStats> parts = stats(score);
        Map<InstrumentKind, int[]> shifts = new EnumMap<>(InstrumentKind.class);
        Map<InstrumentKind, int[]> inRange = new EnumMap<>(InstrumentKind.class);
        for (Member m : members) {
            shifts.computeIfAbsent(m.kind(), k -> score.octaveShifts(k.low(), k.high()));
            inRange.computeIfAbsent(m.kind(), k -> covered(score, k, shifts.get(k)));
        }
        boolean[] covered = new boolean[MusicRoll.MAX_PARTS];
        boolean[] busy = new boolean[members.size()];
        for (int i = 0; i < members.size(); i++) {
            if (members.get(i).lockedMask() < 0) continue;
            masks[i] = members.get(i).lockedMask();
            busy[i] = true;
            for (int p = 0; p < MusicRoll.MAX_PARTS; p++) if ((masks[i] & (1 << p)) != 0) covered[p] = true;
        }
        // 所有可行的配对，按分数从高到低；同分时声部在前的、座次靠左的先配
        record Pair(int part, int member, double score) {}
        List<Pair> pairs = new ArrayList<>();
        for (PartStats ps : parts) {
            for (int i = 0; i < members.size(); i++) {
                if (members.get(i).lockedMask() >= 0) continue;
                double s = score(ps, members.get(i).kind(), shifts.get(members.get(i).kind()), inRange.get(members.get(i).kind()));
                if (!Double.isNaN(s)) pairs.add(new Pair(ps.index(), i, s));
            }
        }
        pairs.sort(Comparator.comparingDouble((Pair p) -> -p.score()).thenComparingInt(Pair::part)
            .thenComparingDouble(p -> members.get(p.member()).seat()));
        // 第一轮：每个声部一件
        for (Pair p : pairs) {
            if (covered[p.part()] || busy[p.member()]) continue;
            masks[p.member()] = 1 << p.part();
            covered[p.part()] = true;
            busy[p.member()] = true;
        }
        // 第二轮：多出来的乐器去齐奏它最合适的声部
        for (Pair p : pairs) {
            if (busy[p.member()]) continue;
            masks[p.member()] = 1 << p.part();
            busy[p.member()] = true;
        }
        // 第三轮：还没人演奏的声部叠给已有声部、能弹多声部的乐器
        for (Pair p : pairs) {
            if (covered[p.part()] || members.get(p.member()).lockedMask() >= 0) continue;
            if (!polyphonic(members.get(p.member()).kind()) || masks[p.member()] == 0) continue;
            masks[p.member()] |= 1 << p.part();
            covered[p.part()] = true;
        }
        return masks;
    }

    /** 每个声部在这件乐器上（整体移八度之后）能弹到的音数。 */
    private static int[] covered(MusicRoll score, InstrumentKind kind, int[] shift) {
        int[] n = new int[MusicRoll.MAX_PARTS];
        for (MusicRoll.Event e : score.events()) {
            if (e.type() != MusicRoll.NOTE || e.part() < 0 || e.part() >= MusicRoll.MAX_PARTS) continue;
            int p = e.pitch() + shift[e.part()];
            if (p >= kind.low() && p <= kind.high()) n[e.part()]++;
        }
        return n;
    }

    /** 配对的分数；弹不了（能弹到的音不到六成）时为 NaN。 */
    static double score(PartStats part, InstrumentKind kind, int[] shift, int[] inRange) {
        double coverage = inRange[part.index()] / (double) part.notes();
        if (coverage < MIN_COVERAGE) return Double.NaN;
        double s = coverage * 40;
        InstrumentKind named = ofProgram(part.program());
        if (named == kind) s += 100;
        else if (stringSection(part.program()) && stringFamily(kind)) s += 60;
        double center = (kind.low() + kind.high()) / 2.0;
        s -= Math.abs(part.median() + shift[part.index()] - center) / 12.0 * 10;
        return s;
    }

    /** 掩码转成声部下标列表。 */
    public static int[] parts(int mask) {
        return java.util.stream.IntStream.range(0, MusicRoll.MAX_PARTS).filter(p -> (mask & (1 << p)) != 0).toArray();
    }
}
