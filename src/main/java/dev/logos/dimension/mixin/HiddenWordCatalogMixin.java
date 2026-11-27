package dev.logos.dimension.mixin;

import java.util.List;

import com.llamalad7.mixinextras.sugar.Local;
import dev.logos.dimension.magic.HiddenWord;
import dev.logos.graph.EnumValues;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 编纂台的卡片库：本机玩家学会了的藏词，元素里多一张（{@code logos.enum.element.<词>}，例如狐火、龙炎）。 */
@Mixin(targets = "dev.logos.client.EnumCatalog", remap = false)
abstract class HiddenWordCatalogMixin {
    @Inject(method = "read", at = @At(value = "INVOKE", target = "Ljava/util/List;stream()Ljava/util/stream/Stream;"), require = 0)
    private void logos_dimension$hidden(EnumValues.Kind kind, CallbackInfoReturnable<List<?>> cir, @Local List<String> ids) {
        if (kind != EnumValues.Kind.ELEMENT) return;
        for (HiddenWord w : HiddenWord.all()) if (w.viewerKnows.getAsBoolean() && !ids.contains(w.word)) ids.add(w.word);
    }
}
