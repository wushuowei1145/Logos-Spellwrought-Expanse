package dev.logos.dimension.mixin;

import dev.logos.dimension.cloudsea.HotSpringStoneBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 温泉不结冰：水面下的水柱通到温泉地热石时，冬天的降温和寒冷群系都不把它冻成冰。 */
@Mixin(Biome.class)
public abstract class HotSpringFreezeMixin {
    @Inject(method = "shouldFreeze(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;Z)Z", at = @At("HEAD"), cancellable = true)
    private void hotSpringStaysOpen(LevelReader level, BlockPos pos, boolean mustBeAtEdge, CallbackInfoReturnable<Boolean> result) {
        if (HotSpringStoneBlock.heatedFrom(level, pos)) result.setReturnValue(false);
    }
}
