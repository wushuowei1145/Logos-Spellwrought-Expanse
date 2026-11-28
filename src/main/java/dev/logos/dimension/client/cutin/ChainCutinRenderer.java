package dev.logos.dimension.client.cutin;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.companion.CompanionStyle;
import dev.logos.dimension.companion.CompanionEntity;
import dev.logos.dimension.companion.CompanionSheet;
import dev.logos.dimension.cutin.ChainCutin;
import dev.logos.dimension.cutin.ChainCutinPayload;
import dev.logos.dimension.cutin.ChainLayout;
import dev.logos.dimension.cutin.ChainTimeline;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * 本机的连携切入（{@link ChainCutin}）。分两步，都在世界画完之后、界面之前：
 *
 * <ol>
 *   <li><b>角色</b>：每一格的角色单独画进自己的一张贴图，做法和原版物品栏里的小人一样（{@code GuiEntityRenderer}：
 *       把输出临时改到自己的贴图、换一个投影、照常提交实体），只是换成仰拍的透视镜头，而且每帧都在动（冲镜、推、扫、歪，
 *       见 {@link CutinShot}）。不再渲染一遍世界，光影包只管世界渲染，这里的角色在原版、Sodium、Iris 下画出来一样。
 *       同伴画的是她的展示模型（角色面板里那个，带她自己的姿势和表情、现在穿的那一套），玩家画他本人（皮肤、手里的东西），摆出架势。</li>
 *   <li><b>合成</b>：一个全屏通道把主画面复制一份当底，压暗战场，再画黑底、斜切的格子、格子里她自己颜色的能量底、角色和逆光描边、
 *       她的光屑、带焰的分割线、白光云和收尾的光幕。格子是按分割线直接算的，不用模板缓冲。</li>
 * </ol>
 *
 * <p>每一格的镜头、光屑、颜色都照那个人自己的档案（同伴的 {@link CompanionStyle.Cutin}，玩家的 {@link HeroCutin}），这里不写死谁是什么样。
 *
 * <p>按游戏刻计时（{@link ChainTimeline}），暂停时停。换维度、退出世界、播完时结束，并释放角色贴图。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class ChainCutinRenderer {
    private static final int LINES = 6, SLOTS = 5;
    private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(LogosDimension.id("pipeline/chain_cutin"))
            .withVertexShader(LogosDimension.id("post/chain_cutin"))
            .withFragmentShader(LogosDimension.id("post/chain_cutin"))
            .withSampler("InSampler")
            .withSampler("Portrait0")
            .withSampler("Portrait1")
            .withSampler("Portrait2")
            .withSampler("Portrait3")
            .withSampler("Portrait4")
            .withUniform("ChainCutin", UniformType.UNIFORM_BUFFER)
            .build();
    private static final int UNIFORM_SIZE;

    static {
        Std140SizeCalculator size = new Std140SizeCalculator();
        for (int i = 0; i < 6 + LINES * 2 + SLOTS * 4; i++) size.putVec4();
        UNIFORM_SIZE = size.get();
    }

    /**
     * 特写怎么取景：画面高度装她身上多高的一段（格），眼睛落在离画面顶多远（画面高度的几成）。同伴是大头的二头身，头约 0.4 格，
     * 装到腰，竖在身侧、高过头顶的武器也在画面里；玩家头约 0.47 格、身子长，装得再多一点，不是一张大脸。
     */
    record Framing(float height, float eyeFromTop) {
        static final Framing COMPANION = new Framing(1.15f, .3f);
        static final Framing PLAYER = new Framing(1.6f, .24f);
    }

    /** 仰拍多少度（镜头在对准的点下面，往上看）。 */
    private static final float LOOK_UP = 5;
    /** 拖影拖多长：她刚才（这么多刻以前）在哪，就从那里拖到现在的位置。 */
    private static final float SMEAR = .35f;
    /**
     * 角色贴图往画面下面多画多少（画面高度的几成）：冲进来、甩出去时格子里露出的是她的身子，而不是贴图的下边。
     * 动得最快的头一两帧还是会露出来，那时贴图下边已经淡掉了。
     */
    static final float EXTEND = .45f;
    /** 角色贴图的分辨率上限（画面那一段的高度，像素）：4K 下按这个缩，五张贴图不至于占太多显存。 */
    private static final int MAX_PORTRAIT_HEIGHT = 1440;
    /** 展示模型快进到第几刻才算摆好（表情、姿势控制器的过渡最长 2 刻，留足余量）。 */
    private static final int WARM_TICKS = 8;
    /** 角色身子朝中间侧多少（度，每离中间一个画面宽度）；头回过来看着镜头的比例。 */
    private static final float TURN = 40, LOOK_BACK = .75f;

    /**
     * 一格里的人在切入里的档案，摊平成一样的：同伴的 {@link CompanionStyle.Cutin} 加上她界面的主色、底色（{@code night0}），
     * 玩家的 {@link HeroCutin}。
     */
    private record Look(float turn, CutinShot shot, CutinMotes motes, int accent, int night) {
        /** 同伴还没加载出来（她那一格是空的）：淡蓝的空格子。 */
        static final Look ABSENT = new Look(0, HeroCutin.PLAYER.shot(), new CutinMotes(CutinMotes.Kind.GLINT, 0xB9C6F2), 0xB9C6F2, 0x14142A);

        static Look of(HeroCutin hero) {
            return new Look(hero.turn(), hero.shot(), hero.motes(), hero.accent(), hero.night());
        }

        static Look of(CompanionStyle style) {
            CompanionStyle.Cutin c = style.cutin();
            return new Look(c.turn(), c.shot(), c.motes(), style.accent() & 0xFFFFFF, style.night0() & 0xFFFFFF);
        }
    }

    private static @Nullable ChainCutin cutin;
    private static @Nullable ChainLayout layout;
    private static @Nullable ResourceKey<Level> dimension;
    private static long startTick;
    private static boolean gentle;
    /** 客户端场景用：停在第几刻上（小于 0 不停）。 */
    private static float held = -1;
    private static long passes, portraitsDrawn, hudHidden;
    /** 同伴的展示模型（不放进世界），按「同伴|装束」存，播完清掉。 */
    private static final Map<String, LivingEntity> models = new HashMap<>();
    private static final @Nullable RenderTarget[] portraits = new RenderTarget[SLOTS];
    private static @Nullable RenderTarget copy;
    private static @Nullable MappableRingBuffer uniforms;
    /** 每一格一个投影矩阵缓冲：一帧里五张贴图的镜头不一样，不在同一个缓冲上来回改写。 */
    private static final @Nullable ProjectionMatrixBuffer[] projections = new ProjectionMatrixBuffer[SLOTS];

    private ChainCutinRenderer() {}

    @SubscribeEvent static void payloads(RegisterClientPayloadHandlersEvent event) {
        event.register(ChainCutinPayload.TYPE, (payload, context) -> context.enqueueWork(() -> play(payload.cutin())));
    }

    @SubscribeEvent static void pipelines(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(PIPELINE);
    }

    @SubscribeEvent static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        stop();
    }

    /** 本机开始放一次（也给客户端场景直接调）。 */
    public static void play(ChainCutin next) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        cutin = next;
        layout = ChainLayout.of(next.members().size());
        dimension = mc.level.dimension();
        startTick = mc.level.getGameTime();
        gentle = mc.options.hideLightningFlash().get();
        models.clear();
    }

    /** 客户端场景用：让它停在第 {@code age} 刻的样子，传负数放开。 */
    public static void hold(float age) {
        held = age;
    }

    public static boolean active() {
        return cutin != null;
    }

    /** 通道一共跑了多少次、画了多少张角色（客户端场景看有没有在画）。 */
    public static long passes() {
        return passes;
    }

    public static long portraitsDrawn() {
        return portraitsDrawn;
    }

    /** 现在放到第几刻；没在放时是负数。 */
    public static float age(float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (cutin == null || mc.level == null) return -1;
        if (held >= 0) return held;
        // 时钟可能被换掉（测试框架会），不让它倒着走
        return Math.max(0, mc.level.getGameTime() - startTick + partialTick);
    }

    private static void stop() {
        cutin = null;
        layout = null;
        dimension = null;
        models.clear();
        for (int i = 0; i < SLOTS; i++) {
            if (portraits[i] != null) portraits[i].destroyBuffers();
            portraits[i] = null;
        }
    }

    /** 黑底盖着的那一段不画界面；光幕盖满时界面回来。排在最后，取消的是已经收集好的整层界面。 */
    @SubscribeEvent(priority = EventPriority.LOWEST) static void hud(RenderGuiEvent.Pre event) {
        float age = age(event.getPartialTick().getGameTimeDeltaPartialTick(false));
        if (age >= 0 && ChainTimeline.hidesHud(age)) {
            event.setCanceled(true);
            hudHidden++;
        }
    }

    /** 一共有几帧把界面藏了（客户端场景看界面藏没藏、回没回来）。 */
    public static long hudHidden() {
        return hudHidden;
    }

    /** 由 Mixin 在世界画完之后调用，排在所有全屏通道最后：它是盖在整个画面上的 2D 层。 */
    public static void draw() {
        Minecraft mc = Minecraft.getInstance();
        if (cutin == null) return;
        if (mc.level == null || !mc.level.dimension().equals(dimension)) {
            stop();
            return;
        }
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float age = age(partial);
        if (age >= ChainTimeline.END) {
            if (held < 0) stop();
            return;
        }
        RenderTarget main = mc.getMainRenderTarget();
        boolean faces = ChainTimeline.portraits(age);
        boolean[] present = new boolean[SLOTS];
        Look[] looks = looks();
        // 刺穿那一拍就开始画（不显示）：展示模型的表情、姿势要先过渡到位，冲进来时才是摆好的样子
        if (age < ChainTimeline.SWAP) {
            warmUp();
            drawPortraits(mc, main, partial, age, looks, present);
        }
        pass(main, age, faces, looks, present);
        passes++;
    }

    /**
     * 展示模型不在世界里，不会自己长大：GeckoLib 的动画按它的 {@code tickCount} 走，不推就一直停在过渡的起点，
     * 几层表情全露出来（角色面板、友人帐也是每刻推一下）。刚造出来的先每帧快进，过渡走完再露脸；之后每刻推一下。
     */
    private static void warmUp() {
        for (LivingEntity m : models.values()) if (!inWorld(m) && m.tickCount < WARM_TICKS) m.tickCount += 2;
    }

    @SubscribeEvent static void tick(ClientTickEvent.Post event) {
        if (cutin == null) return;
        for (LivingEntity m : models.values()) if (!inWorld(m)) m.tickCount++;
    }

    private static boolean inWorld(LivingEntity e) {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.getEntity(e.getId()) == e;
    }

    /** 角色贴图有多宽（画面宽度）：比最宽的格子（四人时右边延到屏幕边上那一格）还宽，格子里看不到贴图的边。 */
    private static float portraitWidth() {
        return .5f;
    }

    // ---------------------------------------------------------------- 角色

    private static void drawPortraits(Minecraft mc, RenderTarget main, float partial, float age, Look[] looks, boolean[] present) {
        if (cutin == null || layout == null || mc.level == null) return;
        float scale = Math.min(1, MAX_PORTRAIT_HEIGHT / (float) Math.max(1, main.height));
        int w = Math.max(16, Math.round(main.width * portraitWidth() * scale)), h = Math.max(16, Math.round(main.height * (1 + EXTEND) * scale));
        List<ChainLayout.Slot> slots = layout.slots();
        for (int i = 0; i < slots.size() && i < SLOTS; i++) {
            ChainLayout.Slot slot = slots.get(i);
            boolean player = slot.member() == ChainLayout.PLAYER;
            LivingEntity who = player ? leader(mc) : companion(mc, cutin.members().get(slot.member()));
            if (who == null) continue;
            RenderTarget target = portrait(i, w, h);
            try {
                drawPortrait(mc, target, i, slot, who, partial, age, looks[i], player);
                present[i] = true;
                portraitsDrawn++;
            } finally {
                RenderSystem.outputColorTextureOverride = null;
                RenderSystem.outputDepthTextureOverride = null;
            }
        }
    }

    /** 每一格的人在切入里的档案（每帧取：同伴可能刚加载出来）。 */
    private static Look[] looks() {
        Look[] looks = new Look[SLOTS];
        Arrays.fill(looks, Look.ABSENT);
        if (cutin == null || layout == null) return looks;
        Minecraft mc = Minecraft.getInstance();
        List<ChainLayout.Slot> slots = layout.slots();
        for (int i = 0; i < slots.size() && i < SLOTS; i++) {
            int member = slots.get(i).member();
            if (member == ChainLayout.PLAYER) {
                looks[i] = Look.of(HeroCutin.PLAYER);
            } else if (mc.level != null && member < cutin.members().size()
                    && mc.level.getEntity(cutin.members().get(member)) instanceof CompanionEntity c) {
                looks[i] = Look.of(CompanionStyle.of(c.def().id()));
            }
        }
        return looks;
    }

    private static @Nullable LivingEntity leader(Minecraft mc) {
        if (cutin == null || mc.level == null) return null;
        Entity e = mc.level.getEntity(cutin.leader());
        if (e instanceof LivingEntity l) return l;
        return mc.player != null && mc.player.getId() == cutin.leader() ? mc.player : null;
    }

    /** 同伴那一格画她在切入里的样子（{@link CompanionStyle.Cutin}：她自己的切入姿势和表情）；造不出来时画她本人。 */
    private static @Nullable LivingEntity companion(Minecraft mc, int id) {
        if (mc.level == null || !(mc.level.getEntity(id) instanceof CompanionEntity c)) return null;
        String companion = c.def().id(), outfit = c.outfitId();
        LivingEntity model = models.computeIfAbsent(companion + "|" + outfit, k -> {
            CompanionSheet sheet = new CompanionSheet(-1, companion, outfit, List.of(), List.of(), 0, 0, "", 0, 0, 0, false, "adaptive", false, 0, 0, 0);
            LivingEntity made = CompanionStyle.of(companion).cutin().make().apply(mc.level, sheet);
            return made != null ? made : c;
        });
        return model;
    }

    private static RenderTarget portrait(int i, int w, int h) {
        RenderTarget t = portraits[i];
        if (t == null) {
            t = portraits[i] = new TextureTarget("logos_dimension chain cutin portrait " + i, w, h, true, false);
        } else if (t.width != w || t.height != h) {
            t.resize(w, h);
        }
        return t;
    }

    /**
     * 把 {@code who} 画进 {@code target}：她站在原点，身子朝中间侧一点（再加上档案里的 {@code turn}）、头回过来看着镜头；
     * 镜头在她前面略低处往上看，照她档案里的 {@link CutinShot} 动：冲进来时从远处跟着冲过来、落位时冲过头一点，停留时推近、横扫、升降，
     * 一直歪着（头往格子外侧倒）。视锥按停在原位时定，镜头推近她就变大。输出临时改到这张贴图上，和原版画物品栏小人时一样。
     */
    private static void drawPortrait(Minecraft mc, RenderTarget target, int index, ChainLayout.Slot slot, LivingEntity who, float partial, float age,
                                     Look look, boolean player) {
        float yaw = (slot.center() - .5f) * TURN + look.turn();
        if (!inWorld(who)) {
            // 展示模型不在世界里：照角色面板的做法摆好朝向再取状态
            who.yBodyRot = who.yBodyRotO = yaw;
            who.setYRot(yaw);
            who.yRotO = yaw;
            who.setYHeadRot(yaw * (1 - LOOK_BACK));
            who.yHeadRotO = yaw * (1 - LOOK_BACK);
            who.setXRot(0);
            who.xRotO = 0;
        }
        var dispatcher = mc.getEntityRenderDispatcher();
        EntityRenderState state = dispatcher.getRenderer(who).createRenderState(who, partial);
        state.shadowPieces.clear();
        state.outlineColor = 0;
        state.nameTag = null;
        state.scoreText = null;
        state.leashStates = null;
        state.displayFireAnimation = false;
        // 不管她站在山洞还是夜里，特写都按满亮画；明暗交给合成时的逆光
        state.lightCoords = 0xF000F0;
        state.x = state.y = state.z = 0;
        if (state instanceof LivingEntityRenderState l) {
            l.bodyRot = yaw;
            // 头回过来看镜头：GeckoLib 的头跟原版人形模型转的方向相反
            l.yRot = (player ? -1 : 1) * yaw * LOOK_BACK;
            l.xRot = 4;
            l.deathTime = 0;
            l.hasRedOverlay = false;
            l.walkAnimationSpeed = 0;
            l.pose = net.minecraft.world.entity.Pose.STANDING;
        }
        if (state instanceof net.minecraft.client.renderer.entity.state.HumanoidRenderState hs) {
            // 蹲着、游泳、滑翔时也站直了拍
            hs.isCrouching = false;
            hs.isFallFlying = false;
            hs.isVisuallySwimming = false;
            hs.swimAmount = 0;
            hs.isUsingItem = false;
        }
        if (player && state instanceof ArmedEntityRenderState a) {
            // 手的角度由档案里的架势定，原版的持物姿势（举弓、格挡……）不叠上去
            a.rightArmPose = HumanoidModel.ArmPose.ITEM;
            a.leftArmPose = HumanoidModel.ArmPose.EMPTY;
            if (a.mainArm == HumanoidArm.LEFT) {
                a.leftArmPose = HumanoidModel.ArmPose.ITEM;
                a.rightArmPose = HumanoidModel.ArmPose.EMPTY;
            }
            state.setRenderData(HeroCutin.POSE, HeroCutin.PLAYER.pose());
        }

        Framing framing = player ? Framing.PLAYER : Framing.COMPANION;
        float eye = state.eyeHeight > .2f ? state.eyeHeight : Math.max(.5f, state.boundingBoxHeight * .9f);
        float frame = framing.height();
        float aimY = eye - (.5f - framing.eyeFromTop()) * frame;
        CutinShot shot = look.shot();
        int order = slot.order();
        // 停在原位时：画面正好装 frame 那么高，视角越广镜头越近
        float base = frame / 2 / (float) Math.tan(Math.toRadians(shot.fov() / 2));
        float below = (float) Math.tan(Math.toRadians(LOOK_UP)) * base;
        float progress = ChainTimeline.progress(age, order);
        float distance = base * (1 + ChainTimeline.approach(age, order) - shot.push() * progress);
        double orbit = Math.toRadians(shot.orbit() * (progress - .5f));
        float camX = (float) Math.sin(orbit) * distance, camZ = (float) Math.cos(orbit) * distance;
        float camY = aimY - below + shot.crane() * frame * (progress - .5f);
        // 头往格子外侧倒（正中那一格往右）：左边的格子画面逆时针转
        float side = Math.abs(slot.center() - .5f) < .01f ? 1 : Math.signum(slot.center() - .5f);
        float lean = side * shot.lean() * (1 + CutinShot.LEAN_SETTLE * ChainTimeline.settle(age, order));

        // 镜头对准画面那一段的正中；贴图往下多画 EXTEND，所以视锥上下不对称
        float near = .05f, k = near / (float) Math.sqrt(base * base + below * below);
        float halfWidth = frame * (1 + EXTEND) * target.width / (float) target.height / 2;
        Matrix4f projection = new Matrix4f().setFrustum(-halfWidth * k, halfWidth * k, -(frame / 2 + EXTEND * frame) * k, frame / 2 * k, near, 64,
                RenderSystem.getDevice().isZZeroToOne());
        Matrix4f view = new Matrix4f().lookAt(camX, camY, camZ, 0, aimY, 0, 0, 1, 0);
        Quaternionf rotation = new Quaternionf().rotateZ((float) Math.toRadians(-lean)).mul(view.getNormalizedRotation(new Quaternionf()));
        PoseStack poseStack = new PoseStack();
        poseStack.mulPose(rotation);
        poseStack.translate(-camX, -camY, -camZ);

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.clearColorAndDepthTextures(target.getColorTexture(), 0, target.getDepthTexture(), 1.0);
        RenderSystem.outputColorTextureOverride = target.getColorTextureView();
        RenderSystem.outputDepthTextureOverride = target.getDepthTextureView();
        if (projections[index] == null) projections[index] = new ProjectionMatrixBuffer("logos_dimension chain cutin portrait " + index);
        RenderSystem.setProjectionMatrix(projections[index].getBuffer(projection), ProjectionType.PERSPECTIVE);
        // 镜头差不多是正着看的，世界的两道光照方向（从上方斜照）在这里也成立
        mc.gameRenderer.getLighting().setupFor(Lighting.Entry.LEVEL);
        CameraRenderState camera = new CameraRenderState();
        camera.orientation = new Quaternionf(rotation).conjugate();
        FeatureRenderDispatcher features = mc.gameRenderer.getFeatureRenderDispatcher();
        dispatcher.submit(state, camera, 0, 0, 0, poseStack, features.getSubmitNodeStorage());
        features.renderAllFeatures();
        mc.renderBuffers().bufferSource().endBatch();
    }

    // ---------------------------------------------------------------- 合成

    private static RenderTarget copy(RenderTarget main) {
        if (copy == null) {
            copy = new TextureTarget("logos_dimension chain cutin", main.width, main.height, false, false);
        } else if (copy.width != main.width || copy.height != main.height) {
            copy.resize(main.width, main.height);
        }
        return copy;
    }

    private static void pass(RenderTarget main, float age, boolean faces, Look[] looks, boolean[] present) {
        if (cutin == null || layout == null) return;
        RenderTarget input = copy(main);
        if (uniforms == null) uniforms = new MappableRingBuffer(() -> "logos_dimension chain cutin", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UNIFORM_SIZE);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToTexture(main.getColorTexture(), input.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);

        List<ChainLayout.Line> lines = layout.lines();
        List<ChainLayout.Slot> slots = layout.slots();
        float aspect = main.height / (float) Math.max(1, main.width);
        float lineStrength = age < ChainTimeline.SWAP ? 1 : ChainTimeline.residual(age);
        try (GpuBuffer.MappedView view = encoder.mapBuffer(uniforms.currentBuffer(), false, true)) {
            Std140Builder b = Std140Builder.intoBuffer(view.data());
            b.putVec4(main.width, main.height, age, cutin.seed() & 0xFFF);
            b.putVec4(ChainTimeline.dim(age), ChainTimeline.plate(age, gentle), ChainTimeline.backdrop(age), faces ? 1 : 0);
            b.putVec4(ChainTimeline.cloud(age, gentle), ChainTimeline.veil(age, gentle), ChainTimeline.beam(age), lineStrength);
            b.putVec4(lines.size(), ChainTimeline.exposure(age, gentle), ChainTimeline.shake(age, cutin.seed(), false) * aspect,
                    ChainTimeline.shake(age, cutin.seed(), true));
            b.putVec4(layout.fullPlate() ? 1 : 0, EXTEND, portraitWidth(), 0);
            putColor(b, veilTint(looks, slots.size()), 0);
            for (int i = 0; i < LINES; i++) {
                if (i >= lines.size()) {
                    b.putVec4(-9, -9, 0, 1);
                    continue;
                }
                ChainLayout.Line l = lines.get(i);
                float dx = (float) Math.tan(Math.toRadians(l.tilt())) * aspect * .5f;
                b.putVec4(l.x() - dx, l.x() + dx, ChainTimeline.grow(age, i, l.pierce()), l.edge() ? 1 : 0);
            }
            for (int i = 0; i < LINES; i++) putColor(b, i < lines.size() ? lineColor(i, slots, looks) : 0xFFFFFF, 0);
            for (int i = 0; i < SLOTS; i++) {
                if (i >= slots.size()) {
                    b.putVec4(-9, 0, 0, 0);
                    continue;
                }
                int order = slots.get(i).order();
                float slide = ChainTimeline.slide(age, i, order);
                b.putVec4(slots.get(i).center(), slide, ChainTimeline.slide(age - SMEAR, i, order) - slide, present[i] ? 1 : 0);
            }
            for (int i = 0; i < SLOTS; i++) putColor(b, looks[i].accent(), ChainTimeline.direction(i));
            for (int i = 0; i < SLOTS; i++) putColor(b, looks[i].night(), 0);
            for (int i = 0; i < SLOTS; i++) putColor(b, looks[i].motes().color(), looks[i].motes().kind().ordinal());
        }
        var linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        try (RenderPass pass = encoder.createRenderPass(() -> "logos_dimension chain cutin", main.getColorTextureView(), OptionalInt.empty())) {
            pass.setPipeline(PIPELINE);
            pass.setUniform("ChainCutin", uniforms.currentBuffer());
            pass.bindTexture("InSampler", input.getColorTextureView(), linear);
            for (int i = 0; i < SLOTS; i++) {
                RenderTarget t = faces && present[i] ? portraits[i] : null;
                pass.bindTexture("Portrait" + i, t != null ? t.getColorTextureView() : input.getColorTextureView(), linear);
            }
            pass.draw(0, 3);
        }
        uniforms.rotate();
    }

    private static void putColor(Std140Builder b, int rgb, float w) {
        b.putVec4(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f, w);
    }

    /** 光幕四周的颜色：在场各人主色的平均。 */
    private static int veilTint(Look[] looks, int count) {
        int r = 0, g = 0, b = 0, n = Math.max(1, Math.min(count, SLOTS));
        for (int i = 0; i < n; i++) {
            r += (looks[i].accent() >> 16) & 0xFF;
            g += (looks[i].accent() >> 8) & 0xFF;
            b += looks[i].accent() & 0xFF;
        }
        return (r / n) << 16 | (g / n) << 8 | b / n;
    }

    /** 分割线的外焰取两边离中间远的那一格的主色（贴着玩家的线就是同伴的颜色）。 */
    private static int lineColor(int line, List<ChainLayout.Slot> slots, Look[] looks) {
        int left = line - 1, right = line;
        boolean hasLeft = left >= 0 && left < slots.size(), hasRight = right < slots.size();
        if (!hasLeft && !hasRight) return 0xFFFFFF;
        int pick;
        if (!hasLeft) pick = right;
        else if (!hasRight) pick = left;
        else pick = Math.abs(slots.get(left).center() - .5f) >= Math.abs(slots.get(right).center() - .5f) ? left : right;
        return looks[pick].accent();
    }

    /** 测试用：现在的分格（没在放时是 null）。 */
    public static @Nullable ChainLayout layout() {
        return layout;
    }
}
