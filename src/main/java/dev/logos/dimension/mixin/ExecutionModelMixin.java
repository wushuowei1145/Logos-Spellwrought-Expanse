package dev.logos.dimension.mixin;

import dev.logos.dimension.client.execution.ExecutionClient;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 处决特写里人形的身体（玩家、僵尸、村民……连同盔甲）在半空张开四肢，挨打时往刀的反方向甩；
 * 飞身刺、撬剑时直接摆成给定的姿势（要分左右手，所以把整个渲染状态交过去）。
 */
@Mixin(HumanoidModel.class)
public abstract class ExecutionModelMixin {
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"))
    private void executionLimbs(HumanoidRenderState state, CallbackInfo ci) {
        ExecutionClient.Body body = state.getRenderData(ExecutionClient.BODY);
        if (body != null) ExecutionClient.limbs((HumanoidModel<?>) (Object) this, body, state);
    }
}
