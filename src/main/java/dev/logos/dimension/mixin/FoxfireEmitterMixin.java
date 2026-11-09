package dev.logos.dimension.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.logos.dimension.foxfire.Foxfire;
import dev.logos.lang.Spell;
import dev.logos.net.EmitterEditPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 术式发射器改写：玩家写的术式里 {@code foxfire} 是不认识的词（{@link Foxfire#authoring}）。 */
@Mixin(value = EmitterEditPayload.class, remap = false)
abstract class FoxfireEmitterMixin {
    @WrapOperation(method = "handle", at = @At(value = "INVOKE", target = "Ldev/logos/runtime/Casting;compile(Ljava/lang/String;)Ldev/logos/lang/Spell;"), require = 0)
    private static Spell logos_dimension$authoring(String src, Operation<Spell> original) {
        return Foxfire.authoring(src, () -> original.call(src));
    }
}
