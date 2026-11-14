package dev.logos.dimension.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.dimension.client.music.MusicianPoses;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 坐在星辰花琴凳上、站在工位上演奏的原版人形（玩家、僵尸、骷髅……）的身体真正画出来的地方。
 * 26.1 的 {@code LivingEntityRenderer.submit} 只把模型交出去，到这里画之前会按渲染状态再 setupAnim 一次，
 * {@link MusicianModelMixin} 在那边摆的手臂、腿只有乐器层用得上，身体（还有盔甲）又回到骑乘的姿势。所以画之前再摆一遍。
 */
@Mixin(ModelFeatureRenderer.class)
public abstract class MusicianDrawMixin {
    @Inject(method = "renderModel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/Model;setupAnim(Ljava/lang/Object;)V", shift = At.Shift.AFTER))
    private void logos$musicianPose(SubmitNodeStorage.ModelSubmit<?> submit, RenderType renderType, VertexConsumer buffer,
                                    OutlineBufferSource outlineBufferSource, MultiBufferSource.BufferSource crumblingBufferSource, CallbackInfo ci) {
        if (submit.model() instanceof HumanoidModel<?> humanoid && submit.state() instanceof HumanoidRenderState state) {
            MusicianPoses.afterSetupAnim(humanoid, state);
        }
    }
}
