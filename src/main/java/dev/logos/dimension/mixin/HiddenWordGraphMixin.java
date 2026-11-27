package dev.logos.dimension.mixin;

import java.util.List;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.logos.dimension.magic.HiddenWord;
import dev.logos.graph.GraphCompiler;
import dev.logos.lang.Spell;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 编纂台里画的术式图编成术式：本机玩家没学会的藏词（{@link HiddenWord#authoring}）是不认识的词（编纂台在客户端编图）。 */
@Mixin(value = GraphCompiler.class, remap = false)
abstract class HiddenWordGraphMixin {
    @WrapOperation(method = "compile", at = @At(value = "INVOKE", target = "Ldev/logos/lang/Compiler;compile(Ljava/util/List;Ljava/lang/String;)Ldev/logos/lang/Spell;"), require = 0)
    private static Spell logos_dimension$authoring(List<?> top, String src, Operation<Spell> original) {
        return HiddenWord.authoring(HiddenWord.knownByViewer(), () -> original.call(top, src));
    }
}
