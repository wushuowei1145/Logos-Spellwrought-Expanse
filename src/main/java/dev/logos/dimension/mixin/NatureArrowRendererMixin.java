package dev.logos.dimension.mixin;

import dev.logos.dimension.client.NatureArrowRendering;
import net.minecraft.client.renderer.entity.ArrowRenderer;
import net.minecraft.client.renderer.entity.state.ArrowRenderState;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** 共用原版箭模型和实体光照，只给带同步标记的箭换纹理。 */
@Mixin(ArrowRenderer.class)
public abstract class NatureArrowRendererMixin {
    @Shadow protected abstract Identifier getTextureLocation(ArrowRenderState state);

    @Redirect(method = "submit(Lnet/minecraft/client/renderer/entity/state/ArrowRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/ArrowRenderer;getTextureLocation(Lnet/minecraft/client/renderer/entity/state/ArrowRenderState;)Lnet/minecraft/resources/Identifier;"))
    private Identifier natureTexture(ArrowRenderer<?, ?> renderer, ArrowRenderState state) {
        return NatureArrowRendering.texture(state, getTextureLocation(state));
    }
}
