package dev.logos.dimension.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 给原版生物追加目标用（僵尸、灾厄村民把森林精灵当村民打，见 npc.ElfFactions）。 */
@Mixin(Mob.class)
public interface MobGoalsAccess {
    @Accessor("targetSelector")
    GoalSelector logos_dimension$targetSelector();
}
