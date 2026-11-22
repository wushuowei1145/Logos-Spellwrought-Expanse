package dev.logos.dimension.client.jiansui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.jiansui.AxialSwordBlockEntity;
import dev.logos.dimension.jiansui.JianSuiBlocks;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.jspecify.annotations.Nullable;

/**
 * 遗剑：贯穿古剑倒插在地上，剑尖埋进地面下那一格，剑柄朝上，微微斜着；刚插下时剑身上一点淡青白的光，
 * {@value #FADE_TICKS} 刻里慢慢暗下去（docs/JIAN_SUI_BOSS.md 第 12 节）。摆法和 {@link JianSuiCueClient} 里崩飞后落下的那把一样，接得上。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public class AxialSwordRenderer implements BlockEntityRenderer<AxialSwordBlockEntity, AxialSwordRenderer.State> {
    static final float FADE_TICKS = 600;
    /** 剑格离地面多高之前的那一点抬高（格）。 */
    static final float LIFT = .2f;
    /** 斜着插的角度（度）。 */
    static final float TILT = 6;

    public static class State extends BlockEntityRenderState {
        float glow;
    }

    public AxialSwordRenderer(BlockEntityRendererProvider.Context context) {
    }

    @SubscribeEvent
    static void register(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(JianSuiBlocks.AXIAL_SWORD_ENTITY.get(), AxialSwordRenderer::new);
    }

    /** 从方块底面正中，把姿势摆到「倒插着的贯穿古剑」的网格坐标（模型单位，剑尖朝 +Y）。 */
    static void place(PoseStack pose) {
        pose.translate(0, LIFT, 0);
        pose.mulPose(Axis.ZP.rotationDegrees(TILT));
        pose.mulPose(Axis.XP.rotationDegrees(180));
        pose.scale(1f / 32, 1f / 32, 1f / 32);
        pose.translate(0, -24, 0);
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(AxialSwordBlockEntity sword, State state, float partialTicks, Vec3 camera,
                                   ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(sword, state, partialTicks, camera, breakProgress);
        float age = sword.getLevel() == null ? FADE_TICKS : (float) (sword.getLevel().getGameTime() - sword.placed()) + partialTicks;
        state.glow = age < 0 || age >= FADE_TICKS ? 0 : 1 - age / FADE_TICKS;
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        pose.pushPose();
        pose.translate(.5f, 0, .5f);
        place(pose);
        JianSuiSwords.submit(pose, collector, state.lightCoords, JianSuiSwords.AXIAL, 0xFFFFFFFF);
        if (state.glow > 0) JianSuiSwords.submitGlow(pose, collector, JianSuiSwords.AXIAL, Math.round(state.glow * state.glow * 150) << 24 | 0xC8F2FA);
        pose.popPose();
    }

    @Override
    public boolean shouldRenderOffScreen() {
        return true;
    }
}
