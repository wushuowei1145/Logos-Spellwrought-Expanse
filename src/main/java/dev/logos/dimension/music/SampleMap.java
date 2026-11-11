package dev.logos.dimension.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 一件乐器的采样表：assets/logos_dimension/instruments/&lt;乐器&gt;.json。模组本体里是合成音源
 * （tools/instrument_samples.py），「音乐厅音源」资源包里同名的表换成录音（tools/instrument_sources.py）。
 * 每个区是一个采样文件，管一段音高、一段力度，记着它的实际音高（含音分）、增益、力度曲线；
 * 持续音（弓弦、管乐）另有循环点，大键琴另有松键声。来源与许可见 docs/INSTRUMENT_SAMPLES.md。
 *
 * @param format 表的格式版本；比 {@link #FORMAT} 新的表读不懂，跳过它用下一层（通常是模组本体的合成音源）
 * @param quality recorded（资源包的录音）或 synth（本体合成）
 * @param range 方块乐器的可闻距离（格）；手持乐器另按广播距离算
 */
public record SampleMap(int format, String quality, String source, int range, List<Zone> zones) {
    /** 这个版本的模组读得懂的采样表格式。 */
    public static final int FORMAT = 1;

    /** 是不是资源包里的录音音源。 */
    public boolean recorded() {
        return "recorded".equals(quality);
    }

    /**
     * @param file 声音文件（sounds/ 下、不带 .ogg，例如 instrument/grand_piano/60_2）
     * @param key 采样的实际音高（MIDI，带小数的音分）
     * @param gain 力度等于 vhi 时的音量
     * @param curve 力度曲线：音量 = gain × (力度 / vhi) ^ curve
     * @param frames 采样点数（44.1 kHz）
     * @param release 松键后淡出的秒数（会止音的乐器才用）
     */
    public record Zone(String file, float key, int lo, int hi, int vlo, int vhi, float gain, float curve, int frames,
                       int loopStart, int loopEnd, float release, @Nullable String releaseSound, float releaseGain) {
        /** 有循环点：按住多久响多久。 */
        public boolean loops() {
            return loopEnd > loopStart && loopStart >= 0;
        }

        /** 变调到这个音高的倍率。 */
        public float ratio(int pitch) {
            return (float) Math.pow(2.0, (pitch - key) / 12.0);
        }

        /** 这个力度下的音量（0–1）。 */
        public float volume(int velocity) {
            double v = Math.max(1, Math.min(vhi, velocity));
            return (float) Math.min(1.0, gain * Math.pow(v / vhi, curve));
        }

        boolean covers(int pitch, int velocity) {
            return pitch >= lo && pitch <= hi && velocity >= vlo && velocity <= vhi;
        }
    }

    /** 这个音、这个力度用哪个区：正好覆盖的优先；没有就取力度层最近、再音高最近的（表不全时也能出声）。 */
    public @Nullable Zone pick(int pitch, int velocity) {
        Zone best = null;
        int bestCost = Integer.MAX_VALUE;
        for (Zone z : zones) {
            if (z.covers(pitch, velocity)) return z;
            int dv = velocity < z.vlo ? z.vlo - velocity : Math.max(0, velocity - z.vhi);
            int dp = pitch < z.lo ? z.lo - pitch : Math.max(0, pitch - z.hi);
            int cost = dv * 1000 + dp;
            if (cost < bestCost) {
                bestCost = cost;
                best = z;
            }
        }
        return best;
    }

    public static SampleMap parse(JsonObject json) {
        List<Zone> zones = new ArrayList<>();
        JsonArray array = json.getAsJsonArray("zones");
        for (JsonElement e : array) {
            JsonObject z = e.getAsJsonObject();
            int loopStart = -1, loopEnd = -1;
            if (z.has("loop")) {
                JsonArray loop = z.getAsJsonArray("loop");
                loopStart = loop.get(0).getAsInt();
                loopEnd = loop.get(1).getAsInt();
            }
            zones.add(new Zone(z.get("file").getAsString(), z.get("key").getAsFloat(), z.get("lo").getAsInt(), z.get("hi").getAsInt(),
                z.get("vlo").getAsInt(), z.get("vhi").getAsInt(), z.get("gain").getAsFloat(), z.get("curve").getAsFloat(),
                z.get("frames").getAsInt(), loopStart, loopEnd, z.has("release") ? z.get("release").getAsFloat() : 0f,
                z.has("releaseSound") ? z.get("releaseSound").getAsString() : null,
                z.has("releaseGain") ? z.get("releaseGain").getAsFloat() : 0f));
        }
        return new SampleMap(json.has("format") ? json.get("format").getAsInt() : 1,
            json.has("quality") ? json.get("quality").getAsString() : "synth",
            json.has("source") ? json.get("source").getAsString() : "",
            json.has("range") ? json.get("range").getAsInt() : 32, List.copyOf(zones));
    }
}
