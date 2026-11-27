package dev.logos.dimension.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.logos.dimension.foxfire.Foxfire;
import dev.logos.dimension.foxfire.FoxfireCue;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.lang.Element;
import dev.logos.lang.Spell;
import dev.logos.runtime.Casting;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 狐火（龙炎）术式施放时，脚下那一圈换成狐火（龙炎）的颜色（Logos 按奥秘画的施法圈不画）。 */
@Mixin(value = Casting.class, remap = false)
abstract class FoxfireCastMixin {
    @WrapOperation(method = "run", at = @At(value = "INVOKE", target = "Ldev/logos/runtime/Vfx;cast(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/phys/Vec3;Ldev/logos/lang/Element;)V"), require = 0)
    private static void logos_dimension$cast(ServerLevel level, Vec3 at, Element element, Operation<Void> original,
                                             @Local(argsOnly = true) LivingEntity caster, @Local(argsOnly = true) Spell spell) {
        FoxfireFlame flame = element == Element.ARCANE ? Foxfire.flameOf(spell, caster) : null;
        if (flame != null) FoxfireCue.cast(level, at, flame);
        else original.call(level, at, element);
    }
}
