package dev.logos.dimension.mixin;

import java.util.Set;

import dev.logos.dimension.magic.HiddenWord;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 书灵拿到的「搭档学会了什么」：学会的每个藏词加上它的 {@link HiddenWord#unlock}（{@code HiddenWordSageMixin} 据此让它写）。 */
@Mixin(targets = "dev.logos.sage.SageRunes", remap = false)
abstract class HiddenWordSageRunesMixin {
    @Inject(method = "available", at = @At("RETURN"), require = 0)
    private static void logos_dimension$hidden(Player player, CallbackInfoReturnable<Set<String>> cir) {
        for (HiddenWord w : HiddenWord.all()) if (w.knows(player)) cir.getReturnValue().add(w.unlock);
    }
}
