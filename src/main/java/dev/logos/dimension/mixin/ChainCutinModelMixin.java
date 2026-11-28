package dev.logos.dimension.mixin;

import dev.logos.dimension.client.cutin.ChainCutinRenderer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 连携切入里的玩家摆出架势（{@link ChainCutinRenderer.HeroPose}）：模型照常摆完动作之后，直接定双臂、头和双腿的角度。
 * 只认渲染状态上的 {@link ChainCutinRenderer#PLAYER_POSE}，世界里的玩家不受影响。
 */
@Mixin(HumanoidModel.class)
public abstract class ChainCutinModelMixin {
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"))
    private void chainCutinPose(HumanoidRenderState state, CallbackInfo ci) {
        ChainCutinRenderer.HeroPose pose = state.getRenderData(ChainCutinRenderer.PLAYER_POSE);
        if (pose == null) return;
        HumanoidModel<?> model = (HumanoidModel<?>) (Object) this;
        boolean left = state.mainArm == HumanoidArm.LEFT;
        float mirror = left ? -1 : 1;
        (left ? model.leftArm : model.rightArm).setRotation(pose.mainArmX(), mirror * pose.mainArmY(), mirror * pose.mainArmZ());
        (left ? model.rightArm : model.leftArm).setRotation(pose.offArmX(), mirror * pose.offArmY(), mirror * pose.offArmZ());
        model.head.xRot += pose.headX();
        model.head.zRot += mirror * pose.headZ();
        (left ? model.leftLeg : model.rightLeg).xRot = pose.frontLegX();
        (left ? model.rightLeg : model.leftLeg).xRot = pose.backLegX();
    }
}
