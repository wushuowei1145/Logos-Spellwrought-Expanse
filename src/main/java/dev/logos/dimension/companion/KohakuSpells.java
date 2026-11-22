package dev.logos.dimension.companion;

import dev.logos.dimension.LogosDimension;
import dev.logos.lang.Intent;
import dev.logos.lang.Spell;
import dev.logos.runtime.Casting;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

/**
 * 琥珀自带的术式（docs/KOHAKU_COMPANION.md 第 5 节）：都是载页，放在 {@code data/logos_dimension/companion/spells/kohaku/*.logos}，
 * 可以直接改。伤害由 Logos 乘她的法术强度，治疗、护盾乘她的治疗强度（等于她的法术强度）。随爪之契越解锁越多。
 *
 * @param id       文件名（也是面板上能力的 ID）
 * @param seal     第几枚爪之契解锁（0 是本来就会）
 * @param intent   意图（她按意图挑页）
 * @param cooldown 这一页自己的冷却（刻）
 */
record KohakuSpells(String id, int seal, Intent intent, int cooldown) {
    /** 一滴就够：本来就会的单体攻击。 */
    static final KohakuSpells ONE_DROP = new KohakuSpells("one_drop", 0, Intent.STRIKE, 24);
    /** 现在做好的几页（之后的按契印一页页加）。 */
    static final List<KohakuSpells> ALL = List.of(ONE_DROP);

    private static @Nullable MinecraftServer server;
    private static final Map<String, Optional<Spell>> CACHE = new HashMap<>();

    /**
     * 这一页编好的术式；「攻击玩家」开着时（{@code players}）加上 {@code :players true}。读不到、编不过时为 null（记一条日志）。
     * 资源重载、Logos 的限制变了以后重新编。
     */
    @Nullable Spell spell(MinecraftServer s, boolean players) {
        synchronized (CACHE) {
            if (server != s || generation != Casting.generation()) {
                server = s;
                generation = Casting.generation();
                CACHE.clear();
            }
            return CACHE.computeIfAbsent(id + "|" + players, k -> {
                try (var reader = s.getResourceManager().getResourceOrThrow(LogosDimension.id("companion/spells/kohaku/" + id + ".logos")).openAsReader()) {
                    String source = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
                    if (players) source = source.replace(":trigger cast", ":trigger cast :players true");
                    return Optional.of(Casting.compile(source));
                } catch (Exception error) {
                    com.mojang.logging.LogUtils.getLogger().warn("琥珀的术式 {} 不可用", id, error);
                    return Optional.empty();
                }
            }).orElse(null);
        }
    }

    private static int generation = -1;

    /** 盖了 {@code seals} 枚爪之契时会不会这一页。 */
    boolean unlocked(int seals) {
        return CompanionBond.unlocked(seals, seal);
    }

    static @Nullable KohakuSpells byId(String id) {
        for (KohakuSpells s : ALL) if (s.id.equals(id)) return s;
        return null;
    }
}
