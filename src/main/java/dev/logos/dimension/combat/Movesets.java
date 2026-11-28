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
 * 招式文件可以写 {@code "extends": "<命名空间>:<名>"} 继承另一份：顶层字段和同名招式以自己的为准，其余照搬（{@code ai} 例外，见 {@link #inherit}）。
 * 按资源管理器缓存，{@code /reload} 换了资源管理器就重新读取；文件缺失或格式错误时记一次日志并返回 null，
 * 使用方据此退回原版行为（docs/COMBAT_SKELETON.md 第 1 节第 5 条）。同一份文件里给 AI 看的那一份见 {@link #ai}。
 */
public final class Movesets {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static ResourceManager cachedFor;
    private record Loaded(Moveset set, WeaponAi ai) {}
    private static final Map<Identifier, Optional<Loaded>> CACHE = new HashMap<>();
    /** 角色自己的招式文件有没有（{@link #forCharacter}）。 */
    private static final Map<Identifier, Boolean> EXISTS = new HashMap<>();
    private Movesets() {}

    public static synchronized Moveset get(MinecraftServer server, Identifier id) {
        var loaded = loaded(server, id);
        return loaded == null ? null : loaded.set;
    }

    /** 这份招式文件给 AI 看的那一份（docs/WEAPON_AI.md 第 3 节）；读不到或没写 {@code ai} 时为 {@link WeaponAi#NONE}。 */
    public static synchronized WeaponAi ai(MinecraftServer server, Identifier id) {
        var loaded = loaded(server, id);
        return loaded == null ? WeaponAi.NONE : loaded.ai;
    }

    /**
     * 角色 {@code character} 用招式文件 {@code id} 时实际读哪一份（docs/WEAPON_AI.md 第 4 节）：有
     * {@code combat/movesets/<角色>/<名>.json} 就用它（一般 {@code extends} 武器那份），没有就用武器的。
     */
    public static synchronized Identifier forCharacter(MinecraftServer server, String character, Identifier id) {
        var manager = fresh(server);
        Identifier own = id.withPath(p -> character + "/" + p);
        boolean has = EXISTS.computeIfAbsent(own, key -> manager.getResource(key.withPath(p -> "combat/movesets/" + p + ".json")).isPresent());
        return has ? own : id;
    }

    private static ResourceManager fresh(MinecraftServer server) {
        var manager = server.getResourceManager();
        if (manager != cachedFor) { CACHE.clear(); EXISTS.clear(); cachedFor = manager; }
        return manager;
    }

    private static Loaded loaded(MinecraftServer server, Identifier id) {
        var manager = fresh(server);
        return CACHE.computeIfAbsent(id, key -> load(manager, key)).orElse(null);
    }

    private static Optional<Loaded> load(ResourceManager manager, Identifier id) {
        try {
            JsonObject json = read(manager, id, 0);
            Moveset set = Moveset.parse(json);
            return Optional.of(new Loaded(set, ai(id, json, set)));
        } catch (Exception e) {
            LOGGER.error("招式 {} 读取失败，相关武器退回原版行为", id, e);
            return Optional.empty();
        }
    }

    /** 读 AI 那一份；写错了只记日志，招式照常给玩家用（AI 不用这把武器）。 */
    private static WeaponAi ai(Identifier id, JsonObject json, Moveset set) {
        try {
            return WeaponAi.parse(json).check(set);
        } catch (Exception e) {
            LOGGER.error("招式 {} 的 ai 读取失败，AI 不用这把武器", id, e);
            return WeaponAi.NONE;
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

    /**
     * 子招式文件盖在父文件上：顶层字段和同名招式以子文件为准，其余照搬父文件；结果里不留 {@code extends}。
     * {@code ai} 例外（docs/WEAPON_AI.md 第 4 节，角色只改用法）：顶层 {@code ai} 只盖写到的字段；子文件里只写了 {@code ai} 的招式照搬父文件的招，
     * 只把 {@code ai} 盖上去——写的是 {@code false} 或几种用法（数组）就整个换掉，写的是一种用法就只盖写到的字段（父文件是几种用法时盖到每一种上）。
     */
    public static JsonObject inherit(JsonObject parent, JsonObject child) {
        JsonObject merged = parent.deepCopy();
        JsonObject moves = merged.has("moves") ? merged.getAsJsonObject("moves") : new JsonObject();
        for (var entry : child.entrySet()) {
            if (entry.getKey().equals("extends")) continue;
            if (entry.getKey().equals("moves")) for (var move : entry.getValue().getAsJsonObject().entrySet()) {
                JsonObject own = move.getValue().getAsJsonObject();
                if (own.size() == 1 && own.has("ai") && moves.has(move.getKey())) {
                    JsonObject base = moves.getAsJsonObject(move.getKey());
                    base.add("ai", overlay(base.get("ai"), own.get("ai")));
                } else moves.add(move.getKey(), own);
            }
            else if (entry.getKey().equals("ai") && merged.has("ai") && entry.getValue().isJsonObject() && merged.get("ai").isJsonObject())
                merged.add("ai", fields(merged.getAsJsonObject("ai"), entry.getValue().getAsJsonObject()));
            else merged.add(entry.getKey(), entry.getValue());
        }
        merged.add("moves", moves);
        merged.remove("extends");
        return merged;
    }

    /** 招式的 {@code ai}：子文件写的盖到父文件的上面。 */
    private static com.google.gson.JsonElement overlay(com.google.gson.JsonElement base, com.google.gson.JsonElement own) {
        if (base == null || !own.isJsonObject()) return own;
        if (base.isJsonObject()) return fields(base.getAsJsonObject(), own.getAsJsonObject());
        if (base.isJsonArray()) {
            var out = new com.google.gson.JsonArray();
            for (var use : base.getAsJsonArray()) out.add(fields(use.getAsJsonObject(), own.getAsJsonObject()));
            return out;
        }
        return own;
    }

    private static JsonObject fields(JsonObject base, JsonObject own) {
        JsonObject out = base.deepCopy();
        for (var e : own.entrySet()) out.add(e.getKey(), e.getValue());
        return out;
    }
}
