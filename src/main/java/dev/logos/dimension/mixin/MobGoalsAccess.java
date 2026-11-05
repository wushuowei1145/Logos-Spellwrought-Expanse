package dev.logos.dimension.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 给原版生物追加目标与行为用（僵尸、灾厄村民把森林精灵、神狐当村民打，苦力怕躲神狐，见 npc.ElfFactions）。 */
@Mixin(Mob.class)
public interface MobGoalsAccess {
    @Accessor("targetSelector")
    GoalSelector logos_dimension$targetSelector();

    @Accessor("goalSelector")
    GoalSelector logos_dimension$goalSelector();
}
