package dev.logos.dimension.mixin;

import java.util.List;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.logos.dimension.foxfire.Foxfire;
import dev.logos.graph.GraphCompiler;
import dev.logos.lang.Spell;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 编纂台里画的术式图编成术式：{@code foxfire} 是不认识的词（{@link Foxfire#authoring}）。 */
@Mixin(value = GraphCompiler.class, remap = false)
abstract class FoxfireGraphMixin {
    @WrapOperation(method = "compile", at = @At(value = "INVOKE", target = "Ldev/logos/lang/Compiler;compile(Ljava/util/List;Ljava/lang/String;)Ldev/logos/lang/Spell;"), require = 0)
    private static Spell logos_dimension$authoring(List<?> top, String src, Operation<Spell> original) {
        return Foxfire.authoring(() -> original.call(top, src));
    }
}
