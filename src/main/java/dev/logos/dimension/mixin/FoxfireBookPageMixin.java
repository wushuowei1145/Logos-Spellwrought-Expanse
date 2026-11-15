package dev.logos.dimension.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.logos.dimension.foxfire.Foxfire;
import dev.logos.item.SpellPageItem;
import dev.logos.lang.Spell;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 用书与笔誊写载页：玩家写的术式里 {@code foxfire} 是不认识的词（{@link Foxfire#authoring}），学会了狐火之言的除外。 */
@Mixin(value = SpellPageItem.class, remap = false)
abstract class FoxfireBookPageMixin {
    @WrapOperation(method = "use", at = @At(value = "INVOKE", target = "Ldev/logos/runtime/Casting;compile(Ljava/lang/String;)Ldev/logos/lang/Spell;"), require = 0)
    private Spell logos_dimension$authoring(String src, Operation<Spell> original, @Local(argsOnly = true) Player player) {
        return Foxfire.authoring(Foxfire.knows(player), src, () -> original.call(src));
    }
}
