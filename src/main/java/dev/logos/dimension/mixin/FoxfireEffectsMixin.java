package dev.logos.dimension.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.logos.dimension.corruption.Corruption;
import dev.logos.dimension.corruption.CorruptionDamage;
import dev.logos.dimension.foxfire.Foxfire;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.dimension.foxfire.FoxfireWraps;
import dev.logos.lang.Element;
import dev.logos.runtime.Effects;
import dev.logos.runtime.SpellInstance;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 元素子类型的结算：狐火（奥秘，docs/FOXFIRE.md 第 2 节）和腐败（生命，docs/CORRUPTION.md「腐败伤害」）共用这几个入口。
 * 狐火照样走 Logos 的奥秘伤害，Logos 的三个入口各自定下这一下是不是狐火——狐火术式的奥秘伤害、{@link Foxfire#deal}、
 * 附属给的狐火普通缠绕——在里面把伤害来源换成狐火，打进去之后附带狐火的效果。腐败同理：腐败一方
 * （{@link CorruptionDamage#aligned}）打出的生命伤害换成腐败伤害来源。
 * 每个入口都重新定（不是就清掉），免得受伤时触发的别的伤害也被算进去。狐火的命中特效在 {@code RyukaBurstMixin} 里换，
 * 腐败的在 {@code CorruptionVfxMixin} 里换。
 */
@Mixin(value = Effects.class, remap = false)
abstract class FoxfireEffectsMixin {
    @WrapMethod(method = "damage(Ldev/logos/runtime/SpellInstance;Lnet/minecraft/world/entity/LivingEntity;DLdev/logos/lang/Element;)Z", require = 0)
    private static boolean logos_dimension$spell(SpellInstance inst, LivingEntity t, double amount, Element el, Operation<Boolean> original) {
        FoxfireFlame flame = el == Element.ARCANE ? Foxfire.flameOf(inst) : null;
        boolean corrupt = el == Element.LIFE && CorruptionDamage.aligned(inst.caster);
        return CorruptionDamage.within(corrupt, () -> Foxfire.within(flame == null ? null : new Foxfire.Context(flame, true), () -> original.call(inst, t, amount, el)));
    }

    @WrapMethod(method = "damage(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/LivingEntity;DLdev/logos/lang/Element;)Z", require = 0)
    private static boolean logos_dimension$creature(LivingEntity attacker, LivingEntity t, double amount, Element el, Operation<Boolean> original) {
        Foxfire.Context context = Foxfire.claim();
        boolean corrupt = el == Element.LIFE && CorruptionDamage.aligned(attacker);
        return CorruptionDamage.within(corrupt, () -> Foxfire.within(el == Element.ARCANE ? context : null, () -> original.call(attacker, t, amount, el)));
    }

    @WrapMethod(method = "followUp", require = 0)
    private static boolean logos_dimension$wrap(LivingEntity attacker, LivingEntity t, double amount, Element el, Operation<Boolean> original) {
        FoxfireFlame flame = el == Element.ARCANE ? FoxfireWraps.plain(attacker) : null;
        boolean corrupt = el == Element.LIFE && CorruptionDamage.aligned(attacker);
        return CorruptionDamage.within(corrupt, () -> Foxfire.within(flame == null ? null : new Foxfire.Context(flame, true), () -> original.call(attacker, t, amount, el)));
    }

    @WrapOperation(method = "hurt", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/damagesource/DamageSources;indirectMagic(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/damagesource/DamageSource;"),
            require = 0)
    private static DamageSource logos_dimension$source(DamageSources sources, Entity direct, Entity causing, Operation<DamageSource> original) {
        if (Foxfire.current() != null) return sources.source(Foxfire.DAMAGE, direct, causing);
        if (CorruptionDamage.current()) return sources.source(Corruption.DAMAGE, direct, causing);
        return original.call(sources, direct, causing);
    }

    @WrapOperation(method = "hurt", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;hurtServer(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)Z"),
            require = 0)
    private static boolean logos_dimension$landed(LivingEntity t, ServerLevel level, DamageSource source, float amount, Operation<Boolean> original) {
        boolean hurt = original.call(t, level, source, amount);
        if (hurt && source.is(Foxfire.DAMAGE)) Foxfire.landed(source, t);
        return hurt;
    }
}
