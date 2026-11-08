package dev.logos.dimension.mixin;

import dev.logos.client.ShaderPackOverlay;
import dev.logos.dimension.client.vfx.DawnvowScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 开着光影包时，Logos 等光影包合成完画面，再把自己的特效（物品上的火焰、光柱等）画上去（{@link ShaderPackOverlay#draw}）。
 * 曦誓的后期在这之前留一份画面，就知道哪些光是特效加上去的，调暗周围时不把它们一起调暗。
 * Logos 改了这个方法时注入不上（{@code require = 0}），退回不分层的调暗，不会崩。
 */
@Mixin(value = ShaderPackOverlay.class, remap = false)
public abstract class DawnvowOverlayMixin {
    @Inject(method = "draw", at = @At("HEAD"), require = 0)
    private static void logos_dimension$beforeOverlay(CallbackInfo ci) {
        DawnvowScreen.beforeOverlay();
    }
}
