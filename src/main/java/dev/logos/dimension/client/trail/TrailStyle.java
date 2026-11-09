package dev.logos.dimension.client.trail;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.logos.dimension.LogosDimension;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * 一种刀光的外观（{@code assets/<ns>/combat/trail_styles/<名字>.json}，见 docs/WEAPON_TRAILS.md）：
 * 几层带子叠在一起（照 StylizedSlashesV2 的刀光材质：形状遮罩、流动的颜色噪声、往尾部吃的侵蚀、越老越窄），
 * 外加刀尖甩出的粒子与一层柔和泛光。
 *
 * @param life   刀光上一点从扫过到散尽的秒数
 * @param expand 刀光在散尽前顺着刀身往外飘出多少格（UE 刀光网格随年龄放大的那一下）
 * @param layers 从下往上画的各层
 * @param sparks 刀尖甩出的粒子；没有时为 null
 * @param glow   沿刀尖的泛光：不透明度（0 不画）
 * @param glowColor 泛光颜色（RGB）
 * @param glowWidth 泛光带子的半宽（格）
 */
public record TrailStyle(Identifier id, float life, float expand, List<Layer> layers, @Nullable Sparks sparks,
                         float glow, int glowColor, float glowWidth) {

    /** 默认的侵蚀与扭曲噪声：沿刀光拉长的条纹（本附属生成）；默认的颜色噪声：Logos 的云雾噪声。 */
    static final Identifier ANISO = LogosDimension.id("textures/vfx/trail/noise_aniso.png");
    static final Identifier CLOUDY = Identifier.fromNamespaceAndPath("logos", "textures/vfx/charge_aura/noise_cloudy.png");

    /**
     * 一层带子。几何上要用的：{@code inner}/{@code outer} 是横跨刀身的位置（0 刀身起点，1 刀尖；大于 1 伸出刀尖之外），
     * {@code narrow}/{@code narrowPow} 是越老越从柄那一侧收掉多少（年龄 a 处收掉 narrow × a^narrowPow），
     * {@code facing} 是沿刀尖路径朝镜头的带子半宽（0 不画，侧着看时补一道看得见的刀光）。
     * 其余参数都写进着色器的 #define（{@code defines}，名字见 weapon_trail.fsh），由 JSON 的同名字段给出。
     */
    public record Layer(Identifier mask, Identifier erosionNoise, Identifier detailNoise, Identifier distortNoise,
                        float inner, float outer, float narrow, float narrowPow, float facing, Map<String, Float> defines) {
        public float define(String name) { return defines.getOrDefault(name, 0f); }
    }

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
        return new TrailStyle(id, f(json, "life", .3f), f(json, "expand", 0), List.copyOf(layers), sparks,
                f(glow, "alpha", 0), color(glow, "color", 0xFFFFFF), f(glow, "width", .25f));
    }

    private static Layer layer(JsonObject j) {
        float[] span = j.has("span") ? floats(j.getAsJsonArray("span")) : new float[]{0, 1};
        Map<String, Float> d = new TreeMap<>();
        // 颜色：两色按颜色噪声插值，第三色是形状最浓处的白热芯
        JsonArray colors = j.has("colors") ? j.getAsJsonArray("colors") : null;
        int c1 = colors == null ? 0xFFFFFF : parseColor(colors.get(0).getAsString());
        int c2 = colors == null || colors.size() < 2 ? c1 : parseColor(colors.get(1).getAsString());
        int hot = colors == null || colors.size() < 3 ? 0xFFFFFF : parseColor(colors.get(2).getAsString());
        rgb(d, "C1", c1);
        rgb(d, "C2", c2);
        rgb(d, "C3", hot);
        d.put("COLOR_STRENGTH", f(j, "color_strength", 1.5f));
        d.put("COLOR_CONTRAST", f(j, "color_contrast", 1));
        float[] ct = pair(j, "color_tile", .5f, 1);
        d.put("COLOR_TILE_U", ct[0]);
        d.put("COLOR_TILE_V", ct[1]);
        d.put("COLOR_PAN", f(j, "color_pan", .5f));
        d.put("HOT_FROM", f(j, "hot_from", .85f));
        d.put("HOT_GAIN", f(j, "hot_gain", 2));
        d.put("EMISSIVE_MASK", f(j, "emissive_mask", .5f));
        // 形状
        d.put("MAIN_CONTRAST", f(j, "main_contrast", 1.5f));
        d.put("MAIN_STRENGTH", f(j, "main_strength", 1));
        d.put("OPACITY", f(j, "opacity", 1));
        d.put("OCCLUDE", Math.clamp(f(j, "occlude", .5f), 0, 1));
        // 扭曲
        d.put("DISTORT", f(j, "distort", .05f));
        float[] dt = pair(j, "distort_tile", .4f, 1);
        d.put("DIST_TILE_U", dt[0]);
        d.put("DIST_TILE_V", dt[1]);
        d.put("DIST_PAN", f(j, "distort_pan", .3f));
        // 不透明噪声
        JsonObject om = j.has("opacity_noise") ? j.getAsJsonObject("opacity_noise") : new JsonObject();
        d.put("OM_STRENGTH", f(om, "strength", 0));
        d.put("OM_CONTRAST", f(om, "contrast", 2));
        float[] ot = pair(om, "tile", .5f, 1);
        d.put("OM_TILE_U", ot[0]);
        d.put("OM_TILE_V", ot[1]);
        d.put("OM_PAN", f(om, "pan", .5f));
        // 侵蚀
        JsonObject er = j.has("erosion") ? j.getAsJsonObject("erosion") : new JsonObject();
        d.put("EROSION", f(er, "amount", 1));
        d.put("ER_START", Math.clamp(f(er, "start", .15f), 0, .95f));
        d.put("ER_POW", f(er, "pow", 1.4f));
        d.put("ER_FEATHER", Math.max(.01f, f(er, "feather", .3f)));
        d.put("ER_CONTRAST", f(er, "contrast", 2));
        d.put("ER_STRENGTH", f(er, "strength", 1));
        float[] et = pair(er, "tile", .35f, .12f);
        d.put("ER_TILE_U", et[0]);
        d.put("ER_TILE_V", et[1]);
        d.put("ER_PAN", f(er, "pan", 0));
        // 收窄与淡出
        float narrow = Math.clamp(f(j, "narrow", .7f), 0, .97f), narrowPow = Math.max(.1f, f(j, "narrow_pow", 1.3f));
        d.put("NARROW", narrow);
        d.put("NARROW_POW", narrowPow);
        d.put("NARROW_SOFT", Math.max(.01f, f(j, "narrow_soft", .15f)));
        JsonObject fade = j.has("fade") ? j.getAsJsonObject("fade") : new JsonObject();
        d.put("FADE_IN", f(fade, "in", .06f));
        d.put("FADE_OUT", f(fade, "out", 0));
        d.put("FADE_TAIL", f(fade, "tail", .2f));
        // 外缘刃线
        d.put("RIM", f(j, "rim", 0));
        d.put("RIM_WIDTH", Math.max(.5f, f(j, "rim_width", 14)));
        return new Layer(Identifier.parse(j.get("mask").getAsString()), texture(j, "erosion_noise", ANISO),
                texture(j, "detail_noise", CLOUDY), texture(j, "distort_noise", ANISO),
                span[0], span[1], narrow, narrowPow, f(j, "facing", 0), Map.copyOf(d));
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

    private static void rgb(Map<String, Float> d, String name, int rgb) {
        d.put(name + "_R", (rgb >> 16 & 255) / 255f);
        d.put(name + "_G", (rgb >> 8 & 255) / 255f);
        d.put(name + "_B", (rgb & 255) / 255f);
    }

    private static Identifier texture(JsonObject j, String key, Identifier fallback) {
        return j.has(key) ? Identifier.parse(j.get(key).getAsString()) : fallback;
    }

    private static float[] pair(JsonObject j, String key, float a, float b) {
        if (!j.has(key)) return new float[]{a, b};
        float[] v = floats(j.getAsJsonArray(key));
        return v.length >= 2 ? v : new float[]{v[0], v[0]};
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
