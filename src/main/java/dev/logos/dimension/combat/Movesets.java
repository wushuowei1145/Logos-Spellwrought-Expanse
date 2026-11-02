package dev.logos.dimension.combat;

import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.util.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

/**
 * 从服务端数据包读取招式：{@code data/<命名空间>/combat/movesets/<名>.json}。
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
        var path = id.withPath(p -> "combat/movesets/" + p + ".json");
        var resource = manager.getResource(path);
        if (resource.isEmpty()) { LOGGER.error("招式 {} 不存在（{}），相关武器退回原版行为", id, path); return Optional.empty(); }
        try (var reader = resource.get().openAsReader()) {
            return Optional.of(Moveset.parse(JsonParser.parseReader(reader).getAsJsonObject()));
        } catch (Exception e) {
            LOGGER.error("招式 {} 读取失败，相关武器退回原版行为", id, e);
            return Optional.empty();
        }
    }
}
