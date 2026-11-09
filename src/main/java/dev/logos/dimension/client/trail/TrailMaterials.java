package dev.logos.dimension.client.trail;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.logos.dimension.LogosDimension;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;

/**
 * 每层刀光一条管线：层的参数（颜色、噪声、侵蚀、收窄、刃线……）写成着色器的 #define，所以一个着色器画所有样式，
 * 顶点里只带每帧会变的东西（年龄、横跨位置、路程、种子、淡出）。形状遮罩按边缘夹紧取，三张噪声按平铺取，都线性过滤。
 */
final class TrailMaterials {
    private static final Map<TrailStyle.Layer, RenderType> TYPES = new HashMap<>();
    private static int next;

    private TrailMaterials() {}

    static RenderType of(TrailStyle.Layer layer) {
        return TYPES.computeIfAbsent(layer, TrailMaterials::create);
    }

    /** 资源重载后样式会重新读，旧的材质不再用。 */
    static void clear() { TYPES.clear(); }

    private static RenderType create(TrailStyle.Layer l) {
        String key = "weapon_trail_" + next++;
        var builder = RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(LogosDimension.id("pipeline/" + key))
                .withVertexShader(LogosDimension.id("core/weapon_trail")).withFragmentShader(LogosDimension.id("core/weapon_trail"))
                .withSampler("Sampler0").withSampler("Sampler1").withSampler("Sampler2").withSampler("Sampler3")
                .withVertexFormat(DefaultVertexFormat.BLOCK, VertexFormat.Mode.QUADS)
                .withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA));
        l.defines().forEach(builder::withShaderDefine);
        return RenderType.create(key, RenderSetup.builder(builder.build())
                .withTexture("Sampler0", l.mask(), () -> RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR))
                .withTexture("Sampler1", l.erosionNoise(), () -> RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR))
                .withTexture("Sampler2", l.detailNoise(), () -> RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR))
                .withTexture("Sampler3", l.distortNoise(), () -> RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR))
                .createRenderSetup());
    }
}
