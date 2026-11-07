package dev.logos.dimension.combat;

import dev.logos.dimension.longsword.LongswordCombat;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;

/**
 * 弹刀成功后让攻击方出现破绽：攻击方进入招式文件里的僵直招式（没有转移，播完回待机），这段时间不能出招。
 * 僵直长短写在招式文件里：玩家用 {@link Moveset#PARRIED}（约 0.5 秒），NPC 用 {@link Moveset#PARRIED_NPC}（约 0.8 秒）。
 * 只对会做出反应的攻击方生效：拿着本附属战斗武器的玩家，以及实现 {@link Reactive} 的生物；
 * 原版和其他模组的生物不受影响（docs/COMBAT_SKELETON.md 第 1 节第 1 条）。
 */
public final class Parry {
    /**
     * 能对被弹反做出反应的生物。用招式状态机的 NPC 调 {@link Parry#staggerNpc}、{@link StaggerGoal#interrupt} 和 {@link Parry#isStagger}，
     * 并加上 {@link StaggerGoal}；Boss 可以自己决定播什么动作、僵直多久。
     */
    public interface Reactive {
        /** 这一下攻击被 {@code by} 弹开；做出了反应时返回 true。 */
        boolean parried(LivingEntity by);
        /** 正在被弹反的僵直里。 */
        boolean staggered();
    }

    private Parry() {}

    /** 这个伤害来源是不是攻击者亲手的近战（弹射物、法术、爆炸都不是）。 */
    public static boolean melee(DamageSource source) {
        return source.getDirectEntity() instanceof LivingEntity && source.getDirectEntity() == source.getEntity()
                && (source.is(DamageTypes.MOB_ATTACK) || source.is(DamageTypes.PLAYER_ATTACK) || source.is(DamageTypes.MOB_ATTACK_NO_AGGRO))
                && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY);
    }

    /** {@code defender} 弹开了 {@code source} 这一下：近战的攻击方进入僵直。返回攻击方是否做出了反应。 */
    public static boolean stagger(DamageSource source, LivingEntity defender) {
        if (!melee(source) || source.getEntity() == defender) return false;
        if (source.getEntity() instanceof ServerPlayer player) return LongswordCombat.parried(player);
        return source.getEntity() instanceof Reactive reactive && reactive.parried(defender);
    }

    /** NPC 的招式状态机进入僵直：招式文件的 {@code parried_npc}，没有就退回 {@code parried}；都没有时没有反应，返回 false。 */
    public static boolean staggerNpc(MoveRunner runner, double now) {
        Moveset set = runner.moveset();
        String move = set.move(Moveset.PARRIED_NPC) != null ? Moveset.PARRIED_NPC : Moveset.PARRIED;
        if (set.move(move) == null) return false;
        runner.force(move, now);
        return true;
    }

    /** 这个招式是被弹反的僵直。 */
    public static boolean isStagger(Moveset.Move move) {
        return move != null && (Moveset.PARRIED.equals(move.name()) || Moveset.PARRIED_NPC.equals(move.name()));
    }
}
