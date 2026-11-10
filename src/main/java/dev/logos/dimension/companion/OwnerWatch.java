package dev.logos.dimension.companion;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 玩家模型：白梅对主人「这一阵在做什么、处境如何」的了解（docs/SHIRAUME_COMPANION.md 第 3 节）。
 * 挖、放、挨打、出手、击杀由事件喂进来（{@link Companions}），移动和站着不动由她的心智每次思考时取样。
 *
 * <p>只在服务端，主人下线时丢掉。判断（{@link #activity}）是纯函数，便于测试。
 */
public final class OwnerWatch {
    private static final Map<UUID, OwnerWatch> ALL = new ConcurrentHashMap<>();

    /** 主人在做什么。 */
    public enum Activity { SLEEPING, FIGHTING, MINING, BUILDING, TRAVELING, IDLE, AFK }

    /** 这么多刻内的事算「这一阵」。 */
    static final int RECENT = 100, FIGHT = 80;
    /** 站着不动这么久算发呆、挂机。 */
    public static final int IDLE_TICKS = 200, AFK_TICKS = 1800;

    long lastMined = -10_000, lastBuilt = -10_000, lastHurt = -10_000, lastAttack = -10_000, lastFall = -10_000;
    long lastStrongKill = -10_000, lastKill = -10_000;
    /** 主人最近一次死在哪里、什么时候：那附近的掉落是主人的遗物，她不捡。 */
    long lastDeath = -100_000;
    @Nullable Vec3 deathAt;
    float lastHurtAmount;
    int mined, built;
    @Nullable UUID attacker, attacked;
    long attackerAt = -10_000, attackedAt = -10_000;
    @Nullable Vec3 lastPos;
    long stillSince;
    double speed;
    /** 最近一次被她看到「走远了」的时刻，回来时挥手用。 */
    long farSince = -1;

    public static OwnerWatch of(ServerPlayer p) {
        return ALL.computeIfAbsent(p.getUUID(), id -> new OwnerWatch());
    }

    static void forget(UUID id) {
        ALL.remove(id);
    }

    /** 测试用：当作主人已经站着不动 {@code ticks} 刻（挂机）。 */
    public static void pretendStill(ServerPlayer p, long ticks) {
        OwnerWatch w = of(p);
        w.lastPos = p.position();
        w.stillSince = p.level().getGameTime() - ticks;
        w.speed = 0;
    }

    void mined(long now) {
        mined = now - lastMined > RECENT ? 1 : mined + 1;
        lastMined = now;
    }

    void built(long now) {
        built = now - lastBuilt > RECENT ? 1 : built + 1;
        lastBuilt = now;
    }

    void hurt(long now, float amount, @Nullable LivingEntity by, boolean fall) {
        lastHurt = now;
        lastHurtAmount = amount;
        if (fall) lastFall = now;
        if (by != null) {
            attacker = by.getUUID();
            attackerAt = now;
        }
    }

    void attacked(long now, LivingEntity target) {
        lastAttack = now;
        attacked = target.getUUID();
        attackedAt = now;
    }

    void died(long now, Vec3 at) {
        lastDeath = now;
        deathAt = at;
    }

    void killed(long now, LivingEntity dead) {
        lastKill = now;
        if (dead.getMaxHealth() >= ShiraumeCompanion.STRONG) lastStrongKill = now;
    }

    /** 心智每次思考时取一次样：主人挪了多少、站着不动了多久。 */
    void sample(long now, Vec3 pos, int interval) {
        if (lastPos == null) {
            lastPos = pos;
            stillSince = now;
            return;
        }
        double moved = pos.distanceTo(lastPos);
        speed = moved / Math.max(1, interval);
        if (moved > .15) stillSince = now;
        lastPos = pos;
    }

    long still(long now) {
        return now - stillSince;
    }

    boolean fighting(long now) {
        return now - lastHurt < FIGHT && attacker != null || now - lastAttack < FIGHT;
    }

    Activity activity(long now, boolean sleeping) {
        return activity(now, sleeping, lastMined, mined, lastBuilt, built, fighting(now), speed, still(now));
    }

    /** 判断主人在做什么：睡觉 > 战斗 > 挖 > 建 > 赶路 > 发呆 / 挂机。 */
    static Activity activity(long now, boolean sleeping, long lastMined, int mined, long lastBuilt, int built, boolean fighting,
                             double speed, long still) {
        if (sleeping) return Activity.SLEEPING;
        if (fighting) return Activity.FIGHTING;
        if (now - lastMined < RECENT && mined >= 3) return Activity.MINING;
        if (now - lastBuilt < RECENT && built >= 3) return Activity.BUILDING;
        if (speed > .12) return Activity.TRAVELING;
        if (still >= AFK_TICKS) return Activity.AFK;
        return Activity.IDLE;
    }
}
