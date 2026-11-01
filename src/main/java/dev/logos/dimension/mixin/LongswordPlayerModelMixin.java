package dev.logos.dimension.mixin;

import dev.logos.dimension.client.longsword.LongswordClient;
import dev.logos.dimension.longsword.LongswordStance;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 原版玩家与盔甲只保留躯干，双臂由双手剑骨架按真实玩家皮肤提交。 */
@Mixin(HumanoidModel.class)
public abstract class LongswordPlayerModelMixin {
    @Unique private boolean longswordHiddenArms;
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"))
    private void longswordPose(HumanoidRenderState state, CallbackInfo ci) {
        if (!(state instanceof AvatarRenderState avatar)) return;
        var frame = state.getRenderData(LongswordClient.FRAME);
        var model = (HumanoidModel<?>) (Object) this;
        if (frame == null) {
            if (longswordHiddenArms) {
                model.rightArm.visible = !avatar.isSpectator; model.leftArm.visible = !avatar.isSpectator;
                longswordHiddenArms = false;
            }
            return;
        }
        model.rightArm.visible = false; model.leftArm.visible = false;
        longswordHiddenArms = true;
        var pose=LongswordStance.body(frame.sample().bodyRotation(),avatar.mainArm==net.minecraft.world.entity.HumanoidArm.LEFT,state.isCrouching);
        var position=pose.transform().getTranslation(new Vector3f());
        var rotation=pose.transform().getEulerAnglesZYX(new Vector3f());
        model.body.setPos(position.x,position.y,position.z);
        model.body.setRotation(rotation.x,rotation.y,rotation.z);
        var neck=pose.neckOffset();
        model.head.x+=neck.x;model.head.y+=neck.y;model.head.z+=neck.z;
    }
}
