package dev.logos.dimension.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.logos.client.VeinVisual;
import dev.logos.client.VeinVisuals;
import dev.logos.dimension.client.foxfire.FoxfireClient;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.lang.Element;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 狐火术式的游脉（服务端先发了 {@code FoxfireCue.VEIN}）：和 Logos 各元素共用的游脉一样沿地面一路冒火，
 * 只是冒的是狐火（{@code FoxfireVeinVisual}）。
 */
@Mixin(value = VeinVisuals.class, remap = false)
abstract class FoxfireVeinVisualsMixin {
    @WrapOperation(method = "hold", at = @At(value = "INVOKE", target = "Ldev/logos/client/VeinVisual;create(Ldev/logos/lang/Element;Z)Ldev/logos/client/VeinVisual;"), require = 0)
    private static VeinVisual logos_dimension$foxfire(Element element, boolean lingers, Operation<VeinVisual> original, @Local(argsOnly = true, ordinal = 0) int id) {
        FoxfireFlame flame = element == Element.ARCANE ? FoxfireClient.vein(id) : null;
        return flame == null ? original.call(element, lingers) : FoxfireClient.veinVisual(flame);
    }
}
