package dev.logos.dimension.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.logos.dimension.magic.HiddenWord;
import dev.logos.lang.Spell;
import dev.logos.net.InscribePayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 编纂台誊写：玩家没学会的藏词（{@link HiddenWord#authoring}）是不认识的词。 */
@Mixin(value = InscribePayload.class, remap = false)
abstract class HiddenWordInscribeMixin {
    @WrapOperation(method = "handle", at = @At(value = "INVOKE", target = "Ldev/logos/runtime/Casting;compile(Ljava/lang/String;)Ldev/logos/lang/Spell;"), require = 0)
    private static Spell logos_dimension$authoring(String src, Operation<Spell> original, @Local(argsOnly = true) IPayloadContext ctx) {
        return HiddenWord.authoring(HiddenWord.knownBy(ctx.player()), src, () -> original.call(src));
    }
}
