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
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
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

    private static final Map<Integer, Run> RUNS = new HashMap<>();
    private static @Nullable Run viewing;
    /** 客户端场景用：停在这一刻上截图。 */
    private static @Nullable Float held;

    private ExecutionClient() {}

    /**
     * 身体这一帧的样子：整个身体绕中心（脚底往上 {@code pivot} 格）转 {@code rotation}，再挪 {@code lift}，都按世界坐标轴。
     * 人形的模型还会张开四肢（{@code limbs} 是张开的程度），并往被打的方向 {@code flail}（世界坐标）的反方向甩。
     */
    public record Body(Vector3f lift, Quaternionf rotation, float pivot, float limbs, Vector3f flail) {}

    @SubscribeEvent static void packets(RegisterClientPayloadHandlersEvent event) {
        event.register(ExecutionPayload.TYPE, (payload, context) -> start(payload));
    }

    public static void start(ExecutionPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.level.getEntity(payload.target()) instanceof LivingEntity target)) return;
        Run run = new Run(mc.level, target, payload);
        RUNS.put(payload.target(), run);
        if (!payload.viewer()) return;
        viewing = run;
        var timeline = run.timeline;
        CameraShot shot = CameraShot.of(CameraPoint.on(target, new Vec3(0, run.pivot + run.lift, 0)), CameraPoint.on(target, new Vec3(0, run.pivot, 0)))
                .timing(ExecutionTimeline.BLEND_IN, timeline.hold(), ExecutionTimeline.BLEND_OUT);
        if (timeline.shot().letterbox()) shot = shot.letterbox();
        CameraDirector.play(shot, run);
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

    /** 这一拍画面是不是对调过的（客户端场景按帧记录）。 */
    public static boolean swapped() {
        Run run = viewing();
        return run != null && run.swapped(run.age(partialTick()));
    }

    /** {@code entity} 现在是否正被处决（身体按特写摆）。 */
    public static boolean posing(Entity entity) {
        Run run = RUNS.get(entity.getId());
        return run != null && run.level == entity.level();
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

    /** 由 Mixin 在取出实体的渲染状态之后调用：被处决的实体带上这一刻的身体姿态，不画影子（影子会跟着身体一起飞起来）。 */
    public static void extracted(Entity entity, EntityRenderState state) {
        Run run = RUNS.get(entity.getId());
        if (run == null || run.level != entity.level()) return;
        state.setRenderData(BODY, run.body(run.age(state.partialTick)));
        state.shadowPieces.clear();
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
            Entity target = run.level.getEntity(run.target);
            if (mc.level != run.level || target == null || !target.isAlive() && run.age(0) < run.timeline.impact()) {
                // 目标没了（走远、死了、换了世界）：镜头提前回去，画面马上恢复
                if (run == viewing) {
                    if (mc.level == run.level) CameraDirector.returnNow();
                    viewing = null;
                }
                it.remove();
                continue;
            }
            if (!mc.isPaused() && held == null && ++run.ticks >= run.timeline.duration()) {
                if (run == viewing) viewing = null;
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
        private final Vector3f[] push, axis;
        private final Vector3f[] shakeDir;
        private final Vector3f finisherPush, finisherAxis, slamAxis;
        private final float spin;
        private final List<Cue> cues = new ArrayList<>();
        private int nextCue;
        int ticks;

        private record Cue(float at, Runnable action) {}

        Run(ClientLevel level, LivingEntity entity, ExecutionPayload payload) {
            this.level = level;
            this.target = entity.getId();
            this.timeline = new ExecutionTimeline(payload.shot());
            this.viewer = payload.viewer();
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
            push = new Vector3f[slashes.size()];
            axis = new Vector3f[slashes.size()];
            shakeDir = new Vector3f[slashes.size() + 2];
            for (int i = 0; i < slashes.size(); i++) {
                Slash s = slashes.get(i);
                push[i] = screenToWorld(timeline.cut(s.contact()), s.angle());
                axis[i] = tipAxis(push[i], timeline.cut(s.contact()));
                shakeDir[i] = new Vector3f(random.nextFloat() - .5f, random.nextFloat() - .5f, random.nextFloat() - .5f).normalize();
            }
            shakeDir[slashes.size()] = new Vector3f(random.nextFloat() - .5f, .6f, random.nextFloat() - .5f).normalize();
            shakeDir[slashes.size() + 1] = new Vector3f(random.nextFloat() * .4f - .2f, 1, random.nextFloat() * .4f - .2f).normalize();
            Cut last = timeline.cut(timeline.finalHit());
            finisherPush = screenToWorld(last, timeline.finisher().angle());
            finisherAxis = tipAxis(finisherPush, last);
            Vector3f flat = new Vector3f(finisherPush.x, 0, finisherPush.z);
            slamAxis = flat.lengthSquared() < 1e-4 ? toVector(right(last)) : new Vector3f(UP).cross(flat).normalize();
            spin = (random.nextBoolean() ? 1 : -1) * .012f;
            buildCues(random);
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
                if (since > 0 && since < 6) sum.fma(.045f * slashes.get(i).force() * (float) (Math.exp(-since / 1.1) * Math.sin(since * 4.2 + i)), shakeDir[i]);
            }
            float hit = age - timeline.finalHit();
            if (hit > 0 && hit < 10) sum.fma((float) (.2 * Math.exp(-hit / 2.2) * Math.sin(hit * 3.6)), shakeDir[slashes.size()]);
            float land = age - timeline.impact();
            if (land > 0 && land < 10) sum.fma((float) (.28 * Math.exp(-land / 2) * Math.sin(land * 3.9 + 1)), shakeDir[slashes.size() + 1]);
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
                rotation.fma(.42f * k, axis[i]);
                rotation.fma(s.twist() * ExecutionTimeline.punch(since, .9f), UP);
                move.fma(.1f * k, push[i]);
                flail.fma(ExecutionTimeline.punch(since - .35f, 1) * s.force(), push[i]);
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
