package dev.logos.dimension.client;

import com.mojang.blaze3d.buffers.*;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.FilterMode;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.screenfx.PostVfx;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * 世界球体着色（docs/CINEMATICS.md「世界球体着色」）：一次全屏通道画最多 {@link #MAX} 个世界坐标的球，每个球有中心、半径、模式和强度，
 * 边缘的噪声按世界坐标取（不贴屏幕纹理）。只改已经画好的世界画面，不重画世界，光影包下也一样。
 *
 * <ul>
 * <li>{@link Mode#TAINT} 染腐：球内压暗、染成腐败的暗红（阶段三从中心漫开的腐败）。
 * <li>{@link Mode#PURIFY} 净化：球内略提亮、偏暖绿，边缘一圈淡绿光（公主身边的净化圈）。
 * <li>{@link Mode#REVEAL} 显露：球外染腐、球内保持原样，边缘一圈净化光；{@code width} 是最远的范围（结局的大范围净化）。
 * <li>{@link Mode#PULSE} 搏动：半径处一圈很淡的去饱和波，{@code width} 是波的厚度（腐败之心每次搏动）。
 * </ul>
 * 谁要画就在 {@link #collect} 里交球；结局另有一次全局的收缩和眩光（{@link #ending}）。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class WorldTintScreen {
    public enum Mode { TAINT, PURIFY, REVEAL, PULSE }

    /** {@code rim}：显露模式边缘那圈净化光的强度，其他模式不用。 */
    public record Sphere(Vec3 center, float radius, Mode mode, float strength, float width, float rim) {
        public Sphere(Vec3 center, float radius, Mode mode, float strength, float width) { this(center, radius, mode, strength, width, 0); }
    }

    /** 结局全局的收缩与眩光：{@code focus} 是眩光的中心。 */
    public record Ending(float collapse, float glare, Vec3 focus) {}

    public static final int MAX = 8;
    private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(LogosDimension.id("pipeline/world_tint")).withVertexShader(LogosDimension.id("post/explosion"))
            .withFragmentShader(LogosDimension.id("post/world_tint")).withSampler("InSampler").withSampler("DepthSampler")
            .withUniform("WorldTint", UniformType.UNIFORM_BUFFER).build();
    private static final int SIZE;
    static {
        var size = new Std140SizeCalculator().putMat4f().putVec4().putVec4().putVec4().putVec4();
        for (int i = 0; i < MAX * 2; i++) size.putVec4();
        SIZE = size.get();
    }
    private static @Nullable RenderTarget copy;
    private static @Nullable MappableRingBuffer uniforms;
    private static long passes, spheres;
    private static final List<Sphere> FRAME = new ArrayList<>();

    private WorldTintScreen() {}

    @SubscribeEvent static void pipeline(net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent e) { e.registerPipeline(PIPELINE); }

    /** 通道跑了几次、一共画了几个球（客户端场景看有没有在画）。 */
    public static long passes() { return passes; }
    public static long spheresDrawn() { return spheres; }

    /** 这一帧要不要留深度（世界开始画之前问）。 */
    public static boolean wanted() { return QueenEndingClient.active() || QueenBattleTints.active(); }

    private static void collect(List<Sphere> out) {
        QueenEndingClient.tints(out);
        QueenBattleTints.collect(out);
    }

    private static @Nullable Ending ending() { return QueenEndingClient.ending(); }

    public static void draw(Matrix4f viewProjection) {
        FRAME.clear();
        collect(FRAME);
        Ending ending = ending();
        if (FRAME.isEmpty() && ending == null) return;
        var mc = Minecraft.getInstance();
        var depth = PostVfx.depth();
        if (depth == null) return;
        var main = mc.getMainRenderTarget();
        if (copy == null) copy = new TextureTarget("world tint", main.width, main.height, false);
        else if (copy.width != main.width || copy.height != main.height) copy.resize(main.width, main.height);
        if (uniforms == null) uniforms = new MappableRingBuffer(() -> "world tint", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, SIZE);
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToTexture(main.getColorTexture(), copy.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
        Vec3 eye = mc.gameRenderer.getMainCamera().position();
        int count = Math.min(MAX, FRAME.size());
        float time = mc.level == null ? 0 : (mc.level.getGameTime() % 24000) + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        try (var mapped = encoder.mapBuffer(uniforms.currentBuffer(), false, true)) {
            var b = Std140Builder.intoBuffer(mapped.data()).putMat4f(new Matrix4f(viewProjection).invert())
                    .putVec4((float) (eye.x % 4096), (float) (eye.y % 4096), (float) (eye.z % 4096), 0)
                    .putVec4(time, RenderSystem.getDevice().isZZeroToOne() ? 1 : 0, mc.options.hideLightningFlash().get() ? 1 : 0, count);
            if (ending == null) b.putVec4(0, 0, 0, 0).putVec4(0, 0, 0, 0);
            else {
                Vec3 f = ending.focus().subtract(eye);
                b.putVec4(ending.collapse(), mc.options.hideLightningFlash().get() ? ending.glare() * .22f : ending.glare(), 1, 0)
                        .putVec4((float) f.x, (float) f.y, (float) f.z, 0);
            }
            for (int i = 0; i < MAX; i++) {
                if (i < count) {
                    Sphere s = FRAME.get(i);
                    Vec3 c = s.center().subtract(eye);
                    b.putVec4((float) c.x, (float) c.y, (float) c.z, s.radius()).putVec4(s.mode().ordinal(), s.strength(), s.width(), s.rim());
                } else b.putVec4(0, 0, 0, 0).putVec4(-1, 0, 0, 0);
            }
        }
        try (var pass = encoder.createRenderPass(() -> "world tint", main.getColorTextureView(), OptionalInt.empty())) {
            pass.setPipeline(PIPELINE); pass.setUniform("WorldTint", uniforms.currentBuffer());
            pass.bindTexture("InSampler", copy.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.bindTexture("DepthSampler", depth.getDepthTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(0, 3);
        }
        uniforms.rotate();
        passes++;
        spheres += count;
    }
}
