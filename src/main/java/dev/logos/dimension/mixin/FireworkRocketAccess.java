package dev.logos.dimension.mixin;

import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 烟火弹（firearm.RoundEffects）：把烟火的寿命设成 0，让它在落点当场炸开，不先往上飞。 */
@Mixin(FireworkRocketEntity.class)
public interface FireworkRocketAccess {
    @Accessor("lifetime")
    void logos_dimension$setLifetime(int ticks);
}
