package dev.logos.dimension.client.camera;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.camera.CameraShot;
import dev.logos.dimension.camera.CameraShotPayload;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.CalculatePlayerTurnEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/**
 * 本机正在播的那段运镜。每帧把镜头放在「玩家这一帧的真实视角」和机位之间，比例由 {@link CameraShot#weight} 给：
 * 比例为 0 时就是原版视角，所以进出都接得上，玩家中途被打飞了也会准确地回到玩家现在的位置。全程不换相机实体、
 * 不重载世界，也就没有黑屏；玩家的物理和位置同步照常。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class CameraDirector {
    /** 镜头离玩家视角超过这么远才算离开：离开时才切第三人称、藏界面，回到这个距离内就交还控制。 */
    private static final double AWAY = .6;
    /** 鼠标灵敏度取这个值时，原版的转向公式 (s × 0.6 + 0.2)³ 正好归零。 */
    private static final double NO_TURN = -.2 / .6;
    /** 看向正上下方时偏航没有定义，留一点角度。 */
    private static final float MAX_PITCH = 89.5f;
    /** 上下遮幅各占屏幕高度多少，16:9 下接近 2.39:1 的宽银幕。 */
    private static final float BAR_HEIGHT = .125f;
    private static final int BAR_COLOR = 0xFF000000;

    private static @Nullable CameraShot shot;
    private static @Nullable Level level;
    private static int ticks;
    /** 正常是 1；提前收尾时是当时的比例，让回程从那里接着走。 */
    private static float strength = 1;
    private static float weight;
    private static boolean locked, away, returned;
    /** 玩家自己选的视角，播完恢复。 */
    private static CameraType userView = CameraType.FIRST_PERSON;
    private static @Nullable Vec3 lastFrom, lastLook;
    /** 这一帧的第一人称视角，见 {@link #captureEyes}。 */
    private static @Nullable Pose eyes;
    /** 上一帧的画面，新的一段打断旧的时从这里接。 */
    private static @Nullable Pose shown;
    /** 打断旧镜头时它的画面：新的一段的进场从这里出发，而不是从玩家视角。 */
    private static @Nullable Pose carry;
    /** 遮幅伸出来多少：0 在屏幕外，1 完全进来。 */
    private static float bars;
    /** 进场时遮幅从哪里出发：打断旧镜头时是它当时的量，其余时候是 0。 */
    private static float barsFrom;
    /** 停留时遮幅的量，也是回程的起点：带遮幅是 1，不带是 0；提前收尾时是当时的量。 */
    private static float barsPeak;
    /** 电影蒙版的强度，走法和遮幅一样。 */
    private static float grade, gradeFrom, gradePeak;

    private CameraDirector() {}

    /** 镜头的位置和朝向。朝向的含义同 {@link Camera#setRotation}。 */
    public record Pose(Vec3 pos, float yaw, float pitch, float roll) {
        static Pose of(Camera camera) {
            return new Pose(camera.position(), camera.yRot(), camera.xRot(), camera.getRoll());
        }

        private Quaterniond rotation() {
            return new Quaterniond().rotationYXZ(Math.PI - Math.toRadians(yaw), -Math.toRadians(pitch), -Math.toRadians(roll));
        }

        /** 朝向走球面插值：镜头转向接近垂直的方向时，分开插偏航和俯仰会原地打转。 */
        static Pose blend(Pose a, Pose b, float t) {
            if (t <= 0) return a;
            if (t >= 1) return b;
            Vector3d angles = a.rotation().slerp(b.rotation(), t).getEulerAnglesYXZ(new Vector3d());
            return new Pose(a.pos.lerp(b.pos, t), (float) (180 - Math.toDegrees(angles.y)), (float) -Math.toDegrees(angles.x), (float) -Math.toDegrees(angles.z));
        }
    }

    @SubscribeEvent static void packets(RegisterClientPayloadHandlersEvent event) {
        event.register(CameraShotPayload.TYPE, (payload, context) -> play(payload.shot()));
    }

    /** 开始播一段。上一段还没放完时直接从它的画面接过来；玩家的视角设置仍按最早那段开始前的算。 */
    public static void play(CameraShot next) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (shot == null) {
            userView = mc.options.getCameraType();
            carry = null;
        } else {
            carry = shown;
            if (locked && !next.lockControl()) unlock(mc);
        }
        barsFrom = bars;
        barsPeak = next.letterboxed() ? 1 : 0;
        gradeFrom = grade;
        gradePeak = next.cinematic() ? 1 : 0;
        shot = next;
        level = mc.level;
        ticks = 0;
        strength = 1;
        returned = false;
        lastFrom = lastLook = null;
        locked = next.lockControl();
        if (locked) KeyMapping.releaseAll();
    }

    public static boolean active() {
        return shot != null;
    }

    /** 玩家的操作现在是否被锁着。 */
    public static boolean locked() {
        return locked;
    }

    /** 遮幅伸出来多少：0 在屏幕外，1 完全进来。 */
    public static float bars() {
        return bars;
    }

    /** 电影蒙版现在的强度：0 没有，1 完全显现。 */
    public static float grade() {
        return grade;
    }

    /** 当前这段走到第几刻（不含帧内插值），没在播时是 -1。客户端场景按它截图。 */
    public static int age() {
        return shot == null ? -1 : ticks;
    }

    /** 让镜头现在就开始往回走（玩家死了、镜头该提前结束时）。 */
    public static void returnNow() {
        if (shot == null || ticks >= shot.returnsAt()) return;
        strength = weight;
        barsPeak = bars;
        gradePeak = grade;
        carry = null;
        ticks = shot.returnsAt();
    }

    /** 由 Mixin 在原版把镜头推到第三人称之前调用：这时的位姿就是第一人称视角，已含镜头抖动。 */
    public static void captureEyes(Camera camera) {
        if (shot != null) eyes = Pose.of(camera);
    }

    /** 由 Mixin 在原版摆好镜头后调用：返回这一帧该用的位姿，没在播时返回 null。 */
    public static @Nullable Pose frame(Camera camera, float partialTick) {
        if (shot == null || level == null || eyes == null) return null;
        Pose player = userView.isFirstPerson() ? eyes : Pose.of(camera);
        float age = ticks + partialTick;
        float curve = shot.weight(age);
        weight = strength * curve;
        bars = envelope(age, curve, barsFrom, barsPeak);
        grade = envelope(age, curve, gradeFrom, gradePeak);
        if (carry != null && age >= shot.blendIn()) carry = null;
        lastFrom = shot.from().resolve(level, partialTick, lastFrom);
        lastLook = shot.lookAt().resolve(level, partialTick, lastLook);
        Pose target = lastFrom == null || lastLook == null ? player : aim(camera, partialTick, lastFrom, lastLook);
        Pose pose = Pose.blend(carry != null ? carry : player, target, weight);
        updateView(Minecraft.getInstance(), pose.pos.distanceTo(player.pos), age);
        return shown = pose;
    }

    /** 遮幅和蒙版的量：进场时从 {@code from} 走到 {@code peak}，回程时从 {@code peak} 走到 0，曲线和镜头是同一条。 */
    private static float envelope(float age, float curve, float from, float peak) {
        return age < shot.returnsAt() ? Mth.lerp(curve, from, peak) : peak * curve;
    }

    /** 站在 {@code from} 看 {@code look} 的位姿，叠上这一帧的镜头抖动（爆炸之类的震动在运镜时也看得到）。 */
    private static Pose aim(Camera camera, float partialTick, Vec3 from, Vec3 look) {
        Vec3 d = look.subtract(from);
        double flat = Math.hypot(d.x, d.z);
        // 正对上下方时偏航没有定义，沿用玩家的朝向，画面的「上」就是玩家面朝的方向
        float yaw = flat < 1e-4 ? eyes.yaw : (float) -Math.toDegrees(Math.atan2(d.x, d.z));
        float pitch = d.lengthSqr() < 1e-8 ? eyes.pitch : Mth.clamp((float) -Math.toDegrees(Math.atan2(d.y, flat)), -MAX_PITCH, MAX_PITCH);
        var body = camera.entity();
        return new Pose(from, yaw + eyes.yaw - body.getViewYRot(partialTick), pitch + eyes.pitch - body.getViewXRot(partialTick), eyes.roll);
    }

    /**
     * 镜头离开头部时切到第三人称：手、身体、屏幕效果和各处「是不是第一人称」的判断都按镜头在外面处理。
     * 这时镜头离头已有一段距离，切换本身看不出来。回到头部附近就切回去并交还控制。
     */
    private static void updateView(Minecraft mc, double distance, float age) {
        boolean nowAway = distance >= AWAY;
        if (nowAway != away) {
            away = nowAway;
            if (userView.isFirstPerson()) mc.options.setCameraType(nowAway ? CameraType.THIRD_PERSON_BACK : CameraType.FIRST_PERSON);
        }
        if (!returned && !nowAway && age >= shot.returnsAt()) {
            returned = true;
            if (locked) unlock(mc);
        }
    }

    /** 和关掉界面时一样：按键状态对回实际按着的键，切换式的潜行疾跑恢复。 */
    private static void unlock(Minecraft mc) {
        locked = false;
        if (mc.screen == null) {
            KeyMapping.setAll();
            KeyMapping.restoreToggleStatesOnScreenClosed();
        }
    }

    private static void finish(Minecraft mc) {
        if (locked) unlock(mc);
        mc.options.setCameraType(userView);
        shot = null;
        level = null;
        eyes = shown = carry = null;
        lastFrom = lastLook = null;
        away = returned = false;
        strength = 1;
        weight = 0;
        bars = barsFrom = barsPeak = 0;
        grade = gradeFrom = gradePeak = 0;
    }

    /** 放在最前面，在这一刻所有读按键的逻辑之前把按键清掉，和打开界面时一样。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST) static void tickPre(ClientTickEvent.Pre event) {
        if (shot == null) return;
        Minecraft mc = Minecraft.getInstance();
        // 退出了世界或换了维度：机位在这边没有意义，立刻结束
        if (mc.level != level || mc.player == null) {
            finish(mc);
            return;
        }
        if (!mc.isPaused()) {
            if (mc.player.isDeadOrDying()) returnNow();
            if (++ticks >= shot.duration()) {
                finish(mc);
                return;
            }
        }
        if (locked) KeyMapping.releaseAll();
        // 切视角的键这段时间不生效，否则和上面切第三人称打架
        while (mc.options.keyTogglePerspective.consumeClick()) {}
    }

    /** 镜头没回来之前把手一直压在屏幕下面，回来时它自己抬起来，不会突然出现。 */
    @SubscribeEvent static void tickPost(ClientTickEvent.Post event) {
        if (shot == null || returned) return;
        var hands = Minecraft.getInstance().gameRenderer.itemInHandRenderer;
        hands.itemUsed(InteractionHand.MAIN_HAND);
        hands.itemUsed(InteractionHand.OFF_HAND);
    }

    @SubscribeEvent static void mouse(CalculatePlayerTurnEvent event) {
        if (!locked) return;
        event.setMouseSensitivity(NO_TURN);
        event.setCinematicCameraEnabled(false);
    }

    /** 滚轮换手持物不走按键映射，要单独拦。 */
    @SubscribeEvent static void scroll(InputEvent.MouseScrollingEvent event) {
        if (locked) event.setCanceled(true);
    }

    /** 遮幅画在界面层之前：隐藏界面（包括 F1）时它照样在，保留界面时物品栏压在它上面。 */
    @SubscribeEvent static void hud(RenderGuiEvent.Pre event) {
        drawBars(event.getGuiGraphics());
        if (shot != null && shot.hideHud() && away) event.setCanceled(true);
    }

    /** 用平移而不是取整坐标来滑动：界面缩放大时，按界面像素走会一格一格地跳。 */
    private static void drawBars(GuiGraphicsExtractor graphics) {
        if (bars <= 0) return;
        int width = graphics.guiWidth(), height = graphics.guiHeight();
        int size = Mth.ceil(height * BAR_HEIGHT);
        float slide = height * BAR_HEIGHT * bars;
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(0, slide - size);
        graphics.fill(0, 0, width, size, BAR_COLOR);
        pose.popMatrix();
        pose.pushMatrix();
        pose.translate(0, height - slide);
        graphics.fill(0, 0, width, size, BAR_COLOR);
        pose.popMatrix();
    }
}
