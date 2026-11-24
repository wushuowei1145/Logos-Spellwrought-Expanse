package dev.logos.dimension.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.logos.client.VeinVisual;
import dev.logos.client.VeinVisuals;
import dev.logos.dimension.client.vfx.CorruptionFx;
import dev.logos.dimension.client.vfx.CorruptionThorns;
import dev.logos.lang.Element;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 腐败一方放的生命游脉（服务端先发了 {@code QueenSpellPayload.VEIN}）：和 Logos 的生命游脉一样沿地面长出带刺的藤，
 * 只是画成腐败的紫红色（{@link CorruptionThorns.Vein}）。
 */
@Mixin(value = VeinVisuals.class, remap = false)
abstract class CorruptionVeinVisualsMixin {
    @WrapOperation(method = "hold", at = @At(value = "INVOKE", target = "Ldev/logos/client/VeinVisual;create(Ldev/logos/lang/Element;Z)Ldev/logos/client/VeinVisual;"), require = 0)
    private static VeinVisual logos_dimension$corruption(Element element, boolean lingers, Operation<VeinVisual> original, @Local(argsOnly = true, ordinal = 0) int id) {
        return element == Element.LIFE && CorruptionFx.corruptVein(id) ? new CorruptionThorns.Vein() : original.call(element, lingers);
    }
}
