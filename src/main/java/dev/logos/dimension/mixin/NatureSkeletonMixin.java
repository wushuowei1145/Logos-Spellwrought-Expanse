package dev.logos.dimension.mixin;

import dev.logos.dimension.item.NaturesForceItem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 骷髅持有自然之力时使用弓自己的发射参数，不走固定的原版 1.6 速度。 */
@Mixin(AbstractSkeleton.class)
public abstract class NatureSkeletonMixin {
    @Inject(method = "performRangedAttack", at = @At("HEAD"), cancellable = true)
    private void natureShoot(LivingEntity target, float power, CallbackInfo ci) {
        var skeleton = (AbstractSkeleton) (Object) this;
        var hand = ProjectileUtil.getWeaponHoldingHand(skeleton, item -> item instanceof NaturesForceItem);
        var stack = skeleton.getItemInHand(hand);
        if (stack.getItem() instanceof NaturesForceItem bow && skeleton.level() instanceof ServerLevel level) {
            bow.shootMob(level, skeleton, target, stack, hand);
            ci.cancel();
        }
    }
}
