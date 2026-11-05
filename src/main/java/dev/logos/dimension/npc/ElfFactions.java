package dev.logos.dimension.npc;

import dev.logos.dimension.mixin.MobGoalsAccess;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.raid.Raider;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * 阵营照抄村民：原版僵尸以优先级 3 攻击村民（不必看见），灾厄村民等劫掠者以优先级 3 攻击村民（要看见）。
 * 这里给它们加上同样的、以森林精灵和神狐为对象的目标。精灵这边主动攻击亡灵与灾厄村民，见 {@link ForestElf#isFoe}；
 * 神狐见 {@link DivineFox#isFoe}。苦力怕像躲猫一样躲神狐（与原版躲猫、豹猫同一优先级和速度）。
 * 玩家的 Logos 使魔不加，免得使魔去打精灵。
 */
public final class ElfFactions {
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || LogosMagicBridge.masterOf(event.getEntity()) != null) return;
        if (event.getEntity() instanceof Zombie zombie) {
            var targets = ((MobGoalsAccess) zombie).logos_dimension$targetSelector();
            targets.addGoal(3, new NearestAttackableTargetGoal<>(zombie, ForestElf.class, false));
            targets.addGoal(3, new NearestAttackableTargetGoal<>(zombie, DivineFox.class, false));
        } else if (event.getEntity() instanceof Raider raider) {
            var targets = ((MobGoalsAccess) raider).logos_dimension$targetSelector();
            targets.addGoal(3, new NearestAttackableTargetGoal<>(raider, ForestElf.class, true));
            targets.addGoal(3, new NearestAttackableTargetGoal<>(raider, DivineFox.class, true));
        } else if (event.getEntity() instanceof Creeper creeper) {
            ((MobGoalsAccess) creeper).logos_dimension$goalSelector().addGoal(3, new AvoidEntityGoal<>(creeper, DivineFox.class, 6.0F, 1.0, 1.2));
        }
    }

    private ElfFactions() {}
}
