package dev.logos.dimension.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 一刀打几个目标时，每个目标都按出刀时的攻击蓄力算（原版打完一下就清零），见 longsword.LongswordCombat。 */
@Mixin(LivingEntity.class)
public interface AttackStrengthAccess {
    @Accessor("attackStrengthTicker")
    int logos_dimension$attackStrengthTicker();

    @Accessor("attackStrengthTicker")
    void logos_dimension$setAttackStrengthTicker(int ticks);

    @Accessor("itemSwapTicker")
    int logos_dimension$itemSwapTicker();

    @Accessor("itemSwapTicker")
    void logos_dimension$setItemSwapTicker(int ticks);
}
