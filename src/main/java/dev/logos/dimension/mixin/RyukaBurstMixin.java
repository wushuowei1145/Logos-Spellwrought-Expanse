package dev.logos.dimension.mixin;

import dev.logos.dimension.foxfire.Foxfire;
import dev.logos.dimension.foxfire.FoxfireCue;
import dev.logos.dimension.ryuka.Ryuka;
import dev.logos.lang.Element;
import dev.logos.runtime.Vfx;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 流火的每一刀都经 Logos 的元素伤害结算，Logos 每次命中都放一团奥秘冲击特效；流火出刀极快，第一人称画面会被白光盖住。
 * 只在结算流火这一下（{@link Ryuka#striking}）时不放这团命中特效，换成流火自己的一小簇樱花（{@link Ryuka#strike}）；
 * 白梅（{@code ShiraumeStrike}）的刀和牙打出的奥秘伤害也不放。狐火（docs/FOXFIRE.md）不放奥秘的这一团，换成狐火颜色的一团
 * （缠身每秒烧的那一下什么都不放）。Logos 别的伤害照常。Logos 改了这个方法时注入不上（{@code require = 0}），退回原样，不会崩。
 */
@Mixin(value = Vfx.class, remap = false)
public abstract class RyukaBurstMixin {
    @Inject(method = "burst(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Ldev/logos/lang/Element;Ldev/logos/runtime/Vfx$Burst;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void logos_dimension$quietRyuka(ServerLevel level, Entity entity, Vec3 at, Element element, Vfx.Burst kind, CallbackInfo ci) {
        if (kind != Vfx.Burst.DAMAGE) return;
        if (Ryuka.striking()) { Ryuka.quieted(); ci.cancel(); }
        // 白梅的刀和牙也是奥秘伤害，同样不放这团命中特效（docs/SHIRAUME_BOSS.md 第 4.3 节）
        else if (dev.logos.dimension.shiraume.ShiraumeStrike.striking()) ci.cancel();
        else if (Foxfire.current() instanceof Foxfire.Context fox) {
            ci.cancel();
            if (fox.rules()) FoxfireCue.hit(level, entity, at, fox.flame());
        }
    }
}
