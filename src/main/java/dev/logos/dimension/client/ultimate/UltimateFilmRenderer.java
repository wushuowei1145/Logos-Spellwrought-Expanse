package dev.logos.dimension.client.ultimate;

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
 * 奥义过场盖在画面上的那一层（{@link UltimateFilm}）：一个全屏通道，依次是动态模糊、调色（压暗、去饱和、染色、暗角）、
 * 速度线、擦镜。只在本机放过场时画；排在所有后期之后、连携切入之前（ScreenEffectMixin）。
 *
 * <p>速度线和擦镜是画在屏幕上的 2D 图形：漫画的速度线本来就贴着画面，擦镜十几刻就扫过去，不会出现「世界在转、纹理不动」的晕。
 * 不往画面上写字（拟声字也不写）。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class UltimateFilmRenderer {
    private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(LogosDimension.id("pipeline/ultimate_film"))
            .withVertexShader(LogosDimension.id("post/ultimate_film"))
            .withFragmentShader(LogosDimension.id("post/ultimate_film"))
            .withSampler("InSampler")
            .withUniform("UltimateFilm", UniformType.UNIFORM_BUFFER)
            .build();
    private static final int VEC4S = 10;
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

    private UltimateFilmRenderer() {}

    @SubscribeEvent
    static void pipelines(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(PIPELINE);
    }

    public static long passes() {
        return passes;
    }

    /** 由 ScreenEffectMixin 在所有后期之后、连携切入之前调：先是各段自己的全屏后期，再是过场叠层。 */
    public static void draw() {
        Minecraft mc = Minecraft.getInstance();
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        UltimateClient.screens(partial);
        UltimateFilm film = UltimateFilm.playing();
        if (film == null) return;
        float age = film.age(partial);
        UltimateFilm.Overlay o = film.overlay(age);
        if (o.gradeWeight() <= 0 && o.blurWeight() <= 0 && o.linesWeight() <= 0 && o.wipeProgress() < 0) return;
        RenderTarget main = mc.getMainRenderTarget();
        pass(main, age, film.cast().seed(), o);
        passes++;
    }

    private static RenderTarget copy(RenderTarget main) {
        if (copy == null) {
            copy = new TextureTarget("logos_dimension ultimate film", main.width, main.height, false, false);
        } else if (copy.width != main.width || copy.height != main.height) {
            copy.resize(main.width, main.height);
        }
        return copy;
    }

    private static void pass(RenderTarget main, float age, long seed, UltimateFilm.Overlay o) {
        RenderTarget input = copy(main);
        if (uniforms == null)
            uniforms = new MappableRingBuffer(() -> "logos_dimension ultimate film", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UNIFORM_SIZE);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToTexture(main.getColorTexture(), input.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
        try (GpuBuffer.MappedView view = encoder.mapBuffer(uniforms.currentBuffer(), false, true)) {
            Std140Builder b = Std140Builder.intoBuffer(view.data());
            b.putVec4(main.width, main.height, age, seed & 0xFFF);
            UltimateFilm.Layer g = o.grade();
            float gw = o.gradeWeight();
            b.putVec4(g == null ? 0 : g.a() * gw, g == null ? 0 : g.b() * gw, g == null ? 0 : g.c() * gw, g == null ? 0 : g.d() * gw);
            putColor(b, g == null ? 0xFFFFFF : g.color(), 0);
            UltimateFilm.Layer bl = o.blur();
            b.putVec4(bl == null ? 0 : o.blurWeight(), bl == null ? 0 : bl.mode(), bl == null ? 0 : bl.a(), 0);
            UltimateFilm.Layer l = o.lines();
            b.putVec4(l == null ? 0 : o.linesWeight(), l == null ? 0 : l.mode(), l == null ? 0 : l.a(), 0);
            putColor(b, l == null ? 0xFFFFFF : l.color(), 0);
            b.putVec4(bl != null ? bl.b() : l != null ? l.b() : .5f, bl != null ? bl.c() : l != null ? l.c() : .5f, l == null ? .5f : l.b(),
                    l == null ? .5f : l.c());
            UltimateFilm.Layer w = o.wipe();
            b.putVec4(o.wipeProgress(), w == null ? 0 : w.mode(), 0, 0);
            putColor(b, w == null ? 0xFFFFFF : w.color(), 0);
            putColor(b, w == null ? 0xFFFFFF : w.color2(), 0);
        }
        var linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        try (RenderPass pass = encoder.createRenderPass(() -> "logos_dimension ultimate film", main.getColorTextureView(), OptionalInt.empty())) {
            pass.setPipeline(PIPELINE);
            pass.setUniform("UltimateFilm", uniforms.currentBuffer());
            pass.bindTexture("InSampler", input.getColorTextureView(), linear);
            pass.draw(0, 3);
        }
        uniforms.rotate();
    }

    private static void putColor(Std140Builder b, int rgb, float w) {
        b.putVec4(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f, w);
    }
}
