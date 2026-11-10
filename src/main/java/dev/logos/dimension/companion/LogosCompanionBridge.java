package dev.logos.dimension.companion;

import dev.logos.runtime.Familiars;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 白梅在 Logos 那边的身份：长期同伴（{@code LastingCompanion}，Logos 的使魔系统里一人一个、没有时限的那种）。
 * Logos 把她当主人的使魔（主人的法术不误伤、她的击杀算主人的、{@code sic}、{@code gather}、{@code stance} 下到她身上），
 * 但跟随、拉回、收走都由她自己的心智做；Logos 的命令从这里读（docs/SHIRAUME_COMPANION.md 第 8.12 节）。
 */
final class LogosCompanionBridge {
    private LogosCompanionBridge() {}

    /** 她成了 {@code owner} 的同伴：告诉 Logos（主人之前的长期同伴会被送回去）。 */
    static void bind(ShiraumeCompanion c, ServerPlayer owner) {
        Familiars.bindCompanion(c, owner);
    }

    /** 主人用 {@code sic} 下令打的、或主人正在打的（Logos 记的那一个）；没有为 null。 */
    static @Nullable LivingEntity ordered(ShiraumeCompanion c) {
        LivingEntity t = Familiars.orderedTarget(c);
        return t != null && t.isAlive() && c.canHarm(t) ? t : null;
    }

    /** {@code gather} 叫她去的地方、或者她该守着的地方；跟着主人时为 null。 */
    static @Nullable Vec3 orderPoint(ShiraumeCompanion c) {
        return Familiars.orderPoint(c);
    }

    /** 正在去 {@code gather} 指的地方（还没到）。 */
    static boolean onErrand(ShiraumeCompanion c) {
        return Familiars.onErrand(c);
    }
}
