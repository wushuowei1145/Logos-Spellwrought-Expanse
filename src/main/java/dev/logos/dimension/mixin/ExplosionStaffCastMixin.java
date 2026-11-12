package dev.logos.dimension.mixin;

import dev.logos.dimension.explosion.ExplosionStaffPage;
import dev.logos.lang.Spell;
import dev.logos.runtime.Casting;
import dev.logos.runtime.SpellInstance;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 不是玩家的施法者（车万女仆）拿着爆裂魔杖施放它的载页时，不跑载页上的术式，换成真正的爆裂魔法（{@link ExplosionStaffPage#cast}）。
 * Logos 改了 {@code Casting.run} 时注入不上，女仆就照常放载页上那枚小魔弹，不会崩。
 */
@Mixin(value = Casting.class, remap = false)
abstract class ExplosionStaffCastMixin {
    @Inject(method = "run", at = @At("HEAD"), cancellable = true, require = 0)
    private static void logos_dimension$explosion(LivingEntity caster, Spell spell, double mana, double recovery, @Nullable Entity target,
                                                  @Nullable Vec3 here, boolean charged, CallbackInfoReturnable<SpellInstance> cir) {
        if (!ExplosionStaffPage.cast(caster, spell, mana, target, here)) return;
        // 交回一个什么都没做、已经结束的术式：调用方照常拿到返回值
        SpellInstance done = new SpellInstance(spell, caster, (ServerLevel) caster.level(), 0, recovery);
        done.release();
        cir.setReturnValue(done);
    }
}
