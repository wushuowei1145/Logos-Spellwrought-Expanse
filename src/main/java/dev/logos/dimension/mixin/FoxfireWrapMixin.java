package dev.logos.dimension.mixin;

import dev.logos.dimension.foxfire.Foxfire;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.dimension.foxfire.FoxfireWraps;
import dev.logos.runtime.WrapObj;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 狐火术式的魔力缠绕：缠上、散去时记在手上（{@link FoxfireWraps}），客户端把它画成狐火。 */
@Mixin(value = WrapObj.class, remap = false)
abstract class FoxfireWrapMixin {
    @Shadow
    @Final
    private LivingEntity holder;

    @Inject(method = "wear", at = @At("TAIL"), require = 0)
    private void logos_dimension$shown(CallbackInfo ci) {
        FoxfireFlame flame = Foxfire.flameOf(((SpellObjectAccess) (Object) this).logos_dimension$inst());
        if (flame != null) FoxfireWraps.shown(holder, flame);
    }

    @Inject(method = "onEnded", at = @At("TAIL"), require = 0)
    private void logos_dimension$ended(CallbackInfo ci) {
        FoxfireWraps.spellEnded(holder);
    }
}
