package dev.logos.dimension.music.orchestra;

import dev.logos.dimension.music.InstrumentKind;
import dev.logos.dimension.music.MusicRoll;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * 乐团的声部分派（docs/ORCHESTRA.md 第 5 节）：总谱的每个声部分给一件或几件乐器。纯逻辑，不碰世界，方便单元测试。
 *
 * <p>先认出每个声部是什么乐器：轨道名认得出就按名字（换成对应的 General MIDI 音色号），认不出就按音色号；
 * 音色号只说了一类（48 弦乐合奏、45 拨弦）或者没有对应的乐器时，再看音区。钢琴曲另外拆出旋律、和声、低音（{@link OrchestraScore}）。
 * 然后对每个「声部 × 乐器」打分（{@link #score}），分四轮配：每个声部一件（总分最高的配法，{@link #match}）、多出来的去齐奏、
 * 没人的声部叠给键盘、零星的声部叠给已经在演奏的人。
 * 锁定的乐器（玩家手动改派过，包括手动让它休息）保持原样。
 */
public final class OrchestraArranger {
    /** 至少能弹到这么多比例的音，才考虑让它演奏这个声部。 */
    static final double MIN_COVERAGE = 0.6;
    /** 音比最多的声部的这个比例还少的声部算「零星」（如拨弦的几个音）：不单独占一个人，叠给已经在演奏的人。 */
    static final double SPARSE = 0.05;

    /**
     * @param kind 乐器种类
     * @param seat 从指挥台看过去从左到右的座次（越小越靠左）
     * @param lockedMask 玩家手动改派后锁定的声部掩码（0 是手动让它休息）；没锁时为 -1
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
            // 打击乐一类的音色：钟、钢鼓给钟琴，太鼓、通通鼓、合成鼓给定音鼓
            case 112, 114 -> InstrumentKind.GLOCKENSPIEL;
            case 116, 117, 118 -> InstrumentKind.TIMPANI;
            default -> null;
        };
    }

    /**
     * 轨道名对应的 GM 音色号（认不出时 null）。专业网站的管弦乐 MIDI 常把弦乐都写成 48（弦乐合奏），乐器写在轨道名里；
     * 也有音色号写错的（名字叫 Viola、音色是颤音琴），名字比号码可信。缩写只认整个词（vn、va、vc、cb、fl、ob、cl、hn……）。
     */
    public static @Nullable Integer programOfName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.isBlank()) return null;
        String[] words = n.split("[^\\p{L}]+");
        // 先认长的、会包含别的名字的（bassoon 里有 bass，english horn 里有 horn，violoncello 里有 viol）
        if (has(n, "pizz", "拨弦")) return 45;
        if (has(n, "contrabass", "contrabbasso", "kontrabass", "double bass", "doublebass", "string bass", "低音提琴") || word(words, "cb", "db", "kb")) return 43;
        if (has(n, "bassoon", "fagott", "fagot", "basson", "巴松", "大管")) return 70;
        if (has(n, "violoncell", "cello", "大提琴") || word(words, "vc", "vlc")) return 42;
        if (has(n, "viola", "bratsche", "alto viol", "中提琴") || word(words, "va", "vla")) return 41;
        if (has(n, "violin", "violine", "violino", "geige", "小提琴") || word(words, "vn", "vln", "vl")) return 40;
        if (has(n, "english horn", "cor anglais", "englischhorn", "corno inglese", "英国管")) return 69;
        if (has(n, "oboe", "hautbois", "双簧管") || word(words, "ob")) return 68;
        if (has(n, "piccolo", "短笛")) return 72;
        if (has(n, "flute", "flöte", "flote", "flauto", "长笛") || word(words, "fl")) return 73;
        if (has(n, "clarinet", "klarinette", "clarinetto", "单簧管") || word(words, "cl", "clar")) return 71;
        if (has(n, "trumpet", "trumept", "trompete", "tromba", "trompette", "小号") || word(words, "tpt", "tp")) return 56;
        if (has(n, "trombone", "posaune", "长号") || word(words, "tbn")) return 57;
        if (has(n, "tuba", "大号")) return 58;
        if (has(n, "horn", "corno", "cor ", "圆号") || word(words, "hn", "hr")) return 60;
        if (has(n, "timpani", "pauken", "timbales", "定音鼓") || word(words, "timp")) return 47;
        if (has(n, "glockenspiel", "钟琴", "xylophone", "marimba", "vibraphone") || word(words, "glock")) return 9;
        if (has(n, "tubular", "chimes", "管钟") || word(words, "bells")) return 14;
        if (has(n, "celesta", "钢片琴")) return 8;
        if (has(n, "harpsichord", "cembalo", "clavecin", "大键琴", "羽管键琴")) return 6;
        if (has(n, "organ", "orgel", "organo", "管风琴")) return 19;
        if (has(n, "harp", "harfe", "arpa", "竖琴")) return 46;
        if (has(n, "piano", "klavier", "grand", "钢琴")) return 0;
        if (has(n, "string", "strings", "streicher", "archi", "弦乐")) return 48;
        if (word(words, "bass")) return 43;
        return null;
    }

    private static boolean has(String name, String... keys) {
        for (String k : keys) if (name.contains(k)) return true;
        return false;
    }

    private static boolean word(String[] words, String... keys) {
        for (String w : words) for (String k : keys) if (w.equals(k)) return true;
        return false;
    }

    /** 这个声部当作哪个音色号看：轨道名认得出按名字，否则按纸带里的音色号。 */
    static int program(MusicRoll.Part part) {
        Integer named = programOfName(part.name());
        return named != null ? named : part.program();
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

    /** 没有音高的音色（阿哥哥、木鱼、反向钹、各种音效）：谁都不演奏。 */
    static boolean unpitched(int program) {
        return program == 113 || program == 115 || program >= 119;
    }

    /** 只演奏写给自己的声部的打击乐器。 */
    static boolean percussion(@Nullable InstrumentKind kind) {
        return kind == InstrumentKind.TIMPANI || kind == InstrumentKind.GLOCKENSPIEL || kind == InstrumentKind.TUBULAR_BELLS;
    }

    /**
     * 乐团里没有的乐器找替身：{乐器, 加分}。单簧管由双簧管、长笛接；圆号由小号、巴松管接；长号、大号由巴松管、大提琴、低音提琴接；
     * 萨克斯同单簧管；人声、合唱由提琴、长笛接。
     */
    private static final Map<InstrumentKind, Integer> NONE = Map.of();

    static Map<InstrumentKind, Integer> substitutes(int program) {
        Map<InstrumentKind, Integer> m = new EnumMap<>(InstrumentKind.class);
        switch (program) {
            case 71, 64, 65, 66, 67 -> { m.put(InstrumentKind.OBOE, 70); m.put(InstrumentKind.FLUTE, 60); m.put(InstrumentKind.VIOLA, 30); }
            case 60, 61 -> { m.put(InstrumentKind.TRUMPET, 60); m.put(InstrumentKind.BASSOON, 55); m.put(InstrumentKind.VIOLA, 35); }
            case 57 -> { m.put(InstrumentKind.BASSOON, 60); m.put(InstrumentKind.TRUMPET, 45); m.put(InstrumentKind.CELLO, 40); }
            case 58 -> { m.put(InstrumentKind.BASSOON, 60); m.put(InstrumentKind.DOUBLE_BASS, 50); m.put(InstrumentKind.CELLO, 40); }
            case 52, 53, 54 -> { m.put(InstrumentKind.VIOLIN, 40); m.put(InstrumentKind.FLUTE, 40); m.put(InstrumentKind.VIOLA, 40); }
            default -> { return NONE; }
        }
        return m;
    }

    /** 一个声部的统计：音符数、移调前的中位音高、当作的音色号、是不是钢琴曲拆出来的声部。 */
    record PartStats(int index, int notes, int median, int program, boolean voice) {}

    static List<PartStats> stats(OrchestraScore.Score score) {
        MusicRoll roll = score.roll();
        List<List<Integer>> pitches = new ArrayList<>();
        for (int i = 0; i < MusicRoll.MAX_PARTS; i++) pitches.add(new ArrayList<>());
        for (MusicRoll.Event e : roll.events()) {
            if (e.type() == MusicRoll.NOTE && e.part() >= 0 && e.part() < MusicRoll.MAX_PARTS) pitches.get(e.part()).add(e.pitch());
        }
        List<PartStats> out = new ArrayList<>();
        for (int i = 0; i < MusicRoll.MAX_PARTS; i++) {
            List<Integer> p = pitches.get(i);
            if (p.isEmpty()) continue;
            p.sort(Integer::compare);
            int program = i < roll.parts().size() ? program(roll.parts().get(i)) : 0;
            out.add(new PartStats(i, p.size(), p.get(p.size() / 2), program, score.voice(i)));
        }
        return out;
    }

    /** 每个成员演奏的声部掩码（与 members 一一对应，0 表示休息）。声部编号是 {@link OrchestraScore#of} 那一份总谱的。 */
    public static int[] assign(MusicRoll raw, List<Member> members) {
        return assign(OrchestraScore.of(raw), members);
    }

    static int[] assign(OrchestraScore.Score score, List<Member> members) {
        MusicRoll roll = score.roll();
        int[] masks = new int[members.size()];
        List<PartStats> parts = stats(score);
        int most = 0;
        for (PartStats ps : parts) most = Math.max(most, ps.notes());
        boolean[] sparse = new boolean[MusicRoll.MAX_PARTS];
        for (PartStats ps : parts) sparse[ps.index()] = !ps.voice() && ps.notes() < most * SPARSE && !percussion(ofProgram(ps.program()));
        Map<InstrumentKind, int[]> shifts = new EnumMap<>(InstrumentKind.class);
        Map<InstrumentKind, int[]> inRange = new EnumMap<>(InstrumentKind.class);
        for (Member m : members) {
            shifts.computeIfAbsent(m.kind(), k -> roll.octaveShifts(k.low(), k.high()));
            inRange.computeIfAbsent(m.kind(), k -> covered(roll, k, shifts.get(k)));
        }
        boolean[] covered = new boolean[MusicRoll.MAX_PARTS];
        boolean[] busy = new boolean[members.size()];
        int[] players = new int[MusicRoll.MAX_PARTS];
        for (int i = 0; i < members.size(); i++) {
            if (members.get(i).lockedMask() < 0) continue;
            masks[i] = members.get(i).lockedMask();
            busy[i] = true;
            for (int p = 0; p < MusicRoll.MAX_PARTS; p++) {
                if ((masks[i] & (1 << p)) != 0) {
                    covered[p] = true;
                    players[p]++;
                }
            }
        }
        // 钢琴曲：键盘、竖琴、管风琴弹原曲（全部原来的声部），别的乐器分拆出来的旋律、和声、低音
        if (score.split()) {
            int originals = 0;
            for (PartStats ps : parts) if (!ps.voice()) originals |= 1 << ps.index();
            for (int i = 0; i < members.size(); i++) {
                if (busy[i] || !polyphonic(members.get(i).kind())) continue;
                masks[i] = originals;
                busy[i] = true;
                for (int p : parts(originals)) {
                    covered[p] = true;
                    players[p]++;
                }
            }
        }
        // 所有可行的配对，按分数从高到低；同分时声部在前的、座次靠左的先配
        record Pair(int part, int member, double score) {}
        List<Pair> pairs = new ArrayList<>();
        for (PartStats ps : parts) {
            for (int i = 0; i < members.size(); i++) {
                if (members.get(i).lockedMask() >= 0) continue;
                InstrumentKind kind = members.get(i).kind();
                double s = score(ps, kind, shifts.get(kind), inRange.get(kind));
                if (!Double.isNaN(s)) pairs.add(new Pair(ps.index(), i, s));
            }
        }
        pairs.sort(Comparator.comparingDouble((Pair p) -> -p.score()).thenComparingInt(Pair::part)
            .thenComparingDouble(p -> members.get(p.member()).seat()));
        // 第一轮：每个声部一件（零星的声部不单独占人）。整体最合适地配（总分最高），不是一对一对挑最高的：
        // 否则大提琴先占了离它最近的和声，低音只剩中提琴去移八度
        List<Integer> rows = new ArrayList<>(), cols = new ArrayList<>();
        for (PartStats ps : parts) if (!covered[ps.index()] && !sparse[ps.index()]) rows.add(ps.index());
        for (int i = 0; i < members.size(); i++) if (!busy[i]) cols.add(i);
        double[][] gain = new double[rows.size()][cols.size()];
        for (double[] g : gain) java.util.Arrays.fill(g, Double.NaN);
        List<Integer> bySeat = new ArrayList<>(cols);
        bySeat.sort(Comparator.comparingDouble(i -> members.get(i).seat()));
        for (Pair p : pairs) {
            int r = rows.indexOf(p.part()), c = cols.indexOf(p.member());
            if (r < 0 || c < 0) continue;
            // 同样合适时：声部在前的配座次靠左的（两把小提琴，左边那把拉第一）
            gain[r][c] = p.score() + 1e-6 * (rows.size() - r) * (cols.size() - bySeat.indexOf(p.member()));
        }
        int[] match = match(gain);
        for (int r = 0; r < rows.size(); r++) {
            if (match[r] < 0) continue;
            int part = rows.get(r), member = cols.get(match[r]);
            masks[member] = 1 << part;
            covered[part] = true;
            busy[member] = true;
            players[part]++;
        }
        // 第二轮：多出来的乐器按座次从左到右，去齐奏它最合适的声部：只去写给它自己（或它这一类、它能替的）的声部、
        // 钢琴曲拆出来的声部、认不出是什么乐器的声部；比最合适的差不到 10 分的几个声部里去人少的（第一第二小提琴各一半）
        List<Integer> idle = new ArrayList<>();
        for (int i = 0; i < members.size(); i++) if (!busy[i]) idle.add(i);
        idle.sort(Comparator.comparingDouble(i -> members.get(i).seat()));
        PartStats[] byIndex = new PartStats[MusicRoll.MAX_PARTS];
        for (PartStats ps : parts) byIndex[ps.index()] = ps;
        for (int i : idle) {
            InstrumentKind kind = members.get(i).kind();
            double top = Double.NEGATIVE_INFINITY;
            for (Pair p : pairs) {
                if (p.member() == i && !sparse[p.part()] && doubles(byIndex[p.part()], kind)) top = Math.max(top, p.score());
            }
            Pair best = null;
            for (Pair p : pairs) {
                if (p.member() != i || sparse[p.part()] || !doubles(byIndex[p.part()], kind) || p.score() < top - 10) continue;
                if (best == null || players[p.part()] < players[best.part()]
                    || players[p.part()] == players[best.part()] && p.score() > best.score()) best = p;
            }
            if (best == null) continue;
            masks[i] = 1 << best.part();
            busy[i] = true;
            covered[best.part()] = true;
            players[best.part()]++;
        }
        // 第三轮：还没人演奏的声部（不是拆出来的）叠给已有声部、能弹多声部的乐器
        for (PartStats ps : parts) {
            if (covered[ps.index()] || ps.voice() || sparse[ps.index()]) continue;
            int best = -1;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < members.size(); i++) {
                Member m = members.get(i);
                if (m.lockedMask() >= 0 || !polyphonic(m.kind()) || masks[i] == 0) continue;
                double s = fit(ps, m.kind(), shifts.get(m.kind()), inRange.get(m.kind()));
                if (!Double.isNaN(s) && s > bestScore) {
                    bestScore = s;
                    best = i;
                }
            }
            if (best < 0) continue;
            masks[best] |= 1 << ps.index();
            covered[ps.index()] = true;
        }
        // 第四轮：零星的声部叠给最合适的、已经在演奏的人；都不合适时才让一个闲着的人来
        for (PartStats ps : parts) {
            if (!sparse[ps.index()] || covered[ps.index()]) continue;
            Pair best = null;
            for (Pair p : pairs) {
                if (p.part() != ps.index()) continue;
                if (best == null || masks[p.member()] != 0 && masks[best.member()] == 0) best = p;
                if (masks[best.member()] != 0) break;
            }
            if (best == null) continue;
            masks[best.member()] |= 1 << ps.index();
            covered[ps.index()] = true;
        }
        return masks;
    }

    /**
     * 每一行（声部）配一列（乐器），每列最多配一行，NaN 表示不能配：先配上尽量多的行，再让总分最高（匈牙利算法）。
     * 返回每一行配到的列，没配上为 -1。
     */
    static int[] match(double[][] gain) {
        int n = gain.length, m = n == 0 ? 0 : gain[0].length;
        int[] out = new int[n];
        java.util.Arrays.fill(out, -1);
        if (n == 0 || m == 0) return out;
        // 方阵：补的行、列和不能配的格子代价都一样（很大），配上一对能配的总比不配强
        int size = Math.max(n, m);
        final double big = 1e7, offset = 1e5;
        double[][] cost = new double[size + 1][size + 1];
        for (int i = 1; i <= size; i++) {
            for (int j = 1; j <= size; j++) {
                double g = i <= n && j <= m ? gain[i - 1][j - 1] : Double.NaN;
                cost[i][j] = Double.isNaN(g) ? big : offset - g;
            }
        }
        double[] u = new double[size + 1], v = new double[size + 1];
        int[] p = new int[size + 1], way = new int[size + 1];
        for (int i = 1; i <= size; i++) {
            p[0] = i;
            int j0 = 0;
            double[] minv = new double[size + 1];
            java.util.Arrays.fill(minv, Double.POSITIVE_INFINITY);
            boolean[] used = new boolean[size + 1];
            do {
                used[j0] = true;
                int i0 = p[j0], j1 = 0;
                double delta = Double.POSITIVE_INFINITY;
                for (int j = 1; j <= size; j++) {
                    if (used[j]) continue;
                    double cur = cost[i0][j] - u[i0] - v[j];
                    if (cur < minv[j]) {
                        minv[j] = cur;
                        way[j] = j0;
                    }
                    if (minv[j] < delta) {
                        delta = minv[j];
                        j1 = j;
                    }
                }
                for (int j = 0; j <= size; j++) {
                    if (used[j]) {
                        u[p[j]] += delta;
                        v[j] -= delta;
                    } else {
                        minv[j] -= delta;
                    }
                }
                j0 = j1;
            } while (p[j0] != 0);
            do {
                int j1 = way[j0];
                p[j0] = p[j1];
                j0 = j1;
            } while (j0 != 0);
        }
        for (int j = 1; j <= size; j++) {
            int i = p[j];
            if (i >= 1 && i <= n && j <= m && !Double.isNaN(gain[i - 1][j - 1])) out[i - 1] = j - 1;
        }
        return out;
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

    /**
     * 这件乐器能不能演奏这个声部：打击乐只演奏写给自己的声部；键盘、竖琴、管风琴演奏键盘一类的声部（钢琴曲拆出来的不算）；
     * 别的乐器不演奏写给键盘、打击乐的声部。
     */
    static boolean eligible(PartStats part, InstrumentKind kind) {
        if (!part.voice() && unpitched(part.program())) return false;
        InstrumentKind named = ofProgram(part.program());
        if (percussion(kind)) return !part.voice() && named == kind;
        if (polyphonic(kind)) return !part.voice() && polyphonic(named);
        return part.voice() || !(percussion(named) || polyphonic(named));
    }

    /**
     * 多出来的乐器能不能去齐奏这个声部：钢琴曲拆出来的、认不出是什么乐器的声部都行；写给某件乐器的声部只有那件乐器、
     * 同一类（弦乐合奏配提琴家族、键盘一类配键盘）或替身（单簧管配双簧管……）去齐奏。
     */
    static boolean doubles(PartStats part, InstrumentKind kind) {
        if (part.voice()) return true;
        InstrumentKind named = ofProgram(part.program());
        boolean identified = named != null || stringSection(part.program()) || !substitutes(part.program()).isEmpty();
        if (!identified) return true;
        return named == kind || stringSection(part.program()) && stringFamily(kind) || polyphonic(named) && polyphonic(kind)
            || substitutes(part.program()).containsKey(kind);
    }

    /** 配对的分数；不能演奏或弹不了（能弹到的音不到六成）时为 NaN。 */
    static double score(PartStats part, InstrumentKind kind, int[] shift, int[] inRange) {
        return eligible(part, kind) ? fit(part, kind, shift, inRange) : Double.NaN;
    }

    /**
     * 不管能不能演奏，单看合适的程度：能弹到的比例 × 40；乐器对上 +100（钢琴曲拆出来的声部不看音色）、替身按表、
     * 弦乐合奏一类配提琴家族 +60、键盘一类配键盘 +60；整体移八度每个 −12；移好后的中位音离乐器音域中心每差 12 个半音 −10。
     */
    static double fit(PartStats part, InstrumentKind kind, int[] shift, int[] inRange) {
        double coverage = inRange[part.index()] / (double) part.notes();
        if (coverage < MIN_COVERAGE) return Double.NaN;
        double s = coverage * 40;
        if (!part.voice()) {
            InstrumentKind named = ofProgram(part.program());
            if (named == kind) s += 100;
            else if (stringSection(part.program()) && stringFamily(kind)) s += 60;
            else if (polyphonic(named) && polyphonic(kind)) s += 60;
            else s += substitutes(part.program()).getOrDefault(kind, 0);
        }
        int move = shift[part.index()];
        s -= Math.abs(move) / 12.0 * 12;
        double center = (kind.low() + kind.high()) / 2.0;
        s -= Math.abs(part.median() + move - center) / 12.0 * 10;
        return s;
    }

    /** 掩码转成声部下标列表。 */
    public static int[] parts(int mask) {
        return java.util.stream.IntStream.range(0, MusicRoll.MAX_PARTS).filter(p -> (mask & (1 << p)) != 0).toArray();
    }
}
