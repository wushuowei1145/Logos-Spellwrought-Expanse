package dev.logos.dimension.explosion;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 放完爆裂魔法，魔力耗尽：施法者被强制趴下（原版的趴下姿势，和钻进一格高的缝里时一样）{@link #TICKS} 刻，
 * 致敬原作里惠惠放完就倒地不起。从爆炸的球体散尽开始算（松手后 {@link ExplosionTimeline#GONE_AT} 刻），不提醒。
 *
 * <p>服务端和施法者自己的客户端都要设姿势（NeoForge 的 {@code Player#setForcedPose}），别的客户端从实体数据里看到趴着；
 * 客户端还挡掉跳和疾跑。重新登录、换维度后服务端再设一次、再告诉客户端。死了、变成旁观者就起来了。
 * 只有用法杖放出来的才累趴（{@code /alchemy explosion} 和世界测试直接调 {@link ExplosionMagic#fire} 的不算）。
 */
public final class ExplosionExhaustion {
    /** 趴多久（刻）。 */
    public static final int TICKS = 600;

    /** 一个施法者什么时候趴下、什么时候起来（服务端的刻数）。 */
    private record Spell(long from, long until) {}

    private static final Map<UUID, Spell> SPELLS = new HashMap<>();

    private ExplosionExhaustion() {}

    static void register() {
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> tick(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> SPELLS.clear());
        NeoForge.EVENT_BUS.addListener((LivingDeathEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer p && SPELLS.remove(p.getUUID()) != null) stand(p);
        });
        // 换了维度：客户端换了一个新的玩家对象，再告诉它一次
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer p && p.getForcedPose() == Pose.SWIMMING && SPELLS.containsKey(p.getUUID())) {
                tell(p, (int) (SPELLS.get(p.getUUID()).until() - p.level().getServer().getTickCount()));
            }
        });
    }

    /** {@code delay} 刻后让 {@code p} 趴下 {@link #TICKS} 刻（再放一次就从头算）。 */
    static void schedule(ServerPlayer p, int delay) {
        long now = p.level().getServer().getTickCount();
        SPELLS.put(p.getUUID(), new Spell(now + delay, now + delay + TICKS));
    }

    /** 这个玩家现在是不是被爆裂魔法累趴着（客户端场景看）。 */
    public static boolean exhausted(ServerPlayer p) {
        return p.getForcedPose() == Pose.SWIMMING && SPELLS.containsKey(p.getUUID());
    }

    private static void tick(MinecraftServer server) {
        if (SPELLS.isEmpty()) return;
        long now = server.getTickCount();
        SPELLS.entrySet().removeIf(e -> {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            Spell s = e.getValue();
            if (now >= s.until()) {
                if (p != null) stand(p);
                return true;
            }
            if (p == null || now < s.from()) return false;
            if (p.isSpectator()) {
                stand(p);
                return true;
            }
            if (p.getForcedPose() != Pose.SWIMMING) {
                // 刚到时间，或者重新登录了：服务端设好，再告诉它自己的客户端
                p.setForcedPose(Pose.SWIMMING);
                p.setSprinting(false);
                tell(p, (int) (s.until() - now));
            }
            return false;
        });
    }

    private static void stand(ServerPlayer p) {
        if (p.getForcedPose() == Pose.SWIMMING) p.setForcedPose(null);
        tell(p, 0);
    }

    private static void tell(ServerPlayer p, int ticks) {
        if (p.connection.hasChannel(ExplosionPayload.TYPE)) PacketDistributor.sendToPlayer(p, ExplosionPayload.exhaust(p.getId(), Math.max(0, ticks)));
    }
}
