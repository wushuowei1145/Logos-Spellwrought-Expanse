package dev.logos.dimension.ultimate;

import dev.logos.dimension.companion.CompanionEntity;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jspecify.annotations.Nullable;

/**
 * 奥义打谁（规范 4.2、第六节）：准星指着的、主人刚打过的、她正在对付的、离主人最近的敌对生物。
 * 目标中途死了，就重锁离原来那一点最近的。
 */
public final class UltimateTargets {
    /** 准星往前找多远（格）。 */
    public static final double PICK = 48;
    /** 下令时准星对着的同伴多远以内算数；没有目标时找敌对生物、目标死了重锁都在这么远以内。 */
    public static final double NEAR = 24;
    /** 一个敌人都没有时，往准星前方多远的地面放。 */
    public static final double AIM = 14;
    /** 主人刚打过的多少刻以内还算「正在打」。 */
    static final int RECENT = 100;

    private UltimateTargets() {}

    /** 这一段打谁；一个都没有时是 null（脚本照样演，朝 {@link #aim} 放）。 */
    public static @Nullable LivingEntity pick(ServerPlayer owner, LivingEntity performer) {
        LivingEntity aimed = crosshair(owner, performer);
        if (aimed != null) return aimed;
        LivingEntity hit = owner.getLastHurtMob();
        if (hit != null && owner.tickCount - owner.getLastHurtMobTimestamp() < RECENT && hit.distanceTo(owner) < PICK && harmable(owner, performer, hit))
            return hit;
        if (performer instanceof Mob m && m.getTarget() != null && harmable(owner, performer, m.getTarget())) return m.getTarget();
        return near(owner, performer, owner.position(), NEAR);
    }

    /** 离 {@code around} 最近、{@code radius} 格以内她打得了的敌对生物。 */
    public static @Nullable LivingEntity near(ServerPlayer owner, LivingEntity performer, Vec3 around, double radius) {
        LivingEntity best = null;
        double bestD = radius * radius;
        for (LivingEntity e : owner.level().getEntitiesOfClass(LivingEntity.class, new AABB(around, around).inflate(radius),
                e -> harmable(owner, performer, e))) {
            double d = e.distanceToSqr(around);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    /** 准星指着、{@link #PICK} 格以内、没被方块挡住的、她打得了的那一个。 */
    static @Nullable LivingEntity crosshair(ServerPlayer owner, LivingEntity performer) {
        Entity e = along(owner, PICK, List.of(), c -> c instanceof LivingEntity l && harmable(owner, performer, l));
        return e instanceof LivingEntity l ? l : null;
    }

    /** 准星对着的同伴（{@link #NEAR} 格以内）。 */
    public static @Nullable CompanionEntity companionInSight(ServerPlayer owner, List<CompanionEntity> present) {
        Entity e = along(owner, NEAR, present, c -> true);
        return e instanceof CompanionEntity c ? c : null;
    }

    /** 准星往前 {@code range} 格、方块挡住之前碰到的第一个：{@code among} 不空时只在这些里找，空时在附近所有实体里找。 */
    private static @Nullable Entity along(ServerPlayer owner, double range, List<? extends Entity> among, java.util.function.Predicate<Entity> ok) {
        Vec3 eye = owner.getEyePosition();
        Vec3 end = eye.add(owner.getViewVector(1).scale(range));
        var block = owner.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (block.getType() != HitResult.Type.MISS) end = block.getLocation();
        List<? extends Entity> candidates = among.isEmpty()
                ? owner.level().getEntities(owner, new AABB(eye, end).inflate(1), e -> e.isAlive() && e.isPickable())
                : among;
        Entity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : candidates) {
            if (!ok.test(e)) continue;
            var hit = e.getBoundingBox().inflate(.3).clip(eye, end);
            if (hit.isEmpty()) continue;
            double d = hit.get().distanceToSqr(eye);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    /** 没有目标时往哪放：准星前方 {@link #AIM} 格（碰到方块就停在那），再落到地上。 */
    public static Vec3 aim(ServerPlayer owner) {
        Vec3 eye = owner.getEyePosition();
        Vec3 end = eye.add(owner.getViewVector(1).scale(AIM));
        var level = owner.level();
        var hit = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        Vec3 at = hit.getType() == HitResult.Type.MISS ? end : hit.getLocation();
        var down = level.clip(new ClipContext(at, at.add(0, -8, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        return down.getType() == HitResult.Type.MISS ? at : down.getLocation();
    }

    /**
     * 奥义打不打它：敌对生物、冲着主人或她来的、主人刚打过的；主人、自己人（同伴、宠物）、路过的动物村民都不打。
     * 同伴还要过她自己的 {@link CompanionEntity#canHarm}（攻击玩家开关、主人的宠物）。
     */
    public static boolean harmable(ServerPlayer owner, LivingEntity performer, LivingEntity e) {
        if (!e.isAlive() || e == owner || e == performer || e instanceof CompanionEntity) return false;
        if (performer instanceof CompanionEntity c && !c.canHarm(e)) return false;
        if (e instanceof Enemy) return true;
        if (e instanceof Mob m && (m.getTarget() == owner || m.getTarget() == performer)) return true;
        UUID hit = owner.getLastHurtMob() == null ? null : owner.getLastHurtMob().getUUID();
        return e.getUUID().equals(hit) && owner.tickCount - owner.getLastHurtMobTimestamp() < RECENT;
    }
}
