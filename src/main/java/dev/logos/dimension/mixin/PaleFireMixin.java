package dev.logos.dimension.mixin;

import dev.logos.client.VfxParts;
import dev.logos.dimension.client.longsword.PaleFire;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 色相带着 {@link PaleFire#MARK} 记号时（流火刀身的狐火），照常转色相，再把饱和度压下来。别的色相原样。
 * Logos 改了这个方法时注入不上（{@code require = 0}），流火的火退回原来的浓粉，不会崩。
 */
@Mixin(value = VfxParts.class, remap = false)
abstract class PaleFireMixin {
    @Inject(method = "hue(IF)I", at = @At("HEAD"), cancellable = true, require = 0)
    private static void logos_dimension$pale(int rgb, float turns, CallbackInfoReturnable<Integer> cir) {
        if (PaleFire.marked(turns)) cir.setReturnValue(PaleFire.pale(VfxParts.hue(rgb, PaleFire.unmarked(turns))));
    }
}
