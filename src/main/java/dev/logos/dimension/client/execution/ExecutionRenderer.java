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
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 处决特写的全屏画面：整个画面换成平涂的两色，背景一色、目标的剪影一色，刀光、刀痕和速度线压在上面。
 * 剪影不靠模板缓冲：用深度还原每个像素的位置，落在目标身体的盒子里（盒子跟着身体转）就算身体。
 * 深度在半透明的实体画完之后留一份（玩家的模型是半透明的），所以水和玻璃不会挡住剪影，光影包也是同一条路。
 * <p>飞身刺有两个盒子（目标的和出手的人的，各有各的地面），剪影是两者合起来；刺中时一笔直的刺光加一朵火花。
 * 定格的那几刻（{@link ExecutionTimeline#freezing}）只在第一帧照常画，画完存一份，之后每帧直接贴这一份，不再读深度。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class ExecutionRenderer {
    private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(LogosDimension.id("pipeline/execution"))
            .withVertexShader(LogosDimension.id("post/execution"))
            .withFragmentShader(LogosDimension.id("post/execution"))
            .withSampler("InSampler")
            .withSampler("DepthSampler")
            .withSampler("TerrainSampler")
            .withUniform("Execution", UniformType.UNIFORM_BUFFER)
            .build();
    static final int SLASHES = 4, SCARS = 24, SPARKS = 4;
    private static final int UNIFORM_SIZE;
    static {
        var size = new Std140SizeCalculator().putMat4f().putMat4f();
        for (int i = 0; i < 6 + SLASHES * 2 + SCARS + SPARKS; i++) size.putVec4();
        // 飞身刺：出手的人的盒子（矩阵、半边长和地面），刺光两端，刺光的粗细
        size.putMat4f().putVec4().putVec4().putVec4();
        UNIFORM_SIZE = size.get();
    }
    /** 飞身刺时目标的盒子往上多留多少：贯穿古剑的剑柄、背上的剑都在头顶上面。 */
    private static final float THRUST_MARGIN_UP = .9f;
    /** 出手的人的盒子：左右各比身体宽多少；往前伸多远（手里的东西，长枪也大半在里面），往后多远（甩在身后的手和腿）。 */
    private static final float ACTOR_SIDE = .55f, ACTOR_FRONT = 1.9f, ACTOR_BACK = .75f;
    /** 刺中时手握在身体中心的哪（出手的人自己的坐标：x 朝左手、z 朝前；按右手惯用写，左撇子 x 取反）。 */
    private static final float GRIP_SIDE = -.3f, GRIP_AHEAD = .35f;
    /** 刺光：最粗的地方的半宽（按画面高度），刀尾从胸口往回拖多长，刀尖从胸口透出去多长；之后几拍一拍比一拍细。 */
    private static final float THRUST_BLADE = .05f, THRUST_TAIL = .62f, THRUST_PIERCE = .1f;
    private static final float[] THRUST_FADE = {1, .65f, .38f, .16f};
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
    private static long passes, held;
    /** 飞身刺定格的那一帧，和它是哪一段的。 */
    private static @Nullable RenderTarget frozen;
    private static ExecutionClient.@Nullable Run frozenRun;
    /** 飞身刺：只画了方块时的深度（实体还没画），和这一帧是不是留到了。 */
    private static @Nullable RenderTarget terrain;
    private static boolean terrainFresh;

    private ExecutionRenderer() {}

    @SubscribeEvent static void pipelines(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(PIPELINE);
    }

    /** 通道一共跑了多少次（客户端场景看有没有在画）。 */
    public static long passes() {
        return passes;
    }

    /** 一共有多少帧是直接贴的定格那一帧（客户端场景看飞身刺有没有定格）。 */
    public static long held() {
        return held;
    }

    /** 半透明的实体画完之后留一份深度：玩家的模型是半透明的，在这之前还没画；水和玻璃在这之后才画。 */
    @SubscribeEvent static void afterEntities(RenderLevelStageEvent.AfterTranslucentFeatures event) {
        if (ExecutionClient.viewing() == null) return;
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        target(main).copyDepthFrom(main);
    }

    /**
     * 飞身刺：不透明的方块画完、实体还没画时再留一份深度。剪影只认比它近的像素（实体），盒子里扫进来的方块
     * （顶块上的玄铁心、台阶）深度和它一样，不算身体。这一帧没留到（光影包没走这一步）时退回只看盒子。
     */
    @SubscribeEvent static void afterTerrain(RenderLevelStageEvent.AfterOpaqueBlocks event) {
        ExecutionClient.Run run = ExecutionClient.viewing();
        if (run == null || !run.thrust) return;
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (terrain == null || terrain.useStencil != main.useStencil) {
            if (terrain != null) terrain.destroyBuffers();
            terrain = new TextureTarget("logos_dimension execution terrain", main.width, main.height, true, main.useStencil);
        } else if (terrain.width != main.width || terrain.height != main.height) {
            terrain.resize(main.width, main.height);
        }
        terrain.copyDepthFrom(main);
        terrainFresh = true;
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
        if (timeline.thrust()) return switch (timeline.phase(age)) {
            // 原画顿帧压集中线；黑白一直到定格的尾巴结束
            case LAUNCH -> Mode.OVERLAY;
            case INK, AFTER -> Mode.INK;
            default -> Mode.NONE;
        };
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
        if (run == null || !run.thrust) release();
        if (run == null || mode == null || mode == Mode.NONE || copy == null) return;
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        if (copy.width != main.width || copy.height != main.height) return;
        if (run.thrust) {
            thrust(run, mode, mc, main);
            return;
        }
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
            // 没有出手的人、没有刺光
            b.putMat4f(new Matrix4f()).putVec4(0, 0, 0, 0).putVec4(0, 0, 0, 0).putVec4(0, 0, 0, 0);
        }
        pass(encoder, main, copy, copy);
    }

    /**
     * 主画面复制一份当输入，画一个盖满屏幕的三角形回主画面。{@code ground} 是只画了方块时的深度（飞身刺用；
     * 别的处决不看它，随便给一份能绑的深度）。
     */
    private static void pass(CommandEncoder encoder, RenderTarget main, RenderTarget copy, RenderTarget ground) {
        encoder.copyTextureToTexture(main.getColorTexture(), copy.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (RenderPass pass = encoder.createRenderPass(() -> "logos_dimension execution", main.getColorTextureView(), OptionalInt.empty())) {
            pass.setPipeline(PIPELINE);
            pass.setUniform("Execution", uniforms.currentBuffer());
            pass.bindTexture("InSampler", copy.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.bindTexture("DepthSampler", copy.getDepthTextureView(), nearest);
            pass.bindTexture("TerrainSampler", ground.getDepthTextureView(), nearest);
            pass.draw(0, 3);
        }
        uniforms.rotate();
        passes++;
    }

    /**
     * 飞身刺的一帧：两人的剪影（目标的盒子和出手的人的盒子合起来），集中线以两人之间为中心，刺中时一笔直的刺光和一朵火花。
     * 身体和刺光都一拍一拍地换。定格的那几刻只在第一帧照常画，画完存一份，之后每帧直接贴上去：不读深度，
     * 服务端趁这时把两人传送走，盒子里扫进地形、目标滑过方块都看不见。
     */
    private static void thrust(ExecutionClient.Run run, Mode mode, Minecraft mc, RenderTarget main) {
        // 这一帧留到了只画方块时的深度才拿它分方块和实体（只用一次，下一帧要重新留）
        RenderTarget blocks = terrainFresh && terrain != null && terrain.width == main.width && terrain.height == main.height ? terrain : null;
        terrainFresh = false;
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float age = run.age(partial);
        var timeline = run.timeline;
        boolean freezing = timeline.freezing(age);
        if (!freezing) frozenRun = null;
        else if (frozenRun == run && frozen != null && frozen.width == main.width && frozen.height == main.height) {
            RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(frozen.getColorTexture(), main.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
            held++;
            return;
        }
        Vec3 anchor = run.anchor(partial);
        if (anchor == null) return;
        // 定格的第一帧按定格开始那一刻画：停在后面某一刻截图时也是同一张
        float shown = freezing ? ExecutionTimeline.THRUST_FREEZE : age;
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cam = camera.position();
        float aspect = (float) main.width / main.height;
        Matrix4f inverse = new Matrix4f(WorldStyleRenderer.PROJECTION).mul(camera.getViewRotationMatrix(new Matrix4f())).invert();

        // 目标：不离地，刺中的下一拍往后仰；盒子往上多留一些
        ExecutionClient.Body body = run.body(age);
        Vec3 rel = anchor.add(0, body.pivot(), 0).subtract(cam);
        Matrix4f bodyInverse = new Matrix4f().rotation(new Quaternionf(body.rotation()).conjugate()).translate((float) -rel.x, (float) -rel.y, (float) -rel.z);

        // 出手的人：黑白之前在他自己站的地方，之后按飞身刺摆；盒子跟着他转（连朝向一起），往前伸到手里的东西
        Entity actor = run.actor < 0 ? null : run.level.getEntity(run.actor);
        Matrix4f actorInverse = new Matrix4f();
        float[] actorBox = {0, 0, 0, 0};
        Vec3 actorCentre = anchor.add(run.forward().scale(4)).add(0, run.actorPivot, 0);
        Quaternionf frame = new Quaternionf();
        if (actor != null) {
            float ground;
            if (age < ExecutionTimeline.THRUST_INK) {
                Vec3 feet = actor.getPosition(partial);
                actorCentre = feet.add(0, run.actorPivot, 0);
                float bodyYaw = actor instanceof LivingEntity living ? Mth.rotLerp(partial, living.yBodyRotO, living.yBodyRot) : actor.getYRot();
                frame.rotationY((float) -Math.toRadians(bodyYaw));
                ground = (float) (feet.y - cam.y + .04);
            } else {
                ExecutionClient.Run.Leap leap = run.leap(age);
                actorCentre = run.actorCentre(anchor, leap);
                frame = run.actorFrame(leap.lean());
                ground = (float) (anchor.y - cam.y + .04);
            }
            Vec3 actorRel = actorCentre.subtract(cam);
            float half = (ACTOR_FRONT + ACTOR_BACK) / 2;
            actorInverse.translation(0, 0, -(ACTOR_FRONT - ACTOR_BACK) / 2).rotate(new Quaternionf(frame).conjugate())
                    .translate((float) -actorRel.x, (float) -actorRel.y, (float) -actorRel.z);
            actorBox = new float[] {actor.getBbWidth() / 2 + ACTOR_SIDE, run.actorPivot + MARGIN_UP, half, ground};
        }

        // 集中线以两人之间为中心，两人越近收得越紧
        Vec3 chest = run.chest(anchor);
        Vec3 middle = actorCentre.add(chest).scale(.5);
        float[] screen = screen(mc, camera, middle, (float) (actorCentre.distanceTo(chest) * .25 + .6), aspect);

        // 刺中的那一拍和之后几拍：刺光从出手的人身后穿过手、刺进胸口再透出去一点，一拍比一拍细；胸口炸一朵火花
        int since = ExecutionTimeline.frame(shown) - ExecutionTimeline.frame(ExecutionTimeline.THRUST_CONTACT);
        float[] stroke = {0, 0, 0, 0};
        float width = 0;
        float[] spark = null;
        float[] at = point(mc, camera, chest, screen, aspect);
        if (actor != null && at != null && since >= 0 && since < THRUST_FADE.length) {
            Vec3 grip = actorCentre.add(toVec3(frame.transform(new Vector3f(run.leftHanded() ? -GRIP_SIDE : GRIP_SIDE, 0, GRIP_AHEAD))));
            float[] from = point(mc, camera, grip, screen, aspect);
            if (from != null) {
                float dx = at[0] - from[0], dy = at[1] - from[1];
                float length = (float) Math.hypot(dx, dy);
                if (length > 1e-4f) {
                    dx /= length;
                    dy /= length;
                    stroke = new float[] {at[0] - dx * THRUST_TAIL, at[1] - dy * THRUST_TAIL, at[0] + dx * THRUST_PIERCE, at[1] + dy * THRUST_PIERCE};
                    width = THRUST_BLADE * THRUST_FADE[since];
                }
            }
            if (since <= 1) spark = new float[] {at[0], at[1], SPARK * (since == 0 ? 1.25f : .7f), 3};
        }
        float split = since == 0 ? .002f : 0;

        // 原画顿帧和刺中以后集中线停住不闪（顿帧），蓄势、飞身时一拍换一次，越飞越密
        float beat = ExecutionTimeline.stepped(shown);
        float seed = ExecutionTimeline.frame(shown < ExecutionTimeline.THRUST_INK ? 0 : Math.min(shown, ExecutionTimeline.THRUST_CONTACT)) + timeline.shot().seed() % 97;
        float sparse = shown < ExecutionTimeline.THRUST_INK ? SPEED_SPARSE : Mth.lerp(Mth.clamp((beat - ExecutionTimeline.THRUST_LEAP)
                / (ExecutionTimeline.THRUST_CONTACT - ExecutionTimeline.THRUST_LEAP), 0, 1), SPEED_FROM, SPEED_TO);

        if (uniforms == null) uniforms = new MappableRingBuffer(() -> "logos_dimension execution", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UNIFORM_SIZE);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        boolean zeroToOne = RenderSystem.getDevice().isZZeroToOne();
        try (GpuBuffer.MappedView view = encoder.mapBuffer(uniforms.currentBuffer(), false, true)) {
            var b = Std140Builder.intoBuffer(view.data()).putMat4f(inverse).putMat4f(bodyInverse)
                    .putVec4(run.width / 2 + MARGIN_SIDE, body.pivot() + THRUST_MARGIN_UP, run.width / 2 + MARGIN_SIDE, (float) (anchor.y - cam.y + .04))
                    .putVec4(mode.id, 0, seed, zeroToOne ? 1 : 0)
                    .putVec4(screen[0], screen[1], screen[2], aspect)
                    .putVec4(split, 0, timeline.shot().blackGround() ? 1 : 0, 0)
                    .putVec4(0, 0, 0, 0)
                    .putVec4(0, sparse, 0, spark == null ? 0 : 1);
            for (int i = 0; i < SLASHES * 2 + SCARS; i++) b.putVec4(0, 0, 0, 0);
            for (int i = 0; i < SPARKS; i++) {
                if (i == 0 && spark != null) b.putVec4(spark[0], spark[1], spark[2], spark[3]);
                else b.putVec4(0, 0, 0, 0);
            }
            b.putMat4f(actorInverse).putVec4(actorBox[0], actorBox[1], actorBox[2], actorBox[3])
                    .putVec4(stroke[0], stroke[1], stroke[2], stroke[3]).putVec4(width, blocks != null ? 1 : 0, 0, 0);
        }
        pass(encoder, main, copy, blocks != null ? blocks : copy);
        if (freezing) {
            // 存下定格的这一帧
            encoder.copyTextureToTexture(main.getColorTexture(), frozen(main).getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
            frozenRun = run;
        }
    }

    /** 放定格那一帧的地方，尺寸跟着主画面。 */
    private static RenderTarget frozen(RenderTarget main) {
        if (frozen == null) frozen = new TextureTarget("logos_dimension execution frozen", main.width, main.height, false);
        else if (frozen.width != main.width || frozen.height != main.height) frozen.resize(main.width, main.height);
        return frozen;
    }

    /** 没在看飞身刺：放掉定格那一帧和只画了方块时的深度。 */
    private static void release() {
        frozenRun = null;
        terrainFresh = false;
        if (frozen != null) {
            frozen.destroyBuffers();
            frozen = null;
        }
        if (terrain != null) {
            terrain.destroyBuffers();
            terrain = null;
        }
    }

    /** {@code at} 在画面上相对 {@code screen} 中心的位置（按画面高度，横向乘了宽高比，和着色器里一样）；在镜头身后时是 null。 */
    private static float @Nullable [] point(Minecraft mc, Camera camera, Vec3 at, float[] screen, float aspect) {
        var f = camera.forwardVector();
        if (at.subtract(camera.position()).dot(new Vec3(f.x(), f.y(), f.z())) <= 0) return null;
        Vec3 c = mc.gameRenderer.projectPointToScreen(at);
        return new float[] {(float) ((c.x * .5 + .5 - screen[0]) * aspect), (float) (c.y * .5 + .5 - screen[1])};
    }

    private static Vec3 toVec3(Vector3f v) {
        return new Vec3(v.x, v.y, v.z);
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
