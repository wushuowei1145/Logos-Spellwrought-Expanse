package dev.logos.dimension.mixin;

import dev.logos.dimension.elven.blight.*;
import dev.logos.lang.Element;
import dev.logos.net.FxPayload;
import dev.logos.runtime.Vfx;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 上游未提供按施法者换色的事件；最末发送点只替换女王生命特效，注入失效则退回原生命配色。 */
@Mixin(value = Vfx.class, remap = false)
abstract class QueenLifeVfxMixin {
    @Inject(method = "send(Lnet/minecraft/server/level/ServerLevel;Ldev/logos/net/FxPayload$Kind;Ldev/logos/lang/Element;ILnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;FILdev/logos/runtime/Vfx$Guise;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void queen$life(ServerLevel world, FxPayload.Kind kind, Element element, int variant, Vec3 at, Vec3 aux, float scale, int id, Vfx.Guise guise, CallbackInfo ci) {
        if (element == Element.LIFE && QueenSpellContext.active() && QueenSpellEffects.replace(world, kind.name(), variant, at, aux, scale, id, guise)) ci.cancel();
    }
}
