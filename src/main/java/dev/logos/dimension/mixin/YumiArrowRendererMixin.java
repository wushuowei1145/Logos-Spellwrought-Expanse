package dev.logos.dimension.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.dimension.client.YumiClient;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ArrowRenderer;
import net.minecraft.client.renderer.entity.state.ArrowRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 和弓射出的箭画成和弓的矢，不画原版的十字箭模型；其他箭照常。 */
@Mixin(ArrowRenderer.class)
public abstract class YumiArrowRendererMixin {
    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/ArrowRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At("HEAD"), cancellable = true)
    private void yumiArrow(ArrowRenderState state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
        if (YumiClient.submitArrow(state, pose, collector)) ci.cancel();
    }
}
