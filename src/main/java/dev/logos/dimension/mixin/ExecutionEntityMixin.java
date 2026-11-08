package dev.logos.dimension.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.dimension.client.execution.ExecutionClient;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 处决特写里被抛起、挨打、拍下的身体：取渲染状态时带上这一刻的姿态，画的时候整个实体绕身体中心转、再挪上去。
 * 实体本身的位置和判定都没动，所以任何生物（连同盔甲、手里的东西、名字）都能这样画。
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class ExecutionEntityMixin {
    @Inject(method = "extractEntity", at = @At("RETURN"))
    private <E extends Entity> void executionExtracted(E entity, float partialTicks, CallbackInfoReturnable<EntityRenderState> cir) {
        ExecutionClient.extracted(entity, cir.getReturnValue());
    }

    @WrapMethod(method = "submit")
    private void executionSubmit(EntityRenderState state, CameraRenderState camera, double x, double y, double z, PoseStack pose, SubmitNodeCollector collector, Operation<Void> original) {
        ExecutionClient.Body body = state.getRenderData(ExecutionClient.BODY);
        if (body == null) {
            original.call(state, camera, x, y, z, pose, collector);
            return;
        }
        // 原版会再平移到 (x, y, z)；先在外面套一层「绕身体中心转、再抬起来」，实体就整个按这个姿态画
        pose.pushPose();
        pose.translate(x + body.lift().x, y + body.lift().y + body.pivot(), z + body.lift().z);
        pose.mulPose(body.rotation());
        pose.translate(-x, -y - body.pivot(), -z);
        original.call(state, camera, x, y, z, pose, collector);
        pose.popPose();
    }
}
