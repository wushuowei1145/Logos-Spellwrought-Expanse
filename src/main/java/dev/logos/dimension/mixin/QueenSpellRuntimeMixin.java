package dev.logos.dimension.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.logos.dimension.elven.blight.QueenSpellContext;
import dev.logos.runtime.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 公共接口没有单个施法者的色板参数；只给女王术式的执行范围加换色上下文，不改调度与伤害。 */
@Mixin(value = SpellRuntime.class, remap = false)
abstract class QueenSpellRuntimeMixin {
    @WrapMethod(method = "guard", require = 0)
    private static void queen$guard(SpellInstance inst, Runnable body, Operation<Void> original) { QueenSpellContext.run(inst.caster, () -> original.call(inst, body)); }
    @WrapOperation(method = "tickForms", at = @At(value = "INVOKE", target = "Ldev/logos/runtime/SpellObject;tick()V"), require = 0)
    private static void queen$form(SpellObject object, Operation<Void> original) {
        QueenSpellContext.run(((SpellObjectAccess) object).logos_dimension$inst().caster, () -> original.call(object));
    }
}
