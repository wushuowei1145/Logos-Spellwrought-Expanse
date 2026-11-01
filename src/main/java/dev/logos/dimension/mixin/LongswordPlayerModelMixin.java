package dev.logos.dimension.mixin;

import dev.logos.dimension.client.longsword.LongswordClient;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.joml.Matrix4f;
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
        var r = frame.sample().bodyRotation();
        float radians = (float) Math.PI / 180;
        float mirror = avatar.mainArm == net.minecraft.world.entity.HumanoidArm.LEFT ? -1 : 1;
        model.body.xRot = -r.x * radians; model.body.yRot = -r.y * radians * mirror; model.body.zRot = r.z * radians * mirror;
        var rotation = new Matrix4f().rotateZ(model.body.zRot).rotateY(model.body.yRot).rotateX(model.body.xRot);
        var offset = new Vector3f(0,6,0).sub(rotation.transformPosition(new Vector3f(0,6,0)));
        model.body.x += offset.x; model.body.y += offset.y; model.body.z += offset.z;
        model.head.x += offset.x; model.head.y += offset.y; model.head.z += offset.z;
    }
}
