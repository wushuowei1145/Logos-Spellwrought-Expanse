package dev.logos.dimension.mixin;

import dev.logos.dimension.item.NaturesForceItem;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.RangedBowAttackGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** 保留原版远程 AI 的追踪、视线与走位，只将自然之力的拉弓时长延至四十刻。 */
@Mixin(RangedBowAttackGoal.class)
public abstract class NatureBowGoalMixin {
    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Mob;getTicksUsingItem()I"))
    private int natureDrawTime(net.minecraft.world.entity.Mob mob) {
        return mob.getTicksUsingItem() - (mob.getUseItem().getItem() instanceof NaturesForceItem ? 20 : 0);
    }
}
