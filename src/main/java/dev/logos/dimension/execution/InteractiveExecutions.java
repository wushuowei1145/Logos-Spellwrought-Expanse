package dev.logos.dimension.execution;

import dev.logos.dimension.LogosDimension;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jspecify.annotations.Nullable;

/**
 * 可交互的处决（docs/EXECUTION_SHOTS.md「可交互的处决」）：目标被抛到半空、画面变黑白之后，留一段时间让处决的人自己点左键，
 * 点一下砍一刀；时间到了静一下，重击，拍到地上。画面就是 {@link ExecutionShots} 的特写，只是刀由点击来：
 * <ul>
 *   <li>处决的人和被处决的玩家看特写（镜头、黑白、刀光）；处决的人的左键在这段时间里变成出刀，镜头锁着、人不动。</li>
 *   <li>附近别的玩家只看到目标在半空挨刀：身体一刀刀被打歪，身上闪过刀光。</li>
 *   <li>出刀的节奏由服务端把关：每两刀至少隔 {@code minGap} 刻，点得再快也只按这个节奏结算（多点的排着队，窗口关了就不再收）。</li>
 * </ul>
 * 伤害和别的效果由调用方的 {@link Handler} 在每一刀、重击、落地时自己做，这里只管时间、点击和画面。
 * 处决的人死了、走远、换了世界，或目标没了时中途结束（{@link Handler#end} 收到 false），客户端的镜头提前回来。
 * 其他武器要用：服务端调 {@link #start}，给一个 {@link Handler}。
 */
@EventBusSubscriber(modid = LogosDimension.ID)
public final class InteractiveExecutions {
    /** 处决的人离目标多远就算走远了，中途结束。 */
    static final double LEASH = 24;
    /** 点击窗口关上之后还收几刻排队的刀（网络把相邻两刻的点击挤到同一刻送到时）。 */
    static final int GRACE = 2;

    /** 一次处决里各个时刻要做的事，都在服务端线程上调。 */
    public interface Handler {
        /** 第 {@code index} 刀（从 0 起）砍中。 */
        void slash(ServerPlayer executor, LivingEntity target, int index);

        /** 重击：一共砍了 {@code slashes} 刀之后的最后一刀。 */
        void finish(ServerPlayer executor, LivingEntity target, int slashes);

        /** 被拍到地上。 */
        void impact(ServerPlayer executor, LivingEntity target);

        /** 结束；{@code completed} 为假是中途断了。 */
        void end(ServerPlayer executor, LivingEntity target, boolean completed);
    }

    private static final class Session {
        final ServerPlayer executor;
        final LivingEntity target;
        final Handler handler;
        final ExecutionTimeline timeline;
        final int minGap;
        final long start;
        final List<ServerPlayer> viewers;
        int queued, slashes;
        long lastSlash = Long.MIN_VALUE / 2;
        boolean finished, landed;

        Session(ServerPlayer executor, LivingEntity target, Handler handler, ExecutionTimeline timeline, int minGap, long start, List<ServerPlayer> viewers) {
            this.executor = executor; this.target = target; this.handler = handler; this.timeline = timeline; this.minGap = minGap;
            this.start = start; this.viewers = viewers;
        }

        float age(long now) {
            return now - start;
        }
    }

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private InteractiveExecutions() {}

    /**
     * {@code executor} 处决 {@code target}：{@code shot} 要是可交互的（{@link ExecutionShot#live}），每两刀至少隔 {@code minGap} 刻。
     * 处决的人已经在处决、目标已经被别人处决时不开始，返回 false。
     */
    public static boolean start(ServerPlayer executor, LivingEntity target, ExecutionShot shot, int minGap, Handler handler) {
        if (!shot.interactive()) throw new IllegalArgumentException("要用 ExecutionShot.live 设定点击窗口");
        if (executing(executor) || target(target) != null || !target.isAlive() || !(executor.level() instanceof ServerLevel level)) return false;
        List<ServerPlayer> viewers = new ArrayList<>();
        viewers.add(executor);
        if (target instanceof ServerPlayer victim && victim != executor) viewers.add(victim);
        var timeline = new ExecutionTimeline(shot);
        SESSIONS.put(executor.getUUID(), new Session(executor, target, handler, timeline, Math.max(1, minGap), level.getGameTime(), List.copyOf(viewers)));
        ExecutionShots.play(viewers, executor, target, shot);
        return true;
    }

    /** 这名玩家正在处决。 */
    public static boolean executing(ServerPlayer player) {
        return SESSIONS.containsKey(player.getUUID());
    }

    /** 正在处决 {@code entity} 的人，没有为 null。 */
    public static @Nullable ServerPlayer target(Entity entity) {
        for (Session s : SESSIONS.values()) if (s.target == entity) return s.executor;
        return null;
    }

    /** 这次处决到现在砍了几刀（测试用）；没在处决为 -1。 */
    public static int slashes(ServerPlayer executor) {
        Session s = SESSIONS.get(executor.getUUID());
        return s == null ? -1 : s.slashes;
    }

    /** 处决的人点了一下左键：窗口开着就记一刀，按节奏在之后的刻里结算。 */
    public static void click(ServerPlayer executor) {
        Session s = SESSIONS.get(executor.getUUID());
        if (s == null) return;
        float age = s.age(executor.level().getGameTime());
        if (age >= ExecutionTimeline.FIRST_SLASH && age < s.timeline.windowEnd() + GRACE) s.queued++;
    }

    @SubscribeEvent
    static void tick(ServerTickEvent.Post event) {
        if (SESSIONS.isEmpty()) return;
        for (Session s : List.copyOf(SESSIONS.values())) tick(s);
    }

    private static void tick(Session s) {
        long now = s.executor.level().getGameTime();
        float age = s.age(now);
        boolean gone = s.executor.isRemoved() || !s.executor.isAlive() || s.executor.level() != s.target.level()
                || s.executor.distanceToSqr(s.target) > LEASH * LEASH;
        if (gone || s.target.isRemoved() || !s.target.isAlive() && !s.landed) {
            end(s, false);
            return;
        }
        // 排着队的刀：窗口里（加一点余量）每 minGap 刻结算一刀
        if (s.queued > 0 && age < s.timeline.windowEnd() + GRACE && now - s.lastSlash >= s.minGap) {
            s.queued--;
            s.lastSlash = now;
            int index = s.slashes++;
            var payload = new ExecutionPayload.Slash(s.target.getId(), age);
            for (ServerPlayer viewer : s.viewers) if (viewer != s.executor) ExecutionShots.send(viewer, payload);
            if (s.executor.level() instanceof ServerLevel level) for (ServerPlayer other : ExecutionShots.watchers(level, s.target, s.viewers)) ExecutionShots.send(other, payload);
            s.handler.slash(s.executor, s.target, index);
            if (!s.target.isAlive()) return;
        }
        if (!s.finished && age >= s.timeline.finalHit()) {
            s.finished = true;
            s.queued = 0;
            s.handler.finish(s.executor, s.target, s.slashes);
        }
        if (!s.landed && age >= s.timeline.impact()) {
            s.landed = true;
            s.handler.impact(s.executor, s.target);
        }
        if (age >= s.timeline.duration()) end(s, true);
    }

    private static void end(Session s, boolean completed) {
        SESSIONS.remove(s.executor.getUUID());
        ExecutionShots.release(s.target);
        s.handler.end(s.executor, s.target, completed);
    }

    /** 半空里挨刀的目标不被推开（不然一刀刀把它推出镜头，被定住的生物还会攒着速度，放开时一下子飞走）。 */
    @SubscribeEvent
    static void knockback(LivingKnockBackEvent event) {
        if (!event.getEntity().level().isClientSide() && target(event.getEntity()) != null) event.setCanceled(true);
    }

    @SubscribeEvent
    static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        Session s = SESSIONS.get(event.getEntity().getUUID());
        if (s != null) end(s, false);
    }

    @SubscribeEvent
    static void stopped(ServerStoppedEvent event) {
        SESSIONS.clear();
    }
}
