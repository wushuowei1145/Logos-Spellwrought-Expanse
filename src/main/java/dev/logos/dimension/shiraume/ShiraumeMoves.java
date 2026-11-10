package dev.logos.dimension.shiraume;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Boss 招式文件里只有 Boss 用的字段（通用的招式状态机不认识它们，照常忽略）：
 * 双眼红光的时刻、被弹反时怎么办、够得到多远、往前冲的一段、后跳、破盾、清除增益。
 * 和 {@link dev.logos.dimension.combat.Movesets} 读同一个文件，按资源管理器缓存，{@code /reload} 后重新读。
 */
public final class ShiraumeMoves {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 被弹反时：播自己的被弹反动作（独立招式）、接着下一刀（连续招式）、弹不开。 */
    public enum ParryKind { SINGLE, COMBO, NONE }

    /**
     * 一招的 Boss 专用字段。{@code eyeFlash} 小于 0 表示这一招不闪；{@code lunge} 是 [起, 止, 格数]（动作时间），
     * {@code leap} 是 [时刻, 后退格数, 上跳速度]，没有时为 null。{@code guardBreak}：格挡下来、却没有弹反时破盾（流火除外）；
     * {@code purge}：打中时随机清除几个增益药水效果。
     */
    public record Extra(double eyeFlash, ParryKind parry, @Nullable String parried, @Nullable String follow, double reach,
                        double damage, double cone, double @Nullable [] lunge, double @Nullable [] leap, boolean guardBreak, int purge) {
        static final Extra NONE = new Extra(-1, ParryKind.NONE, null, null, 0, 0, 0, null, null, false, 0);
    }

    private static ResourceManager cachedFor;
    private static final Map<Identifier, Optional<Map<String, Extra>>> CACHE = new HashMap<>();

    private ShiraumeMoves() {}

    /** 这一招的专用字段；文件缺失或没有这一招时返回 {@link Extra#NONE}。 */
    public static Extra of(MinecraftServer server, Identifier moveset, String move) {
        Map<String, Extra> all = all(server, moveset);
        return all == null ? Extra.NONE : all.getOrDefault(move, Extra.NONE);
    }

    public static synchronized @Nullable Map<String, Extra> all(MinecraftServer server, Identifier id) {
        var manager = server.getResourceManager();
        if (manager != cachedFor) { CACHE.clear(); cachedFor = manager; }
        return CACHE.computeIfAbsent(id, key -> load(manager, key)).orElse(null);
    }

    private static Optional<Map<String, Extra>> load(ResourceManager manager, Identifier id) {
        var path = id.withPath(p -> "combat/movesets/" + p + ".json");
        try (var reader = manager.getResourceOrThrow(path).openAsReader()) {
            return Optional.of(parse(JsonParser.parseReader(reader).getAsJsonObject()));
        } catch (Exception e) {
            LOGGER.error("Boss 招式 {} 读取失败", id, e);
            return Optional.empty();
        }
    }

    /** 读出每一招的专用字段（测试也用）。 */
    public static Map<String, Extra> parse(JsonObject json) {
        Map<String, Extra> out = new HashMap<>();
        for (var entry : json.getAsJsonObject("moves").entrySet()) {
            JsonObject m = entry.getValue().getAsJsonObject();
            ParryKind parry = m.has("parry") ? ParryKind.valueOf(m.get("parry").getAsString().toUpperCase(java.util.Locale.ROOT)) : ParryKind.NONE;
            out.put(entry.getKey(), new Extra(
                    m.has("eye_flash") ? m.get("eye_flash").getAsDouble() : -1, parry,
                    m.has("parried") ? m.get("parried").getAsString() : null,
                    m.has("follow") ? m.get("follow").getAsString() : null,
                    m.has("reach") ? m.get("reach").getAsDouble() : 0,
                    m.has("damage") ? m.get("damage").getAsDouble() : 0,
                    m.has("cone") ? m.get("cone").getAsDouble() : 0,
                    numbers(m, "lunge", 3), numbers(m, "leap", 3),
                    m.has("guard_break") && m.get("guard_break").getAsBoolean(),
                    m.has("purge") ? m.get("purge").getAsInt() : 0));
        }
        return Map.copyOf(out);
    }

    private static double @Nullable [] numbers(JsonObject m, String key, int size) {
        if (!m.has(key)) return null;
        JsonArray a = m.getAsJsonArray(key);
        if (a.size() != size) throw new IllegalArgumentException(key + " 要 " + size + " 个数");
        double[] out = new double[size];
        for (int i = 0; i < size; i++) out[i] = a.get(i).getAsDouble();
        return out;
    }
}
