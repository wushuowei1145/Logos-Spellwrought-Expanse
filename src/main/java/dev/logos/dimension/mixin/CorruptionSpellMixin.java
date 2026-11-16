package dev.logos.dimension.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.logos.dimension.corruption.CorruptionDamage;
import dev.logos.runtime.SpellInstance;
import dev.logos.runtime.SpellObject;
import dev.logos.runtime.SpellRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 标出腐败一方的法术在执行（docs/CORRUPTION.md「腐败伤害」）：符文执行、事件触发走 {@code guard}，飞行中的形体走
 * {@code tickForms} 里的 {@code tick}。只有施法者是腐败一方时才进上下文；别的法术直接调原方法，不多开闭包、不碰 ThreadLocal。
 * 只为换特效（{@code CorruptionVfxMixin}），不改调度与伤害。
 */
@Mixin(value = SpellRuntime.class, remap = false)
abstract class CorruptionSpellMixin {
    @WrapMethod(method = "guard", require = 0)
    private static void logos_dimension$guard(SpellInstance inst, Runnable body, Operation<Void> original) {
        if (!CorruptionDamage.aligned(inst.caster)) { original.call(inst, body); return; }
        CorruptionDamage.inSpell(true, () -> original.call(inst, body));
    }

    @WrapOperation(method = "tickForms", at = @At(value = "INVOKE", target = "Ldev/logos/runtime/SpellObject;tick()V"), require = 0)
    private static void logos_dimension$form(SpellObject object, Operation<Void> original) {
        if (!CorruptionDamage.aligned(((SpellObjectAccess) object).logos_dimension$inst().caster)) { original.call(object); return; }
        CorruptionDamage.inSpell(true, () -> original.call(object));
    }
}
