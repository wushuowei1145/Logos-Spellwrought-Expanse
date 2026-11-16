package dev.logos.dimension.mixin;

import dev.logos.dimension.corruption.CorruptionDamage;
import dev.logos.dimension.elven.blight.QueenSpellEffects;
import dev.logos.lang.Element;
import dev.logos.net.FxPayload;
import dev.logos.runtime.Vfx;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 腐败伤害与腐败一方法术的生命特效换成猩红的（docs/CORRUPTION.md「腐败伤害」）。在 Logos 发特效的最末一处判断，
 * 只看元素是不是生命、当前是不是腐败（{@link CorruptionDamage#visual}）；注入失效时退回普通生命配色。
 */
@Mixin(value = Vfx.class, remap = false)
abstract class CorruptionVfxMixin {
    @Inject(method = "send(Lnet/minecraft/server/level/ServerLevel;Ldev/logos/net/FxPayload$Kind;Ldev/logos/lang/Element;ILnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;FILdev/logos/runtime/Vfx$Guise;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void logos_dimension$life(ServerLevel world, FxPayload.Kind kind, Element element, int variant, Vec3 at, Vec3 aux, float scale, int id, Vfx.Guise guise, CallbackInfo ci) {
        // 生命特效换成腐败的；领域没有自己的元素（荆棘穿刺圈的那块地），腐败一方放的一律换成腐败的法阵。
        boolean field = kind.name().startsWith("FIELD_");
        if ((element == Element.LIFE || field) && CorruptionDamage.visual() && QueenSpellEffects.replace(world, kind.name(), variant, at, aux, scale, id, guise)) ci.cancel();
    }
}
