package dev.logos.dimension.client;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.item.NatureArrow;
import net.minecraft.client.renderer.entity.TippableArrowRenderer;
import net.minecraft.client.renderer.entity.SpectralArrowRenderer;
import net.minecraft.client.renderer.entity.state.ArrowRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextKey;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent;

/** 在 NeoForge 清空每帧扩展数据之后收集标记，供原版箭渲染器选择纹理。 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class NatureArrowRendering {
    private static final ContextKey<Boolean> NATURE_ARROW = new ContextKey<>(LogosDimension.id("nature_arrow"));
    public static final Identifier TEXTURE = LogosDimension.id("textures/entity/projectiles/natures_force_arrow.png");

    @SubscribeEvent static void register(RegisterRenderStateModifiersEvent event) {
        event.registerEntityModifier(TippableArrowRenderer.class,
                (arrow, state) -> state.setRenderData(NATURE_ARROW, ((NatureArrow) arrow).natureStage() > 0));
        event.registerEntityModifier(SpectralArrowRenderer.class,
                (arrow, state) -> state.setRenderData(NATURE_ARROW, ((NatureArrow) arrow).natureStage() > 0));
    }

    public static Identifier texture(ArrowRenderState state, Identifier fallback) {
        return Boolean.TRUE.equals(state.getRenderData(NATURE_ARROW)) ? TEXTURE : fallback;
    }
    private NatureArrowRendering() {}
}
