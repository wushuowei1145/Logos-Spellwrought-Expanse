package dev.logos.dimension.combat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.*;
import org.joml.Vector3f;

/**
 * 一段 Bedrock 动作（Blockbench 导出的 {@code .animation.json}，format_version 1.8.0）。
 *
 * <p>读入时换到 Blockbench 空间：旋转 X、Y 取反，位移 X 取反，与 Blockbench 的 Bedrock 导出互逆。
 * 支持 linear、catmullrom、step 三种插值和 pre/post 分段值；Molang 表达式不求值，按 0 处理并记入警告，
 * 动作文件出错时只影响这一段动作，不让游戏崩溃。
 */
public final class CombatClip {
    public enum Channel { ROTATION, POSITION, SCALE }
    public enum Loop { ONCE, LOOP, HOLD }
    private static final byte LINEAR = 0, CATMULLROM = 1, STEP = 2;
    private record Track(float[] times, float[][] pre, float[][] post, byte[] modes) {}
    private final float length;
    private final Loop loop;
    private final Map<String, EnumMap<Channel, Track>> bones;
    /** 动作标记（docs/COMBAT_SKELETON.md 第 6 节）：名字到升序时刻。 */
    private final Map<String, float[]> markers;

    private CombatClip(float length, Loop loop, Map<String, EnumMap<Channel, Track>> bones, Map<String, float[]> markers) {
        this.length = length; this.loop = loop; this.bones = bones; this.markers = markers;
    }
    /** 某个标记的各次时刻（升序，动作时间）；没有这个标记时为空数组。 */
    public float[] markers(String name) { return markers.getOrDefault(name, new float[0]).clone(); }
    public float length() { return length; }
    public Loop loop() { return loop; }
    public Set<String> bones() { return bones.keySet(); }
    public boolean has(String bone, Channel channel) { var b = bones.get(bone); return b != null && b.containsKey(channel); }

    /** 读取整个动作文件；无法解析的单段动作跳过，原因写进 warnings。 */
    public static Map<String, CombatClip> parseFile(JsonObject root, List<String> warnings) {
        Map<String, CombatClip> clips = new LinkedHashMap<>();
        var animations = root.getAsJsonObject("animations");
        if (animations == null) { warnings.add("缺少 animations"); return clips; }
        for (var entry : animations.entrySet()) {
            try { clips.put(entry.getKey(), parse(entry.getValue().getAsJsonObject(), entry.getKey(), warnings)); }
            catch (RuntimeException e) { warnings.add(entry.getKey() + "：" + e.getMessage()); }
        }
        return clips;
    }

    static CombatClip parse(JsonObject json, String name, List<String> warnings) {
        Map<String, EnumMap<Channel, Track>> bones = new HashMap<>();
        float last = 0;
        var boneJson = json.getAsJsonObject("bones");
        if (boneJson != null) for (var bone : boneJson.entrySet()) {
            EnumMap<Channel, Track> channels = new EnumMap<>(Channel.class);
            for (var channel : Channel.values()) {
                var value = bone.getValue().getAsJsonObject().get(channel.name().toLowerCase(Locale.ROOT));
                if (value == null) continue;
                var track = track(value, channel, name + "/" + bone.getKey(), warnings);
                channels.put(channel, track);
                last = Math.max(last, track.times[track.times.length - 1]);
            }
            if (!channels.isEmpty()) bones.put(bone.getKey(), channels);
        }
        float length = json.has("animation_length") ? json.get("animation_length").getAsFloat() : last;
        var loopJson = json.get("loop");
        Loop loop = loopJson == null ? Loop.ONCE : loopJson.getAsJsonPrimitive().isBoolean()
                ? (loopJson.getAsBoolean() ? Loop.LOOP : Loop.ONCE)
                : "hold_on_last_frame".equals(loopJson.getAsString()) ? Loop.HOLD : Loop.ONCE;
        return new CombatClip(length, loop, bones, markers(json.get("timeline")));
    }

    /**
     * 读 Bedrock 的 timeline：键是时刻，值是一行字符串或字符串数组。只认作标记名的内容（小写 snake_case 单词），
     * 真正的 Molang 指令之类原样忽略。
     */
    private static Map<String, float[]> markers(JsonElement timeline) {
        Map<String, List<Float>> found = new HashMap<>();
        if (timeline != null && timeline.isJsonObject()) for (var e : timeline.getAsJsonObject().entrySet()) {
            float t;
            try { t = Float.parseFloat(e.getKey()); } catch (NumberFormatException bad) { continue; }
            List<JsonElement> values = new ArrayList<>();
            if (e.getValue().isJsonArray()) e.getValue().getAsJsonArray().forEach(values::add); else values.add(e.getValue());
            for (var v : values) {
                if (!v.isJsonPrimitive()) continue;
                for (var line : v.getAsString().split("[;\n]")) {
                    var name = line.trim();
                    if (name.matches("[a-z][a-z0-9_]*")) found.computeIfAbsent(name, k -> new ArrayList<>()).add(t);
                }
            }
        }
        Map<String, float[]> out = new HashMap<>();
        found.forEach((name, times) -> {
            float[] a = new float[times.size()];
            for (int i = 0; i < a.length; i++) a[i] = times.get(i);
            Arrays.sort(a);
            out.put(name, a);
        });
        return out;
    }

    private static Track track(JsonElement value, Channel channel, String where, List<String> warnings) {
        // 整条轨道直接给一个值时视为常量。
        if (!value.isJsonObject() || isKeyframe(value.getAsJsonObject())) {
            var v = toSpace(vector(value, where, warnings), channel);
            return new Track(new float[]{0}, new float[][]{v}, new float[][]{v}, new byte[]{LINEAR});
        }
        var keys = new ArrayList<Map.Entry<Float, JsonElement>>();
        for (var e : value.getAsJsonObject().entrySet()) keys.add(Map.entry(Float.parseFloat(e.getKey()), e.getValue()));
        keys.sort(Map.Entry.comparingByKey());
        int n = keys.size();
        float[] times = new float[n]; float[][] pre = new float[n][], post = new float[n][]; byte[] modes = new byte[n];
        for (int i = 0; i < n; i++) {
            times[i] = keys.get(i).getKey();
            var key = keys.get(i).getValue();
            if (key.isJsonObject()) {
                var o = key.getAsJsonObject();
                var p = o.has("post") ? o.get("post") : o.get("pre");
                post[i] = toSpace(vector(p, where, warnings), channel);
                pre[i] = o.has("pre") ? toSpace(vector(o.get("pre"), where, warnings), channel) : post[i];
                var mode = o.has("lerp_mode") ? o.get("lerp_mode").getAsString() : "linear";
                modes[i] = switch (mode) { case "catmullrom" -> CATMULLROM; case "step" -> STEP; default -> LINEAR; };
            } else {
                pre[i] = post[i] = toSpace(vector(key, where, warnings), channel);
            }
        }
        return new Track(times, pre, post, modes);
    }
    private static boolean isKeyframe(JsonObject o) { return o.has("post") || o.has("pre") || o.has("vector"); }

    private static float[] vector(JsonElement value, String where, List<String> warnings) {
        if (value.isJsonObject() && value.getAsJsonObject().has("vector")) value = value.getAsJsonObject().get("vector");
        if (value.isJsonArray()) {
            JsonArray a = value.getAsJsonArray();
            return new float[]{number(a.get(0), where, warnings), number(a.get(1), where, warnings), number(a.get(2), where, warnings)};
        }
        float v = number(value, where, warnings);
        return new float[]{v, v, v};
    }
    private static float number(JsonElement e, String where, List<String> warnings) {
        var p = e.getAsJsonPrimitive();
        if (p.isNumber()) return p.getAsFloat();
        try { return Float.parseFloat(p.getAsString().trim()); }
        catch (NumberFormatException ex) { warnings.add(where + " 使用了未支持的 Molang「" + p.getAsString() + "」，按 0 处理"); return 0; }
    }
    /** Bedrock → Blockbench 空间。 */
    private static float[] toSpace(float[] v, Channel channel) {
        return switch (channel) {
            case ROTATION -> new float[]{-v[0] + 0f, -v[1] + 0f, v[2]};
            case POSITION -> new float[]{-v[0] + 0f, v[1], v[2]};
            case SCALE -> v;
        };
    }

    /** 采样某根骨骼的一条轨道，写入 dest；没有这条轨道时返回 null。时间超出关键帧范围时保持端点值。 */
    public Vector3f sample(String bone, Channel channel, float seconds, Vector3f dest) {
        var b = bones.get(bone);
        var track = b == null ? null : b.get(channel);
        if (track == null) return null;
        var t = track.times;
        if (seconds <= t[0]) return set(dest, track.pre[0]);
        int last = t.length - 1;
        if (seconds >= t[last]) return set(dest, track.post[last]);
        int lo = 0, hi = last;
        while (hi - lo > 1) { int mid = (lo + hi) >>> 1; if (t[mid] <= seconds) lo = mid; else hi = mid; }
        float f = (seconds - t[lo]) / Math.max(1e-6f, t[hi] - t[lo]);
        if (track.modes[lo] == STEP) return set(dest, track.post[lo]);
        float[] a = track.post[lo], b2 = track.pre[hi];
        if (track.modes[lo] == CATMULLROM || track.modes[hi] == CATMULLROM) {
            float[] p0 = lo > 0 ? track.post[lo - 1] : a, p3 = hi < last ? track.pre[hi + 1] : b2;
            return dest.set(catmull(p0[0], a[0], b2[0], p3[0], f), catmull(p0[1], a[1], b2[1], p3[1], f), catmull(p0[2], a[2], b2[2], p3[2], f));
        }
        return dest.set(a[0] + (b2[0] - a[0]) * f, a[1] + (b2[1] - a[1]) * f, a[2] + (b2[2] - a[2]) * f);
    }
    private static Vector3f set(Vector3f dest, float[] v) { return dest.set(v[0], v[1], v[2]); }
    private static float catmull(float p0, float p1, float p2, float p3, float t) {
        float t2 = t * t, t3 = t2 * t;
        return .5f * (2 * p1 + (p2 - p0) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (3 * p1 - p0 - 3 * p2 + p3) * t3);
    }
}
