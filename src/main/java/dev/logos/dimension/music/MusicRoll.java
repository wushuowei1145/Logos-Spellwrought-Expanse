package dev.logos.dimension.music;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 打孔纸带的内容：若干声部 + 按开始时间排好的事件（音符、延音踏板）。时间一律是毫秒，
 * 速度变化在写入时已经折算好。二进制格式见 docs/HEXENWALD_INSTRUMENTS.md 第 6.2 节（魔数 LRL1）。
 *
 * <p>纯 Java，不依赖游戏类：服务端拿它校验上传的纸带，客户端拿它播放，单元测试直接测。
 */
public final class MusicRoll {
    public static final byte[] MAGIC = {'L', 'R', 'L', '1'};
    public static final int MAX_BYTES = 256 * 1024;
    public static final int MAX_EVENTS = 50_000;
    public static final int MAX_PARTS = 16;
    public static final int MAX_TITLE = 64;
    public static final int MAX_PART_NAME = 32;
    public static final int MAX_DURATION_MS = 20 * 60 * 1000;
    public static final int MAX_NOTE_MS = 30_000;

    public static final int NOTE = 0;
    public static final int PEDAL_DOWN = 1;
    public static final int PEDAL_UP = 2;

    /** 声部：名字与 GM 音色号（只作提示，播放时由乐器决定音色）。 */
    public record Part(String name, int program) {}

    /** 一个事件。踏板事件的 pitch/velocity/duration 为 0。 */
    public record Event(int timeMs, int type, int part, int pitch, int velocity, int durationMs) {
        public static Event note(int timeMs, int part, int pitch, int velocity, int durationMs) {
            return new Event(timeMs, NOTE, part, pitch, velocity, durationMs);
        }

        public static Event pedal(int timeMs, int part, boolean down) {
            return new Event(timeMs, down ? PEDAL_DOWN : PEDAL_UP, part, 0, 0, 0);
        }

        public int endMs() {
            return timeMs + durationMs;
        }
    }

    /**
     * 速度、拍号从 timeMs 起改变（整首开头的速度、拍号是 {@link #bpmX100()}、{@link #beatsPerBar()}、{@link #beatUnit()}）。
     * bpmX100 是每分钟四分音符数 ×100（同 MIDI）；拍号改变时从这里起是新的一小节。指挥按它们画拍子（docs/ORCHESTRA.md 第 7 节）。
     */
    public record Tempo(int timeMs, int bpmX100, int beatsPerBar, int beatUnit) {}

    /** 最多记这么多处速度、拍号的变化。 */
    public static final int MAX_TEMPOS = 4096;

    /** 格式不对或超限。message 是给玩家看的中文说明。 */
    public static final class FormatException extends Exception {
        public FormatException(String message) {
            super(message);
        }
    }

    /** 编码里速度、拍号表那一段的标记字节。 */
    private static final int TEMPO_SECTION = 'T';

    private static final Comparator<Event> ORDER = Comparator.comparingInt(Event::timeMs)
        .thenComparingInt(e -> e.type() == PEDAL_UP ? 0 : e.type() == NOTE ? 1 : 2);

    private final String title;
    private final String author;
    private final int bpmX100;
    private final int beatsPerBar;
    private final int beatUnit;
    private final List<Part> parts;
    private final List<Event> events;
    private final List<Tempo> tempos;

    public MusicRoll(String title, String author, int bpmX100, int beatsPerBar, int beatUnit, List<Part> parts, List<Event> events) {
        this(title, author, bpmX100, beatsPerBar, beatUnit, parts, events, List.of());
    }

    public MusicRoll(String title, String author, int bpmX100, int beatsPerBar, int beatUnit, List<Part> parts, List<Event> events,
                     List<Tempo> tempos) {
        this.title = clip(title, MAX_TITLE);
        this.author = clip(author, MAX_TITLE);
        this.bpmX100 = Math.max(100, Math.min(100_000, bpmX100));
        this.beatsPerBar = Math.max(1, Math.min(32, beatsPerBar));
        this.beatUnit = Math.max(1, Math.min(32, beatUnit));
        List<Part> ps = new ArrayList<>();
        for (Part p : parts) ps.add(new Part(clip(p.name(), MAX_PART_NAME), p.program() & 0x7F));
        if (ps.isEmpty()) ps.add(new Part("", 0));
        this.parts = List.copyOf(ps);
        List<Event> sorted = new ArrayList<>(events);
        sorted.sort(ORDER);
        this.events = List.copyOf(sorted);
        List<Tempo> ts = new ArrayList<>();
        for (Tempo t : tempos) {
            if (t.timeMs() <= 0) continue;
            ts.add(new Tempo(t.timeMs(), Math.max(100, Math.min(100_000, t.bpmX100())), Math.max(1, Math.min(32, t.beatsPerBar())),
                Math.max(1, Math.min(32, t.beatUnit()))));
        }
        ts.sort(Comparator.comparingInt(Tempo::timeMs));
        this.tempos = List.copyOf(ts);
    }

    public String title() { return title; }
    public String author() { return author; }
    public int bpmX100() { return bpmX100; }
    public int beatsPerBar() { return beatsPerBar; }
    public int beatUnit() { return beatUnit; }
    public List<Part> parts() { return parts; }
    public List<Event> events() { return events; }
    /** 开头之后的速度、拍号变化（按时间排好）。 */
    public List<Tempo> tempos() { return tempos; }

    public MusicRoll withMeta(String newTitle, String newAuthor) {
        return new MusicRoll(newTitle, newAuthor, bpmX100, beatsPerBar, beatUnit, parts, events, tempos);
    }

    public int noteCount() {
        int n = 0;
        for (Event e : events) if (e.type() == NOTE) n++;
        return n;
    }

    /**
     * 用音域 low–high 的乐器放这卷纸带时，每个声部整体移几个半音（12 的倍数）：让能弹到的音最多，
     * 整个声部一起移，旋律里的音程保持不变（不逐音折叠）。一样多时不移优先，再是移得少的、往上移的。
     * 移完仍超出音域的个别音由播放时按八度折进来（声部本身比乐器音域还宽时才会有）。
     */
    public int[] octaveShifts(int low, int high) {
        int[] shifts = new int[MAX_PARTS];
        int[][] inRange = new int[MAX_PARTS][11];
        boolean[] used = new boolean[MAX_PARTS];
        for (Event e : events) {
            if (e.type() != NOTE || e.part() < 0 || e.part() >= MAX_PARTS) continue;
            used[e.part()] = true;
            for (int k = -5; k <= 5; k++) {
                int p = e.pitch() + 12 * k;
                if (p >= low && p <= high) inRange[e.part()][k + 5]++;
            }
        }
        for (int part = 0; part < MAX_PARTS; part++) {
            if (!used[part]) continue;
            int best = 0;
            for (int k : new int[]{1, -1, 2, -2, 3, -3, 4, -4, 5, -5}) {
                if (inRange[part][k + 5] > inRange[part][best + 5]) best = k;
            }
            shifts[part] = 12 * best;
        }
        return shifts;
    }

    /** 最后一个音结束（或最后一个踏板事件）的时刻。 */
    public int durationMs() {
        int end = 0;
        for (Event e : events) end = Math.max(end, e.type() == NOTE ? e.endMs() : e.timeMs());
        return end;
    }

    /** 不合格时返回原因（中文），合格返回 null。编码前和解码后都用它检查。 */
    public String problem() {
        if (parts.size() > MAX_PARTS) return "声部超过 " + MAX_PARTS + " 个";
        if (events.size() > MAX_EVENTS) return "事件超过 " + MAX_EVENTS + " 个";
        if (tempos.size() > MAX_TEMPOS) return "速度、拍号变化超过 " + MAX_TEMPOS + " 处";
        for (Tempo t : tempos) if (t.timeMs() > MAX_DURATION_MS) return "超过 20 分钟";
        for (Event e : events) {
            if (e.timeMs() < 0 || e.timeMs() > MAX_DURATION_MS) return "超过 20 分钟";
            if (e.part() < 0 || e.part() >= parts.size()) return "声部编号不对";
            if (e.type() == NOTE) {
                if (e.pitch() < 0 || e.pitch() > 127) return "音高不对";
                if (e.velocity() < 1 || e.velocity() > 127) return "力度不对";
                if (e.durationMs() < 1 || e.durationMs() > MAX_NOTE_MS) return "单个音长于 30 秒";
                if (e.endMs() > MAX_DURATION_MS + MAX_NOTE_MS) return "超过 20 分钟";
            } else if (e.type() != PEDAL_DOWN && e.type() != PEDAL_UP) {
                return "未知事件";
            }
        }
        return null;
    }

    public byte[] encode() throws FormatException {
        String bad = problem();
        if (bad != null) throw new FormatException(bad);
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 + events.size() * 6);
        out.writeBytes(MAGIC);
        writeString(out, title);
        writeString(out, author);
        writeVarInt(out, bpmX100);
        out.write(beatsPerBar);
        out.write(beatUnit);
        out.write(parts.size());
        for (Part p : parts) {
            writeString(out, p.name());
            out.write(p.program());
        }
        writeVarInt(out, events.size());
        int last = 0;
        for (Event e : events) {
            writeVarInt(out, e.timeMs() - last);
            last = e.timeMs();
            out.write(e.type());
            out.write(e.part());
            if (e.type() == NOTE) {
                out.write(e.pitch());
                out.write(e.velocity());
                writeVarInt(out, e.durationMs());
            }
        }
        // 速度、拍号表：只在中途有变化时写（没有变化的纸带与旧格式逐字节相同，旧纸带也照常读）
        if (!tempos.isEmpty()) {
            out.write(TEMPO_SECTION);
            writeVarInt(out, tempos.size());
            int lastT = 0;
            for (Tempo t : tempos) {
                writeVarInt(out, t.timeMs() - lastT);
                lastT = t.timeMs();
                writeVarInt(out, t.bpmX100());
                out.write(t.beatsPerBar());
                out.write(t.beatUnit());
            }
        }
        byte[] bytes = out.toByteArray();
        if (bytes.length > MAX_BYTES) throw new FormatException("纸带内容超过 256 KiB");
        return bytes;
    }

    public static MusicRoll decode(byte[] bytes) throws FormatException {
        if (bytes == null || bytes.length > MAX_BYTES) throw new FormatException("纸带内容超过 256 KiB");
        Reader in = new Reader(bytes);
        for (byte b : MAGIC) if (in.u8() != (b & 0xFF)) throw new FormatException("不是纸带格式");
        String title = in.string(MAX_TITLE);
        String author = in.string(MAX_TITLE);
        int bpm = in.varInt();
        int beats = in.u8();
        int unit = in.u8();
        int partCount = in.u8();
        if (partCount < 1 || partCount > MAX_PARTS) throw new FormatException("声部数不对");
        List<Part> parts = new ArrayList<>(partCount);
        for (int i = 0; i < partCount; i++) parts.add(new Part(in.string(MAX_PART_NAME), in.u8() & 0x7F));
        int count = in.varInt();
        if (count > MAX_EVENTS) throw new FormatException("事件超过 " + MAX_EVENTS + " 个");
        List<Event> events = new ArrayList<>(count);
        long time = 0;
        for (int i = 0; i < count; i++) {
            time += in.varInt();
            if (time > MAX_DURATION_MS) throw new FormatException("超过 20 分钟");
            int type = in.u8();
            int part = in.u8();
            if (type == NOTE) {
                int pitch = in.u8();
                int vel = in.u8();
                int dur = in.varInt();
                events.add(new Event((int) time, NOTE, part, pitch, vel, dur));
            } else if (type == PEDAL_DOWN || type == PEDAL_UP) {
                events.add(new Event((int) time, type, part, 0, 0, 0));
            } else {
                throw new FormatException("未知事件");
            }
        }
        List<Tempo> tempos = new ArrayList<>();
        if (in.remaining() > 0) {
            if (in.u8() != TEMPO_SECTION) throw new FormatException("纸带结尾有多余数据");
            int n = in.varInt();
            if (n > MAX_TEMPOS) throw new FormatException("速度、拍号变化超过 " + MAX_TEMPOS + " 处");
            long t = 0;
            for (int i = 0; i < n; i++) {
                t += in.varInt();
                if (t > MAX_DURATION_MS) throw new FormatException("超过 20 分钟");
                tempos.add(new Tempo((int) t, in.varInt(), in.u8(), in.u8()));
            }
        }
        if (in.remaining() != 0) throw new FormatException("纸带结尾有多余数据");
        MusicRoll roll = new MusicRoll(title, author, bpm, beats, unit, parts, events, tempos);
        String bad = roll.problem();
        if (bad != null) throw new FormatException(bad);
        return roll;
    }

    /** 内容哈希：编码后 SHA-256 的前 8 字节。客户端按纸带 ID + 哈希缓存。 */
    public static long hash(byte[] encoded) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(encoded);
            long h = 0;
            for (int i = 0; i < 8; i++) h = (h << 8) | (d[i] & 0xFF);
            return h;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        s = s.replaceAll("\\p{Cntrl}", "").strip();
        return s.length() > max ? s.substring(0, max) : s;
    }

    static void writeVarInt(ByteArrayOutputStream out, int value) {
        if (value < 0) throw new IllegalArgumentException("negative varint");
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static void writeString(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, b.length);
        out.writeBytes(b);
    }

    /** 有边界检查的读取器：任何长度字段都先和剩余字节比较。 */
    private static final class Reader {
        private final byte[] data;
        private int pos;

        Reader(byte[] data) { this.data = data; }

        int remaining() { return data.length - pos; }

        int u8() throws FormatException {
            if (pos >= data.length) throw new FormatException("纸带内容不完整");
            return data[pos++] & 0xFF;
        }

        int varInt() throws FormatException {
            int value = 0;
            for (int shift = 0; shift < 32; shift += 7) {
                int b = u8();
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    if (value < 0) throw new FormatException("数值过大");
                    return value;
                }
            }
            throw new FormatException("数值过大");
        }

        String string(int maxChars) throws FormatException {
            int len = varInt();
            if (len > maxChars * 4 || len > remaining()) throw new FormatException("文字过长");
            String s = new String(data, pos, len, StandardCharsets.UTF_8);
            pos += len;
            return s;
        }
    }
}
