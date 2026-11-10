package dev.logos.dimension.client.execution;

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
import dev.logos.dimension.client.screenfx.WorldStyleRenderer;
import dev.logos.dimension.execution.ExecutionTimeline;
import dev.logos.dimension.execution.ExecutionTimeline.Phase;
import dev.logos.dimension.execution.ExecutionTimeline.Slash;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * 处决特写的全屏画面：整个画面换成平涂的两色，背景一色、目标的剪影一色，刀光、刀痕和速度线压在上面。
 * 剪影不靠模板缓冲：用深度还原每个像素的位置，落在目标身体的盒子里（盒子跟着身体转）就算身体。
 * 深度在半透明的实体画完之后留一份（玩家的模型是半透明的），所以水和玻璃不会挡住剪影，光影包也是同一条路。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class ExecutionRenderer {
    private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(LogosDimension.id("pipeline/execution"))
            .withVertexShader(LogosDimension.id("post/execution"))
            .withFragmentShader(LogosDimension.id("post/execution"))
            .withSampler("InSampler")
            .withSampler("DepthSampler")
            .withUniform("Execution", UniformType.UNIFORM_BUFFER)
            .build();
    static final int SLASHES = 4, SCARS = 24, SPARKS = 4;
    private static final int UNIFORM_SIZE;
    static {
        var size = new Std140SizeCalculator().putMat4f().putMat4f();
        for (int i = 0; i < 6 + SLASHES * 2 + SCARS + SPARKS; i++) size.putVec4();
        UNIFORM_SIZE = size.get();
    }
    /** 一刀最粗的地方有多宽（半宽，按画面高度）。 */
    private static final float BLADE = .026f;
    /** 每道刀痕把它一侧的身体沿刀的方向错开多少（按画面高度）：刀越多，身体越像被一块块斩开。 */
    private static final float SLICE = .009f;
    /** 受击火花的半径（按画面高度）：刀划过身体的那一拍最大，下一拍缩小。 */
    private static final float SPARK = .075f;
    /** 集中线的稀疏程度：越小线越密。第一版固定 0.5；后来的样式随着刀数从疏到密。 */
    private static final float SPEED_SPARSE = .5f, SPEED_FROM = .58f, SPEED_TO = .3f;
    /** 身体的盒子比判定箱大多少：张开的手臂、甩起来的腿和手里的东西都在判定箱外面。 */
    private static final float MARGIN_SIDE = .6f, MARGIN_UP = .35f;
    /** 抛起时和落地后几拍，原画上压一层白色速度线。 */
    private static final float OVERLAY_FROM = .8f, OVERLAY_FRAMES = 4;

    private static @Nullable RenderTarget copy;
    private static @Nullable MappableRingBuffer uniforms;
    private static long passes;

    private ExecutionRenderer() {}

    @SubscribeEvent static void pipelines(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(PIPELINE);
    }

    /** 通道一共跑了多少次（客户端场景看有没有在画）。 */
    public static long passes() {
        return passes;
    }

    /** 半透明的实体画完之后留一份深度：玩家的模型是半透明的，在这之前还没画；水和玻璃在这之后才画。 */
    @SubscribeEvent static void afterEntities(RenderLevelStageEvent.AfterTranslucentFeatures event) {
        if (ExecutionClient.viewing() == null) return;
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        target(main).copyDepthFrom(main);
    }

    private static RenderTarget target(RenderTarget main) {
        if (copy == null || copy.useStencil != main.useStencil) {
            if (copy != null) copy.destroyBuffers();
            copy = new TextureTarget("logos_dimension execution", main.width, main.height, true, main.useStencil);
        } else if (copy.width != main.width || copy.height != main.height) {
            copy.resize(main.width, main.height);
        }
        return copy;
    }

    /** 整个画面换掉的那几拍不画界面。排在最后，运镜的遮幅已经画进去了。 */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST) static void hud(RenderGuiEvent.Pre event) {
        Mode mode = mode(ExecutionClient.viewing());
        if (mode != null && mode.replacesScreen()) event.setCanceled(true);
    }

    /** 这一帧画什么。和着色器里的常量对应。 */
    enum Mode {
        NONE(0), INK(1), RED(2), IMPACT(3), OVERLAY(4);

        final float id;

        Mode(float id) {
            this.id = id;
        }

        boolean replacesScreen() {
            return this == INK || this == RED || this == IMPACT;
        }
    }

    private static @Nullable Mode mode(ExecutionClient.@Nullable Run run) {
        if (run == null) return null;
        float age = run.age(ExecutionClient.partialTick());
        var timeline = run.timeline;
        boolean gentle = Minecraft.getInstance().options.hideLightningFlash().get();
        return switch (timeline.phase(age)) {
            case LAUNCH -> age >= OVERLAY_FROM ? Mode.OVERLAY : Mode.NONE;
            case INK -> Mode.INK;
            case RED -> Mode.RED;
            // 「隐藏闪电的闪烁」打开时落地不白那一拍
            case IMPACT -> gentle ? Mode.OVERLAY : Mode.IMPACT;
            case AFTER -> age < timeline.impact() + ExecutionTimeline.FRAME * OVERLAY_FRAMES ? Mode.OVERLAY : Mode.NONE;
            case DONE -> Mode.NONE;
        };
    }

    /** 由 Mixin 在世界画完之后调用。 */
    public static void draw() {
        ExecutionClient.Run run = ExecutionClient.viewing();
        Mode mode = mode(run);
        if (run == null || mode == null || mode == Mode.NONE || copy == null) return;
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        if (copy.width != main.width || copy.height != main.height) return;
        Entity entity = run.level.getEntity(run.target);
        if (entity == null) return;

        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float age = run.age(partial);
        var timeline = run.timeline;
        // 刀光一拍一拍地换：按这一拍结束时的样子画，刀身才正好压在身体上
        float shown = ExecutionTimeline.stepped(age) + ExecutionTimeline.FRAME;
        ExecutionClient.Body body = run.body(age);
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cam = camera.position();
        Vec3 feet = entity.getPosition(partial);
        Vec3 pivot = feet.add(body.lift().x, body.lift().y + body.pivot(), body.lift().z);
        Vec3 rel = pivot.subtract(cam);

        Matrix4f inverse = new Matrix4f(WorldStyleRenderer.PROJECTION).mul(camera.getViewRotationMatrix(new Matrix4f())).invert();
        Matrix4f bodyInverse = new Matrix4f().rotation(new Quaternionf(body.rotation()).conjugate()).translate((float) -rel.x, (float) -rel.y, (float) -rel.z);
        float aspect = (float) main.width / main.height;
        float[] screen = screen(mc, camera, pivot, run.height * .5f + .2f, aspect);

        boolean slamming = age >= timeline.slamStart();
        float burst = 0;
        if (mode == Mode.RED && !slamming) {
            float since = age - timeline.finalHit();
            burst = since < ExecutionTimeline.FRAME * 2 ? 1 : Math.max(0, 1 - (since - ExecutionTimeline.FRAME * 2) / (ExecutionTimeline.HIT_STOP - ExecutionTimeline.FRAME * 2));
        }
        // 色差只在重击和落地的那一拍，偏一点点：偏多了刀痕边上全是彩虹
        float split = mode == Mode.IMPACT ? .0018f : mode == Mode.RED && age - timeline.finalHit() < ExecutionTimeline.FRAME ? .0025f : 0;
        Slash finisher = timeline.finisher();
        float finisherProgress = mode == Mode.INK || mode == Mode.RED && !slamming ? Math.max(0, finisher.progress(shown)) : 0;
        if (finisherProgress > 1.2f) finisherProgress = 0;

        // 静下来蓄势、重击定格的时候，速度线也停住不闪
        float seedAge = age;
        float quietFrom = timeline.slashes().isEmpty() ? timeline.windowEnd() : Math.max(timeline.slashes().getLast().start() + ExecutionTimeline.STROKE,
                timeline.interactive() ? timeline.windowEnd() : 0);
        if (age >= quietFrom && age < timeline.finalHit()) seedAge = quietFrom;
        else if (age >= timeline.finalHit() && age < timeline.slamStart()) seedAge = timeline.finalHit();
        float seed = ExecutionTimeline.frame(seedAge) + timeline.shot().seed() % 97;

        boolean accents = timeline.shot().swaps().accents();
        List<float[]> active = new ArrayList<>();
        List<float[]> scars = new ArrayList<>();
        List<float[]> sparks = new ArrayList<>();
        int frame = ExecutionTimeline.frame(age);
        if (mode == Mode.INK || mode == Mode.RED && !slamming) {
            var slashes = timeline.slashes();
            // 可交互时刀可能多过刀痕的格子：只留最近的那些刀痕
            int scarFrom = 0;
            if (slashes.size() > SCARS) {
                int seen = 0;
                for (int i = slashes.size() - 1; i >= 0; i--) {
                    if (slashes.get(i).progress(shown) > .5f && ++seen > SCARS) { scarFrom = i + 1; break; }
                }
            }
            for (int i = 0; i < slashes.size(); i++) {
                Slash s = slashes.get(i);
                float p = s.progress(shown);
                float offset = s.offset() * screen[2];
                if (mode == Mode.INK && p > 0 && p < 1.05f && active.size() < SLASHES) active.add(new float[] {s.angle(), offset, p, s.width() * BLADE, s.bend()});
                if (p > .5f && i >= scarFrom && scars.size() < SCARS) {
                    // 刀痕一侧的身体沿刀错开，正负交替；重击时错得更开
                    float shift = accents ? (i % 2 == 0 ? 1 : -1) * SLICE * (1 + 2 * burst) : 0;
                    scars.add(new float[] {s.angle(), offset, s.bend(), shift});
                }
                // 受击火花：刀划过身体的那一拍炸开，下一拍缩小
                int since = frame - ExecutionTimeline.frame(s.contact());
                if (accents && mode == Mode.INK && since >= 0 && since <= 1 && sparks.size() < SPARKS) {
                    double nx = -Math.sin(s.angle()) * offset, ny = Math.cos(s.angle()) * offset;
                    sparks.add(new float[] {(float) nx, (float) ny, SPARK * s.force() * (since == 0 ? 1 : .55f), i});
                }
            }
        }
        float sparse = accents ? SPEED_FROM + (SPEED_TO - SPEED_FROM) * timeline.buildUp(age) : SPEED_SPARSE;

        if (uniforms == null) uniforms = new MappableRingBuffer(() -> "logos_dimension execution", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UNIFORM_SIZE);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        boolean zeroToOne = RenderSystem.getDevice().isZZeroToOne();
        try (GpuBuffer.MappedView view = encoder.mapBuffer(uniforms.currentBuffer(), false, true)) {
            var b = Std140Builder.intoBuffer(view.data()).putMat4f(inverse).putMat4f(bodyInverse)
                    .putVec4(run.width / 2 + MARGIN_SIDE, body.pivot() + MARGIN_UP, run.width / 2 + MARGIN_SIDE, (float) (feet.y - cam.y + .04))
                    .putVec4(mode.id, run.swapped(age) ? 1 : 0, seed, zeroToOne ? 1 : 0)
                    .putVec4(screen[0], screen[1], screen[2], aspect)
                    .putVec4(split, burst, timeline.shot().blackGround() ? 1 : 0, finisherProgress)
                    .putVec4(finisher.angle(), finisher.bend(), finisher.width() * BLADE, slamming ? 1 : 0)
                    .putVec4(0, sparse, scars.size(), sparks.size());
            for (int i = 0; i < SLASHES; i++) {
                float[] s = i < active.size() ? active.get(i) : null;
                if (s == null) b.putVec4(0, 0, 0, 0);
                else b.putVec4(s[0], s[1], s[2], s[3]);
            }
            for (int i = 0; i < SLASHES; i++) b.putVec4(i < active.size() ? active.get(i)[4] : 0, 0, 0, 0);
            for (int i = 0; i < SCARS; i++) {
                float[] s = i < scars.size() ? scars.get(i) : null;
                if (s == null) b.putVec4(0, 0, 0, 0);
                else b.putVec4(s[0], s[1], s[2], s[3]);
            }
            for (int i = 0; i < SPARKS; i++) {
                float[] s = i < sparks.size() ? sparks.get(i) : null;
                if (s == null) b.putVec4(0, 0, 0, 0);
                else b.putVec4(s[0], s[1], s[2], s[3]);
            }
        }
        encoder.copyTextureToTexture(main.getColorTexture(), copy.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (RenderPass pass = encoder.createRenderPass(() -> "logos_dimension execution", main.getColorTextureView(), OptionalInt.empty())) {
            pass.setPipeline(PIPELINE);
            pass.setUniform("Execution", uniforms.currentBuffer());
            pass.bindTexture("InSampler", copy.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.bindTexture("DepthSampler", copy.getDepthTextureView(), nearest);
            pass.draw(0, 3);
        }
        uniforms.rotate();
        passes++;
    }

    /** 身体中心在画面上的位置（0 到 1）和半径（按画面高度）。在镜头身后时退回画面中央。 */
    private static float[] screen(Minecraft mc, Camera camera, Vec3 pivot, float radius, float aspect) {
        var f = camera.forwardVector();
        if (pivot.subtract(camera.position()).dot(new Vec3(f.x(), f.y(), f.z())) <= 0) return new float[] {.5f, .5f, .25f};
        var u = camera.upVector();
        Vec3 c = mc.gameRenderer.projectPointToScreen(pivot);
        Vec3 top = mc.gameRenderer.projectPointToScreen(pivot.add(u.x() * radius, u.y() * radius, u.z() * radius));
        float r = (float) Math.hypot((top.x - c.x) * aspect, top.y - c.y) * .5f;
        return new float[] {(float) (c.x * .5 + .5), (float) (c.y * .5 + .5), Math.max(.05f, r)};
    }
}
