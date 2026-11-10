package dev.logos.dimension.execution;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * 处决特写的服务端入口：投技、处决、Boss 抓住玩家时在服务端调 {@link #play}。
 * 抛起、挨打、拍下都只是画出来的样子，目标的位置和判定不动；不是玩家的目标这段时间被定住（不走 AI、不移动），
 * 玩家目标在看特写时按键被锁住。伤害、击退这些由调用方自己在合适的时候做，{@link #play} 返回的时长和
 * {@link ExecutionTimeline} 里的各个时刻可以用来对齐。用法见 docs/EXECUTION_SHOTS.md。
 * 由玩家点左键出刀的处决见 {@link InteractiveExecutions}。
 */
public final class ExecutionShots {
    /** 被定住的目标和到哪一刻为止（游戏时间）。只在服务端线程上用。 */
    private static final Map<Entity, Long> FROZEN = new WeakHashMap<>();
    /** 旁观的玩家离目标多远以内才发：再远也看不见它。 */
    static final double WATCH_RANGE = 128;

    private ExecutionShots() {}

    public static void register(IEventBus bus) {
        bus.addListener((RegisterPayloadHandlersEvent event) -> {
            var registrar = event.registrar("2");
            registrar.playToClient(ExecutionPayload.TYPE, ExecutionPayload.CODEC);
            registrar.playToClient(ExecutionPayload.Slash.TYPE, ExecutionPayload.Slash.CODEC);
            registrar.playToServer(ExecutionPayload.Click.TYPE, ExecutionPayload.Click.CODEC, (payload, context) -> {
                if (context.player() instanceof ServerPlayer player) InteractiveExecutions.click(player);
            });
        });
        NeoForge.EVENT_BUS.addListener(ExecutionShots::tick);
    }

    /**
     * 让 {@code viewer} 看 {@code target} 被处决的特写；附近别的玩家看到目标被抛起、挨打、拍下。
     *
     * @return 整段多少刻（含镜头回到玩家）
     */
    public static int play(ServerPlayer viewer, LivingEntity target, ExecutionShot shot) {
        return play(List.of(viewer), null, target, shot);
    }

    /**
     * 让 {@code viewers} 里的每个人都看特写（例如处决的人和被处决的玩家），{@code clicker}（可以为 null）是可交互处决里出刀的人；
     * 附近别的玩家只看到目标被抛起、挨打、拍下。
     *
     * @return 整段多少刻（含镜头回到玩家）
     */
    public static int play(Collection<ServerPlayer> viewers, ServerPlayer clicker, LivingEntity target, ExecutionShot shot) {
        var timeline = new ExecutionTimeline(shot);
        float yaw = target.getYRot();
        for (ServerPlayer viewer : viewers) send(viewer, new ExecutionPayload(target.getId(), shot, yaw, true, viewer == clicker));
        if (target.level() instanceof ServerLevel level) {
            var watching = new ExecutionPayload(target.getId(), shot, yaw, false);
            for (ServerPlayer other : watchers(level, target, viewers)) send(other, watching);
            if (!(target instanceof Player)) FROZEN.put(target, level.getGameTime() + timeline.duration());
        }
        return timeline.duration();
    }

    /** 离 {@code target} 不远、不在 {@code viewers} 里的玩家：他们只看到目标在半空挨打。 */
    static List<ServerPlayer> watchers(ServerLevel level, Entity target, Collection<ServerPlayer> viewers) {
        return level.players().stream().filter(p -> !viewers.contains(p) && p.distanceToSqr(target) < WATCH_RANGE * WATCH_RANGE).toList();
    }

    /** {@code entity} 现在是否被处决特写定住。 */
    public static boolean frozen(Entity entity) {
        Long until = FROZEN.get(entity);
        return until != null && entity.level().getGameTime() < until;
    }

    /** 提前放开（可交互的处决中途断了）。 */
    public static void release(Entity entity) {
        FROZEN.remove(entity);
    }

    static void send(ServerPlayer player, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        if (player.connection.hasChannel(payload.type())) PacketDistributor.sendToPlayer(player, payload);
    }

    /**
     * 被定住的生物这一刻整个跳过：不走 AI、不受重力、不被推开，客户端那边看到它停在原地。
     * 已经死了的不跳过，让它照常倒下、消失（不然尸体要在原地站到这一段结束）。
     */
    private static void tick(EntityTickEvent.Pre event) {
        Entity entity = event.getEntity();
        if (entity.level().isClientSide()) return;
        Long until = FROZEN.get(entity);
        if (until == null) return;
        if (entity.isRemoved() || entity.level().getGameTime() >= until || entity instanceof LivingEntity living && living.isDeadOrDying()) {
            FROZEN.remove(entity);
            return;
        }
        event.setCanceled(true);
    }
}
