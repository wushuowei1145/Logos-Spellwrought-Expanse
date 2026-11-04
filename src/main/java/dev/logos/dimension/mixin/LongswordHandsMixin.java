package dev.logos.dimension.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.dimension.client.longsword.SwordCastClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 第一人称手部渲染开始时记下姿势，施法剑据此把剑尖换到世界坐标（只读，不改渲染）。 */
@Mixin(ItemInHandRenderer.class)
public abstract class LongswordHandsMixin {
    @Inject(method = "renderHandsWithItems", at = @At("HEAD"))
    private void longswordHandsBegin(float frameInterp, PoseStack poseStack, SubmitNodeCollector collector, LocalPlayer player, int light, CallbackInfo ci) {
        SwordCastClient.beginHands(poseStack.last().pose());
    }
}
