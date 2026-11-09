package dev.logos.dimension.mixin;

import java.util.List;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.logos.dimension.foxfire.Foxfire;
import dev.logos.lang.Node;
import dev.logos.runtime.Ctx;
import dev.logos.runtime.Interp;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Logos 执行一段符文、触发一个事件时，记下它属于哪种狐火术式（{@link Foxfire#inSpell}），
 * 符文自己放的奥秘特效（例如魔力缠绕缠上手时那一团）就换成狐火的（{@code FoxfireVfxMixin}）。
 */
@Mixin(value = Interp.class, remap = false)
abstract class FoxfireInterpMixin {
    @WrapMethod(method = "run", require = 0)
    private static void logos_dimension$run(List<Node> stmts, Ctx c, Operation<Void> original) {
        Foxfire.inSpell(Foxfire.flameOf(c.inst()), () -> original.call(stmts, c));
    }

    @WrapMethod(method = "fire", require = 0)
    private static void logos_dimension$fire(Node n, String event, Ctx c, Operation<Void> original) {
        Foxfire.inSpell(Foxfire.flameOf(c.inst()), () -> original.call(n, event, c));
    }
}
