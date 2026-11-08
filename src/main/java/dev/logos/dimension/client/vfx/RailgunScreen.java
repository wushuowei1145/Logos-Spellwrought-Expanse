package dev.logos.dimension.client.vfx;

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
import dev.logos.client.VfxParts;
import dev.logos.dimension.LogosDimension;
import java.util.OptionalInt;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import org.jspecify.annotations.Nullable;

/**
 * 超电磁炮发射时的全屏调色：画面一下压暗、偏冷、四角更暗，只有射线在画面上经过的一条走廊保持原样，
 * 并被射线的暖光照亮一点。泛光（{@code GlowBloom}）在这之后才加上去，所以射线格外亮。
 * <p>
 * 由 {@code ScreenEffectMixin} 在世界和手画完之后、爆裂魔法的通道之前调用。只读最终画面的颜色，
 * 不碰光影包的缓冲；附近没有刚发射的超电磁炮时一个通道都不跑。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class RailgunScreen {
    private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(LogosDimension.id("pipeline/railgun"))
            .withVertexShader(LogosDimension.id("post/railgun"))
            .withFragmentShader(LogosDimension.id("post/railgun"))
            .withSampler("InSampler")
            .withUniform("Railgun", UniformType.UNIFORM_BUFFER)
            .build();
    private static final int UNIFORM_SIZE = new Std140SizeCalculator().putVec4().putVec4().putVec4().get();
    /** 离射线这么近时调色最重，这么远时没有。 */
    private static final float NEAR = 24, FAR = 96;
    /** 画面上保持原样的走廊：射线两侧约这么多格。 */
    private static final double CORRIDOR = 1.8;
    /** 走廊在画面上最宽多少（以画面高度为 1）：第一人称时出口贴着镜头，不然会亮掉半个画面。 */
    private static final float CORRIDOR_MAX = .1f;
    /** 射线的暖光（橙）和它照亮周围的强度。 */
    private static final float WARM_R = 1, WARM_G = .58f, WARM_B = .28f, WARM = .45f;
    private static @Nullable RenderTarget copy;
    private static @Nullable MappableRingBuffer uniforms;
    private static long passes;

    private RailgunScreen() {}

    /** 启动时就编译好，第一次开炮时不卡一下。 */
    @SubscribeEvent
    static void pipelines(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(PIPELINE);
    }

    /** 通道一共跑了多少次（客户端场景检查用）。 */
    public static long passes() { return passes; }

    /** 由 Mixin 在世界画完之后调用。 */
    public static void draw() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 eye = camera.position();
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        RailgunShot best = null;
        float dim = 0;
        for (RailgunShot s : RailgunClient.liveShots()) {
            float d = s.dim(s.age(mc.level, pt)) * (1 - VfxParts.smooth(NEAR, FAR, (float) distance(eye, s.muzzle(), s.end())));
            if (d > dim) {
                dim = d;
                best = s;
            }
        }
        if (best == null || dim < .003f) return;

        RenderTarget main = mc.getMainRenderTarget();
        float aspect = (float) main.width / main.height;
        // 射线在画面上的一段：先把镜头背后的部分截掉，再投到画面上；走廊的半宽按两端各自的远近换成画面上的长度
        float[] segment = {0, 0, 0, 0};
        float ra = 0, rb = 0;
        var f = camera.forwardVector();
        Vec3 forward = new Vec3(f.x(), f.y(), f.z());
        Vec3 a = best.muzzle(), b = best.end();
        double da = forward.dot(a.subtract(eye)), db = forward.dot(b.subtract(eye)), clip = .3;
        if (da > clip || db > clip) {
            if (da < clip) a = a.add(b.subtract(a).scale((clip - da) / (db - da)));
            else if (db < clip) b = b.add(a.subtract(b).scale((clip - db) / (da - db)));
            var u = camera.upVector();
            Vec3 up = new Vec3(u.x(), u.y(), u.z()).scale(CORRIDOR);
            float[] pa = screen(mc, a), pb = screen(mc, b);
            segment = new float[] {pa[0], pa[1], pb[0], pb[1]};
            ra = Math.min(CORRIDOR_MAX, span(pa, screen(mc, a.add(up)), aspect));
            rb = Math.min(CORRIDOR_MAX, span(pb, screen(mc, b.add(up)), aspect));
        }

        RenderTarget target = target(main);
        if (uniforms == null) uniforms = new MappableRingBuffer(() -> "logos_dimension railgun", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UNIFORM_SIZE);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToTexture(main.getColorTexture(), target.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
        try (GpuBuffer.MappedView view = encoder.mapBuffer(uniforms.currentBuffer(), false, true)) {
            Std140Builder.intoBuffer(view.data())
                    .putVec4(segment[0], segment[1], segment[2], segment[3])
                    .putVec4(dim, ra, rb, aspect)
                    .putVec4(WARM_R, WARM_G, WARM_B, WARM);
        }
        try (RenderPass pass = encoder.createRenderPass(() -> "logos_dimension railgun", main.getColorTextureView(), OptionalInt.empty())) {
            pass.setPipeline(PIPELINE);
            pass.setUniform("Railgun", uniforms.currentBuffer());
            pass.bindTexture("InSampler", target.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.draw(0, 3);
        }
        uniforms.rotate();
        passes++;
    }

    /** 画面上的位置，0~1，和全屏通道的纹理坐标一致（y 和 projectPointToScreen 的方向相反）。 */
    private static float[] screen(Minecraft mc, Vec3 p) {
        Vec3 ndc = mc.gameRenderer.projectPointToScreen(p);
        return new float[] {(float) (ndc.x * .5 + .5), (float) (.5 - ndc.y * .5)};
    }

    /** 画面上两点的距离，以画面高度为 1。 */
    private static float span(float[] p, float[] q, float aspect) {
        float dx = (q[0] - p[0]) * aspect, dy = q[1] - p[1];
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** 镜头到线段 {@code a}–{@code b} 的距离。 */
    private static double distance(Vec3 eye, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double h = ab.lengthSqr() < 1e-8 ? 0 : Math.max(0, Math.min(1, eye.subtract(a).dot(ab) / ab.lengthSqr()));
        return eye.distanceTo(a.add(ab.scale(h)));
    }

    private static RenderTarget target(RenderTarget main) {
        if (copy == null) {
            copy = new TextureTarget("logos_dimension railgun", main.width, main.height, false);
        } else if (copy.width != main.width || copy.height != main.height) {
            copy.resize(main.width, main.height);
        }
        return copy;
    }
}
