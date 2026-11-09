package dev.logos.dimension.client.trail;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * 一种刀光的外观（{@code assets/<ns>/combat/trail_styles/<名字>.json}，见 docs/WEAPON_TRAILS.md）：
 * 几层带子叠在一起（形状遮罩 × 流动噪声，各自的颜色、宽度、侵蚀），外加刀尖甩出的火星与一层柔和泛光。
 *
 * @param life   刀光上一点从扫过到散尽的秒数
 * @param layers 从下往上画的各层
 * @param sparks 刀尖甩出的粒子；没有时为 null
 * @param glow   沿刀尖的泛光：不透明度（0 不画）
 * @param glowColor 泛光颜色（RGB）
 * @param glowWidth 泛光带子的半宽（格）
 */
public record TrailStyle(Identifier id, float life, List<Layer> layers, @Nullable Sparks sparks,
                         float glow, int glowColor, float glowWidth) {

    /**
     * 一层带子。{@code inner}/{@code outer} 是横跨刀身的位置（0 刀身起点，1 刀尖；大于 1 伸出刀尖之外）。
     * 颜色按热度在 {@code core}（最亮）、{@code mid}、{@code edge}（将散）之间取。{@code occlude} 是这层压暗背景的程度：
     * 0 纯发光，1 照常盖上去（墨色、虚空这类不发光的底）；白天的亮天空会把纯发光的颜色洗成白，带颜色的身子要取中间值。
     * 其余参数见 weapon_trail.fsh。
     */
    public record Layer(Identifier mask, Identifier noise, float occlude, float inner, float outer,
                        int core, int mid, int edge, float intensity, float opacity,
                        float noiseScale, float noiseStretch, float scroll, float distort, float detail,
                        float erosion, float soft, float heat, float rim, float rimWidth) {}

    /**
     * 刀尖甩出的粒子。{@code perBlock}：刀尖每走一格甩出几颗；{@code frames}：贴图里横排几帧（随机取一帧）；
     * {@code colors}：随年龄从第一个渐变到最后一个；{@code spread}：随机初速（格/秒）；{@code inherit}：带上刀尖速度的比例。
     */
    public record Sparks(Identifier texture, int frames, float perBlock, float life, float size, int[] colors,
                         float gravity, float drag, float spread, float inherit, float spin, boolean additive) {}

    public static TrailStyle parse(Identifier id, JsonObject json) {
        List<Layer> layers = new ArrayList<>();
        for (JsonElement e : json.getAsJsonArray("layers")) layers.add(layer(e.getAsJsonObject()));
        Sparks sparks = json.has("sparks") ? sparks(json.getAsJsonObject("sparks")) : null;
        JsonObject glow = json.has("glow") ? json.getAsJsonObject("glow") : new JsonObject();
        return new TrailStyle(id, f(json, "life", .22f), List.copyOf(layers), sparks,
                f(glow, "alpha", 0), color(glow, "color", 0xFFFFFF), f(glow, "width", .25f));
    }

    private static Layer layer(JsonObject j) {
        float[] span = j.has("span") ? floats(j.getAsJsonArray("span")) : new float[]{0, 1};
        JsonArray colors = j.has("colors") ? j.getAsJsonArray("colors") : null;
        int core = colors == null ? 0xFFFFFF : parseColor(colors.get(0).getAsString());
        int mid = colors == null || colors.size() < 2 ? core : parseColor(colors.get(1).getAsString());
        int edge = colors == null || colors.size() < 3 ? mid : parseColor(colors.get(2).getAsString());
        return new Layer(Identifier.parse(j.get("mask").getAsString()), Identifier.parse(j.get("noise").getAsString()),
                j.has("occlude") ? f(j, "occlude", 0) : "blend".equals(s(j, "blend", "add")) ? 1 : 0, span[0], span[1], core, mid, edge,
                f(j, "intensity", 1), f(j, "opacity", 1),
                f(j, "noise_scale", .6f), f(j, "noise_stretch", 1), f(j, "scroll", .8f), f(j, "distort", .08f),
                f(j, "detail", .5f), f(j, "erosion", 1.1f), f(j, "soft", .25f), f(j, "heat", 1.3f),
                f(j, "rim", 0), f(j, "rim_width", 14));
    }

    private static Sparks sparks(JsonObject j) {
        JsonArray c = j.getAsJsonArray("colors");
        int[] colors = new int[c == null ? 1 : c.size()];
        if (c == null) colors[0] = 0xFFFFFF;
        else for (int i = 0; i < colors.length; i++) colors[i] = parseColor(c.get(i).getAsString());
        return new Sparks(Identifier.parse(j.get("texture").getAsString()), j.has("frames") ? j.get("frames").getAsInt() : 1,
                f(j, "per_block", 6), f(j, "life", .45f), f(j, "size", .06f), colors,
                f(j, "gravity", 0), f(j, "drag", 2), f(j, "spread", 1.2f), f(j, "inherit", .2f), f(j, "spin", 3),
                !"blend".equals(s(j, "blend", "add")));
    }

    private static float f(JsonObject j, String key, float fallback) { return j.has(key) ? j.get(key).getAsFloat() : fallback; }
    private static String s(JsonObject j, String key, String fallback) { return j.has(key) ? j.get(key).getAsString() : fallback; }
    private static int color(JsonObject j, String key, int fallback) { return j.has(key) ? parseColor(j.get(key).getAsString()) : fallback; }
    private static float[] floats(JsonArray a) {
        float[] out = new float[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsFloat();
        return out;
    }

    /** {@code "#RRGGBB"} 或 {@code "RRGGBB"}。 */
    public static int parseColor(String s) {
        String hex = s.startsWith("#") ? s.substring(1) : s;
        if (hex.length() != 6) throw new IllegalArgumentException("颜色要写成 #RRGGBB：" + s);
        return Integer.parseInt(hex, 16);
    }
}
