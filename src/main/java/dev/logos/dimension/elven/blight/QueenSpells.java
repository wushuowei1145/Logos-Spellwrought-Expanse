package dev.logos.dimension.elven.blight;

import dev.logos.dimension.LogosDimension;
import dev.logos.lang.Spell;
import dev.logos.runtime.Casting;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** 代码只做选页与付费，弹丸、射线、碰撞和元素伤害全部交给 Logos 载页运行时；耗魔写在招式文件里（{@link QueenMoves}）。 */
public final class QueenSpells {
    private static ResourceManager manager;
    private static final Map<String, Optional<Spell>> SPELLS = new HashMap<>();
    private QueenSpells() {}
    public static Spell spell(MinecraftServer server, String id) {
        var resources = server.getResourceManager();
        if (manager != resources) { manager = resources; SPELLS.clear(); }
        return SPELLS.computeIfAbsent(id, key -> {
            try (var reader = resources.getResourceOrThrow(LogosDimension.id("queen/spells/" + key + ".logos")).openAsReader()) {
                return Optional.of(Casting.compile(reader.lines().collect(java.util.stream.Collectors.joining("\n"))));
            } catch (Exception error) {
                com.mojang.logging.LogUtils.getLogger().warn("腐化女王载页 {} 不可用，跳过这一项法术", key, error);
                return Optional.empty();
            }
        }).orElse(null);
    }
    public static boolean cast(CorruptedElfQueen queen, String id, Vec3 from, @Nullable LivingEntity target, double cost) {
        if (!(queen.level() instanceof net.minecraft.server.level.ServerLevel world)) return false;
        Spell spell = spell(world.getServer(), id);
        if (spell == null || !queen.payMana(cost)) return false;
        // 收到明确前摇后才进入真实施法；女王是腐败一方，她的生命法术就是腐败伤害，特效也换成腐败的。
        dev.logos.dimension.corruption.CorruptionDamage.inSpell(true, () -> Casting.castAs(queen, spell, cost, 0, target, from, true));
        return true;
    }
}
