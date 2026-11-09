package dev.logos.dimension.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.logos.dimension.foxfire.Foxfire;
import dev.logos.lang.Compiler;
import dev.logos.lang.Element;
import dev.logos.lang.Spell;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 术式里藏着的词 {@code foxfire}（docs/FOXFIRE.md 第 4 节）：编译器把它当奥秘读（类型、耗魔、外观推断都同奥秘），
 * 并记下这条术式是狐火术式。玩家自己写术式时（{@link Foxfire#authoring}）它和写错的词一样报错；
 * 拼写提示的词表只列 Logos 的元素，所以也不会提示它。
 */
@Mixin(value = Compiler.class, remap = false)
abstract class FoxfireCompilerMixin {
    @Unique
    private boolean logos_dimension$foxfire;

    @WrapOperation(method = "atom", at = @At(value = "INVOKE", target = "Ldev/logos/lang/Element;parse(Ljava/lang/String;)Ldev/logos/lang/Element;"), require = 0)
    private Element logos_dimension$foxfire(String word, Operation<Element> original) {
        Element element = original.call(word);
        if (element != null || !Foxfire.word(word)) return element;
        logos_dimension$foxfire = true;
        return Element.ARCANE;
    }

    @Inject(method = "compileSpell", at = @At("RETURN"), require = 0)
    private void logos_dimension$record(CallbackInfoReturnable<Spell> cir) {
        if (logos_dimension$foxfire) Foxfire.compiled(cir.getReturnValue());
    }
}
