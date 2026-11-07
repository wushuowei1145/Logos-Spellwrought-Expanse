package dev.logos.dimension.client.screenfx;

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
import dev.logos.dimension.client.camera.CameraDirector;
import dev.logos.dimension.screenfx.ScreenFlash;
import dev.logos.dimension.screenfx.ScreenFlashPayload;
import java.util.List;
import java.util.OptionalInt;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * 本机的全屏后期：黑白闪和电影蒙版。一个自己的全屏通道，把主画面复制一份当输入，再写回主画面。
 * 不用原版的后期链，因为那边的参数在加载时就写死了，而这里每帧都在变；也不占原版那一个后期槽，
 * 附身看到的苦力怕视角可以同时存在。剪影和影子线要知道谁在光源前面、朝哪边，所以闪的时候在半透明之前留一份深度。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class ScreenEffectRenderer {
    private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(LogosDimension.id("pipeline/screen_effect"))
            .withVertexShader(LogosDimension.id("post/screen_effect"))
            .withFragmentShader(LogosDimension.id("post/screen_effect"))
            .withSampler("InSampler")
            .withSampler("DepthSampler")
            .withSampler("HandDepthSampler")
            .withUniform("ScreenEffect", UniformType.UNIFORM_BUFFER)
            .build();
    private static final int UNIFORM_SIZE = new Std140SizeCalculator().putMat4f().putVec4().putVec4().putVec4().putVec4().get();
    /** 闪完之后冲击缩放和色差再用几拍收回去，不会一下弹回原画。 */
    private static final int TAIL_FRAMES = 3;
    private static final float PUNCH = .05f, SPLIT = .006f;
    /** 两次闪之间至少隔多久，连续命中时后来的就不闪了：满屏黑白交替一秒不能超过三次，否则有光敏风险。 */
    private static final long MIN_INTERVAL_NANOS = 700_000_000L;

    private static @Nullable ScreenFlash flash;
    private static long startedAt;
    private static boolean flashedBefore;
    /** 现在放到第几拍。一帧最多往前走一拍，帧率再低每一拍也都能被看到。 */
    private static int shownFrame;
    /** 这一帧是否已经往前走过：界面比世界先收集，闪的那一拍要两边对齐。 */
    private static boolean stepped;
    private static float tail;
    private static Vec3 center = Vec3.ZERO;
    /** 客户端场景用：停在某一拍上截图。 */
    private static ScreenFlash.@Nullable Frame heldFrame;
    /** 颜色的副本，闪的时候还存一份半透明之前的深度。 */
    private static @Nullable RenderTarget copy;
    private static @Nullable MappableRingBuffer uniforms;

    private ScreenEffectRenderer() {}

    @SubscribeEvent static void packets(RegisterClientPayloadHandlersEvent event) {
        event.register(ScreenFlashPayload.TYPE, (payload, context) -> play(payload.flash()));
    }

    /** 闪一次。原版选项「隐藏闪电的闪烁」打开时不闪：这是玩家已经表过态的光敏设置。 */
    public static void play(ScreenFlash next) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.options.hideLightningFlash().get()) return;
        long now = Util.getNanos();
        // 时钟可能被换掉（测试框架就会），算出负数时不当成「刚闪过」
        long since = now - startedAt;
        if (flashedBefore && since >= 0 && since < MIN_INTERVAL_NANOS) return;
        flash = next;
        flashedBefore = true;
        startedAt = now;
        shownFrame = -1;
    }

    /** 这一刻放的是哪一拍，没在闪时是 null。 */
    public static ScreenFlash.@Nullable Frame currentFrame() {
        if (heldFrame != null) return heldFrame;
        return flash != null && shownFrame >= 0 && shownFrame < flash.frames().size() ? flash.frames().get(shownFrame) : null;
    }

    /** 客户端场景用：让画面停在 {@code frame} 这一拍，传 null 放开。 */
    public static void hold(ScreenFlash.@Nullable Frame frame, Vec3 at) {
        heldFrame = frame;
        center = at;
    }

    /** 半透明之前留一份深度：玻璃和水不挡光，也不会变成剪影。 */
    @SubscribeEvent static void beforeTranslucent(RenderLevelStageEvent.AfterOpaqueFeatures event) {
        if (flash == null && heldFrame == null) return;
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        target(main).copyDepthFrom(main);
    }

    private static RenderTarget target(RenderTarget main) {
        if (copy == null || copy.useStencil != main.useStencil) {
            if (copy != null) copy.destroyBuffers();
            copy = new TextureTarget("logos_dimension screen effect", main.width, main.height, true, main.useStencil);
        } else if (copy.width != main.width || copy.height != main.height) {
            copy.resize(main.width, main.height);
        }
        return copy;
    }

    /** 由 Mixin 在世界画完之后调用。 */
    public static void draw() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            flash = null;
            return;
        }
        if (!stepped) step();
        stepped = false;
        ScreenFlash.Frame frame = currentFrame();
        float grade = CameraDirector.grade();
        if (frame == null && tail <= 0 && grade <= 0) return;

        float k = frame != null ? 1 : tail;
        pass(mc, frame == null ? Stage.NONE : Stage.of(frame, glowStrength()), PUNCH * k, SPLIT * k, grade);
    }

    /** 闪的那几拍画面整个换掉了，界面也不画。排在最后，运镜的遮幅已经画进去了。 */
    @SubscribeEvent(priority = EventPriority.LOWEST) static void hud(RenderGuiEvent.Pre event) {
        step();
        stepped = true;
        ScreenFlash.Frame frame = currentFrame();
        if (frame != null && frame.replacesScreen()) event.setCanceled(true);
    }

    private static void step() {
        tail = advance();
    }

    /** 往前走一拍。返回冲击缩放还剩多少：闪的时候是 1，闪完几拍内收到 0。 */
    private static float advance() {
        if (flash == null) return 0;
        int due = ScreenFlash.frameAt(Util.getNanos() - startedAt);
        if (due > shownFrame) shownFrame++;
        int size = flash.frames().size();
        if (shownFrame < size) {
            var at = flash.center().resolve(Minecraft.getInstance().level, 1, center);
            if (at != null) center = at;
            return 1;
        }
        if (shownFrame >= size + TAIL_FRAMES) {
            flash = null;
            return 0;
        }
        float t = (shownFrame - size + 1f) / (TAIL_FRAMES + 1);
        return (1 - t) * (1 - t);
    }

    /** 连着几拍发光时一拍比一拍亮：这是连续发光里的第几拍，按比例算。停住截图时算最亮。 */
    private static float glowStrength() {
        if (heldFrame != null || flash == null) return 1;
        List<ScreenFlash.Frame> frames = flash.frames();
        int start = shownFrame, end = shownFrame;
        while (start > 0 && frames.get(start - 1) == ScreenFlash.Frame.GLOW) start--;
        while (end + 1 < frames.size() && frames.get(end + 1) == ScreenFlash.Frame.GLOW) end++;
        return (shownFrame - start + 1f) / (end - start + 1f);
    }

    /**
     * 每一拍交给着色器的参数。抽象程度和光吞没的半径跟着阶段走：越往后线越长、轮廓越碎，光吞得越大。
     *
     * @param engulf 光吞没的半径，按画面高度
     */
    record Stage(float mode, float strength, float abstraction, float engulf) {
        static final Stage NONE = new Stage(0, 0, 0, 0);

        static Stage of(ScreenFlash.Frame frame, float glow) {
            return switch (frame) {
                case WHITE -> new Stage(1, 1, 0, 0);
                case BLACK -> new Stage(2, 1, 0, 0);
                case NORMAL -> NONE;
                case GLOW -> new Stage(3, glow, 0, .02f + .08f * glow);
                case SILHOUETTE -> new Stage(4, 1, .2f, .07f);
                case ENGULF -> new Stage(5, 1, .55f, .2f);
                case SWAP -> new Stage(6, 1, .9f, .34f);
            };
        }
    }

    private static void pass(Minecraft mc, Stage stage, float punch, float split, float grade) {
        RenderTarget main = mc.getMainRenderTarget();
        RenderTarget target = target(main);
        if (uniforms == null) uniforms = new MappableRingBuffer(() -> "logos_dimension screen effect", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UNIFORM_SIZE);

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToTexture(main.getColorTexture(), target.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);

        Camera camera = mc.gameRenderer.getMainCamera();
        Matrix4f inverse = new Matrix4f(WorldStyleRenderer.PROJECTION).mul(camera.getViewRotationMatrix(new Matrix4f())).invert();
        Vec3 light = center.subtract(camera.position());
        boolean zeroToOne = RenderSystem.getDevice().isZZeroToOne();
        float lightDepth = 0;
        Vec3 screen = new Vec3(.5, .5, 0);
        var forward = camera.forwardVector();
        // 光源在镜头身后时退回画面中央，也没有东西在它前面
        if (light.dot(new Vec3(forward.x(), forward.y(), forward.z())) > 0) {
            Vec3 ndc = mc.gameRenderer.projectPointToScreen(center);
            screen = new Vec3(ndc.x * .5 + .5, ndc.y * .5 + .5, 0);
            lightDepth = (float) (zeroToOne ? ndc.z : ndc.z * .5 + .5);
        }
        float seed = (startedAt / 1_000_000 % 97) + shownFrame;
        try (GpuBuffer.MappedView view = encoder.mapBuffer(uniforms.currentBuffer(), false, true)) {
            Std140Builder.intoBuffer(view.data()).putMat4f(inverse)
                    .putVec4((float) light.x, (float) light.y, (float) light.z, lightDepth)
                    .putVec4(stage.mode(), seed, stage.strength(), stage.abstraction())
                    .putVec4(punch, split, grade, stage.engulf())
                    .putVec4((float) screen.x, (float) screen.y, zeroToOne ? 1 : 0, 0);
        }
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (RenderPass pass = encoder.createRenderPass(() -> "logos_dimension screen effect", main.getColorTextureView(), OptionalInt.empty())) {
            pass.setPipeline(PIPELINE);
            pass.setUniform("ScreenEffect", uniforms.currentBuffer());
            pass.bindTexture("InSampler", target.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.bindTexture("DepthSampler", target.getDepthTextureView(), nearest);
            pass.bindTexture("HandDepthSampler", main.getDepthTextureView(), nearest);
            pass.draw(0, 3);
        }
        uniforms.rotate();
    }
}
