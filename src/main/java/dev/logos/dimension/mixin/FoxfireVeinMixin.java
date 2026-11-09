package dev.logos.dimension.mixin;

import dev.logos.dimension.foxfire.Foxfire;
import dev.logos.dimension.foxfire.FoxfireCue;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.runtime.VeinObj;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 狐火术式的游脉：发头的位置之前先告诉附近的客户端，这条游脉画成狐火的颜色。 */
@Mixin(value = VeinObj.class, remap = false)
abstract class FoxfireVeinMixin {
    @Inject(method = "show", at = @At("HEAD"), require = 0)
    private void logos_dimension$foxfire(CallbackInfo ci) {
        SpellObjectAccess self = (SpellObjectAccess) (Object) this;
        FoxfireFlame flame = Foxfire.flameOf(self.logos_dimension$inst());
        if (flame != null) FoxfireCue.vein(self.logos_dimension$level(), self.logos_dimension$id(), self.logos_dimension$inst().caster.position(), flame);
    }
}
