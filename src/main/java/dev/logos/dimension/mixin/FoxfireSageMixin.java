package dev.logos.dimension.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.Set;
import dev.logos.dimension.foxfire.Foxfire;
import dev.logos.lang.Spell;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 书灵替玩家写的术式：{@code foxfire} 是不认识的词（{@link Foxfire#authoring}）；搭档学会了狐火之言时（书灵拿到的「学会了什么」里有
 * {@link Foxfire#UNLOCK}，见 {@code FoxfireSageRunesMixin}）照常认得。
 */
@Mixin(targets = "dev.logos.sage.SageAgent", remap = false)
abstract class FoxfireSageMixin {
    @WrapOperation(method = "check", at = @At(value = "INVOKE", target = "Ldev/logos/lang/Compiler;compile(Ljava/lang/String;)Ldev/logos/lang/Spell;"), require = 0)
    private static Spell logos_dimension$authoring(String src, Operation<Spell> original, @Local(argsOnly = true) Set<String> available) {
        return Foxfire.authoring(available.contains(Foxfire.UNLOCK), () -> original.call(src));
    }
}
