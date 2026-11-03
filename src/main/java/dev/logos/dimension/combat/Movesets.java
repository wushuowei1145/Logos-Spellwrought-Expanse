package dev.logos.dimension.combat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.util.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

/**
 * 从服务端数据包读取招式：{@code data/<命名空间>/combat/movesets/<名>.json}。
 * 招式文件可以写 {@code "extends": "<命名空间>:<名>"} 继承另一份：顶层字段和同名招式以自己的为准，其余照搬。
 * 按资源管理器缓存，{@code /reload} 换了资源管理器就重新读取；文件缺失或格式错误时记一次日志并返回 null，
 * 使用方据此退回原版行为（docs/COMBAT_SKELETON.md 第 1 节第 5 条）。
 */
public final class Movesets {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static ResourceManager cachedFor;
    private static final Map<Identifier, Optional<Moveset>> CACHE = new HashMap<>();
    private Movesets() {}

    public static synchronized Moveset get(MinecraftServer server, Identifier id) {
        var manager = server.getResourceManager();
        if (manager != cachedFor) { CACHE.clear(); cachedFor = manager; }
        return CACHE.computeIfAbsent(id, key -> load(manager, key)).orElse(null);
    }

    private static Optional<Moveset> load(ResourceManager manager, Identifier id) {
        try {
            return Optional.of(Moveset.parse(read(manager, id, 0)));
        } catch (Exception e) {
            LOGGER.error("招式 {} 读取失败，相关武器退回原版行为", id, e);
            return Optional.empty();
        }
    }

    /** 读出招式文件并展开 {@code extends}（最多套四层）。 */
    private static JsonObject read(ResourceManager manager, Identifier id, int depth) throws java.io.IOException {
        var path = id.withPath(p -> "combat/movesets/" + p + ".json");
        var resource = manager.getResource(path);
        if (resource.isEmpty()) throw new IllegalArgumentException("招式文件不存在：" + path);
        JsonObject json;
        try (var reader = resource.get().openAsReader()) { json = JsonParser.parseReader(reader).getAsJsonObject(); }
        if (!json.has("extends")) return json;
        if (depth >= 4) throw new IllegalArgumentException("招式继承太深：" + id);
        return inherit(read(manager, Identifier.parse(json.get("extends").getAsString()), depth + 1), json);
    }

    /** 子招式文件盖在父文件上：顶层字段和同名招式以子文件为准，其余照搬父文件；结果里不留 {@code extends}。 */
    public static JsonObject inherit(JsonObject parent, JsonObject child) {
        JsonObject merged = parent.deepCopy();
        JsonObject moves = merged.has("moves") ? merged.getAsJsonObject("moves") : new JsonObject();
        for (var entry : child.entrySet()) {
            if (entry.getKey().equals("extends")) continue;
            if (entry.getKey().equals("moves")) for (var move : entry.getValue().getAsJsonObject().entrySet()) moves.add(move.getKey(), move.getValue());
            else merged.add(entry.getKey(), entry.getValue());
        }
        merged.add("moves", moves);
        merged.remove("extends");
        return merged;
    }
}
