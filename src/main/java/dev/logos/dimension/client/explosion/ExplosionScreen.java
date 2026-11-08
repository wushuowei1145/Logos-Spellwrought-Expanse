package dev.logos.dimension.client.explosion;

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
import dev.logos.dimension.client.screenfx.WorldStyleRenderer;
import java.util.OptionalInt;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * 爆裂魔法的全屏后期，一个通道，在世界和手都画完之后、泛光和黑白闪之前（{@code ScreenEffectMixin}）：
 * <ul>
 *   <li>吟唱时周围变暗、偏冷，边角更暗：施法者和法阵（之后由 {@link PostVfx} 画上去）显得格外亮；</li>
 *   <li>火柱落地后，爆点周围按到爆点的真实距离（由深度还原）渐渐红热，别处暗下去，再到白热；</li>
 *   <li>一瞬间整个画面转成蓝色，中心爆出极亮的十字闪光；</li>
 *   <li>白热的球体：每个像素算视线和球的交点，球面上的明暗由三维噪声和边缘的菲涅尔决定，和地面相交处一圈更亮，散去时按噪声一块块消失；</li>
 *   <li>空气被推开：两层看不见的球壳，视线擦过球壳的地方把背后的画面往外折射一点，带一层淡淡的白雾。</li>
 * </ul>
 * 只读最终画面的颜色和半透明之前的深度（与 {@link PostVfx} 共用那一份），和 Sodium、光影包都走同一条路。
 * 没有吟唱和爆炸时一个通道都不跑。原版选项「隐藏闪电的闪烁」打开时，转蓝和十字闪大幅减弱。
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = LogosDimension.ID, value = net.neoforged.api.distmarker.Dist.CLIENT)
public final class ExplosionScreen {
    private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(LogosDimension.id("pipeline/explosion"))
            .withVertexShader(LogosDimension.id("post/explosion"))
            .withFragmentShader(LogosDimension.id("post/explosion"))
            .withSampler("InSampler")
            .withSampler("DepthSampler")
            .withSampler("HandDepthSampler")
            .withSampler("NoiseSampler")
            .withUniform("Explosion", UniformType.UNIFORM_BUFFER)
            .build();
    private static final int UNIFORM_SIZE = new Std140SizeCalculator().putMat4f().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().get();
    private static @Nullable RenderTarget copy;
    private static @Nullable MappableRingBuffer uniforms;
    private static long passes;

    private ExplosionScreen() {}

    /** 启动时就编译好，第一次画时不卡一下。 */
    @net.neoforged.bus.api.SubscribeEvent
    static void pipelines(net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent event) {
        event.registerPipeline(PIPELINE);
    }

    /** 通道一共跑了多少次（客户端场景检查用）。 */
    public static long passes() {
        return passes;
    }

    /** 由 Mixin 在世界画完之后调用：先画这一个全屏通道，再画 {@link PostVfx} 收到的特效。 */
    public static void draw() {
        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        Matrix4f viewProjection = new Matrix4f(WorldStyleRenderer.PROJECTION).mul(camera.getViewRotationMatrix(new Matrix4f()));
        if (mc.level != null && ExplosionClient.active()) pass(mc, camera, viewProjection);
        PostVfx.draw(viewProjection);
    }

    private static void pass(Minecraft mc, Camera camera, Matrix4f viewProjection) {
        RenderTarget depth = PostVfx.depth();
        if (depth == null) return;
        float pt = ExplosionClient.partial();
        Vec3 eye = camera.position();
        float dim = ExplosionClient.dim(eye, pt);
        BlastView blast = ExplosionClient.primary(eye);
        float heat = 0, white = 0, blue = 0, cross = 0, sphere = 0, sphereAlpha = 0;
        float[] air = {0, 0}, wave = {0, 0};
        Vec3 center = eye.add(0, -1000, 0);
        float radius = 64, facing = 1;
        float sx = .5f, sy = .5f;
        boolean zeroToOne = RenderSystem.getDevice().isZZeroToOne();
        if (blast != null) {
            float age = blast.age(ExplosionClient.ticks(), pt);
            center = blast.center;
            radius = blast.radius;
            double distance = eye.distanceTo(center);
            // 离得越远越淡，但几百格外的爆炸照样照亮天空
            float far = 1 - .6f * Draw.smooth(radius * 3, radius * 10, (float) distance);
            var f = camera.forwardVector();
            Vec3 forward = new Vec3(f.x(), f.y(), f.z());
            Vec3 to = center.add(0, radius * .2, 0).subtract(eye);
            float dot = (float) (to.lengthSqr() < 1e-6 ? 1 : forward.dot(to.normalize()));
            facing = Mth.clamp((dot + .3f) / .9f, .35f, 1);
            if (dot > 0) {
                Vec3 ndc = mc.gameRenderer.projectPointToScreen(center.add(0, radius * .15, 0));
                sx = (float) (ndc.x * .5 + .5);
                sy = (float) (ndc.y * .5 + .5);
            }
            boolean calm = mc.options.hideLightningFlash().get();
            heat = blast.heat(age) * far;
            white = blast.white(age) * far;
            blue = blast.blue(age) * far * (calm ? .3f : 1);
            cross = blast.cross(age) * far * (calm ? .35f : 1);
            sphere = blast.sphere(age);
            sphereAlpha = blast.sphereAlpha(age);
            air = blast.airShock(age);
            wave = blast.blastWave(age);
        } else {
            // 没有爆炸时：有人刚蓄满，从身上推出去的空气也用这层折射
            ExplosionClient.Shell shell = ExplosionClient.chargeShell(eye, pt);
            if (shell != null) {
                center = shell.center();
                air = new float[] {shell.radius(), shell.strength()};
                var f = camera.forwardVector();
                if (new Vec3(f.x(), f.y(), f.z()).dot(center.subtract(eye)) > 0) {
                    Vec3 ndc = mc.gameRenderer.projectPointToScreen(center);
                    sx = (float) (ndc.x * .5 + .5);
                    sy = (float) (ndc.y * .5 + .5);
                }
            }
        }
        // 自己蓄满后（第一人称）画面四周往上流的蓝光
        float aura = ExplosionClient.aura(pt);
        // 烟散的那二十来秒里全屏没什么可画：一个通道都不跑
        if (dim <= 0 && heat <= 0 && blue <= 0 && cross <= 0 && sphereAlpha <= 0 && air[1] <= 0 && wave[1] <= 0 && aura <= 0) return;
        RenderTarget main = mc.getMainRenderTarget();
        RenderTarget target = target(main);
        if (uniforms == null) uniforms = new MappableRingBuffer(() -> "logos_dimension explosion", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UNIFORM_SIZE);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToTexture(main.getColorTexture(), target.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
        Vec3 rel = center.subtract(eye);
        float seconds = (ExplosionClient.ticks() + pt) / 20f;
        try (GpuBuffer.MappedView view = encoder.mapBuffer(uniforms.currentBuffer(), false, true)) {
            Std140Builder.intoBuffer(view.data())
                    .putMat4f(new Matrix4f(viewProjection).invert())
                    .putVec4((float) rel.x, (float) rel.y, (float) rel.z, radius)
                    .putVec4(dim, heat, white, blue)
                    .putVec4(cross, sphere, sphereAlpha, seconds)
                    .putVec4(air[0], air[1], wave[0], wave[1])
                    .putVec4(sx, sy, zeroToOne ? 1 : 0, facing)
                    .putVec4((float) main.width / main.height, aura, 0, 0);
        }
        var linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        var noise = mc.getTextureManager().getTexture(PostVfx.logos("ward/noise"));
        try (RenderPass pass = encoder.createRenderPass(() -> "logos_dimension explosion", main.getColorTextureView(), OptionalInt.empty())) {
            pass.setPipeline(PIPELINE);
            pass.setUniform("Explosion", uniforms.currentBuffer());
            pass.bindTexture("InSampler", target.getColorTextureView(), linear);
            pass.bindTexture("DepthSampler", depth.getDepthTextureView(), nearest);
            pass.bindTexture("HandDepthSampler", main.getDepthTextureView(), nearest);
            pass.bindTexture("NoiseSampler", noise.getTextureView(), RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR));
            pass.draw(0, 3);
        }
        uniforms.rotate();
        passes++;
    }

    private static RenderTarget target(RenderTarget main) {
        if (copy == null) {
            copy = new TextureTarget("logos_dimension explosion", main.width, main.height, false);
        } else if (copy.width != main.width || copy.height != main.height) {
            copy.resize(main.width, main.height);
        }
        return copy;
    }
}
