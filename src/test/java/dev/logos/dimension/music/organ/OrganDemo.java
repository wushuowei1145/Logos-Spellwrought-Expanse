package dev.logos.dimension.music.organ;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管风琴新旧合成的离线试听：照游戏里的放法（每个游戏刻改一次音量、循环点、按目标音高变调、松键补尾音）
 * 把同一段谱子用第一版（{@link LegacyOrganSynth}）和现在的合成（{@link OrganSynth}）各渲染一遍，写成 wav。
 *
 * <pre>
 * java ... dev.logos.dimension.music.organ.OrganDemo &lt;输出目录&gt;
 * </pre>
 * 单元测试 {@code OrganDemoTest} 在设了系统属性 {@code logos_dimension.organ_demo} 时调用它。
 */
public final class OrganDemo {
    static final int RATE = OrganSynth.RATE;
    static final int TICK = RATE / 20;
    /** 混音总音量：几个音叠在一起也不削波。旧版和新版用同一个值，响度可以直接比。 */
    static final double MASTER = 0.28;
    /** 新版播放音量相对 loudness×0.9 的倍数（游戏里同样，封顶 1）。 */
    static double newBoost = 1.0;

    /** 一个音：第几刻按下、第几刻松开、音高。 */
    record Note(int on, int off, int pitch) {}

    /** 一段谱子。 */
    record Phrase(String name, int length, List<Note> notes) {}

    /** 一组音栓。 */
    record Registration(String file, String name, int mask, List<Phrase> phrases) {}

    private OrganDemo() {}

    static List<Phrase> manual() {
        List<Note> single = List.of(new Note(0, 50, 67));
        List<Note> scale = new ArrayList<>();
        int[] steps = {60, 62, 64, 65, 67, 69, 71, 72};
        for (int i = 0; i < steps.length; i++) scale.add(new Note(i * 6, i == steps.length - 1 ? i * 6 + 18 : i * 6 + 6, steps[i]));
        List<Note> chord = List.of(new Note(0, 60, 48), new Note(0, 60, 60), new Note(0, 60, 64), new Note(0, 60, 67));
        return List.of(new Phrase("single", 110, single), new Phrase("scale", 126, scale), new Phrase("chord", 140, chord));
    }

    static List<Phrase> pedal() {
        List<Note> notes = List.of(new Note(0, 50, 24), new Note(50, 90, 31),
            new Note(90, 150, 36), new Note(90, 150, 48), new Note(90, 150, 52), new Note(90, 150, 55));
        return List.of(new Phrase("pedal", 230, notes));
    }

    static List<Registration> registrations() {
        List<Registration> out = new ArrayList<>();
        out.add(new Registration("01_柔和", "柔和（长笛 8′）", OrganStop.Preset.SOFT.mask(), manual()));
        out.add(new Registration("02_晨祷", "晨祷（主音栓 8′ + 长笛 4′）", OrganStop.Preset.MATINS.mask(), manual()));
        out.add(new Registration("03_合唱", "合唱（主音栓 8′ + 八度 4′ + 十五度 2′）", OrganStop.Preset.CHORUS.mask(), manual()));
        out.add(new Registration("04_全奏", "全奏", OrganStop.Preset.PLENUM.mask(), manual()));
        out.add(new Registration("05_号角独奏", "号角独奏（号角 8′ + 长笛 8′）", OrganStop.Preset.TRUMPET.mask(), manual()));
        out.add(new Registration("06_天籁", "天籁（天籁 8′ + 长笛 8′）", OrganStop.Preset.CELESTE.mask(), manual()));
        out.add(new Registration("07_双簧管", "双簧管 8′ + 长笛 8′", OrganStop.OBOE_8.bit() | OrganStop.FLUTE_8.bit(), manual()));
        out.add(new Registration("08_低音踏板", "低音踏板（低音 16′ + 长笛 8′ + 主音栓 8′）",
            OrganStop.BOURDON_16.bit() | OrganStop.FLUTE_8.bit() | OrganStop.PRINCIPAL_8.bit(), pedal()));
        return out;
    }

    // —— 播放 ——

    /** 音量随时间变：每个游戏刻设一次，声音引擎（OpenAL Soft）在一次混音更新里（约 20 ms）过渡到新值。 */
    static final class Gain {
        final List<int[]> at = new ArrayList<>();
        final List<Double> value = new ArrayList<>();

        Gain set(int sample, double v) {
            at.add(new int[]{sample});
            value.add(v);
            return this;
        }

        double get(int n) {
            double prev = 0, cur = 0;
            int since = Integer.MAX_VALUE;
            for (int i = 0; i < at.size(); i++) {
                int t = at.get(i)[0];
                if (t > n) break;
                prev = i == 0 ? value.get(0) : value.get(i - 1);
                cur = value.get(i);
                since = n - t;
            }
            if (since == Integer.MAX_VALUE) return 0;
            int ramp = RATE / 50;
            return since >= ramp ? cur : prev + (cur - prev) * since / (double) ramp;
        }

        int end() {
            return at.isEmpty() ? 0 : at.get(at.size() - 1)[0];
        }
    }

    /** 把一个声音混进 out：从 start 开始，按 ratio 变调；loopEnd > 0 时在 [loopStart, loopEnd) 里循环，直到音量归零。 */
    static void play(float[] out, short[] pcm, int loopStart, int loopEnd, double ratio, int start, Gain gain, int stop) {
        double pos = 0;
        for (int n = start; n < Math.min(out.length, stop); n++) {
            if (loopEnd > 0) {
                while (pos >= loopEnd) pos -= loopEnd - loopStart;
            } else if (pos >= pcm.length - 1) {
                break;
            }
            int i = (int) pos;
            double f = pos - i;
            double y0 = at(pcm, i - 1, loopStart, loopEnd), y1 = at(pcm, i, loopStart, loopEnd);
            double y2 = at(pcm, i + 1, loopStart, loopEnd), y3 = at(pcm, i + 2, loopStart, loopEnd);
            // 三次 Hermite 插值
            double c1 = 0.5 * (y2 - y0), c2 = y0 - 2.5 * y1 + 2 * y2 - 0.5 * y3, c3 = 0.5 * (y3 - y0) + 1.5 * (y1 - y2);
            double y = ((c3 * f + c2) * f + c1) * f + y1;
            out[n] += (float) (y / 32768.0 * gain.get(n));
            pos += ratio;
        }
    }

    private static double at(short[] pcm, int i, int loopStart, int loopEnd) {
        if (loopEnd > 0 && i >= loopEnd) i -= loopEnd - loopStart;
        if (i < 0 || i >= pcm.length) return 0;
        return pcm[i];
    }

    // —— 第一版 ——

    static float[] renderOld(Registration reg, RoomAcoustics.Reverb room) {
        LegacyOrganSynth.Sample[] samples = LegacyOrganSynth.render(reg.mask());
        float base = (float) (OrganStop.loudness(reg.mask()) * 0.9);
        float[] out = new float[length(reg) * TICK];
        int offset = 0;
        Map<String, short[]> tails = new HashMap<>();
        for (Phrase ph : reg.phrases()) {
            for (Note note : ph.notes()) {
                LegacyOrganSynth.Sample s = samples[LegacyOrganSynth.index(note.pitch())];
                double ratio = LegacyOrganSynth.ratio(s, note.pitch());
                int on = (offset + note.on()) * TICK, off = (offset + note.off()) * TICK;
                // 起音 3 刻：1/4 → 2/4 → 3/4 → 1；松键后每刻 ×0.45，低于 0.01 停
                Gain g = new Gain().set(on, base * 0.9 / 4);
                for (int t = 1; t <= 3 && on + t * TICK < off; t++) g.set(on + t * TICK, base * 0.9 * (t + 1) / 4);
                double v = base * 0.9;
                if (note.off() - note.on() < 3) v = base * 0.9 * (note.off() - note.on() + 1) / 4;
                int t = off;
                while (v >= 0.01) {
                    t += TICK;
                    v *= 0.45;
                    g.set(t, v < 0.01 ? 0 : v);
                }
                play(out, s.pcm(), 0, s.pcm().length, ratio, on, g, t + TICK);
                if (room.on()) {
                    RoomAcoustics.Reverb r = RoomAcoustics.fromBucket(RoomAcoustics.bucket(room));
                    short[] tail = tails.computeIfAbsent(LegacyOrganSynth.index(note.pitch()) + "",
                        k -> LegacyOrganSynth.tail(s.pcm(), Math.max(0.2, r.seconds()), r.brightness()));
                    float wet = (float) (0.3 + 0.15 * Math.min(1, room.seconds() / 3));
                    play(out, tail, 0, 0, ratio, off, new Gain().set(off, base * wet), out.length);
                }
            }
            offset += ph.length();
        }
        return out;
    }

    // —— 现在的合成 ——

    /** 新版在游戏里的放法：起音已经合成在采样里，直接全音量；松键后每刻 ×0.35，同时尾音用 1 刻推上来，音量按混响已长到的程度。 */
    static float[] renderNew(Registration reg, RoomAcoustics.Reverb room) {
        RoomAcoustics.Reverb r = room.on() ? RoomAcoustics.fromBucket(RoomAcoustics.bucket(room)) : RoomAcoustics.Reverb.NONE;
        OrganSynth.Raw[] raws = java.util.stream.IntStream.range(0, OrganSynth.COUNT).parallel()
            .mapToObj(i -> OrganSynth.raw(OrganSynth.note(i), reg.mask(), r)).toArray(OrganSynth.Raw[]::new);
        OrganSynth.Sample[] samples = OrganSynth.finish(raws);
        float base = (float) Math.min(1, OrganStop.loudness(reg.mask()) * 0.9 * newBoost);
        float[] out = new float[length(reg) * TICK];
        int offset = 0;
        for (Phrase ph : reg.phrases()) {
            for (Note note : ph.notes()) {
                int index = OrganSynth.index(note.pitch());
                OrganSynth.Sample s = samples[index];
                double ratio = OrganSynth.ratio(s, note.pitch());
                int on = (offset + note.on()) * TICK, off = (offset + note.off()) * TICK;
                Gain g = new Gain().set(on, base);
                double v = base;
                int t = off;
                while (v >= 0.01) {
                    t += TICK;
                    v *= 0.35;
                    g.set(t, v < 0.01 ? 0 : v);
                }
                play(out, s.pcm(), s.loopStart(), s.loopEnd(), ratio, on, g, t + TICK);
                if (s.hasTail()) {
                    float tg = base * s.bloomAt(note.off() - note.on());
                    play(out, s.tail(), 0, 0, ratio, off, new Gain().set(off, tg / 2).set(off + TICK, tg), out.length);
                }
            }
            offset += ph.length();
        }
        return out;
    }

    static int length(Registration reg) {
        int n = 0;
        for (Phrase p : reg.phrases()) n += p.length();
        return n;
    }

    // —— 输出 ——

    static void writeWav(Path file, float[] mono) throws IOException {
        ByteBuffer data = ByteBuffer.allocate(mono.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : mono) data.putShort((short) Math.round(Math.max(-1, Math.min(1, v * MASTER)) * 32767));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()).putInt(36 + data.capacity()).put("WAVE".getBytes());
        h.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1).putInt(RATE).putInt(RATE * 2)
            .putShort((short) 2).putShort((short) 16);
        h.put("data".getBytes()).putInt(data.capacity());
        bytes.write(h.array());
        bytes.write(data.array());
        Files.createDirectories(file.getParent());
        Files.write(file, bytes.toByteArray());
    }

    static float[] concat(List<float[]> parts, double gapSeconds) {
        int gap = (int) (gapSeconds * RATE), n = 0;
        for (float[] p : parts) n += p.length + gap;
        float[] out = new float[n];
        int at = 0;
        for (float[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length + gap;
        }
        return out;
    }

    static double peak(float[] a) {
        double m = 0;
        for (float v : a) m = Math.max(m, Math.abs(v * MASTER));
        return m;
    }

    static double rmsDb(float[] a) {
        double s = 0;
        for (float v : a) s += (double) v * v * MASTER * MASTER;
        return 10 * Math.log10(s / a.length + 1e-20);
    }

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "organ-demo");
        if (args.length > 1) newBoost = Double.parseDouble(args[1]);
        RoomAcoustics.Reverb cathedral = RoomAcoustics.choose(RoomAcoustics.CATHEDRAL, RoomAcoustics.Reverb.NONE, false);
        Object[][] rooms = {{"教堂档", cathedral}, {"无混响", RoomAcoustics.Reverb.NONE}};
        for (Object[] roomEntry : rooms) {
            String roomName = (String) roomEntry[0];
            RoomAcoustics.Reverb room = (RoomAcoustics.Reverb) roomEntry[1];
            List<float[]> ab = new ArrayList<>();
            for (Registration reg : registrations()) {
                long t0 = System.nanoTime();
                float[] old = renderOld(reg, room);
                long t1 = System.nanoTime();
                float[] neu = renderNew(reg, room);
                long t2 = System.nanoTime();
                writeWav(dir.resolve(roomName).resolve(reg.file() + "_旧.wav"), old);
                writeWav(dir.resolve(roomName).resolve(reg.file() + "_新.wav"), neu);
                ab.add(old);
                ab.add(new float[RATE]);
                ab.add(neu);
                ab.add(new float[RATE * 2]);
                System.out.printf("%s %-12s old peak %.2f rms %.1f dB (%d ms) | new peak %.2f rms %.1f dB (%d ms)%n", roomName, reg.file(),
                    peak(old), rmsDb(old), (t1 - t0) / 1_000_000, peak(neu), rmsDb(neu), (t2 - t1) / 1_000_000);
            }
            writeWav(dir.resolve(roomName).resolve("00_新旧对比_全部.wav"), concat(ab, 0));
        }
    }
}
