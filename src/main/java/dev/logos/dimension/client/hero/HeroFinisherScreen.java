package dev.logos.dimension.client.hero;

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
import dev.logos.dimension.client.screenfx.PostVfx;
import dev.logos.dimension.ultimate.HeroFinisherTimeline;
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * 最后一击自己的全屏后期（{@code UltimateShow#screen}，在过场叠层之前画），只在下令的玩家的画面上：
 * 开演几刻压黑（{@link HeroFinisherTimeline#exposure}），Cut 1 全黑里正中一朵暖金白芯的火苗和几点慢慢浮起的火星，
 * Cut 2 曝光慢慢抬起来、带一点暮色的深蓝，火苗让给世界里掌心的那团火。
 *
 * <p>火苗是画在屏幕上的图形，但钉在掌心投到屏幕上的那一点、跟着它走（docs/CINEMATICS.md「不要贴在屏幕上的纹理」）。
 * 压黑是一次缓慢的变化；开了「隐藏闪电的闪烁」时压黑用 {@link HeroFinisherTimeline#BLACK_IN_GENTLE} 刻。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class HeroFinisherScreen {
    private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(LogosDimension.id("pipeline/hero_finisher"))
            .withVertexShader(LogosDimension.id("post/hero_finisher"))
            .withFragmentShader(LogosDimension.id("post/hero_finisher"))
            .withSampler("InSampler")
            .withUniform("HeroFinisher", UniformType.UNIFORM_BUFFER)
            .build();
    private static final int VEC4S = 4;
    private static final int UNIFORM_SIZE;

    static {
        Std140SizeCalculator size = new Std140SizeCalculator();
        for (int i = 0; i < VEC4S; i++) size.putVec4();
        UNIFORM_SIZE = size.get();
    }

    private static @Nullable RenderTarget copy;
    private static @Nullable MappableRingBuffer uniforms;
    /** 画了几次（客户端场景检查）。 */
    private static long passes;

    private HeroFinisherScreen() {}

    @SubscribeEvent
    static void pipelines(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(PIPELINE);
    }

    public static long passes() {
        return passes;
    }

    /** 这一帧的压黑和火苗；第 {@code age} 刻已经全亮、没有火苗时什么都不画。 */
    static void draw(HeroFinisherShow show, float age) {
        Minecraft mc = Minecraft.getInstance();
        boolean gentle = mc.options.hideLightningFlash().get();
        float exposure = HeroFinisherTimeline.exposure(age, gentle);
        float flame = HeroFinisherTimeline.flame(age);
        if (exposure >= 1 && flame <= 0) return;
        // 暮色：曝光抬到一半时最蓝
        float dusk = age >= HeroFinisherTimeline.REVEAL ? 4 * exposure * (1 - exposure) * .6f : 0;
        float partial = age - (float) Math.floor(age);
        float[] at = project(show.palm(show.feet(partial)));
        RenderTarget main = mc.getMainRenderTarget();
        pass(main, age, show.cast.seed(), exposure, dusk, at == null ? 0 : flame, at == null ? .5f : at[0], at == null ? .5f : at[1]);
        passes++;
    }

    /** 世界里的一点投到画面上（0–1，左下是 0）；在镜头后面时是 null。 */
    private static float @Nullable [] project(Vec3 world) {
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 cam = camera.position();
        Vector4f v = PostVfx.viewProjection().transform(new Vector4f((float) (world.x - cam.x), (float) (world.y - cam.y), (float) (world.z - cam.z), 1));
        if (v.w <= 1e-3f) return null;
        return new float[]{v.x / v.w * .5f + .5f, v.y / v.w * .5f + .5f};
    }

    private static RenderTarget copy(RenderTarget main) {
        if (copy == null) {
            copy = new TextureTarget("logos_dimension hero finisher", main.width, main.height, false, false);
        } else if (copy.width != main.width || copy.height != main.height) {
            copy.resize(main.width, main.height);
        }
        return copy;
    }

    private static void pass(RenderTarget main, float age, long seed, float exposure, float dusk, float flame, float x, float y) {
        RenderTarget input = copy(main);
        if (uniforms == null)
            uniforms = new MappableRingBuffer(() -> "logos_dimension hero finisher", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UNIFORM_SIZE);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToTexture(main.getColorTexture(), input.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
        try (GpuBuffer.MappedView view = encoder.mapBuffer(uniforms.currentBuffer(), false, true)) {
            Std140Builder b = Std140Builder.intoBuffer(view.data());
            b.putVec4(main.width, main.height, age, seed & 0xFFF);
            b.putVec4(exposure, dusk, flame, .13f);
            b.putVec4(x, y, 0, 0);
            int gold = HeroFinisherTimeline.GOLD;
            b.putVec4(((gold >> 16) & 0xFF) / 255f, ((gold >> 8) & 0xFF) / 255f, (gold & 0xFF) / 255f, 1);
        }
        var linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        try (RenderPass pass = encoder.createRenderPass(() -> "logos_dimension hero finisher", main.getColorTextureView(), OptionalInt.empty())) {
            pass.setPipeline(PIPELINE);
            pass.setUniform("HeroFinisher", uniforms.currentBuffer());
            pass.bindTexture("InSampler", input.getColorTextureView(), linear);
            pass.draw(0, 3);
        }
        uniforms.rotate();
    }
}
