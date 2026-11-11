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
        dev.logos.dimension.client.screenfx.PostVfx.begin(dev.logos.dimension.client.explosion.ExplosionClient.active()
                || dev.logos.dimension.client.vfx.DawnvowScreen.active());
        dev.logos.dimension.client.vfx.DawnvowScreen.beginFrame();
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"))
    private void screenEffectDraw(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        if (!advanceGameTime) return;
        // 爆裂魔法、曦誓的调色和全屏特效最先画，在后期里画的特效紧跟其后，不会被调暗
        // 超电磁炮发射时的压暗也在泛光之前，射线的光是后加的
        dev.logos.dimension.client.vfx.RailgunScreen.draw();
        Matrix4f viewProjection = dev.logos.dimension.client.screenfx.PostVfx.viewProjection();
        dev.logos.dimension.client.explosion.ExplosionScreen.draw(viewProjection);
        dev.logos.dimension.client.vfx.DawnvowScreen.draw(viewProjection);
        dev.logos.dimension.client.screenfx.PostVfx.draw(viewProjection);
        // 泛光排在前面：画风和黑白闪处理的是已经带泛光的画面
        GlowBloom.draw(hudProjection.getMatrix(new Matrix4f()));
        // 指挥选中的声部的描边：当成画面的一部分，后面的全屏效果照样作用在它上面
        dev.logos.dimension.client.music.PartOutline.draw();
        // 白梅战的色散与红光：在泛光之后（红晕和刀光一起被分开、被染红），画风与黑白闪之前
        dev.logos.dimension.client.shiraume.ShiraumeScreen.draw();
        WorldStyleRenderer.draw();
        // 处决特写把整个画面换成平涂的颜色，黑白闪和蒙版还能叠在它上面
        dev.logos.dimension.client.execution.ExecutionRenderer.draw();
        ScreenEffectRenderer.draw();
    }
}
