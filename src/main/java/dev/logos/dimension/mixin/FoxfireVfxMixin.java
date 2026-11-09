package dev.logos.dimension.mixin;

import dev.logos.dimension.foxfire.Foxfire;
import dev.logos.dimension.foxfire.FoxfireCue;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.lang.Element;
import dev.logos.runtime.Vfx;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 狐火术式里符文自己放的那一团奥秘（缠上手时、炼成体碎掉时……）换成狐火颜色的一团。命中的那一团在 {@code RyukaBurstMixin} 里换。 */
@Mixin(value = Vfx.class, remap = false)
abstract class FoxfireVfxMixin {
    @Inject(method = "burst(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/phys/Vec3;Ldev/logos/lang/Element;I)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void logos_dimension$burst(ServerLevel level, Vec3 at, Element element, int n, CallbackInfo ci) {
        FoxfireFlame flame = element == Element.ARCANE ? Foxfire.spellFlame() : null;
        if (flame == null) return;
        ci.cancel();
        FoxfireCue.burst(level, at, flame, (float) Math.min(2.5, .5 + n / 20.0));
    }
}
