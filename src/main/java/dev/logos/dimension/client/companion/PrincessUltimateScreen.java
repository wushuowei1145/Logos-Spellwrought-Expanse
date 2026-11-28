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
import dev.logos.dimension.client.screenfx.PostVfx;
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
 * 精灵公主奥义自己的全屏后期（docs/ELF_PRINCESS_COMPANION.md 第 11 节）：Cut 1「种子破土」四周彻底压黑，只在新苗那一点留一圈，
 * 圈里的地面也压暗，发光的新苗和地缝照常亮着（过场叠层的 {@code grade} 只能整片压暗，发光的东西会跟着暗下去）。
 * 圈心是新苗的世界坐标投到画面上的位置，跟着镜头走。由 {@link PrincessUltimateShow#screen} 调，只在本机玩家下的令时画；
 * 着色器 {@code shaders/post/princess_ultimate.fsh}。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class PrincessUltimateScreen {
    private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(LogosDimension.id("pipeline/princess_ultimate"))
            .withVertexShader(LogosDimension.id("post/princess_ultimate"))
            .withFragmentShader(LogosDimension.id("post/princess_ultimate"))
            .withSampler("InSampler")
            .withUniform("PrincessUltimate", UniformType.UNIFORM_BUFFER)
            .build();
    private static final int UNIFORM_SIZE = new Std140SizeCalculator().putVec4().putVec4().putVec4().get();
    /** 圈的半径、边缘（画面高度的几成），圈外、圈里压暗几成，发光的像素留几成。 */
    static final float RADIUS = .2f, SOFT = .22f, OUTER = .96f, INNER = .62f, KEEP = .9f;

    private static @Nullable RenderTarget copy;
    private static @Nullable MappableRingBuffer uniforms;
    /** 画了几次（客户端场景检查）。 */
    private static long passes;

    private PrincessUltimateScreen() {}

    @SubscribeEvent
    static void pipelines(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(PIPELINE);
    }

    public static long passes() {
        return passes;
    }

    /** 以 {@code focus}（世界坐标）为圈心压黑，强度 {@code weight}（0–1）。 */
    static void spotlight(Vec3 focus, float weight) {
        if (weight <= .003f) return;
        Minecraft mc = Minecraft.getInstance();
        Vec3 cam = mc.gameRenderer.getMainCamera().position();
        Vector4f clip = PostVfx.viewProjection().transform(new Vector4f((float) (focus.x - cam.x), (float) (focus.y - cam.y), (float) (focus.z - cam.z), 1));
        // 新苗跑到镜头后面时圈心放到画面外：整片压黑
        float fx = clip.w > 1e-4f ? clip.x / clip.w * .5f + .5f : -10, fy = clip.w > 1e-4f ? clip.y / clip.w * .5f + .5f : -10;
        RenderTarget main = mc.getMainRenderTarget();
        RenderTarget input = copy(main);
        if (uniforms == null)
            uniforms = new MappableRingBuffer(() -> "logos_dimension princess ultimate", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UNIFORM_SIZE);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToTexture(main.getColorTexture(), input.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
        try (GpuBuffer.MappedView view = encoder.mapBuffer(uniforms.currentBuffer(), false, true)) {
            Std140Builder.intoBuffer(view.data())
                    .putVec4(main.width, main.height, fx, fy)
                    .putVec4(RADIUS, SOFT, OUTER, INNER)
                    .putVec4(weight, KEEP, 0, 0);
        }
        var linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        try (RenderPass pass = encoder.createRenderPass(() -> "logos_dimension princess ultimate", main.getColorTextureView(), OptionalInt.empty())) {
            pass.setPipeline(PIPELINE);
            pass.setUniform("PrincessUltimate", uniforms.currentBuffer());
            pass.bindTexture("InSampler", input.getColorTextureView(), linear);
            pass.draw(0, 3);
        }
        uniforms.rotate();
        passes++;
    }

    private static RenderTarget copy(RenderTarget main) {
        if (copy == null) {
            copy = new TextureTarget("logos_dimension princess ultimate", main.width, main.height, false, false);
        } else if (copy.width != main.width || copy.height != main.height) {
            copy.resize(main.width, main.height);
        }
        return copy;
    }
}
