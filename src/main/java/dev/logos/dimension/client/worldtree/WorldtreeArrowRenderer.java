package dev.logos.dimension.client.worldtree;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.worldtree.WorldtreeArrow;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.object.projectile.ArrowModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ArrowRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.ArrowRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

/** 世界树之弓的箭：原版箭的网格，贴图照世界树之箭画；追踪箭是半透明自发光的虚影。 */
public class WorldtreeArrowRenderer extends ArrowRenderer<WorldtreeArrow, WorldtreeArrowRenderer.State> {
    private static final Identifier SOLID = LogosDimension.id("textures/entity/projectiles/worldtree_arrow.png");
    private static final Identifier PHANTOM = LogosDimension.id("textures/entity/projectiles/worldtree_arrow_phantom.png");
    private final ArrowModel model;

    public static class State extends ArrowRenderState {
        public boolean phantom;
    }

    public WorldtreeArrowRenderer(EntityRendererProvider.Context context) {
        super(context);
        model = new ArrowModel(context.bakeLayer(ModelLayers.ARROW));
    }

    @Override public State createRenderState() { return new State(); }

    @Override
    public void extractRenderState(WorldtreeArrow arrow, State state, float partialTick) {
        super.extractRenderState(arrow, state, partialTick);
        state.phantom = arrow.phantom();
    }

    @Override
    protected Identifier getTextureLocation(State state) {
        return state.phantom ? PHANTOM : SOLID;
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        if (!state.phantom) {
            super.submit(state, pose, collector, camera);
            return;
        }
        // 与原版箭相同的朝向，只是半透明、自发光
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(state.yRot - 90));
        pose.mulPose(Axis.ZP.rotationDegrees(state.xRot));
        collector.submitModel(model, state, pose, RenderTypes.entityTranslucentEmissive(PHANTOM), 0xF000F0, OverlayTexture.NO_OVERLAY, state.outlineColor, null);
        pose.popPose();
    }
}
