package dev.logos.dimension.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.logos.dimension.client.screenfx.GlowBloom;
import dev.logos.dimension.client.screenfx.ScreenEffectRenderer;
import dev.logos.dimension.client.screenfx.WorldStyleRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.Projection;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 全屏后期插在世界（含光影包的合成和手）画完之后、界面之前，紧跟原版自己的后期。
 * 这时主画面已经是最终结果，和谁画的地形、用没用光影都没有关系。
 */
@Mixin(GameRenderer.class)
public abstract class ScreenEffectMixin {
    @Shadow @Final private Projection hudProjection;

    /** 记下世界渲染真正用的投影矩阵，含视角晃动和受伤倾斜。 */
    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/ProjectionType;)V", ordinal = 0))
    private void screenEffectProjection(DeltaTracker deltaTracker, CallbackInfo ci, @Local(ordinal = 0) Matrix4f projection) {
        WorldStyleRenderer.PROJECTION.set(projection);
        GlowBloom.begin();
        dev.logos.dimension.client.screenfx.PostVfx.begin(dev.logos.dimension.client.explosion.ExplosionClient.active());
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"))
    private void screenEffectDraw(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        if (!advanceGameTime) return;
        // 爆裂魔法的调色和球体最先画，在后期里画的特效紧跟其后，不会被调暗
        dev.logos.dimension.client.explosion.ExplosionScreen.draw();
        // 泛光排在前面：画风和黑白闪处理的是已经带泛光的画面
        GlowBloom.draw(hudProjection.getMatrix(new Matrix4f()));
        WorldStyleRenderer.draw();
        ScreenEffectRenderer.draw();
    }
}
