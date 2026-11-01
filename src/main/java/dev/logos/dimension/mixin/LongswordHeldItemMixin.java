package dev.logos.dimension.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.dimension.client.longsword.LongswordClient;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 双手占用期间关闭原版持物层，避免同一把剑重复出现。 */
@Mixin(ItemInHandLayer.class)
public abstract class LongswordHeldItemMixin {
    @Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/ArmedEntityRenderState;FF)V", at = @At("HEAD"), cancellable = true)
    private void longswordItem(PoseStack pose, SubmitNodeCollector collector, int light, ArmedEntityRenderState state, float yRot, float xRot, CallbackInfo ci) {
        if (state.getRenderData(LongswordClient.FRAME) != null) ci.cancel();
    }
}
