package dev.logos.dimension.companion;

import dev.logos.dimension.LogosDimension;
import dev.logos.lang.Spell;
import dev.logos.runtime.Casting;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

/**
 * 拉塞塔的掌心赤焰（龙炎弹）：一页她自己的术式 {@code companion/spells/lacerta/flame_bolt.logos}，由她自己施放（{@code Casting.castAs}，同琥珀的页）。
 * 编好的按「攻击玩家」开没开各缓存一份；资源重载、Logos 的限制变了以后重新编。
 */
final class LacertaFlame {
    private static @Nullable MinecraftServer server;
    private static int generation = -1;
    private static final Map<Boolean, Optional<Spell>> CACHE = new HashMap<>();

    private LacertaFlame() {}

    static @Nullable Spell spell(MinecraftServer s, boolean players) {
        synchronized (CACHE) {
            if (server != s || generation != Casting.generation()) {
                server = s;
                generation = Casting.generation();
                CACHE.clear();
            }
            return CACHE.computeIfAbsent(players, k -> {
                try (var reader = s.getResourceManager().getResourceOrThrow(LogosDimension.id("companion/spells/lacerta/flame_bolt.logos")).openAsReader()) {
                    String source = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
                    if (players) source = source.replace(":trigger cast", ":trigger cast :players true");
                    return Optional.of(Casting.compile(source));
                } catch (Exception error) {
                    com.mojang.logging.LogUtils.getLogger().warn("拉塞塔的掌心赤焰不可用", error);
                    return Optional.empty();
                }
            }).orElse(null);
        }
    }
}
