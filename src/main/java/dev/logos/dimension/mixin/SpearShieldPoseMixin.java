package dev.logos.dimension.mixin;

import dev.logos.dimension.client.SpearShieldVisual;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 反手搭配把举盾经过时间同时交给副手原版长矛物品动画，不改变实际使用手。 */
@Mixin(HumanoidRenderState.class)
public abstract class SpearShieldPoseMixin {
    @Inject(method = "ticksUsingItem", at = @At("HEAD"), cancellable = true)
    private void offhandUseTime(HumanoidArm arm, CallbackInfoReturnable<Float> cir) {
        var state = (HumanoidRenderState) (Object) this;
        if (SpearShieldVisual.kineticActive(state) && arm != state.mainArm) cir.setReturnValue(state.ticksUsingItem);
    }
}
