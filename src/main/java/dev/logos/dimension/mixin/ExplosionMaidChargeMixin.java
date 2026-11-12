package dev.logos.dimension.mixin;

import dev.logos.dimension.explosion.ExplosionStaffPage;
import dev.logos.runtime.ManaVessel;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 车万女仆（Logos 的女仆兼容 {@code compat/tlm/CastSpells}）拿着爆裂魔杖时，魔力池里攒够 6400 才开始蓄力：
 * 爆裂魔法必须注满才放，上限不到 6400 的女仆就一直不放。没装车万女仆时这个类从不加载，注入也就不发生；
 * Logos 改了 {@code begin} 时注入不上，女仆会拿现有的魔力去蓄，不满时注入的魔力退回她的魔力池（见 {@link ExplosionStaffPage#cast}）。
 */
@Pseudo
@Mixin(targets = "dev.logos.compat.tlm.CastSpells", remap = false)
abstract class ExplosionMaidChargeMixin {
    @Inject(method = "begin", at = @At("HEAD"), cancellable = true, require = 0)
    private void logos_dimension$waitForFull(@Coerce LivingEntity maid, @Coerce ManaVessel pool, @Coerce Object page, @Coerce Object with,
                                             LivingEntity target, CallbackInfo ci) {
        if (ExplosionStaffPage.waiting(maid, pool)) ci.cancel();
    }
}
