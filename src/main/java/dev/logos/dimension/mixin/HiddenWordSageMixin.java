package dev.logos.dimension.mixin;

import java.util.Set;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.logos.dimension.magic.HiddenWord;
import dev.logos.lang.Spell;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 书灵替玩家写的术式：藏词（{@link HiddenWord#authoring}）是不认识的词；搭档学会了的（书灵拿到的「学会了什么」里有
 * {@link HiddenWord#unlock}，见 {@code HiddenWordSageRunesMixin}）照常认得。
 */
@Mixin(targets = "dev.logos.sage.SageAgent", remap = false)
abstract class HiddenWordSageMixin {
    @WrapOperation(method = "check", at = @At(value = "INVOKE", target = "Ldev/logos/lang/Compiler;compile(Ljava/lang/String;)Ldev/logos/lang/Spell;"), require = 0)
    private static Spell logos_dimension$authoring(String src, Operation<Spell> original, @Local(argsOnly = true) Set<String> available) {
        return HiddenWord.authoring(w -> available.contains(w.unlock), () -> original.call(src));
    }
}
