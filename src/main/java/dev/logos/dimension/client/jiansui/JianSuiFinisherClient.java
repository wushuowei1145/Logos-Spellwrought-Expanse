package dev.logos.dimension.client.jiansui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.camera.CameraDirector;
import dev.logos.dimension.client.execution.ExecutionClient;
import dev.logos.dimension.client.vfx.DawnvowShake;
import dev.logos.dimension.execution.ExecutionTimeline;
import dev.logos.dimension.jiansui.JianSuiCue;
import dev.logos.dimension.jiansui.JianSuiFight;
import dev.logos.dimension.jiansui.JianSuiPry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.ClientAvatarEntity;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.client.renderstate.AvatarRenderStateModifier;
import net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * 剑祟终结里撬剑的那一段（docs/JIAN_SUI_BOSS.md 第 11 节），本机的部分：
 * <ul>
 *   <li><b>撬剑的姿势。</b>打中剑祟的人贴在它胸前、双手握着刺进去的东西；每撬一下往下一压、顿一下；第三下之后被震退一步，
 *       落回他一直站着的地方，再交还原版。姿势按剑祟插值后的位置摆，服务端把人挪来挪去也看不出来。交给处决特写的身体姿态
 *       （{@link ExecutionClient#BODY}），由这里的渲染状态修改器写，不另加 Mixin；处决特写自己还在摆这个人时以它为准。</li>
 *   <li><b>撬剑的输入。</b>过场里（黑白开始到镜头回来）打中的人的攻击键被吞掉；撬剑的窗口里按一下就告诉服务端
 *       （{@link JianSuiPry}），最多三下、两下至少隔 {@link JianSuiFight#PRY_GAP} 刻，本机先响一声。算不算、什么时候撬都由服务端定，
 *       不按也会自己撬完。屏幕上不出任何提示。</li>
 *   <li><b>每撬一下</b>（{@link JianSuiCue#PRY_BEAT}）：镜头轻轻一抖；剑祟的反应交给 {@link JianSuiRenderer}（{@link #reaction}）。</li>
 * </ul>
 * 不管手里拿什么都拦攻击键，是 COMBAT_SKELETON 第 1 节第 7 条的例外（维护者 2026-11-22 批准），只在这段过场里。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class JianSuiFinisherClient {
    /**
     * 撬的时候双手握着刺进胸口的东西（按右手惯用写，左手惯用由 {@link ExecutionClient} 镜像；弧度，原版模型零件的转角）：
     * 主手和腿照「飞身刺」刺中后定住的样子（{@link ExecutionClient#IMPALED}），甩在身后的另一只手收回来、往里握到同一个地方。按截图调。
     */
    private static final ExecutionClient.Pose GRIP = new ExecutionClient.Pose(ExecutionClient.IMPALED.mainArmX(), ExecutionClient.IMPALED.mainArmY(),
            ExecutionClient.IMPALED.mainArmZ(), -.62f, .55f, -.05f, ExecutionClient.IMPALED.frontLegX(), ExecutionClient.IMPALED.backLegX(), -.3f);
    /** 回到彩色后另一只手几刻收回来握住。 */
    private static final float GRIP_IN = 5;
    /** 震退以后落地站好（接近原版拿着东西、空着手站着的样子），交还原版时不跳。 */
    private static final ExecutionClient.Pose STAND_ITEM = new ExecutionClient.Pose(-.31f, 0, 0, 0, 0, 0, 0, 0, 0),
            STAND_EMPTY = new ExecutionClient.Pose(0, 0, 0, 0, 0, 0, 0, 0, 0);
    /** 每撬一下：两臂往下压多少（弧度），身子往下沉多少（格）、往前多倾多少（弧度）。 */
    private static final float PRESS_ARMS = (float) Math.toRadians(15), PRESS_DIP = .1f, PRESS_LEAN = (float) Math.toRadians(6);
    /** 顿帧：一下顶到最大以后停几刻再回来。 */
    static final float HIT_STOP = 2;
    /** 震退几刻落地；中间抛起多高（格）、往后仰多少（弧度）。 */
    private static final float KNOCK = 6, KNOCK_ARC = .35f, KNOCK_TILT = .3f;
    /** 每撬一下镜头抖多大（插剑那一下是 1）。 */
    private static final float SHAKE = .3f;
    /** 贯穿古剑每撬一下顶出多少格、几刻顶到；面具上的一道裂纹几刻裂完。 */
    static final float PUSH_PER_BEAT = .12f, PUSH_RISE = 3, CRACK_GROW = 2;
    /** 节拍按剑祟胸口的位置认是哪一只的：离多远以内算。 */
    private static final double NEAR = 3;
    /** 节拍留多久（刻）：最早的一下到剑祟移除。 */
    private static final double BEAT_LIFE = JianSuiFight.PRY_CLOSE - JianSuiFight.PRY_OPEN + JianSuiFight.BURST_AFTER + JianSuiFight.GONE_AFTER + 20;

    /** 一次终结：打中的人、剑祟（实体编号）、从哪一刻算起（服务端游戏刻）；本机（打中的人）按了几下、上一下在第几刻。 */
    private static final class Finisher {
        final int hitter, boss;
        final long since;
        int sent;
        long lastSent = Long.MIN_VALUE / 2;

        Finisher(JianSuiPry.Start start) {
            hitter = start.hitter();
            boss = start.boss();
            since = start.since();
        }
    }

    /** 撬了一下：服务端的游戏刻、剑祟胸口、第几下。 */
    private record Beat(long time, Vec3 chest, int index) {}

    /** 剑祟这一帧被撬成什么样：两只手抽一下多大（0–1），贯穿古剑顶出多少格，面具裂了几道（带小数：正在裂的那一道裂到几成）。 */
    record Reaction(float twitch, float push, float cracks) {}

    /** 按打中的人（实体编号）记。 */
    private static final Map<Integer, Finisher> FINISHERS = new HashMap<>();
    private static final List<Beat> BEATS = new ArrayList<>();
    /** 这些记录属于哪个世界：{@code mc.level} 换了一个就全部作废。 */
    private static @Nullable Level shown;
    /** 客户端场景用：停在这一刻（游戏刻，可以有小数）上截图。 */
    private static @Nullable Double held;

    private JianSuiFinisherClient() {}

    @SubscribeEvent
    static void payloads(RegisterClientPayloadHandlersEvent event) {
        event.register(JianSuiPry.Start.TYPE, (start, context) -> context.enqueueWork(() -> start(start)));
    }

    /** 终结开始（服务端在发黑白「飞身刺」的同一刻发来；客户端场景也直接调它）。 */
    public static void start(JianSuiPry.Start start) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        sync(mc.level);
        FINISHERS.put(start.hitter(), new Finisher(start));
        // 重置以后重打：上一次留下的节拍作废
        BEATS.removeIf(b -> b.time < start.since());
    }

    /** 撬了一下（{@link JianSuiCueClient} 转来）：记下来，镜头轻轻一抖。 */
    static void beat(JianSuiCue cue) {
        BEATS.add(new Beat(cue.time(), cue.from(), Math.round(cue.value())));
        if (BEATS.size() > 32) BEATS.removeFirst();
        DawnvowShake.at(cue.from(), SHAKE);
    }

    /** 终结被提前截断、剑祟还留着（{@link JianSuiCue#FINISHER_END}）：撬这一只的都不再摆，每一下的反应收掉。 */
    static void end(JianSuiCue cue) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        FINISHERS.values().removeIf(f -> level.getEntity(f.boss) instanceof Entity boss && boss.position().add(0, 2.4, 0).distanceTo(cue.from()) < NEAR);
        BEATS.removeIf(b -> b.chest.distanceTo(cue.from()) < NEAR);
    }

    /** 客户端场景用：让撬剑的姿势和剑祟的反应停在 {@code gameTime} 刻上，传 null 放开。 */
    public static void hold(@Nullable Double gameTime) {
        held = gameTime;
    }

    private static double now(float partialTick) {
        var level = Minecraft.getInstance().level;
        if (held != null) return held;
        // 先转成 double 再加：游戏刻大了以后 long + float 会丢掉零点几刻
        return level == null ? 0 : (double) level.getGameTime() + partialTick;
    }

    /** 顿帧式的一下：一刻内顶到最大，停 {@link #HIT_STOP} 刻，再慢慢回来（约 6 刻）。 */
    static float kick(float since) {
        if (since <= 0) return 0;
        if (since < 1) return ExecutionTimeline.punch(since, 1);
        if (since < 1 + HIT_STOP) return 1;
        return ExecutionTimeline.punch(since - HIT_STOP, 1);
    }

    private static boolean near(Beat b, Vec3 chest) {
        return b.chest.distanceToSqr(chest) < NEAR * NEAR;
    }

    /** {@code boss} 这一帧被撬成什么样（{@link JianSuiRenderer} 用）；没被撬时 null。 */
    static @Nullable Reaction reaction(Entity boss, float partialTick) {
        if (BEATS.isEmpty()) return null;
        double now = now(partialTick);
        Vec3 chest = boss.getPosition(partialTick).add(0, 2.4, 0);
        float twitch = 0, push = 0, cracks = 0;
        boolean any = false;
        for (Beat b : BEATS) {
            float since = (float) (now - b.time);
            if (since < 0 || !near(b, chest)) continue;
            any = true;
            twitch = Math.max(twitch, kick(since));
            float k = Math.min(1, since / PUSH_RISE);
            push += PUSH_PER_BEAT * (1 - (1 - k) * (1 - k) * (1 - k));
            cracks = Math.max(cracks, b.index + Math.min(1, since / CRACK_GROW));
        }
        return any ? new Reaction(twitch, push, cracks) : null;
    }

    /**
     * 这一次终结在哪一刻崩出（游戏刻）：第三下之后 {@link JianSuiFight#BURST_AFTER} 刻。还没收到第三下时按最晚的算
     * （三下都自己撬），收不到也不会一直摆着。
     */
    private static double burst(Finisher f, @Nullable Entity boss) {
        for (Beat b : BEATS)
            if (b.index >= JianSuiFight.PRY_AUTO.length - 1 && b.time >= f.since && (boss == null || near(b, boss.position().add(0, 2.4, 0))))
                return b.time + JianSuiFight.BURST_AFTER;
        return f.since + JianSuiFight.PRY_AUTO[2] + JianSuiFight.BURST_AFTER;
    }

    /** 这一次终结撬了几下。 */
    private static int beats(Finisher f, @Nullable Entity boss) {
        int n = 0;
        for (Beat b : BEATS) if (b.time >= f.since && (boss == null || near(b, boss.position().add(0, 2.4, 0)))) n++;
        return n;
    }

    /** 过场在哪一刻完（镜头回到玩家）：崩出后收尾，再加镜头往回走的那段。 */
    private static double end(Finisher f, @Nullable Entity boss) {
        return burst(f, boss) + JianSuiFight.GONE_AFTER + JianSuiFight.FIN_RETURN;
    }

    // ---------------------------------------------------------------- 撬剑的姿势

    @SubscribeEvent
    static void modifiers(RegisterRenderStateModifiersEvent event) {
        event.registerAvatarEntityModifier(new AvatarRenderStateModifier() {
            @Override public <T extends Avatar & ClientAvatarEntity> void accept(T avatar, AvatarRenderState state) {
                if (!FINISHERS.isEmpty()) pose(avatar, state);
            }
        });
    }

    /**
     * 打中的人这一帧的样子：从「飞身刺」刺中后定住的样子接着摆（{@link ExecutionClient#impaled}：贴在剑祟胸前、面朝它、往前倾，
     * 手里的东西刺在胸口），另一只手收回来一起握着；每撬一下往下一压、顿一下。第三下之后 {@link JianSuiFight#BURST_AFTER} 刻崩出，
     * 被震退：往后、往下，{@link #KNOCK} 刻落回他真正站着的地方（服务端一直让他站在剑祟面前一步），然后不再摆，交还原版。
     * 朝向按剑祟现在的朝向算（换场景时服务端把它转成了朝外）。原版挥手、用东西、走路、潜行的影响都清掉，
     * 原版的手和手里的东西（任何模组的、空手）照常画。
     */
    private static void pose(Avatar avatar, AvatarRenderState state) {
        Finisher f = FINISHERS.get(avatar.getId());
        if (f == null || state.isSpectator || !(avatar.level().getEntity(f.boss) instanceof LivingEntity boss)) return;
        float partial = state.partialTick;
        double now = now(partial);
        // 从换场景那一刻开始（旁观的人这时不再按黑白特写摆；看特写的人那边特写还在摆，它在后面写，以它为准）
        double age = now - f.since;
        if (age < JianSuiFight.FIN_SWAP) return;
        float knock = (float) ((now - burst(f, boss)) / KNOCK);
        if (knock >= 1) return;
        ExecutionClient.neutral(state);
        float yaw = Mth.rotLerp(partial, boss.yBodyRotO, boss.yBodyRot);
        ExecutionClient.Body impaled = ExecutionClient.impaled(avatar, boss, yaw, state);
        double radians = Math.toRadians(yaw);
        Vec3 out = new Vec3(-Math.sin(radians), 0, Math.cos(radians));
        Vec3 chest = boss.getPosition(partial).add(0, 2.4, 0);
        float press = 0;
        for (Beat b : BEATS) if (b.time >= f.since && near(b, chest)) press = Math.max(press, kick((float) (now - b.time)));
        float grip = (float) Mth.clamp((age - JianSuiFight.FIN_COLOUR) / GRIP_IN, 0, 1);
        ExecutionClient.Pose limbs = pressed(ExecutionClient.Pose.lerp(ExecutionClient.IMPALED, GRIP, grip * grip * (3 - 2 * grip)), press);
        // 往前多倾：绕身子的左右轴转（世界坐标），头往剑祟那边倒；按的时候往下一沉
        Quaternionf rotation = new Quaternionf().rotationAxis(PRESS_LEAN * press, (float) -out.z, 0, (float) out.x).mul(impaled.rotation());
        Vector3f lift = new Vector3f(impaled.lift()).sub(0, PRESS_DIP * press, 0);
        if (knock > 0) {
            float k = knock * knock * (3 - 2 * knock), arc = Mth.sin((float) Math.PI * knock);
            // 落回脚下（位移归零），中间抛起一点；身子回正（只剩面朝剑祟的那一下转），崩开时往后仰一下
            lift.mul(1 - k).add(0, KNOCK_ARC * arc, 0);
            Quaternionf upright = new Quaternionf().rotationY((float) Math.toRadians(state.bodyRot - yaw - 180));
            Quaternionf back = new Quaternionf().rotationAxis(-KNOCK_TILT * arc, (float) -out.z, 0, (float) out.x);
            rotation = new Quaternionf(rotation).slerp(upright, k).premul(back);
            limbs = ExecutionClient.Pose.lerp(limbs, avatar.getMainHandItem().isEmpty() ? STAND_EMPTY : STAND_ITEM, k);
        }
        state.setRenderData(ExecutionClient.BODY, new ExecutionClient.Body(lift, rotation, impaled.pivot(), 0, new Vector3f(), limbs));
        // 影子留在脚下会和抬起来的身子分开
        state.shadowPieces.clear();
    }

    /** 握着的样子往下压 {@code press}（0–1）：两臂一起往下。 */
    private static ExecutionClient.Pose pressed(ExecutionClient.Pose g, float press) {
        float d = PRESS_ARMS * press;
        return new ExecutionClient.Pose(g.mainArmX() + d, g.mainArmY(), g.mainArmZ(), g.offArmX() + d, g.offArmY(), g.offArmZ(), g.frontLegX(), g.backLegX(), g.headX());
    }

    // ---------------------------------------------------------------- 撬剑的输入

    /** 本机打中了剑祟、过场还在放（镜头还没回来）的那一次终结；不是时 null。 */
    private static @Nullable Finisher own() {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;
        Finisher f = FINISHERS.get(mc.player.getId());
        return f != null && now(0) < end(f, mc.level.getEntity(f.boss)) ? f : null;
    }

    /**
     * 过场里打中的人按攻击键（鼠标或键盘，按它绑定的那个）：吞掉，不交给原版；撬剑的窗口里再告诉服务端。
     * 镜头锁着时按键映射每刻都被松开，只能在原始的输入事件里认（同可交互的处决）。
     */
    @SubscribeEvent
    static void mouse(InputEvent.MouseButton.Pre event) {
        var key = Minecraft.getInstance().options.keyAttack.getKey();
        if (key.getType() == InputConstants.Type.MOUSE && key.getValue() == event.getButton() && press(event.getAction())) event.setCanceled(true);
    }

    @SubscribeEvent
    static void key(InputEvent.Key event) {
        var key = Minecraft.getInstance().options.keyAttack.getKey();
        if (key.getType() == InputConstants.Type.KEYSYM && key.getValue() == event.getKey()) press(event.getAction());
    }

    /** 测试用：当成打中的人按了一下攻击键；真的告诉了服务端时返回 true。 */
    public static boolean press() {
        Finisher f = own();
        return f != null && Minecraft.getInstance().screen == null && CameraDirector.active() && pry(f);
    }

    /** 返回 true 时这次按键吞掉（过场里，不管是不是在撬剑的窗口里）。 */
    private static boolean press(int action) {
        Finisher f = own();
        if (f == null || Minecraft.getInstance().screen != null || !CameraDirector.active()) return false;
        if (action == GLFW.GLFW_PRESS) pry(f);
        return true;
    }

    /** 撬剑的窗口里、还没按满三下、和上一下隔够了：告诉服务端，本机先响一声（服务端那一下的金属摩擦要等它回来）。 */
    private static boolean pry(Finisher f) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return false;
        long t = mc.level.getGameTime() - f.since;
        int most = JianSuiFight.PRY_AUTO.length;
        if (t < JianSuiFight.PRY_OPEN || t >= JianSuiFight.PRY_CLOSE || f.sent >= most || beats(f, mc.level.getEntity(f.boss)) >= most
                || t - f.lastSent < JianSuiFight.PRY_GAP) return false;
        f.sent++;
        f.lastSent = t;
        ClientPacketDistributor.sendToServer(new JianSuiPry());
        mc.level.playLocalSound(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ(), SoundEvents.CHAIN_HIT, SoundSource.PLAYERS, .6f, 1.3f, false);
        return true;
    }

    // ---------------------------------------------------------------- 收拾

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        var level = Minecraft.getInstance().level;
        sync(level);
        if (level == null || FINISHERS.isEmpty() && BEATS.isEmpty()) return;
        double now = now(0);
        FINISHERS.values().removeIf(f -> now > end(f, level.getEntity(f.boss)));
        BEATS.removeIf(b -> now - b.time > BEAT_LIFE);
    }

    @SubscribeEvent
    static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
        shown = null;
    }

    /** {@code mc.level} 换了一个（换维度、换世界）：这边记着的全部作废。 */
    private static void sync(@Nullable Level level) {
        if (level == shown) return;
        shown = level;
        clear();
    }

    private static void clear() {
        FINISHERS.clear();
        BEATS.clear();
    }

    /** 客户端场景用：收到了几下撬剑。 */
    static int beatCount() {
        return BEATS.size();
    }

    /** 客户端场景用：最近一下撬剑在服务端的哪一刻，还没撬过时 -1。 */
    static long lastBeat() {
        return BEATS.isEmpty() ? -1 : BEATS.getLast().time;
    }

    /** 客户端场景用：清掉上一个场景留下的终结（同一个维度里换场景时客户端世界不换，不会自己清）。 */
    static void reset() {
        clear();
        held = null;
    }
}
