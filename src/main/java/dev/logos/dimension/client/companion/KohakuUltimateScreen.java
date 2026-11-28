package dev.logos.dimension.client.companion;

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
 * 琥珀奥义自己的全屏后期（docs/KOHAKU_COMPANION.md 第 20 节）：凝时的反色、色散、波纹，画面坍缩凝进琥珀，晶石、轻弹的裂纹和四芒星，
 * 全屏 Voronoi 碎镜，碎片飞散。参数见 {@link KohakuUltimateLook}，着色器 {@code shaders/post/kohaku_ultimate.fsh}。
 *
 * <p>由她的 {@code UltimateShow#screen} 每帧调（框架在所有后期之后、过场叠层之前调它），不改公共的渲染顺序。
 * 只读两样东西：此刻的主画面，和凝时那一刻存下的一帧（{@link #capture}）。不读深度，Sodium、Iris 和光影包下走的是同一条路。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class KohakuUltimateScreen {
    private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(LogosDimension.id("pipeline/kohaku_ultimate"))
            .withVertexShader(LogosDimension.id("post/kohaku_ultimate"))
            .withFragmentShader(LogosDimension.id("post/kohaku_ultimate"))
            .withSampler("InSampler")
            .withSampler("FrozenSampler")
            .withUniform("KohakuUltimate", UniformType.UNIFORM_BUFFER)
            .build();
    private static final int VEC4S = 7;
    private static final int UNIFORM_SIZE;

    static {
        Std140SizeCalculator size = new Std140SizeCalculator();
        for (int i = 0; i < VEC4S; i++) size.putVec4();
        UNIFORM_SIZE = size.get();
    }

    private static @Nullable RenderTarget copy, frozen;
    private static @Nullable MappableRingBuffer uniforms;
    /** 画了几次、抓了几次（客户端场景检查）。 */
    private static long passes, captures;

    private KohakuUltimateScreen() {}

    @SubscribeEvent
    static void pipelines(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(PIPELINE);
    }

    public static long passes() {
        return passes;
    }

    public static long captures() {
        return captures;
    }

    /** 这一帧在画面上的晶石：中心（画面 0–1，左下是 0）、半高（画面高的几成）、歪几度（弧度）。 */
    public record Gem(float x, float y, float radius, float tilt) {
        public static final Gem CENTER = new Gem(.5f, .5f, .2f, 0);
    }

    /** 把此刻的主画面存下来（凝时那一刻的敌群）。 */
    static void capture() {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        RenderTarget target = frozen(main);
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(main.getColorTexture(), target.getColorTexture(), 0, 0, 0, 0, 0,
                main.width, main.height);
        captures++;
    }

    /** 画一遍：{@code ripple} 是波纹的中心（画面 0–1），{@code amber} 是琥珀色。 */
    static void draw(KohakuUltimateLook look, float age, long seed, float rippleX, float rippleY, Gem gem, int amber) {
        if (!look.active()) return;
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        RenderTarget input = copy(main);
        RenderTarget still = frozen(main);
        if (uniforms == null)
            uniforms = new MappableRingBuffer(() -> "logos_dimension kohaku ultimate", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UNIFORM_SIZE);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToTexture(main.getColorTexture(), input.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
        try (GpuBuffer.MappedView view = encoder.mapBuffer(uniforms.currentBuffer(), false, true)) {
            Std140Builder b = Std140Builder.intoBuffer(view.data());
            b.putVec4(main.width, main.height, age, seed & 0xFFF);
            b.putVec4(look.invert(), look.split(), look.ripple(), look.tint());
            b.putVec4(look.collapse(), look.gem(), look.crack(), look.glint());
            b.putVec4(look.shatter(), look.spread(), look.burst(), look.frozen() ? 1 : 0);
            b.putVec4(rippleX, rippleY, gem.x(), gem.y());
            b.putVec4(gem.radius(), look.centered() ? 1 : 0, gem.tilt(), 0);
            b.putVec4(((amber >> 16) & 0xFF) / 255f, ((amber >> 8) & 0xFF) / 255f, (amber & 0xFF) / 255f, 0);
        }
        var linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        try (RenderPass pass = encoder.createRenderPass(() -> "logos_dimension kohaku ultimate", main.getColorTextureView(), OptionalInt.empty())) {
            pass.setPipeline(PIPELINE);
            pass.setUniform("KohakuUltimate", uniforms.currentBuffer());
            pass.bindTexture("InSampler", input.getColorTextureView(), linear);
            pass.bindTexture("FrozenSampler", still.getColorTextureView(), linear);
            pass.draw(0, 3);
        }
        uniforms.rotate();
        passes++;
    }

    private static RenderTarget copy(RenderTarget main) {
        if (copy == null) copy = new TextureTarget("logos_dimension kohaku ultimate", main.width, main.height, false, false);
        else if (copy.width != main.width || copy.height != main.height) copy.resize(main.width, main.height);
        return copy;
    }

    private static RenderTarget frozen(RenderTarget main) {
        if (frozen == null) frozen = new TextureTarget("logos_dimension kohaku ultimate frozen", main.width, main.height, false, false);
        else if (frozen.width != main.width || frozen.height != main.height) frozen.resize(main.width, main.height);
        return frozen;
    }
}
