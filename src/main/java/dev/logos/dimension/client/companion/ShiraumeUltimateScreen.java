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
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * 白梅奥义的全屏后期（docs/SHIRAUME_COMPANION.md 第 11 节，着色器 {@code shaders/post/shiraume_ultimate.fsh}）：水镜倒影、
 * 浮世绘木刻、纸形代和因果丝、居合、破镜、黑白现世、因果斩和九尾，都在这一个通道里。
 *
 * <p>由她的 {@code UltimateShow#screen} 每帧调 {@link #draw}（公共的奥义叠层 {@code UltimateFilmRenderer} 在所有后期之后、过场叠层之前调它），
 * 不改公共的渲染顺序。只读两样：主画面的颜色、自己在 {@code AfterOpaqueFeatures} 复制的深度（她和敌人是 entityCutout，在里面），
 * 所以原版、Sodium、Iris 下走的是同一条路。破镜要的那一帧镜中画面存在另一张图里（{@link #hold}）。
 * 只有过场在本机放、这一帧要画时才复制深度，平常一帧不花。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class ShiraumeUltimateScreen {
    private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(LogosDimension.id("pipeline/shiraume_ultimate"))
            .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
            .withFragmentShader(LogosDimension.id("post/shiraume_ultimate"))
            .withSampler("InSampler")
            .withSampler("DepthSampler")
            .withSampler("HeldSampler")
            .withUniform("ShiraumeUltimate", UniformType.UNIFORM_BUFFER)
            .build();
    /** 因果丝最多几根（一个 vec4 放两根的那一头）、千纸最多几张。和着色器一致。 */
    public static final int THREADS = 8, PAPERS = 16;
    /** 矩阵后面一共几个 vec4：11 个单独的、因果丝 4 个、千纸 16 个、破镜和斩痕 4 个。 */
    private static final int VEC4S = 11 + THREADS / 2 + PAPERS + 4;
    private static final int UNIFORM_SIZE;

    static {
        Std140SizeCalculator size = new Std140SizeCalculator().putMat4f();
        for (int i = 0; i < VEC4S; i++) size.putVec4();
        UNIFORM_SIZE = size.get();
    }

    /** 这一帧画什么（{@code ShiraumeUltimateShow} 每帧填）。数组按着色器的顺序一个 vec4 一组。 */
    public static final class Params {
        public final Matrix4f inverse = new Matrix4f();
        public float age, seed;
        public float cameraX, cameraY, cameraZ;
        /** 夜雨、水面线、水漫过几成、倒影的暗度。 */
        public float night, waterLine, flood, reflection;
        public float puddleX, puddleY, puddleZ, puddleRadius;
        public float rippleX, rippleY, rippleAge = -1, waterHeight;
        public float ukiyo, mirror, mono, hideFlash;
        public float moonX, moonY = 1, moonZ, moonRadius = .1f, moonOn;
        public float handX, handY, handSize, handState;
        public float burn, iaido = -1;
        public int threads;
        public float threadsOn;
        public int papers;
        public float papersOn;
        public final float[] thread = new float[THREADS * 2];
        public final float[] paper = new float[PAPERS * 4];
        public float shatter = -1, shatterX = .5f, shatterY = .5f;
        public float slash = -1, slashX = .5f, slashY = .5f, slashAngle;
        public float shift, tails, tailsGrow;
        public float tailsX = .5f, tailsY = .3f, tailsSize;
    }

    private static @Nullable RenderTarget scene, held;
    private static @Nullable MappableRingBuffer uniforms;
    private static boolean wanted, captured, heldValid;
    private static long passes, holds;

    private ShiraumeUltimateScreen() {}

    @SubscribeEvent
    static void pipelines(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(PIPELINE);
    }

    /** 这一帧要画（世界开始画之前说）：到半透明之前会留一份深度。 */
    public static void want() {
        wanted = true;
    }

    @SubscribeEvent
    static void beforeTranslucent(RenderLevelStageEvent.AfterOpaqueFeatures event) {
        if (!wanted) return;
        wanted = false;
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (scene == null || scene.useStencil != main.useStencil) {
            if (scene != null) scene.destroyBuffers();
            scene = new TextureTarget("logos_dimension shiraume ultimate", main.width, main.height, true, main.useStencil);
        } else if (scene.width != main.width || scene.height != main.height) {
            scene.resize(main.width, main.height);
        }
        scene.copyDepthFrom(main);
        captured = true;
    }

    /** 通道跑了几次、存了几次镜中画面（客户端场景检查）。 */
    public static long passes() {
        return passes;
    }

    public static long holds() {
        return holds;
    }

    /** 破镜要用的那一帧作废（新的一段开演时）。 */
    public static void forget() {
        heldValid = false;
    }

    /**
     * 画一帧。{@code hold} 为真时画完把结果存下来，留给破镜（碎片里是这一帧）。这一帧没复制到深度（世界没画、过场刚接手）时什么都不做。
     */
    public static void draw(Params p, boolean hold) {
        if (!captured || scene == null) return;
        captured = false;
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        if (scene.width != main.width || scene.height != main.height) return;
        if (uniforms == null)
            uniforms = new MappableRingBuffer(() -> "logos_dimension shiraume ultimate", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UNIFORM_SIZE);
        RenderTarget frozen = held(main);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToTexture(main.getColorTexture(), scene.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
        try (GpuBuffer.MappedView view = encoder.mapBuffer(uniforms.currentBuffer(), false, true)) {
            Std140Builder b = Std140Builder.intoBuffer(view.data()).putMat4f(p.inverse);
            b.putVec4(main.width, main.height, p.age, RenderSystem.getDevice().isZZeroToOne() ? 1 : 0);
            b.putVec4(p.cameraX, p.cameraY, p.cameraZ, p.seed);
            b.putVec4(p.night, p.waterLine, p.flood, p.reflection);
            b.putVec4(p.puddleX, p.puddleY, p.puddleZ, p.puddleRadius);
            b.putVec4(p.rippleX, p.rippleY, p.rippleAge, p.waterHeight);
            b.putVec4(p.ukiyo, p.mirror, p.mono, p.hideFlash);
            b.putVec4(p.moonX, p.moonY, p.moonZ, p.moonRadius);
            b.putVec4(p.moonOn, 0, 0, 0);
            b.putVec4(p.handX, p.handY, p.handSize, p.handState);
            b.putVec4(p.burn, p.iaido, 0, 0);
            b.putVec4(p.threads, p.threadsOn, p.papers, p.papersOn);
            for (int i = 0; i < THREADS / 2; i++) b.putVec4(p.thread[i * 4], p.thread[i * 4 + 1], p.thread[i * 4 + 2], p.thread[i * 4 + 3]);
            for (int i = 0; i < PAPERS; i++) b.putVec4(p.paper[i * 4], p.paper[i * 4 + 1], p.paper[i * 4 + 2], p.paper[i * 4 + 3]);
            b.putVec4(heldValid ? p.shatter : -1, p.shatterX, p.shatterY, 0);
            b.putVec4(p.slash, p.slashX, p.slashY, p.slashAngle);
            b.putVec4(p.shift, p.tails, p.tailsGrow, 0);
            b.putVec4(p.tailsX, p.tailsY, p.tailsSize, 0);
        }
        var linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (RenderPass pass = encoder.createRenderPass(() -> "logos_dimension shiraume ultimate", main.getColorTextureView(), OptionalInt.empty())) {
            pass.setPipeline(PIPELINE);
            pass.setUniform("ShiraumeUltimate", uniforms.currentBuffer());
            pass.bindTexture("InSampler", scene.getColorTextureView(), linear);
            pass.bindTexture("DepthSampler", scene.getDepthTextureView(), nearest);
            pass.bindTexture("HeldSampler", frozen.getColorTextureView(), linear);
            pass.draw(0, 3);
        }
        uniforms.rotate();
        passes++;
        if (hold) {
            encoder.copyTextureToTexture(main.getColorTexture(), frozen.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
            heldValid = true;
            holds++;
        }
    }

    private static RenderTarget held(RenderTarget main) {
        if (held == null) {
            held = new TextureTarget("logos_dimension shiraume ultimate held", main.width, main.height, false, false);
        } else if (held.width != main.width || held.height != main.height) {
            held.resize(main.width, main.height);
            heldValid = false;
        }
        return held;
    }
}
