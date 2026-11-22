package dev.logos.dimension.client.execution;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.camera.CameraPoint;
import dev.logos.dimension.camera.CameraShot;
import dev.logos.dimension.camera.CameraShots;
import dev.logos.dimension.client.camera.CameraDirector;
import dev.logos.dimension.execution.ExecutionPayload;
import dev.logos.dimension.execution.ExecutionTimeline;
import dev.logos.dimension.execution.ExecutionTimeline.Cut;
import dev.logos.dimension.execution.ExecutionTimeline.Phase;
import dev.logos.dimension.execution.ExecutionTimeline.Slash;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 本机的处决特写。每个被处决的实体一段 {@link Run}：身体怎么被抛起、挨打、拍下，按时间表算出来交给实体渲染（所有看得见它的玩家都有）；
 * 看特写的那一段还接管镜头（{@link CameraDirector.Rig}：三个机位硬切、每刀抖一下、视角收窄再猛地推近）和全屏画面（{@link ExecutionRenderer}）。
 * 身体的样子和画面都由时间表直接算出，同一刻在实体渲染和全屏通道里算出来一模一样，剪影才对得上。
 * <p>可交互的处决（{@link dev.logos.dimension.execution.InteractiveExecutions}）：刀不是种子定好的，处决的人点一下左键这边先画一刀
 * 并告诉服务端，被处决的玩家和旁观的人收到服务端发来的每一刀再画。旁观的人还在世界里看到刀光（{@link ExecutionSlashes}）。
 * <p>飞身刺（{@link dev.logos.dimension.execution.ExecutionShot.Choreo#THRUST}）：一段摆两个实体，目标和出手的人。两人都按目标这一帧
 * 插值后的位置摆（出手的人的位移相对他自己插值后的位置算），所以谁被传送了都看不出来；出手的人的四肢直接摆成 {@link Pose}。
 * 接着演的运镜一接走镜头，这一段马上结束，不再摆这两个人。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class ExecutionClient {
    /** 实体渲染状态上带的身体姿态。 */
    public static final ContextKey<Body> BODY = new ContextKey<>(LogosDimension.id("execution_body"));
    /** 特写时的视角（度），比平常窄，像长焦镜头：背景少、人物大。 */
    private static final float SHOT_FOV = 55;
    /** 躺倒在地上时身体倒下多少度。 */
    private static final float LIE = (float) Math.toRadians(80);
    private static final Vector3f UP = new Vector3f(0, 1, 0);
    /** 飞身刺：出手的人从目标前方多远蹲下起跳（脚底，格），蹲下时压低多少，飞过去的弧线拱起多高。 */
    private static final float THRUST_FROM = 4.2f, SQUAT = .12f, ARC = .55f;
    /** 飞身刺里身体往目标那边前倾多少：蹲下蓄势、飞身、刺中那一拍、刺着不动。 */
    private static final float CROUCH_LEAN = (float) Math.toRadians(20), LEAP_LEAN = (float) Math.toRadians(25),
            CONTACT_LEAN = (float) Math.toRadians(16), HELD_LEAN = (float) Math.toRadians(10);
    /** 目标被刺中往后仰多少。 */
    private static final float RECOIL = (float) Math.toRadians(12);
    /**
     * 刺中时手握在多高（出手的人身高的几成）：原版拿在手里的东西和手臂差不多成直角，手臂垂在腰间往前送一点时它才是平着朝前的，
     * 手臂抬平了它就指着天。所以是人飞到够高，从腰间把它平着刺进胸口。
     */
    private static final float GRIP = .5f;
    /** 胸口在目标身高的几成（剑祟胸口的光缝在这里）。 */
    private static final float CHEST = .65f;
    /** 没找到出手的人时按玩家的身高算。 */
    private static final float ACTOR_HEIGHT = 1.8f;
    /** 飞身刺的四肢，以右手惯用写。站着。 */
    private static final Pose STAND = new Pose(0, 0, 0, 0, 0, 0, 0, 0, 0);
    /** 蓄势：主手往后收，手里的东西斜指地面；另一只手往前伸着瞄；前腿屈膝在前，后腿往后蹬。 */
    private static final Pose CROUCH = new Pose(.5f, 0, .12f, -1.5f, 0, -.1f, -.6f, .55f, -.3f);
    /** 飞身：主手往前送，手里的东西放平指着目标；另一只手甩到身后；前膝抬起，后腿拖在后面。 */
    private static final Pose LEAP = new Pose(-.45f, -.1f, .05f, .9f, 0, -.45f, -1f, .7f, -.4f);
    /**
     * 刺中后定住：主手伸到最前、略往里收，手里的东西从腰间平着往上一点刺进胸口；另一只手甩在身后，前膝抬着，后腿垂在后面。
     * 接着演的过场从这个姿势接着摆（{@link #impaled}）。
     */
    public static final Pose IMPALED = new Pose(-.6f, -.19f, .04f, .75f, 0, -.6f, -.9f, .5f, -.2f);

    private static final Map<Integer, Run> RUNS = new HashMap<>();
    /** 飞身刺里出手的人 → 他那一段：他的身体也由这一段摆。 */
    private static final Map<Integer, Run> ACTORS = new HashMap<>();
    private static @Nullable Run viewing;
    /** 客户端场景用：停在这一刻上截图。 */
    private static @Nullable Float held;

    private ExecutionClient() {}

    /**
     * 身体这一帧的样子：整个身体绕中心（脚底往上 {@code pivot} 格）转 {@code rotation}，再挪 {@code lift}，都按世界坐标轴。
     * 人形的模型还会张开四肢（{@code limbs} 是张开的程度），并往被打的方向 {@code flail}（世界坐标）的反方向甩；
     * {@code pose} 不为 null 时四肢不张开也不甩，直接摆成这个姿势（飞身刺出手的人、接着演的过场里撬剑的人）。
     */
    public record Body(Vector3f lift, Quaternionf rotation, float pivot, float limbs, Vector3f flail, @Nullable Pose pose) {
        public Body(Vector3f lift, Quaternionf rotation, float pivot, float limbs, Vector3f flail) {
            this(lift, rotation, pivot, limbs, flail, null);
        }
    }

    /**
     * 人形模型四肢的姿势（弧度，模型零件的转角），直接替换原版算出来的，不是加上去。以右手惯用写：{@code mainArm} 是拿主手东西的
     * 那条手臂，{@code frontLeg} 是同一侧的腿；左撇子整个左右镜像（主手是左臂，前腿是左腿，绕竖轴和前后轴的转角取反）。
     * 原版的挥手、用东西、潜行还会挪手臂和手里的东西，要摆的那一帧取渲染状态时先 {@link #neutral} 一下。
     */
    public record Pose(float mainArmX, float mainArmY, float mainArmZ, float offArmX, float offArmY, float offArmZ, float frontLegX, float backLegX, float headX) {
        /** 从 {@code a} 走到 {@code b} 的 {@code t}（0 到 1）。 */
        public static Pose lerp(Pose a, Pose b, float t) {
            return new Pose(Mth.lerp(t, a.mainArmX, b.mainArmX), Mth.lerp(t, a.mainArmY, b.mainArmY), Mth.lerp(t, a.mainArmZ, b.mainArmZ),
                    Mth.lerp(t, a.offArmX, b.offArmX), Mth.lerp(t, a.offArmY, b.offArmY), Mth.lerp(t, a.offArmZ, b.offArmZ),
                    Mth.lerp(t, a.frontLegX, b.frontLegX), Mth.lerp(t, a.backLegX, b.backLegX), Mth.lerp(t, a.headX, b.headX));
        }
    }

    @SubscribeEvent static void packets(RegisterClientPayloadHandlersEvent event) {
        event.register(ExecutionPayload.TYPE, (payload, context) -> start(payload));
        event.register(ExecutionPayload.Slash.TYPE, (payload, context) -> slash(payload));
    }

    /** 服务端发来可交互处决的一刀：按服务端的刻数放，这边已经走过了就从现在开始划。 */
    public static void slash(ExecutionPayload.Slash payload) {
        Run run = RUNS.get(payload.target());
        if (run != null && run.timeline.interactive() && !run.clicker) run.addSlash(Math.max(payload.age(), run.age(partialTick()) - .5f));
    }

    /** 正在看的那一段已经有几刀（客户端场景检查用），没在看为 -1。 */
    public static int slashes() {
        Run run = viewing();
        return run == null ? -1 : run.timeline.slashes().size();
    }

    /** 本机是不是可交互处决里出刀的人，而且现在点得了。 */
    public static boolean clicking() {
        Run run = viewing();
        return run != null && run.clicker && run.timeline.open(run.age(partialTick()));
    }

    /**
     * 可交互处决里，处决的人按下攻击键（鼠标或键盘，按它绑定的那个）：这边先画一刀，再告诉服务端。
     * 镜头锁着时按键映射每刻都被松开，只能在原始的输入事件里认；认到了就吞掉，不交给原版。
     */
    @SubscribeEvent static void mouse(InputEvent.MouseButton.Pre event) {
        var key = Minecraft.getInstance().options.keyAttack.getKey();
        if (key.getType() == InputConstants.Type.MOUSE && key.getValue() == event.getButton() && click(event.getAction())) event.setCanceled(true);
    }

    @SubscribeEvent static void key(InputEvent.Key event) {
        var key = Minecraft.getInstance().options.keyAttack.getKey();
        if (key.getType() == InputConstants.Type.KEYSYM && key.getValue() == event.getKey()) click(event.getAction());
    }

    /** 测试用：当成处决的人点了一下。 */
    public static boolean click() {
        return click(GLFW.GLFW_PRESS);
    }

    private static boolean click(int action) {
        Run run = viewing();
        if (run == null || !run.clicker || Minecraft.getInstance().screen != null) return false;
        if (action != GLFW.GLFW_PRESS) return true;
        float age = run.age(partialTick());
        if (!run.timeline.open(age) || age - run.lastClick < run.minGap) return true;
        run.lastClick = age;
        run.addSlash(age);
        ClientPacketDistributor.sendToServer(new ExecutionPayload.Click());
        return true;
    }

    /** 旁观的人看的可交互处决（世界里画刀光用）。 */
    static List<Run> watched() {
        List<Run> out = new ArrayList<>();
        for (Run run : RUNS.values()) if (!run.viewer && run.timeline.interactive()) out.add(run);
        return out;
    }

    public static void start(ExecutionPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.level.getEntity(payload.target()) instanceof LivingEntity target)) return;
        Run run = new Run(mc.level, target, payload);
        Run old = RUNS.put(payload.target(), run);
        if (old != null) forget(old);
        if (run.actor >= 0) ACTORS.put(run.actor, run);
        if (!payload.viewer()) return;
        viewing = run;
        var timeline = run.timeline;
        CameraShot shot;
        if (run.thrust) shot = run.thrustShot(target);
        else shot = CameraShot.of(CameraPoint.on(target, new Vec3(0, run.pivot + run.lift, 0)), CameraPoint.on(target, new Vec3(0, run.pivot, 0)))
                .timing(ExecutionTimeline.BLEND_IN, timeline.hold(), ExecutionTimeline.BLEND_OUT);
        if (timeline.shot().letterbox()) shot = shot.letterbox();
        CameraDirector.play(shot, run);
    }

    /** 这一段不再摆出手的人（被同一个目标的新一段换掉、走完或提前结束）。 */
    private static void forget(Run run) {
        if (run.actor >= 0 && ACTORS.get(run.actor) == run) ACTORS.remove(run.actor);
    }

    /** 正在看的那一段；镜头被别的运镜接走了就不算。 */
    static @Nullable Run viewing() {
        if (viewing != null && CameraDirector.rig() != viewing) viewing = null;
        return viewing;
    }

    /** 本机正在看处决特写。 */
    public static boolean active() {
        return viewing() != null;
    }

    /** 正在看的那一段现在走到哪个阶段，没在看时是 null。 */
    public static @Nullable Phase phase() {
        Run run = viewing();
        return run == null ? null : run.timeline.phase(run.age(partialTick()));
    }

    /** 正在看的那一段走到第几刻（不含帧内插值），没在看时是 -1。客户端场景停住再放开时按它接回原来那一刻。 */
    public static int age() {
        Run run = viewing();
        return run == null ? -1 : (int) Math.floor(run.age(0));
    }

    /** 这一拍画面是不是对调过的（客户端场景按帧记录）。 */
    public static boolean swapped() {
        Run run = viewing();
        return run != null && run.swapped(run.age(partialTick()));
    }

    /** {@code entity} 现在是否正被处决（身体按特写摆）；飞身刺里出手的人从黑白开始算。 */
    public static boolean posing(Entity entity) {
        Run run = RUNS.get(entity.getId());
        if (run != null && run.level == entity.level() && !run.handedOff()) return true;
        run = ACTORS.get(entity.getId());
        return run != null && run.level == entity.level() && !run.handedOff() && run.age(partialTick()) >= ExecutionTimeline.THRUST_INK;
    }

    /** 客户端场景用：让特写和镜头停在第 {@code age} 刻上，传 null 放开，从那一刻接着往下走。 */
    public static void hold(@Nullable Float age) {
        if (age == null && held != null) for (Run run : RUNS.values()) run.ticks = (int) Math.floor(held);
        held = age;
        CameraDirector.hold(age);
    }

    static float partialTick() {
        return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
    }

    /**
     * 由 Mixin 在取出实体的渲染状态之后调用：被处决的实体带上这一刻的身体姿态，不画影子（影子会跟着身体一起飞起来）。
     * 飞身刺的目标不离地，影子留着；出手的人飞起来时去掉。这里在所有渲染状态的修改器之后，没有这一段的实体不碰。
     */
    public static void extracted(Entity entity, EntityRenderState state) {
        Run run = RUNS.get(entity.getId());
        if (run != null && run.level == entity.level() && !run.handedOff()) {
            state.setRenderData(BODY, run.body(run.age(state.partialTick)));
            // 飞身刺的目标不离地，影子留着；刺中以后它往后仰，影子会跟着歪，也去掉
            if (!run.thrust || run.age(state.partialTick) >= ExecutionTimeline.THRUST_CONTACT) state.shadowPieces.clear();
            return;
        }
        run = ACTORS.get(entity.getId());
        if (run != null && run.level == entity.level() && !run.handedOff()) run.actorExtracted(entity, state);
    }

    /**
     * 原版的挥手、用东西、走路、潜行、游泳、看向不再带动身体、四肢和手里的东西：四肢按 {@link Pose} 摆的那一帧，取渲染状态时调。
     * 只改渲染状态，实体本身不动。
     */
    public static void neutral(EntityRenderState state) {
        if (state instanceof LivingEntityRenderState living) {
            living.walkAnimationSpeed = 0;
            living.xRot = 0;
            living.yRot = 0;
        }
        if (state instanceof ArmedEntityRenderState armed) {
            armed.attackTime = 0;
            armed.rightArmPose = HumanoidModel.ArmPose.EMPTY;
            armed.leftArmPose = HumanoidModel.ArmPose.EMPTY;
        }
        if (state instanceof HumanoidRenderState humanoid) {
            humanoid.isUsingItem = false;
            humanoid.ticksUsingItem = 0;
            humanoid.isCrouching = false;
            humanoid.isPassenger = false;
            humanoid.isFallFlying = false;
            humanoid.isVisuallySwimming = false;
            humanoid.swimAmount = 0;
        }
    }

    /**
     * 飞身刺刺中后定住的那个姿势，给接着演的过场接着摆（撬剑时从这里加动作）：出手的人面朝目标，脚底在目标前方、比目标的脚高，
     * 手里的东西刺在目标胸口，四肢是 {@link #IMPALED}。和这一段最后的样子一模一样，所以从它手里接过来不会跳。
     * 在出手的人的渲染状态修改器里调（{@code state} 是他的，{@code targetYaw} 是飞身刺开始时目标的朝向），再配上 {@link #neutral}。
     */
    public static Body impaled(Entity actor, Entity target, float targetYaw, LivingEntityRenderState state) {
        Vec3 forward = forward(targetYaw);
        Vec3 feet = target.getPosition(state.partialTick).add(forward.scale(reach(target.getBbWidth())))
                .add(0, rise(target.getBbHeight(), actorHeight(actor)), 0);
        return new Body(Run.toVector(feet.subtract(actor.getPosition(state.partialTick))), facing(forward, HELD_LEAN, state.bodyRot, targetYaw),
                actorHeight(actor) / 2, 0, new Vector3f(), IMPALED);
    }

    /** 朝向 {@code yaw}（度）时的前方。 */
    private static Vec3 forward(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Vec3(-Math.sin(radians), 0, Math.cos(radians));
    }

    /** 刺中后出手的人的脚底在目标前方多远：手里的东西大半截没进胸口。 */
    private static float reach(float targetWidth) {
        return targetWidth * .3f + .45f;
    }

    /** 刺中后出手的人的脚底比目标的脚高多少：握在腰间的手正对着胸口。 */
    private static float rise(float targetHeight, float actorHeight) {
        return Math.max(0, targetHeight * CHEST - actorHeight * GRIP);
    }

    private static float actorHeight(@Nullable Entity actor) {
        return actor == null ? ACTOR_HEIGHT : actor.getDimensions(net.minecraft.world.entity.Pose.STANDING).height();
    }

    /** 面朝目标（目标朝向 {@code targetYaw} 的反方向）站着的人往目标那边前倾 {@code lean}：绕他自己的左右轴转，头往目标那边倒。 */
    private static Quaternionf lean(Vec3 forward, float lean) {
        return new Quaternionf().rotationAxis(lean, (float) -forward.z, 0, (float) forward.x);
    }

    /**
     * 出手的人整个身体的转动（世界坐标轴）：先从他自己现在的朝向（渲染状态里的 {@code bodyRot}）转到面朝目标，再前倾。
     * 原版画身体时按 {@code 180 - bodyRot} 绕竖轴转，所以在外面再补一个 {@code bodyRot - 朝向} 就是面朝目标。
     */
    private static Quaternionf facing(Vec3 forward, float lean, float bodyRot, float targetYaw) {
        return lean(forward, lean).mul(new Quaternionf().rotationY((float) Math.toRadians(bodyRot - targetYaw - 180)));
    }

    /**
     * 人形模型的四肢。{@link Body#pose} 为 null 时照下面的 {@link #limbs(HumanoidModel, Body, float)} 张开、甩动；
     * 不为 null 时直接摆成那个姿势，左撇子镜像过来。
     */
    public static void limbs(HumanoidModel<?> model, Body body, HumanoidRenderState state) {
        Pose pose = body.pose();
        if (pose == null) {
            limbs(model, body, state.bodyRot);
            return;
        }
        boolean left = state.mainArm == HumanoidArm.LEFT;
        float mirror = left ? -1 : 1;
        (left ? model.leftArm : model.rightArm).setRotation(pose.mainArmX(), mirror * pose.mainArmY(), mirror * pose.mainArmZ());
        (left ? model.rightArm : model.leftArm).setRotation(pose.offArmX(), mirror * pose.offArmY(), mirror * pose.offArmZ());
        (left ? model.leftLeg : model.rightLeg).xRot = pose.frontLegX();
        (left ? model.rightLeg : model.leftLeg).xRot = pose.backLegX();
        model.head.xRot = pose.headX();
    }

    /**
     * 人形模型的四肢：在半空张开（手臂往外抬、头往后仰、两腿分开），挨打时往刀的反方向甩，头慢半拍被带过去。
     * {@code bodyRot} 是身体的朝向（度），用来把世界里被打的方向换到身体自己的左右前后。
     */
    public static void limbs(net.minecraft.client.model.HumanoidModel<?> model, Body body, float bodyRot) {
        double yaw = Math.toRadians(bodyRot);
        Vector3f f = body.flail();
        // 身体右手边是 (-cos, 0, -sin)，前方是 (-sin, 0, cos)
        float side = (float) (-f.x * Math.cos(yaw) - f.z * Math.sin(yaw));
        float ahead = (float) (-f.x * Math.sin(yaw) + f.z * Math.cos(yaw));
        float up = f.y;
        float w = body.limbs();
        model.rightArm.zRot += .55f * w - .9f * side - .4f * up;
        model.leftArm.zRot += -.55f * w - .9f * side + .4f * up;
        model.rightArm.xRot += -.25f * w + .8f * ahead;
        model.leftArm.xRot += -.1f * w + .8f * ahead;
        model.head.zRot += .35f * side;
        model.head.xRot += -.25f * w - .45f * ahead;
        model.rightLeg.zRot += .12f * w - .3f * side;
        model.leftLeg.zRot += -.12f * w - .3f * side;
        model.rightLeg.xRot += .2f * w + .45f * ahead;
        model.leftLeg.xRot += -.15f * w + .45f * ahead;
    }

    @SubscribeEvent static void tick(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (RUNS.isEmpty()) return;
        for (Iterator<Run> it = RUNS.values().iterator(); it.hasNext(); ) {
            Run run = it.next();
            if (run.handedOff()) {
                // 飞身刺被接着演的运镜接走了：那边的镜头照常走，这边只是不再摆人
                if (run == viewing) viewing = null;
                forget(run);
                it.remove();
                continue;
            }
            Entity target = run.level.getEntity(run.target);
            if (mc.level != run.level || target == null || !target.isAlive() && run.age(0) < run.timeline.impact()) {
                // 目标没了（走远、死了、换了世界）：镜头提前回去，画面马上恢复。出手的人没了不算，只是不再摆他
                if (run == viewing) {
                    if (mc.level == run.level) CameraDirector.returnNow();
                    viewing = null;
                }
                forget(run);
                it.remove();
                continue;
            }
            if (!mc.isPaused() && held == null && ++run.ticks >= run.timeline.duration()) {
                if (run == viewing) viewing = null;
                forget(run);
                it.remove();
            }
        }
    }

    /** 声音和落地的尘土按帧放：刀和刀之间不到三刻，按刻放会忽快忽慢。 */
    @SubscribeEvent static void frame(RenderFrameEvent.Pre event) {
        if (RUNS.isEmpty()) return;
        float partial = partialTick();
        for (Run run : RUNS.values()) run.cue(run.age(partial));
    }

    /** 视角收窄，每刀猛地推近一点，重击推近并定住，落地时往外一弹。只改世界的视角，不改手的。 */
    @SubscribeEvent static void fov(ViewportEvent.ComputeFov event) {
        Run run = viewing();
        if (run == null || !event.usedConfiguredFov()) return;
        float w = CameraDirector.weight();
        float age = run.age((float) event.getPartialTick());
        event.setFOV(Mth.lerp(w, event.getFOV(), SHOT_FOV) * (1 - run.zoom(age) * w));
    }

    /** 一个被处决的实体。 */
    static final class Run implements CameraDirector.Rig {
        final ClientLevel level;
        final int target;
        final ExecutionTimeline timeline;
        final boolean viewer;
        /** 目标朝向的前方和右手边。 */
        private final Vec3 forward, side;
        final float height, width, pivot;
        /** 抛多高（脚底离地），头顶有方块时矮一些；躺下时身体中心比站着低多少（负数）。 */
        final float lift, lie;
        /** 第几刀被往哪推、身体绕哪根轴歪、镜头往哪抖；可交互时刀一刀刀加进来，跟着加。 */
        private final List<Vector3f> push = new ArrayList<>(), axis = new ArrayList<>(), shakeDir = new ArrayList<>();
        private final Vector3f hitShake, landShake;
        private final Vector3f finisherPush, finisherAxis, slamAxis;
        private final float spin;
        private final List<Cue> cues = new ArrayList<>();
        private int nextCue;
        int ticks;
        /** 可交互处决里本机是出刀的人；上一刀点在第几刻；两刀至少隔几刻（和服务端一样是 1 刻）。 */
        final boolean clicker;
        float lastClick = -100;
        final float minGap = 1;
        private final Random slashRandom;
        /** 飞身刺；出手的人（实体编号，没有是 -1）；目标的朝向（度）。 */
        final boolean thrust;
        final int actor;
        private final float targetYaw;
        /** 出手的人站直时多高、身体中心多高，刺中后脚底在目标前方多远、比目标的脚高多少。 */
        final float actorHeight, actorPivot, reach, rise;
        /** 镜头在两人的哪一侧（目标右手边是 1）：出手的人拿东西的那只手朝着镜头。 */
        private final double camSide;
        /** 打中前出手的人挥到一半的手：顿帧时停在这里。 */
        private final float heldSwing;
        /** 定格时镜头停在哪：定格开始那一帧目标的脚底，之后它被传送走也不跟。 */
        private @Nullable Vec3 frozenAnchor, lastAnchor;

        private record Cue(float at, Runnable action) {}

        /** 飞身刺里出手的人这一拍：脚底在目标前方多远、比目标的脚高多少，往目标那边前倾多少（弧度），四肢。 */
        record Leap(float ahead, float up, float lean, Pose limbs) {}

        Run(ClientLevel level, LivingEntity entity, ExecutionPayload payload) {
            this.level = level;
            this.target = entity.getId();
            this.timeline = new ExecutionTimeline(payload.shot());
            this.viewer = payload.viewer();
            this.clicker = payload.clicker();
            this.thrust = timeline.thrust();
            this.actor = thrust ? payload.actor() : -1;
            this.targetYaw = payload.yaw();
            Entity by = actor < 0 ? null : level.getEntity(actor);
            actorHeight = actorHeight(by);
            actorPivot = actorHeight / 2;
            reach = reach(entity.getBbWidth());
            rise = rise(entity.getBbHeight(), actorHeight);
            camSide = by instanceof LivingEntity living ? (living.getMainArm() == HumanoidArm.LEFT ? 1 : -1) : timeline.cut(0).side();
            heldSwing = by instanceof LivingEntity living ? living.getAttackAnim(0) : 0;
            double yaw = Math.toRadians(payload.yaw());
            forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
            side = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
            height = entity.getBbHeight();
            width = entity.getBbWidth();
            pivot = height / 2;
            lift = liftRoom(level, entity, Mth.clamp(2.2f + .45f * height, 2.5f, 4.5f));
            lie = (float) (pivot * Math.cos(LIE) + width / 2 * Math.sin(LIE)) - pivot;

            Random random = new Random(payload.shot().seed() * 31L + 7);
            var slashes = timeline.slashes();
            for (Slash s : slashes) react(s, random);
            hitShake = new Vector3f(random.nextFloat() - .5f, .6f, random.nextFloat() - .5f).normalize();
            landShake = new Vector3f(random.nextFloat() * .4f - .2f, 1, random.nextFloat() * .4f - .2f).normalize();
            slashRandom = new Random(payload.shot().seed() * 131L + 3);
            Cut last = timeline.cut(timeline.finalHit());
            finisherPush = screenToWorld(last, timeline.finisher().angle());
            finisherAxis = tipAxis(finisherPush, last);
            Vector3f flat = new Vector3f(finisherPush.x, 0, finisherPush.z);
            slamAxis = flat.lengthSquared() < 1e-4 ? toVector(right(last)) : new Vector3f(UP).cross(flat).normalize();
            spin = (random.nextBoolean() ? 1 : -1) * .012f;
            if (thrust) buildThrustCues();
            else buildCues(random);
        }

        /** 记下这一刀把身体往哪推、绕哪根轴歪、镜头往哪抖。 */
        private void react(Slash s, Random random) {
            Vector3f p = screenToWorld(timeline.cut(s.contact()), s.angle());
            push.add(p);
            axis.add(tipAxis(p, timeline.cut(s.contact())));
            shakeDir.add(new Vector3f(random.nextFloat() - .5f, random.nextFloat() - .5f, random.nextFloat() - .5f).normalize());
        }

        /** 可交互：第 {@code at} 刻起划一刀，身体、镜头、声音跟着。 */
        void addSlash(float at) {
            Slash s = timeline.add(at);
            react(s, slashRandom);
            int i = timeline.slashes().size() - 1;
            // 越砍越快，音调也越来越高；暴击声隔几刀响一下
            float pitch = 1.05f + .5f * Math.min(1, i / 24f) + slashRandom.nextFloat() * .1f;
            boolean crit = i % 3 == 2;
            float contact = s.contact();
            addCue(new Cue(contact, () -> {
                sound(SoundEvents.PLAYER_ATTACK_SWEEP, .9f, pitch, contact);
                if (crit) sound(SoundEvents.PLAYER_ATTACK_CRIT, .6f, 1.3f, contact);
            }));
        }

        private void addCue(Cue cue) {
            int at = nextCue;
            while (at < cues.size() && cues.get(at).at() <= cue.at()) at++;
            cues.add(at, cue);
        }

        /** 头顶有方块时抛不了那么高：留出头顶 0.3 格。 */
        private static float liftRoom(ClientLevel level, LivingEntity entity, float wanted) {
            Vec3 top = entity.position().add(0, entity.getBbHeight(), 0);
            var hit = level.clip(new ClipContext(top, top.add(0, wanted + .5, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
            if (hit.getType() == HitResult.Type.MISS) return wanted;
            return (float) Mth.clamp(hit.getLocation().y - top.y - .3, 0, wanted);
        }

        float age(float partialTick) {
            return held != null ? held : ticks + partialTick;
        }

        boolean swapped(float age) {
            return timeline.swapped(age) && !Minecraft.getInstance().options.hideLightningFlash().get();
        }

        // ---- 机位 ----

        /** 机位方向（从身体中心指向镜头），世界坐标。 */
        Vec3 direction(Cut cut) {
            return side.scale(cut.side()).add(0, cut.up(), 0).add(forward.scale(cut.forward())).normalize();
        }

        /** 这个机位下画面的右方和上方（世界坐标），不算画面倾斜。 */
        private Vec3 right(Cut cut) {
            Vec3 look = direction(cut).scale(-1);
            Vec3 r = look.cross(new Vec3(0, 1, 0));
            return r.lengthSqr() < 1e-6 ? side : r.normalize();
        }

        private Vec3 up(Cut cut) {
            return right(cut).cross(direction(cut).scale(-1)).normalize();
        }

        /** 画面上朝 {@code angle} 的方向在这个机位下是世界里的哪个方向。 */
        private Vector3f screenToWorld(Cut cut, float angle) {
            return toVector(right(cut).scale(Math.cos(angle)).add(up(cut).scale(Math.sin(angle))).normalize());
        }

        /** 被往 {@code push} 推的时候身体绕哪根轴歪：头往推的方向倒；竖着劈时往镜头这边弯腰或往后仰。 */
        private Vector3f tipAxis(Vector3f push, Cut cut) {
            Vector3f a = new Vector3f(UP).cross(push).add(toVector(right(cut)).mul(push.y));
            return a.lengthSquared() < 1e-6 ? toVector(right(cut)) : a.normalize();
        }

        private static Vector3f toVector(Vec3 v) {
            return new Vector3f((float) v.x, (float) v.y, (float) v.z);
        }

        @Override public CameraDirector.@Nullable Aim aim(float age, float partialTick) {
            if (thrust) return thrustAim(age, partialTick);
            Entity entity = level.getEntity(target);
            if (entity == null) return null;
            Vec3 feet = entity.getPosition(partialTick);
            Vec3 apex = feet.add(0, pivot + lift, 0);
            Cut cut = timeline.cut(age);
            Vec3 from = CameraShots.reach(level, apex, apex.add(direction(cut).scale(cut.distance())));
            Vec3 look = feet.add(0, pivot + height(age) + .1, 0);
            return new CameraDirector.Aim(from.add(shake(age)), look, cut.roll() + rollKick(age));
        }

        /** 每刀抖一下，重击和落地抖得最厉害。按帧连续地算，不跟着画面一拍一拍地跳。 */
        private Vec3 shake(float age) {
            Vector3f sum = new Vector3f();
            var slashes = timeline.slashes();
            for (int i = 0; i < slashes.size(); i++) {
                float since = age - slashes.get(i).contact();
                if (since > 0 && since < 6) sum.fma(.045f * slashes.get(i).force() * (float) (Math.exp(-since / 1.1) * Math.sin(since * 4.2 + i)), shakeDir.get(i));
            }
            float hit = age - timeline.finalHit();
            if (hit > 0 && hit < 10) sum.fma((float) (.2 * Math.exp(-hit / 2.2) * Math.sin(hit * 3.6)), hitShake);
            float land = age - timeline.impact();
            if (land > 0 && land < 10) sum.fma((float) (.28 * Math.exp(-land / 2) * Math.sin(land * 3.9 + 1)), landShake);
            return new Vec3(sum.x, sum.y, sum.z);
        }

        private float rollKick(float age) {
            float roll = 0;
            var slashes = timeline.slashes();
            for (int i = 0; i < slashes.size(); i++) {
                float since = age - slashes.get(i).contact();
                if (since > 0 && since < 6) roll += (i % 2 == 0 ? 1.2f : -1.2f) * ExecutionTimeline.punch(since, .6f);
            }
            roll += 3.5f * ExecutionTimeline.punch(age - timeline.finalHit(), 1);
            roll -= 2.5f * ExecutionTimeline.punch(age - timeline.impact(), .8f);
            return roll;
        }

        /** 视角推近多少（比例）：负数是往外弹。 */
        float zoom(float age) {
            if (thrust) return thrustZoom(age);
            float z = 0;
            for (Slash s : timeline.slashes()) {
                float since = age - s.contact();
                if (since > 0 && since < 6) z += .03f * s.force() * ExecutionTimeline.punch(since, .5f);
            }
            float hit = age - timeline.finalHit();
            if (hit > 0) z += .13f * (hit < ExecutionTimeline.HIT_STOP ? Math.min(1, hit / .6f) : Math.max(0, 1 - (hit - ExecutionTimeline.HIT_STOP) / 2));
            z -= .08f * ExecutionTimeline.punch(age - timeline.impact(), .8f);
            // 不是每刀对调时，靠镜头一刀比一刀推得近来攒劲，拍下去时松开
            if (timeline.shot().swaps().accents()) {
                float release = age < timeline.slamStart() ? 1 : Math.max(0, 1 - (age - timeline.slamStart()) / 2);
                z += .07f * timeline.buildUp(age) * release;
            }
            return z;
        }

        // ---- 身体 ----

        /** 身体中心离站着时高了多少（不含挨打时的晃动），镜头盯着它，按帧连续地走。 */
        float height(float age) {
            if (age < ExecutionTimeline.APEX) {
                float t = 1 - age / ExecutionTimeline.APEX;
                return lift * (1 - t * t * t);
            }
            if (age < timeline.slamStart()) return hover(age);
            if (age < timeline.impact()) {
                float t = (age - timeline.slamStart()) / ExecutionTimeline.SLAM;
                return Mth.lerp(t * t, hover(timeline.slamStart()), lie);
            }
            if (age < timeline.returnAt()) {
                float t = (age - timeline.impact()) / ExecutionTimeline.SETTLE;
                return lie + .22f * (float) Math.sin(Math.PI * t) * (1 - t);
            }
            float t = recovery(age);
            return lie * (1 - t);
        }

        /** 在最高点慢慢飘一点，不是钉死在空中。 */
        private float hover(float age) {
            return lift + .12f * (float) Math.sin((age - ExecutionTimeline.APEX) * .13f);
        }

        /** 落地后站起来走到哪了：0 还躺着，1 站好了。 */
        private float recovery(float age) {
            float t = Mth.clamp((age - timeline.returnAt()) / ExecutionTimeline.RECOVER, 0, 1);
            return t * t * (3 - 2 * t);
        }

        /**
         * 这一刻的身体姿态。被打的这一段一拍一拍地跳（像动画），站起来的时候连续地走。
         * 每刀：头往刀走的方向一下子歪过去再慢慢回来，同时拧一下；重击歪得最厉害并定住；拍下去时倒成横躺。
         */
        Body body(float age) {
            if (thrust) return thrustBody(age);
            float a = age < timeline.returnAt() ? ExecutionTimeline.stepped(age) : age;
            Vector3f rotation = new Vector3f();
            Vector3f move = new Vector3f(0, height(a), 0);
            Vector3f flail = new Vector3f();
            var slashes = timeline.slashes();
            for (int i = 0; i < slashes.size(); i++) {
                Slash s = slashes.get(i);
                float since = a - s.contact();
                if (since <= 0 || since > 14) continue;
                float k = ExecutionTimeline.punch(since, .7f) * s.force();
                rotation.fma(.42f * k, axis.get(i));
                rotation.fma(s.twist() * ExecutionTimeline.punch(since, .9f), UP);
                move.fma(.1f * k, push.get(i));
                flail.fma(ExecutionTimeline.punch(since - .35f, 1) * s.force(), push.get(i));
            }
            rotation.fma(spin * Mth.clamp(a - ExecutionTimeline.APEX, 0, timeline.slamStart() - ExecutionTimeline.APEX), UP);
            if (a >= timeline.finalHit()) {
                // 重击：歪到最大后定住，直到被拍下去
                float since = Math.min(a, timeline.slamStart()) - timeline.finalHit();
                float k = since < .9f ? ExecutionTimeline.punch(since, .9f) : 1;
                rotation.fma(.95f * k, finisherAxis);
                move.fma(.25f * k, finisherPush);
                flail.fma(1.6f * k, finisherPush);
            }
            float limbs = Math.min(1, age / ExecutionTimeline.APEX);
            if (a >= timeline.slamStart()) {
                Vector3f lying = new Vector3f(slamAxis).mul(LIE);
                float t = Math.min(1, (a - timeline.slamStart()) / ExecutionTimeline.SLAM);
                rotation.lerp(lying, t);
                move.x *= 1 - t;
                move.z *= 1 - t;
                if (a >= timeline.returnAt()) {
                    float up = recovery(a);
                    rotation.set(lying).mul(1 - up);
                    flail.mul(1 - up);
                    limbs = 1 - up;
                }
            }
            float angle = rotation.length();
            Quaternionf q = angle < 1e-5f ? new Quaternionf() : new Quaternionf().rotationAxis(angle, rotation.x / angle, rotation.y / angle, rotation.z / angle);
            return new Body(move, q, pivot, limbs * limbs * (3 - 2 * limbs), flail);
        }

        // ---- 飞身刺 ----

        /**
         * 被接着演的部分接走了：看特写的人这边，别的运镜一来这一段就结束，不再摆人。旁观的人没有镜头也没有定格，
         * 到 {@link ExecutionTimeline#THRUST_SWAP}（接着演的一方可能在这时换场景、把目标转个方向）就不再摆，
         * 之后由接着演的人自己按目标现在的朝向摆，不会按换之前的朝向摆反。
         */
        boolean handedOff() {
            if (!thrust) return false;
            if (viewer) return CameraDirector.rig() != this;
            return age(partialTick()) >= ExecutionTimeline.THRUST_SWAP;
        }

        /** 目标这一帧插值后的脚底，两个人都按它摆；目标找不到时是 null。 */
        @Nullable Vec3 anchor(float partialTick) {
            Entity entity = level.getEntity(target);
            return entity == null ? null : entity.getPosition(partialTick);
        }

        /** 目标的前方（世界坐标）：出手的人从这边飞过来，面朝反方向。 */
        Vec3 forward() {
            return forward;
        }

        /** 目标胸口，刺进去的地方。 */
        Vec3 chest(Vec3 anchor) {
            return anchor.add(0, height * CHEST, 0);
        }

        /**
         * 出手的人第 {@code age} 刻的样子，一拍一拍地跳：在目标前方几格外蹲下（黑白的第一拍就蹲下去大半），起跳后沿弧线飞过去，
         * 刺中的那一拍正好到，之后刺着不动。
         */
        Leap leap(float age) {
            int f = ExecutionTimeline.frame(age);
            int crouch = ExecutionTimeline.frame(ExecutionTimeline.THRUST_CROUCH), jump = ExecutionTimeline.frame(ExecutionTimeline.THRUST_LEAP);
            int hit = ExecutionTimeline.frame(ExecutionTimeline.THRUST_CONTACT);
            if (f < jump) {
                float t = 1 - Mth.clamp((f - crouch + 1) / 3f, 0, 1);
                float c = 1 - t * t;
                return new Leap(THRUST_FROM, -SQUAT * c, CROUCH_LEAN * c, Pose.lerp(STAND, CROUCH, c));
            }
            if (f < hit) {
                float s = (f - jump + 1) / (float) (hit - jump + 1);
                float ahead = Mth.lerp((float) Math.pow(s, 1.15), THRUST_FROM, reach);
                float up = Mth.lerp(s, -SQUAT, rise) + ARC * 4 * s * (1 - s);
                Pose limbs = s < .5f ? Pose.lerp(CROUCH, LEAP, s / .5f) : Pose.lerp(LEAP, IMPALED, (s - .5f) / .5f);
                return new Leap(ahead, up, LEAP_LEAN, limbs);
            }
            return new Leap(reach, rise, f == hit ? CONTACT_LEAN : HELD_LEAN, IMPALED);
        }

        /** 出手的人身体中心（世界坐标），转动的中心。 */
        Vec3 actorCentre(Vec3 anchor, Leap leap) {
            return anchor.add(forward.scale(leap.ahead())).add(0, leap.up() + actorPivot, 0);
        }

        /** 出手的人摆好以后的朝向（世界坐标轴）：z 朝前（朝着目标），x 朝他的左手边，再往前倾 {@code lean}。 */
        Quaternionf actorFrame(float lean) {
            return lean(forward, lean).mul(new Quaternionf().rotationY((float) Math.atan2(-forward.x, -forward.z)));
        }

        /** 出手的人是左撇子（拿主手东西的是左手）。 */
        boolean leftHanded() {
            return level.getEntity(actor) instanceof LivingEntity living && living.getMainArm() == HumanoidArm.LEFT;
        }

        /** 取出手的人的渲染状态时：黑白之前照原样站着，挥到一半的手停住（顿帧）；之后整个人按飞身刺摆，原版的动作都不要。 */
        void actorExtracted(Entity entity, EntityRenderState state) {
            float age = age(state.partialTick);
            if (age < ExecutionTimeline.THRUST_INK) {
                if (state instanceof ArmedEntityRenderState armed) armed.attackTime = heldSwing;
                if (state instanceof LivingEntityRenderState living) living.walkAnimationSpeed = 0;
                return;
            }
            Vec3 anchor = anchor(state.partialTick);
            if (anchor == null) return;
            Leap leap = leap(age);
            Vec3 feet = anchor.add(forward.scale(leap.ahead())).add(0, leap.up(), 0);
            float bodyRot = state instanceof LivingEntityRenderState living ? living.bodyRot : entity.getYRot();
            neutral(state);
            state.setRenderData(BODY, new Body(toVector(feet.subtract(entity.getPosition(state.partialTick))), facing(forward, leap.lean(), bodyRot, targetYaw),
                    actorPivot, 0, new Vector3f(), leap.limbs()));
            state.shadowPieces.clear();
        }

        /** 目标：刺中之前不动；刺中的下一拍往后一仰（刺中那一拍先顿住），之后一直仰着。不离地，四肢不动。 */
        private Body thrustBody(float age) {
            float since = ExecutionTimeline.stepped(age) - ExecutionTimeline.THRUST_CONTACT;
            float k = since <= 0 ? 0 : since < .9f ? ExecutionTimeline.punch(since, .9f) : 1;
            return new Body(new Vector3f(), k <= 0 ? new Quaternionf() : lean(forward, RECOIL * k), pivot, 0, new Vector3f());
        }

        /** 机位看哪：两人之间偏目标一些，高度过目标身高一半，蹲着的人的脚和目标的头都在画面里。 */
        private Vec3 look(Vec3 anchor) {
            return anchor.add(forward.scale(THRUST_FROM * .3)).add(0, height * .55, 0);
        }

        private double distance() {
            return clicker ? ExecutionTimeline.THRUST_NEAR : ExecutionTimeline.THRUST_FAR;
        }

        /** 进出的过渡和遮幅用的那一段：机位由 {@link #aim} 逐帧给，这两个点只在找不到目标时用。 */
        CameraShot thrustShot(Entity entity) {
            Vec3 look = look(Vec3.ZERO);
            return CameraShot.of(CameraPoint.on(entity, look.add(side.scale(camSide * distance()))), CameraPoint.on(entity, look))
                    .timing(timeline.blendIn(), timeline.hold(), timeline.blendOut());
        }

        /**
         * 飞身刺的机位：在两人连线的侧面看两人之间，出手的人自己看得近、别人看得远；刺中时推近一点、抖一下、歪一点。
         * 定格时整个镜头停在定格开始那一刻的样子，之后目标被传送走也不跟。
         */
        private CameraDirector.@Nullable Aim thrustAim(float age, float partialTick) {
            // 定格以后一直停着：彩色的运镜来晚了（服务端卡）时，镜头也不跟着被传送走的目标跳过去
            boolean frozen = age >= ExecutionTimeline.THRUST_FREEZE;
            Vec3 anchor = frozen ? frozenAnchor : null;
            if (anchor == null) {
                // 定格那一帧用上一帧的位置：这一帧要是赶上了换场景，也停在换之前
                anchor = frozen && lastAnchor != null ? lastAnchor : anchor(partialTick);
                if (anchor == null) anchor = lastAnchor;
                if (anchor == null) return null;
            }
            frozenAnchor = frozen ? anchor : null;
            lastAnchor = anchor;
            float a = frozen ? ExecutionTimeline.THRUST_FREEZE : age;
            Vec3 look = look(anchor);
            Vec3 from = look.add(side.scale(camSide * distance() * (1 - .1 * pushIn(a)))).add(0, -.25, 0);
            from = CameraShots.reach(level, look, from);
            float roll = (float) (camSide * 2.5 * ExecutionTimeline.punch(a - ExecutionTimeline.THRUST_CONTACT, .8f));
            return new CameraDirector.Aim(from.add(thrustShake(a)), look, roll);
        }

        /** 刺中后镜头推近了多少（0 到 1）：一下推过去，之后停在那里。 */
        private static float pushIn(float age) {
            float t = Mth.clamp((age - ExecutionTimeline.THRUST_CONTACT) / 1.5f, 0, 1);
            return t * t * (3 - 2 * t);
        }

        /** 起跳时轻轻一抖，刺中时抖得厉害。按帧连续地算。 */
        private Vec3 thrustShake(float age) {
            Vector3f sum = new Vector3f();
            float jump = age - ExecutionTimeline.THRUST_LEAP;
            if (jump > 0 && jump < 5) sum.fma((float) (.03 * Math.exp(-jump / 1.2) * Math.sin(jump * 4.4)), hitShake);
            float hit = age - ExecutionTimeline.THRUST_CONTACT;
            if (hit > 0 && hit < 10) sum.fma((float) (.16 * Math.exp(-hit / 1.8) * Math.sin(hit * 3.8)), landShake);
            return new Vec3(sum.x, sum.y, sum.z);
        }

        /** 刺中时视角猛地推近一下，之后跟着镜头推近的那一点停住；定格时不变。 */
        private float thrustZoom(float age) {
            float a = timeline.freezing(age) ? ExecutionTimeline.THRUST_FREEZE : age;
            return .1f * ExecutionTimeline.punch(a - ExecutionTimeline.THRUST_CONTACT, .5f) + .04f * pushIn(a);
        }

        /** 起跳一阵风声；刺中时穿刺、暴击、重击叠在一起，压低一点。 */
        private void buildThrustCues() {
            cues.add(new Cue(ExecutionTimeline.THRUST_LEAP, () -> {
                thrustSound(SoundEvents.PLAYER_ATTACK_SWEEP, .8f, .6f, THRUST_FROM, 1);
                thrustSound(SoundEvents.WIND_CHARGE_BURST.value(), .5f, 1.4f, THRUST_FROM, 1);
            }));
            cues.add(new Cue(ExecutionTimeline.THRUST_CONTACT, () -> {
                double chest = height * CHEST;
                thrustSound(SoundEvents.TRIDENT_HIT, 1.1f, .8f, 0, chest);
                thrustSound(SoundEvents.PLAYER_ATTACK_CRIT, 1, .55f, 0, chest);
                thrustSound(SoundEvents.PLAYER_ATTACK_STRONG, .9f, .5f, 0, chest);
            }));
        }

        /** 在目标前方 {@code ahead}、高 {@code up} 的地方放一个声音。 */
        private void thrustSound(SoundEvent sound, float volume, float pitch, double ahead, double up) {
            Vec3 feet = feet();
            if (feet == null) return;
            Vec3 at = feet.add(forward.scale(ahead)).add(0, up, 0);
            level.playLocalSound(at.x, at.y, at.z, sound, SoundSource.PLAYERS, volume, pitch, false);
        }

        // ---- 声音和尘土 ----

        private void buildCues(Random random) {
            cues.add(new Cue(0, () -> {
                sound(SoundEvents.PLAYER_ATTACK_KNOCKBACK, 1, .7f, 0);
                sound(SoundEvents.WIND_CHARGE_BURST.value(), .8f, .9f, 0);
                dust(0, 10, .18f);
            }));
            var slashes = timeline.slashes();
            for (int i = 0; i < slashes.size(); i++) {
                float at = slashes.get(i).contact();
                // 越砍越快，音调也越来越高
                float pitch = 1.05f + .5f * i / Math.max(1, slashes.size() - 1) + random.nextFloat() * .1f;
                boolean crit = i % 3 == 2;
                cues.add(new Cue(at, () -> {
                    sound(SoundEvents.PLAYER_ATTACK_SWEEP, .9f, pitch, at);
                    if (crit) sound(SoundEvents.PLAYER_ATTACK_CRIT, .6f, 1.3f, at);
                }));
            }
            // 最后一刀之后是一段安静：什么都不响，重击才显得重
            float hit = timeline.finalHit();
            cues.add(new Cue(hit, () -> {
                sound(SoundEvents.PLAYER_ATTACK_CRIT, 1, .55f, hit);
                sound(SoundEvents.PLAYER_ATTACK_STRONG, 1, .5f, hit);
                sound(SoundEvents.GENERIC_EXPLODE.value(), .6f, 1.8f, hit);
            }));
            cues.add(new Cue(timeline.slamStart(), () -> sound(SoundEvents.MACE_SMASH_AIR, 1, .8f, timeline.slamStart())));
            float land = timeline.impact();
            cues.add(new Cue(land, () -> {
                sound(SoundEvents.MACE_SMASH_GROUND_HEAVY, 1.2f, .9f, land);
                sound(SoundEvents.GENERIC_EXPLODE.value(), .7f, .7f, land);
                dust(land, 44, .55f);
            }));
            cues.sort(Comparator.comparingDouble(Cue::at));
        }

        /** 放到 {@code age} 为止该响的声音、该起的尘土。 */
        void cue(float age) {
            while (nextCue < cues.size() && cues.get(nextCue).at() <= age) cues.get(nextCue++).action().run();
        }

        private @Nullable Vec3 feet() {
            Entity entity = level.getEntity(target);
            return entity == null ? null : entity.position();
        }

        private void sound(SoundEvent sound, float volume, float pitch, float at) {
            Vec3 feet = feet();
            if (feet != null) level.playLocalSound(feet.x, feet.y + pivot + height(at), feet.z, sound, SoundSource.PLAYERS, volume, pitch, false);
        }

        /** 脚下的方块碎屑和烟往四周炸开；{@code speed} 大时还有一团爆炸。 */
        private void dust(float at, int count, float speed) {
            Vec3 feet = feet();
            if (feet == null) return;
            var ground = level.getBlockState(BlockPos.containing(feet.x, feet.y - .2, feet.z));
            Random random = new Random(timeline.shot().seed() + count);
            for (int i = 0; i < count; i++) {
                double a = Math.TAU * i / count + random.nextDouble() * .2;
                double v = speed * (.6 + random.nextDouble() * .5);
                double x = feet.x + Math.cos(a) * .4, z = feet.z + Math.sin(a) * .4;
                if (!ground.isAir()) level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, ground), x, feet.y + .1, z, Math.cos(a) * v, .1 + random.nextDouble() * .15, Math.sin(a) * v);
                if (i % 3 == 0) level.addParticle(ParticleTypes.POOF, x, feet.y + .15, z, Math.cos(a) * v * .5, .02, Math.sin(a) * v * .5);
            }
            if (speed > .4f) level.addParticle(ParticleTypes.EXPLOSION, feet.x, feet.y + .3, feet.z, 0, 0, 0);
        }
    }
}
