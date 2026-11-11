package dev.logos.dimension.music.orchestra;

import dev.logos.dimension.music.MusicRoll;
import java.util.ArrayList;
import java.util.List;

/**
 * 纸带的拍子（docs/ORCHESTRA.md 第 7.1 节）：任意时刻在第几小节、第几拍、拍内相位。按纸带开头的速度、拍号和
 * {@link MusicRoll#tempos()} 里的变化分段：只改速度时小节、拍接着数；改拍号时从那里起是新的一小节。
 * 第 0 小节从 0 毫秒开始；起奏前（负的时刻）按第一段往前数，是第 -1 小节（指挥的预备拍）。
 */
public final class BeatMap {
    /** 一段速度、拍号不变的时间：从 startMs 起，那一刻是第 startBar 小节第 startBeat 拍（可以带小数）。 */
    private record Segment(double startMs, int startBar, double startBeat, double beatMs, int beatsPerBar, int beatUnit) {}

    /**
     * 某一时刻的拍子。
     * @param bar 第几小节（从 0 起，起奏前为负）
     * @param beat 小节里第几拍（从 0 起）
     * @param phase 拍内 0–1
     * @param beatMs 这一拍有多长（按拍号的拍：x/4 是四分音符，x/8 是八分音符）
     */
    public record Beat(int bar, int beat, double phase, int beatsPerBar, int beatUnit, double beatMs) {}

    private final List<Segment> segments;

    private BeatMap(List<Segment> segments) {
        this.segments = segments;
    }

    public static BeatMap of(MusicRoll roll) {
        List<Segment> segs = new ArrayList<>();
        segs.add(segment(0, 0, 0, roll.bpmX100(), roll.beatsPerBar(), roll.beatUnit()));
        for (MusicRoll.Tempo t : roll.tempos()) {
            Segment prev = segs.get(segs.size() - 1);
            double beats = prev.startBeat() + (t.timeMs() - prev.startMs()) / prev.beatMs();
            int whole = (int) Math.floor(beats / prev.beatsPerBar());
            int bar = prev.startBar() + whole;
            double beat = beats - whole * (double) prev.beatsPerBar();
            if (t.beatsPerBar() != prev.beatsPerBar() || t.beatUnit() != prev.beatUnit()) {
                // 拍号改在小节线上；差一点点（导入时毫秒取整的误差）也当成小节线
                if (beat > 0.05 && beat < prev.beatsPerBar() - 0.05) bar++;
                else if (beat >= prev.beatsPerBar() - 0.05) bar++;
                beat = 0;
            }
            segs.add(segment(t.timeMs(), bar, beat, t.bpmX100(), t.beatsPerBar(), t.beatUnit()));
        }
        return new BeatMap(segs);
    }

    private static Segment segment(double startMs, int bar, double beat, int bpmX100, int beatsPerBar, int beatUnit) {
        double quarterMs = 60000.0 * 100 / Math.max(100, bpmX100);
        return new Segment(startMs, bar, beat, quarterMs * 4.0 / beatUnit, beatsPerBar, beatUnit);
    }

    private Segment segmentAt(double ms) {
        Segment s = segments.get(0);
        for (Segment x : segments) {
            if (x.startMs() <= ms) s = x;
            else break;
        }
        return s;
    }

    public Beat at(double ms) {
        Segment s = segmentAt(ms);
        double beats = s.startBeat() + (ms - s.startMs()) / s.beatMs();
        int whole = (int) Math.floor(beats / s.beatsPerBar());
        double inBar = beats - whole * (double) s.beatsPerBar();
        int beat = Math.min(s.beatsPerBar() - 1, (int) Math.floor(inBar));
        return new Beat(s.startBar() + whole, beat, Math.max(0, Math.min(1, inBar - beat)), s.beatsPerBar(), s.beatUnit(), s.beatMs());
    }

    /** 第 bar 小节第一拍的时刻（从第几小节起奏用）。 */
    public double barStartMs(int bar) {
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            double t = s.startMs() + ((bar - s.startBar()) * (double) s.beatsPerBar() - s.startBeat()) * s.beatMs();
            boolean last = i == segments.size() - 1;
            if (t >= s.startMs() - 1 && (last || t < segments.get(i + 1).startMs())) return Math.max(0, t);
        }
        Segment s = segments.get(0);
        return Math.max(0, s.startMs() + (bar * (double) s.beatsPerBar()) * s.beatMs());
    }

    /** 一共几段（测试用）。 */
    int segmentCount() {
        return segments.size();
    }
}
