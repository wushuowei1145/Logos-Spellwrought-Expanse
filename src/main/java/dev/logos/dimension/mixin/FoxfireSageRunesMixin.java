package dev.logos.dimension.mixin;

import dev.logos.dimension.foxfire.Foxfire;
import java.util.Set;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 书灵拿到的「搭档学会了什么」：学会了狐火之言的加上 {@link Foxfire#UNLOCK}（{@code FoxfireSageMixin} 据此让它写 foxfire）。 */
@Mixin(targets = "dev.logos.sage.SageRunes", remap = false)
abstract class FoxfireSageRunesMixin {
    @Inject(method = "available", at = @At("RETURN"), require = 0)
    private static void logos_dimension$foxfire(Player player, CallbackInfoReturnable<Set<String>> cir) {
        if (Foxfire.knows(player)) cir.getReturnValue().add(Foxfire.UNLOCK);
    }
}
