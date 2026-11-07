package dev.logos.dimension.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.dimension.client.dissolve.Dissolves;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 物品的渲染状态记住谁拿着它；那只手上的东西在消散时，物品只画还留下的部分。 */
@Mixin(ItemStackRenderState.class)
public abstract class DissolveItemStateMixin implements Dissolves.HeldItem {
    @Shadow ItemDisplayContext displayContext;
    @Unique private int logos_dimension$owner = -1;

    @Override public int logos_dimension$owner() { return logos_dimension$owner; }

    @Override public void logos_dimension$owner(int owner) { logos_dimension$owner = owner; }

    @Override public ItemDisplayContext logos_dimension$context() { return displayContext; }

    @Inject(method = "clear", at = @At("HEAD"))
    private void dissolveClear(CallbackInfo ci) {
        logos_dimension$owner = -1;
    }

    @WrapMethod(method = "submit")
    private void dissolveSubmit(PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay, int outline, Operation<Void> original) {
        original.call(poseStack, Dissolves.itemCollector(collector, this, poseStack), light, overlay, outline);
    }
}
