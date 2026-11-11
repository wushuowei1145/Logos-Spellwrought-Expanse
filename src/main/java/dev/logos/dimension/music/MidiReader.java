package dev.logos.dimension.music;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 标准 MIDI 文件（格式 0 / 1）→ 纸带。自己写的解析器，不用 javax.sound.midi：
 * 所有长度字段先和剩余字节比较再读；轨道数、事件数在解析途中就按上限截停；出错时报出第几轨第几个事件。
 *
 * <p>只在客户端用（服务端从不读 MIDI），但放在公共包里，单元测试不必启动游戏。
 */
public final class MidiReader {
    public static final int MAX_FILE_BYTES = 1024 * 1024;
    public static final int MAX_TRACKS = 64;
    private static final int DRUM_CHANNEL = 9;

    /** 解析结果：纸带 + 给玩家看的提示（打击乐被略过、被截断等）。 */
    public record Result(MusicRoll roll, List<String> notes, boolean truncated) {}

    private MidiReader() {}

    public static Result read(byte[] data, String fallbackTitle) throws MusicRoll.FormatException {
        if (data.length > MAX_FILE_BYTES) throw new MusicRoll.FormatException("文件超过 1 MiB");
        Cursor c = new Cursor(data, -1);
        if (!c.tag().equals("MThd")) throw new MusicRoll.FormatException("不是 MIDI 文件（缺少 MThd 头）");
        int headerLen = c.u32();
        if (headerLen < 6 || headerLen > c.remaining()) throw new MusicRoll.FormatException("MIDI 文件头不完整");
        int format = c.u16();
        int trackCount = c.u16();
        int division = c.u16();
        c.skip(headerLen - 6);
        if (format > 1) throw new MusicRoll.FormatException("不支持格式 " + format + " 的 MIDI（只支持 0 和 1）");
        if (trackCount < 1) throw new MusicRoll.FormatException("MIDI 文件里没有轨道");
        List<String> notes = new ArrayList<>();
        if (trackCount > MAX_TRACKS) {
            notes.add("轨道超过 " + MAX_TRACKS + " 条，只读前 " + MAX_TRACKS + " 条");
            trackCount = MAX_TRACKS;
        }

        // 第一遍：读出所有轨道的原始事件（tick 时间）
        List<Raw> raws = new ArrayList<>();
        TreeMap<Long, Integer> tempos = new TreeMap<>();
        Map<Integer, String> trackNames = new HashMap<>();
        int[] timeSig = {4, 4};
        boolean sawTimeSig = false;
        TreeMap<Long, int[]> meters = new TreeMap<>();
        boolean truncated = false;
        int track = 0;
        while (track < trackCount && c.remaining() >= 8) {
            String tag = c.tag();
            int len = c.u32();
            if (len < 0 || len > c.remaining()) throw new MusicRoll.FormatException("第 " + (track + 1) + " 轨长度超出文件");
            if (!tag.equals("MTrk")) {
                c.skip(len);
                continue;
            }
            Cursor t = new Cursor(c.slice(len), track);
            long tick = 0;
            int status = 0;
            int index = 0;
            while (t.remaining() > 0) {
                index++;
                t.event = index;
                tick += t.varLen();
                int b = t.u8();
                if (b == 0xFF) {
                    int type = t.u8();
                    int mlen = t.varLen();
                    byte[] meta = t.bytes(mlen);
                    if (type == 0x2F) break;
                    if (type == 0x51 && mlen == 3) {
                        int us = ((meta[0] & 0xFF) << 16) | ((meta[1] & 0xFF) << 8) | (meta[2] & 0xFF);
                        if (us > 0) tempos.put(tick, us);
                    } else if (type == 0x03 && !trackNames.containsKey(track)) {
                        trackNames.put(track, new String(meta, StandardCharsets.UTF_8).strip());
                    } else if (type == 0x58 && mlen >= 2) {
                        int[] sig = {Math.max(1, meta[0] & 0xFF), 1 << Math.min(5, meta[1] & 0xFF)};
                        meters.putIfAbsent(tick, sig);
                        if (!sawTimeSig) {
                            timeSig[0] = sig[0];
                            timeSig[1] = sig[1];
                            sawTimeSig = true;
                        }
                    }
                    status = 0;
                    continue;
                }
                if (b == 0xF0 || b == 0xF7) {
                    t.skip(t.varLen());
                    status = 0;
                    continue;
                }
                int d1;
                if ((b & 0x80) != 0) {
                    status = b;
                    d1 = -1;
                } else {
                    if (status == 0) throw t.error("缺少状态字节");
                    d1 = b;
                }
                int kind = status & 0xF0;
                int channel = status & 0x0F;
                int dataLen = kind == 0xC0 || kind == 0xD0 ? 1 : 2;
                if (d1 < 0) d1 = t.u8();
                int d2 = dataLen == 2 ? t.u8() : 0;
                if (d1 > 127 || d2 > 127) throw t.error("数据字节超过 127");
                switch (kind) {
                    case 0x90, 0x80, 0xB0, 0xC0 -> {
                        if (raws.size() >= MusicRoll.MAX_EVENTS * 2) {
                            truncated = true;
                        } else {
                            raws.add(new Raw(tick, track, channel, kind, d1, d2));
                        }
                    }
                    default -> { }
                }
            }
            track++;
        }
        if (track == 0) throw new MusicRoll.FormatException("MIDI 文件里没有轨道");

        // tick → 毫秒：速度表分段累加
        TempoMap map = new TempoMap(division, tempos);
        int bpmX100 = (int) Math.round(60_000_000.0 / tempos.getOrDefault(tempos.isEmpty() ? 0L : tempos.firstKey(), 500_000) * 100.0);
        // 中途的速度、拍号变化（指挥按它画拍子）：开头那一处已经是 bpmX100 和 timeSig
        List<MusicRoll.Tempo> marks = new ArrayList<>();
        {
            java.util.TreeSet<Long> at = new java.util.TreeSet<>();
            at.addAll(tempos.keySet());
            at.addAll(meters.keySet());
            long firstTempo = tempos.isEmpty() ? 0L : tempos.firstKey();
            long firstMeter = meters.isEmpty() ? 0L : meters.firstKey();
            int[] meter = timeSig.clone();
            int us = tempos.isEmpty() ? 500_000 : tempos.firstEntry().getValue();
            for (long tick : at) {
                if (tempos.containsKey(tick)) us = tempos.get(tick);
                if (meters.containsKey(tick)) meter = meters.get(tick);
                if (tick <= Math.max(firstTempo, firstMeter) && tick <= 0) continue;
                if (tick == firstTempo && tick == firstMeter) continue;
                int ms = map.ms(tick);
                if (ms <= 0) continue;
                marks.add(new MusicRoll.Tempo(ms, (int) Math.round(60_000_000.0 / us * 100.0), meter[0], meter[1]));
            }
        }

        // 第二遍：配对音符开始/结束，按通道分声部
        raws.sort((a, b) -> a.tick != b.tick ? Long.compare(a.tick, b.tick) : Integer.compare(a.order(), b.order()));
        Map<Integer, Integer> partOfChannel = new HashMap<>();
        List<MusicRoll.Part> parts = new ArrayList<>();
        int[] program = new int[16];
        @SuppressWarnings("unchecked")
        ArrayDeque<Raw>[][] open = new ArrayDeque[16][128];
        List<MusicRoll.Event> events = new ArrayList<>();
        boolean[] pedal = new boolean[16];
        int drums = 0;
        for (Raw r : raws) {
            if (r.kind == 0xC0) {
                program[r.channel] = r.d1;
                continue;
            }
            if (r.channel == DRUM_CHANNEL) {
                if (r.kind == 0x90 && r.d2 > 0) drums++;
                continue;
            }
            if (r.kind == 0xB0) {
                if (r.d1 != 64) continue;
                boolean down = r.d2 >= 64;
                if (down == pedal[r.channel]) continue;
                pedal[r.channel] = down;
                Integer part = partOfChannel.get(r.channel);
                if (part != null) events.add(MusicRoll.Event.pedal(map.ms(r.tick), part, down));
                continue;
            }
            boolean on = r.kind == 0x90 && r.d2 > 0;
            if (on) {
                // 第一次出现音符时给这个通道开一个声部（除去打击乐最多 15 个，不会超过 16）
                partOfChannel.computeIfAbsent(r.channel, ch -> {
                    String name = trackNames.getOrDefault(r.track, "");
                    parts.add(new MusicRoll.Part(name.isEmpty() ? "通道 " + (ch + 1) : name, program[ch]));
                    return parts.size() - 1;
                });
                if (open[r.channel][r.d1] == null) open[r.channel][r.d1] = new ArrayDeque<>();
                open[r.channel][r.d1].addLast(r);
            } else {
                ArrayDeque<Raw> q = open[r.channel][r.d1];
                Raw start = q == null ? null : q.pollFirst();
                if (start == null) continue;
                int part = partOfChannel.get(r.channel);
                int startMs = map.ms(start.tick);
                int dur = Math.max(1, Math.min(MusicRoll.MAX_NOTE_MS, map.ms(r.tick) - startMs));
                events.add(MusicRoll.Event.note(startMs, part, start.d1, start.d2, dur));
            }
        }
        // 没有结束的音：按 2 秒收尾
        for (int ch = 0; ch < 16; ch++) {
            for (int p = 0; p < 128; p++) {
                ArrayDeque<Raw> q = open[ch][p];
                if (q == null) continue;
                for (Raw start : q) {
                    int part = partOfChannel.get(ch);
                    events.add(MusicRoll.Event.note(map.ms(start.tick), part, start.d1, start.d2, 2000));
                }
            }
        }
        if (drums > 0) notes.add("打击乐（第 10 通道）" + drums + " 个音没有导入");

        // 截到上限：事件数、总时长
        events.sort((a, b) -> Integer.compare(a.timeMs(), b.timeMs()));
        List<MusicRoll.Event> kept = new ArrayList<>(Math.min(events.size(), MusicRoll.MAX_EVENTS));
        for (MusicRoll.Event e : events) {
            if (kept.size() >= MusicRoll.MAX_EVENTS || e.timeMs() > MusicRoll.MAX_DURATION_MS) {
                truncated = true;
                break;
            }
            kept.add(e);
        }
        if (truncated) notes.add("超过纸带上限（" + MusicRoll.MAX_EVENTS + " 个事件 / 20 分钟），后面的部分没有导入");
        if (kept.isEmpty()) throw new MusicRoll.FormatException("MIDI 文件里没有可以演奏的音符");
        if (parts.isEmpty()) parts.add(new MusicRoll.Part("", 0));
        MusicRoll roll = new MusicRoll(fallbackTitle, "", bpmX100, timeSig[0], timeSig[1], parts, kept, marks);
        // 编码后可能仍超 256 KiB（极密集的曲子）：再按大小截一次
        while (true) {
            try {
                roll.encode();
                break;
            } catch (MusicRoll.FormatException tooBig) {
                List<MusicRoll.Event> ev = roll.events();
                if (ev.size() < 2) throw tooBig;
                roll = new MusicRoll(roll.title(), "", bpmX100, timeSig[0], timeSig[1], parts, ev.subList(0, ev.size() * 9 / 10), marks);
                if (!truncated) {
                    truncated = true;
                    notes.add("内容超过 256 KiB，后面的部分没有导入");
                }
            }
        }
        return new Result(roll, List.copyOf(notes), truncated);
    }

    private record Raw(long tick, int track, int channel, int kind, int d1, int d2) {
        /** 同一时刻先结束旧音、再开始新音，踏板与音色在音符之前。 */
        int order() {
            boolean off = kind == 0x80 || kind == 0x90 && d2 == 0;
            return kind == 0xC0 ? 0 : kind == 0xB0 ? 1 : off ? 2 : 3;
        }
    }

    /** 速度表：tick → 毫秒。SMPTE 时间码按固定帧率换算。 */
    private static final class TempoMap {
        private final long[] ticks;
        private final double[] msAt;
        private final double[] msPerTick;
        private final double smpteMsPerTick;

        TempoMap(int division, TreeMap<Long, Integer> tempos) throws MusicRoll.FormatException {
            if ((division & 0x8000) != 0) {
                int fps = -(byte) (division >> 8);
                int perFrame = division & 0xFF;
                if (fps <= 0 || perFrame <= 0) throw new MusicRoll.FormatException("MIDI 时间码不对");
                smpteMsPerTick = 1000.0 / (fps * perFrame);
                ticks = new long[0];
                msAt = new double[0];
                msPerTick = new double[0];
                return;
            }
            if (division <= 0) throw new MusicRoll.FormatException("MIDI 每拍 tick 数不对");
            smpteMsPerTick = 0;
            TreeMap<Long, Integer> all = new TreeMap<>(tempos);
            all.putIfAbsent(0L, 500_000);
            ticks = new long[all.size()];
            msAt = new double[all.size()];
            msPerTick = new double[all.size()];
            int i = 0;
            double ms = 0;
            long lastTick = 0;
            double lastRate = 500.0 / division;
            for (var e : all.entrySet()) {
                ms += (e.getKey() - lastTick) * lastRate;
                ticks[i] = e.getKey();
                msAt[i] = ms;
                msPerTick[i] = e.getValue() / 1000.0 / division;
                lastTick = e.getKey();
                lastRate = msPerTick[i];
                i++;
            }
        }

        int ms(long tick) {
            double v;
            if (ticks.length == 0) {
                v = tick * smpteMsPerTick;
            } else {
                int lo = 0, hi = ticks.length - 1;
                while (lo < hi) {
                    int mid = (lo + hi + 1) >>> 1;
                    if (ticks[mid] <= tick) lo = mid; else hi = mid - 1;
                }
                v = msAt[lo] + (tick - ticks[lo]) * msPerTick[lo];
            }
            return (int) Math.min(Integer.MAX_VALUE / 2, Math.round(v));
        }
    }

    /** 有边界检查的游标；出错信息带轨道号与事件序号。 */
    private static final class Cursor {
        private final byte[] data;
        private final int track;
        private int pos;
        int event;

        Cursor(byte[] data, int track) {
            this.data = data;
            this.track = track;
        }

        int remaining() { return data.length - pos; }

        MusicRoll.FormatException error(String what) {
            return new MusicRoll.FormatException(track < 0 ? "MIDI 文件头：" + what
                : "第 " + (track + 1) + " 轨第 " + event + " 个事件：" + what);
        }

        int u8() throws MusicRoll.FormatException {
            if (pos >= data.length) throw error("数据提前结束");
            return data[pos++] & 0xFF;
        }

        int u16() throws MusicRoll.FormatException {
            return (u8() << 8) | u8();
        }

        int u32() throws MusicRoll.FormatException {
            long v = ((long) u8() << 24) | (u8() << 16) | (u8() << 8) | u8();
            return v > Integer.MAX_VALUE ? -1 : (int) v;
        }

        String tag() throws MusicRoll.FormatException {
            byte[] b = bytes(4);
            return new String(b, StandardCharsets.ISO_8859_1);
        }

        int varLen() throws MusicRoll.FormatException {
            int v = 0;
            for (int i = 0; i < 4; i++) {
                int b = u8();
                v = (v << 7) | (b & 0x7F);
                if ((b & 0x80) == 0) return v;
            }
            throw error("变长数值超过 4 字节");
        }

        byte[] bytes(int n) throws MusicRoll.FormatException {
            if (n < 0 || n > remaining()) throw error("长度超出数据范围");
            byte[] b = new byte[n];
            System.arraycopy(data, pos, b, 0, n);
            pos += n;
            return b;
        }

        byte[] slice(int n) throws MusicRoll.FormatException {
            return bytes(n);
        }

        void skip(int n) throws MusicRoll.FormatException {
            if (n < 0 || n > remaining()) throw error("长度超出数据范围");
            pos += n;
        }
    }
}
