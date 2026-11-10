package dev.logos.dimension.client.companion;

import com.geckolib.model.DefaultedEntityGeoModel;
import com.geckolib.renderer.GeoEntityRenderer;
import com.geckolib.renderer.base.GeoRenderState;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.NpcRenderer;
import dev.logos.dimension.companion.Companions;
import dev.logos.dimension.companion.Katashiro;
import dev.logos.dimension.companion.ShiraumeCompanion;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** 白梅同伴与形代的渲染（docs/SHIRAUME_COMPANION.md）。 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class CompanionClient {
    private CompanionClient() {}

    @SubscribeEvent
    static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(Companions.SHIRAUME.get(), CompanionRenderer::new);
        event.registerEntityRenderer(Companions.KATASHIRO.get(), KatashiroRenderer::new);
    }

    /**
     * 白梅：NPC 的通用渲染。入场最初几刻不画（{@code misc} 控制器有 2 刻过渡，免得先看到原尺寸的她）；
     * 蜷着睡时不叠加视线，头不跟着人转。
     */
    public static class CompanionRenderer<R extends LivingEntityRenderState & GeoRenderState> extends NpcRenderer<ShiraumeCompanion, R> {
        public CompanionRenderer(EntityRendererProvider.Context context) {
            super(context, "shiraume_companion");
        }

        @Override
        public boolean shouldRender(ShiraumeCompanion entity, Frustum frustum, double x, double y, double z) {
            return !entity.hiddenForSummon() && super.shouldRender(entity, frustum, x, y, z);
        }

        @Override
        public void addRenderData(ShiraumeCompanion animatable, Void relatedObject, R state, float partialTick) {
            super.addRenderData(animatable, relatedObject, state, partialTick);
            if (animatable.curled()) {
                state.xRot = 0;
                state.yRot = 0;
            }
        }
    }

    /** 形代：和她同样 2 倍制作、缩放 0.5，透明像素剪出轮廓，正反两面都画。 */
    public static class KatashiroRenderer<R extends EntityRenderState & GeoRenderState> extends GeoEntityRenderer<Katashiro, R> {
        public KatashiroRenderer(EntityRendererProvider.Context context) {
            super(context, new DefaultedEntityGeoModel<>(LogosDimension.id("shiraume_katashiro")));
            withScale(NpcRenderer.MODEL_SCALE);
            this.shadowRadius = 0;
        }

        @Override
        public RenderType getRenderType(R state, Identifier texture) {
            return RenderTypes.entityCutout(texture);
        }
    }
}
