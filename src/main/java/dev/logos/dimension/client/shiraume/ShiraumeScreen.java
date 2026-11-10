package dev.logos.dimension.client.shiraume;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import dev.logos.dimension.LogosDimension;
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import org.jspecify.annotations.Nullable;

/**
 * 白梅战的全屏通道（docs/SHIRAUME_BOSS.md 第 9 节）：断封印、弹反、拍地时的色散，封印全断之后封印之间里高光转红，
 * 以及缩地、处决冲刺、斩法扑击时朝她汇聚的速度线与径向模糊。
 * 由 {@code ScreenEffectMixin} 在泛光之后、画风与黑白闪之前调用；只读最终画面的颜色，不碰光影包的缓冲。
 * 三样都没有时一个通道都不跑。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class ShiraumeScreen {
    private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(LogosDimension.id("pipeline/shiraume"))
            .withVertexShader(LogosDimension.id("post/shiraume"))
            .withFragmentShader(LogosDimension.id("post/shiraume"))
            .withSampler("InSampler")
            .withUniform("Shiraume", UniformType.UNIFORM_BUFFER)
            .build();
    private static final int UNIFORM_SIZE = new Std140SizeCalculator().putVec4().putVec4().get();
    private static @Nullable RenderTarget copy;
    private static @Nullable MappableRingBuffer uniforms;
    private static long passes;

    private ShiraumeScreen() {}

    @SubscribeEvent
    static void pipelines(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(PIPELINE);
    }

    /** 通道一共跑了多少次（客户端场景检查用）。 */
    public static long passes() {
        return passes;
    }

    /** 由 Mixin 在世界画完之后调用。 */
    public static void draw() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float split = ShiraumeClient.aberration();
        float mood = ShiraumeClient.mood(mc.gameRenderer.getMainCamera().position(), pt);
        float[] speed = ShiraumeClient.speed(pt);
        if (split < .002f && mood < .002f && speed[0] < .002f) return;
        RenderTarget main = mc.getMainRenderTarget();
        RenderTarget target = target(main);
        if (uniforms == null) uniforms = new MappableRingBuffer(() -> "logos_dimension shiraume", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UNIFORM_SIZE);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToTexture(main.getColorTexture(), target.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
        try (GpuBuffer.MappedView view = encoder.mapBuffer(uniforms.currentBuffer(), false, true)) {
            Std140Builder.intoBuffer(view.data()).putVec4(split, mood, (float) main.width / main.height, (mc.level.getGameTime() + pt) / 20f)
                    .putVec4(speed[0], speed[1], speed[2], 0);
        }
        try (RenderPass pass = encoder.createRenderPass(() -> "logos_dimension shiraume", main.getColorTextureView(), OptionalInt.empty())) {
            pass.setPipeline(PIPELINE);
            pass.setUniform("Shiraume", uniforms.currentBuffer());
            pass.bindTexture("InSampler", target.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.draw(0, 3);
        }
        uniforms.rotate();
        passes++;
    }

    private static RenderTarget target(RenderTarget main) {
        if (copy == null) {
            copy = new TextureTarget("logos_dimension shiraume", main.width, main.height, false);
        } else if (copy.width != main.width || copy.height != main.height) {
            copy.resize(main.width, main.height);
        }
        return copy;
    }
}
