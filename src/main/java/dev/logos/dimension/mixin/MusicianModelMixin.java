package dev.logos.dimension.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.dimension.client.music.MusicianPoses;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 坐在星辰花琴凳上演奏的人形（玩家、僵尸、骷髅……）：在模型自己的 setupAnim 全部跑完之后再摆双臂。
 * 僵尸之类的模型在 setupAnim 末尾还会再抬一次手，所以不能放在 HumanoidModel.setupAnim 里。只在渲染状态带着演奏者时起作用。
 */
@Mixin(LivingEntityRenderer.class)
public abstract class MusicianModelMixin {
    @Shadow protected EntityModel<?> model;

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Ljava/lang/Object;)V", shift = At.Shift.AFTER))
    private void musicianArms(LivingEntityRenderState state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
        if (model instanceof HumanoidModel<?> humanoid && state instanceof HumanoidRenderState humanoidState) {
            MusicianPoses.afterSetupAnim(humanoid, humanoidState);
        }
    }
}
