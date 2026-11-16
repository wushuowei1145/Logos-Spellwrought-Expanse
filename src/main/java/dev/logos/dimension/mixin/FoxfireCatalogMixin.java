package dev.logos.dimension.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.logos.dimension.foxfire.Foxfire;
import dev.logos.graph.EnumValues;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 编纂台的卡片库：本机玩家学会了狐火之言时，元素里多一张「狐火」（{@code logos.enum.element.foxfire}）。 */
@Mixin(targets = "dev.logos.client.EnumCatalog", remap = false)
abstract class FoxfireCatalogMixin {
    @Inject(method = "read", at = @At(value = "INVOKE", target = "Ljava/util/List;stream()Ljava/util/stream/Stream;"), require = 0)
    private void logos_dimension$foxfire(EnumValues.Kind kind, CallbackInfoReturnable<List<?>> cir, @Local List<String> ids) {
        if (kind == EnumValues.Kind.ELEMENT && Foxfire.viewerKnows.getAsBoolean() && !ids.contains(Foxfire.WORD)) ids.add(Foxfire.WORD);
    }
}
