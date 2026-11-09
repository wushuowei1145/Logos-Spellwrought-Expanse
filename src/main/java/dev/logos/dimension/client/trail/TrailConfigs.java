package dev.logos.dimension.client.trail;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.logos.dimension.LogosDimension;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * 哪把武器、哪一招出刀光（docs/WEAPON_TRAILS.md）：每个动作组一个 {@code combat/trails/<动作组>.json}，没有这个文件的动作组
 * （弓）不出刀光。里面写刀身在武器网格里沿 +Y 的范围（{@code "blade": {"from": y0, "to": y1}}，网格单位，原点在握柄；
 * 共用动作组的不同网格可在 {@code "meshes"} 里各写一份，也可各自指定样式）、默认样式、自动出光的刀尖速度、各招的出光时段。
 * 刀身不写在武器网格文件里，因为那些文件由导入工具整份重写。
 */
public final class TrailConfigs {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, Optional<Set>> SETS = new HashMap<>();

    private TrailConfigs() {}

    /** 刀身在武器网格里的范围（沿 +Y），以及武器自己指定的样式（没有时为 null）。 */
    public record Blade(float from, float to, @Nullable Identifier style) {}

    /**
     * 一招的刀光：{@code windows} 是出光的时段（招式里的秒，[开始, 结束]），为空时按刀尖速度自动出光；
     * {@code none} 为真时这一招不出光；{@code style} 覆盖样式（没有时为 null）。
     */
    public record Clip(List<float[]> windows, boolean none, @Nullable Identifier style) {
        static final Clip AUTO = new Clip(List.of(), false, null);
    }

    /**
     * 动作组的刀光。{@code style} 默认样式；{@code fixed} 为真时魔力缠绕不改样式（武器自带属性，比如流火的狐火）；
     * {@code speed} 自动出光的刀尖速度（格/秒，第三人称骨架里量）；{@code auto} 为假时只有列出的招式出光。
     */
    public record Set(@Nullable Blade blade, Map<String, Blade> meshes, Identifier style, boolean fixed, float speed, boolean auto,
                      Map<String, Clip> clips) {
        public Clip clip(String name) {
            Clip c = clips.get(name);
            return c != null ? c : auto ? Clip.AUTO : new Clip(List.of(), true, null);
        }
        /** 这个网格的刀身：{@code meshes} 里有就用它，否则用动作组的；都没有时为 null（不出刀光）。 */
        public @Nullable Blade blade(String mesh) {
            Blade b = meshes.get(mesh);
            return b != null ? b : blade;
        }
    }

    public static @Nullable Set set(String animations) {
        return SETS.computeIfAbsent(animations, key -> read("combat/trails/" + key + ".json").map(TrailConfigs::parse)).orElse(null);
    }

    static Set parse(JsonObject json) {
        Map<String, Clip> clips = new HashMap<>();
        if (json.has("clips")) for (var entry : json.getAsJsonObject("clips").entrySet()) clips.put(entry.getKey(), clip(entry.getValue()));
        Map<String, Blade> meshes = new HashMap<>();
        if (json.has("meshes")) for (var entry : json.getAsJsonObject("meshes").entrySet()) meshes.put(entry.getKey(), blade(entry.getValue().getAsJsonObject()));
        return new Set(json.has("blade") ? blade(json.getAsJsonObject("blade")) : null, Map.copyOf(meshes),
                json.has("style") ? Identifier.parse(json.get("style").getAsString()) : TrailStyles.PHYSICAL,
                json.has("fixed") && json.get("fixed").getAsBoolean(),
                json.has("speed") ? json.get("speed").getAsFloat() : 7,
                !json.has("auto") || json.get("auto").getAsBoolean(), Map.copyOf(clips));
    }

    private static Blade blade(JsonObject t) {
        return new Blade(t.get("from").getAsFloat(), t.get("to").getAsFloat(),
                t.has("style") ? Identifier.parse(t.get("style").getAsString()) : null);
    }

    private static Clip clip(JsonElement e) {
        if (e.isJsonPrimitive() && "none".equals(e.getAsString())) return new Clip(List.of(), true, null);
        JsonObject j = e.getAsJsonObject();
        List<float[]> windows = new ArrayList<>();
        if (j.has("windows")) for (JsonElement w : j.getAsJsonArray("windows")) {
            var a = w.getAsJsonArray();
            windows.add(new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat()});
        }
        return new Clip(List.copyOf(windows), false, j.has("style") ? Identifier.parse(j.get("style").getAsString()) : null);
    }

    private static Optional<JsonObject> read(String path) {
        var resource = Minecraft.getInstance().getResourceManager().getResource(LogosDimension.id(path));
        if (resource.isEmpty()) return Optional.empty();
        try (var reader = resource.get().openAsReader()) {
            return Optional.of(JsonParser.parseReader(reader).getAsJsonObject());
        } catch (Exception e) {
            LOGGER.error("刀光设置 {} 读取失败", path, e);
            return Optional.empty();
        }
    }

    public static void clear() { SETS.clear(); }
}
