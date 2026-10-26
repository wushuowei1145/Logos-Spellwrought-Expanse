package dev.logos.dimension.npc;

import dev.logos.dimension.mixin.MobGoalsAccess;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.raid.Raider;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * 阵营照抄村民：原版僵尸以优先级 3 攻击村民（不必看见），灾厄村民等劫掠者以优先级 3 攻击村民（要看见）。
 * 这里给它们加上同样的、以森林精灵为对象的目标。精灵这边主动攻击亡灵与灾厄村民，见 {@link ForestElf#isFoe}。
 * 玩家的 Logos 使魔不加，免得使魔去打精灵。
 */
public final class ElfFactions {
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || LogosMagicBridge.masterOf(event.getEntity()) != null) return;
        if (event.getEntity() instanceof Zombie zombie) {
            ((MobGoalsAccess) zombie).logos_dimension$targetSelector().addGoal(3, new NearestAttackableTargetGoal<>(zombie, ForestElf.class, false));
        } else if (event.getEntity() instanceof Raider raider) {
            ((MobGoalsAccess) raider).logos_dimension$targetSelector().addGoal(3, new NearestAttackableTargetGoal<>(raider, ForestElf.class, true));
        }
    }

    private ElfFactions() {}
}
