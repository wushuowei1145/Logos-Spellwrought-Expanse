package dev.logos.dimension.client;

import com.mojang.blaze3d.buffers.*;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.FilterMode;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.screenfx.PostVfx;
import dev.logos.dimension.elven.blight.QueenBattleRules;
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/** 世界坐标球形净化遮罩：恢复后的地景在波内显露，外侧保留猩红侵蚀，边缘带噪声与局部辉光。 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class QueenPurificationScreen {
    private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(LogosDimension.id("pipeline/queen_purification")).withVertexShader(LogosDimension.id("post/explosion"))
            .withFragmentShader(LogosDimension.id("post/queen_purification")).withSampler("InSampler").withSampler("DepthSampler")
            .withUniform("Purification", UniformType.UNIFORM_BUFFER).build();
    private static final int SIZE = new Std140SizeCalculator().putMat4f().putVec4().putVec4().putVec4().get();
    private static @Nullable RenderTarget copy;
    private static @Nullable MappableRingBuffer uniforms;
    private static long passes;
    private QueenPurificationScreen() {}
    @SubscribeEvent static void pipeline(net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent e) { e.registerPipeline(PIPELINE); }
    public static long passes() { return passes; }
    public static void draw(Matrix4f viewProjection) {
        if (!QueenEndingClient.active()) return;
        var mc = Minecraft.getInstance(); var depth = PostVfx.depth();
        float age = QueenEndingClient.age();
        if (depth == null || age < 45) return;
        var main = mc.getMainRenderTarget();
        if (copy == null) copy = new TextureTarget("queen purification", main.width, main.height, false);
        else if (copy.width != main.width || copy.height != main.height) copy.resize(main.width, main.height);
        if (uniforms == null) uniforms = new MappableRingBuffer(() -> "queen purification", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, SIZE);
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToTexture(main.getColorTexture(), copy.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
        Vec3 eye = mc.gameRenderer.getMainCamera().position(), relative = QueenEndingClient.center().subtract(eye);
        try (var mapped = encoder.mapBuffer(uniforms.currentBuffer(), false, true)) {
            Std140Builder.intoBuffer(mapped.data()).putMat4f(new Matrix4f(viewProjection).invert())
                    .putVec4((float) relative.x, (float) relative.y, (float) relative.z, QueenBattleRules.radius(age))
                    .putVec4(age, RenderSystem.getDevice().isZZeroToOne() ? 1 : 0, mc.options.hideLightningFlash().get() ? 1 : 0, QueenBattleRules.PURIFY_RADIUS)
                    .putVec4((float) (eye.x % 4096), (float) (eye.y % 4096), (float) (eye.z % 4096), 0);
        }
        try (var pass = encoder.createRenderPass(() -> "queen purification", main.getColorTextureView(), OptionalInt.empty())) {
            pass.setPipeline(PIPELINE); pass.setUniform("Purification", uniforms.currentBuffer());
            pass.bindTexture("InSampler", copy.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.bindTexture("DepthSampler", depth.getDepthTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(0, 3);
        }
        uniforms.rotate(); passes++;
    }
}
