package dev.logos.dimension.execution;

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
 */
public final class ExecutionShots {
    /** 被定住的目标和到哪一刻为止（游戏时间）。只在服务端线程上用。 */
    private static final Map<Entity, Long> FROZEN = new WeakHashMap<>();
    /** 旁观的玩家离目标多远以内才发：再远也看不见它。 */
    private static final double WATCH_RANGE = 128;

    private ExecutionShots() {}

    public static void register(IEventBus bus) {
        bus.addListener((RegisterPayloadHandlersEvent event) -> event.registrar("1").playToClient(ExecutionPayload.TYPE, ExecutionPayload.CODEC));
        NeoForge.EVENT_BUS.addListener(ExecutionShots::tick);
    }

    /**
     * 让 {@code viewer} 看 {@code target} 被处决的特写；附近别的玩家看到目标被抛起、挨打、拍下。
     *
     * @return 整段多少刻（含镜头回到玩家）
     */
    public static int play(ServerPlayer viewer, LivingEntity target, ExecutionShot shot) {
        var timeline = new ExecutionTimeline(shot);
        float yaw = target.getYRot();
        send(viewer, new ExecutionPayload(target.getId(), shot, yaw, true));
        if (target.level() instanceof ServerLevel level) {
            var watching = new ExecutionPayload(target.getId(), shot, yaw, false);
            for (ServerPlayer other : level.players()) {
                if (other != viewer && other.distanceToSqr(target) < WATCH_RANGE * WATCH_RANGE) send(other, watching);
            }
            if (!(target instanceof Player)) FROZEN.put(target, level.getGameTime() + timeline.duration());
        }
        return timeline.duration();
    }

    /** {@code entity} 现在是否被处决特写定住。 */
    public static boolean frozen(Entity entity) {
        Long until = FROZEN.get(entity);
        return until != null && entity.level().getGameTime() < until;
    }

    private static void send(ServerPlayer player, ExecutionPayload payload) {
        if (player.connection.hasChannel(ExecutionPayload.TYPE)) PacketDistributor.sendToPlayer(player, payload);
    }

    /** 被定住的生物这一刻整个跳过：不走 AI、不受重力、不被推开，客户端那边看到它停在原地。 */
    private static void tick(EntityTickEvent.Pre event) {
        Entity entity = event.getEntity();
        if (entity.level().isClientSide()) return;
        Long until = FROZEN.get(entity);
        if (until == null) return;
        if (entity.isRemoved() || entity.level().getGameTime() >= until) {
            FROZEN.remove(entity);
            return;
        }
        event.setCanceled(true);
    }
}
