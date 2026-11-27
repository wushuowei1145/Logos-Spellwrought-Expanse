package dev.logos.dimension.mixin;

import java.util.ArrayList;
import java.util.List;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.logos.dimension.magic.HiddenWord;
import dev.logos.lang.Compiler;
import dev.logos.lang.Element;
import dev.logos.lang.Spell;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 术式里藏着的词（{@code foxfire}、{@code dragonfire}，见 {@link HiddenWord}）：编译器把它当奥秘读（类型、耗魔、外观推断都同奥秘），
 * 并记下这条术式用过它。玩家自己写术式时没学会的词（{@link HiddenWord#authoring}）和写错的词一样报错；
 * 拼写提示的词表只列 Logos 的元素，所以也不会提示它。
 */
@Mixin(value = Compiler.class, remap = false)
abstract class HiddenWordCompilerMixin {
    @Unique
    private List<HiddenWord> logos_dimension$hidden;

    @WrapOperation(method = "atom", at = @At(value = "INVOKE", target = "Ldev/logos/lang/Element;parse(Ljava/lang/String;)Ldev/logos/lang/Element;"), require = 0)
    private Element logos_dimension$hidden(String word, Operation<Element> original) {
        Element element = original.call(word);
        if (element != null) return element;
        for (HiddenWord w : HiddenWord.all()) {
            if (!w.accepts(word)) continue;
            if (logos_dimension$hidden == null) logos_dimension$hidden = new ArrayList<>(2);
            if (!logos_dimension$hidden.contains(w)) logos_dimension$hidden.add(w);
            return Element.ARCANE;
        }
        return null;
    }

    @Inject(method = "compileSpell", at = @At("RETURN"), require = 0)
    private void logos_dimension$record(CallbackInfoReturnable<Spell> cir) {
        if (logos_dimension$hidden != null) for (HiddenWord w : logos_dimension$hidden) w.compiled(cir.getReturnValue());
    }
}
