package dev.logos.dimension.combat;

import dev.logos.dimension.LogosDimension;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;
import org.jspecify.annotations.Nullable;

/**
 * 玩家和同伴共用的命中结算（docs/WEAPON_AI.md 第 2.2 节）：锥形里打到谁（{@link #cone}）、这一下的招式倍数与击退
 * （{@link #begin} / {@link #end} 之间的受伤、击退事件）、连招自己留下的受击冷却（{@link Combo}）。
 */
@EventBusSubscriber(modid = LogosDimension.ID)
public final class CombatHits {
    /** 一刀最多打几个目标。 */
    public static final int MAX_TARGETS = 8;
    /** 正在结算的招式与目标：招式的 damage、knockback 与附加效果的伤害倍数由伤害、击退事件读。 */
    private static final ThreadLocal<Moveset.Move> MOVE = new ThreadLocal<>();
    private static final ThreadLocal<Entity> TARGET = new ThreadLocal<>();

    private CombatHits() {}

    /**
     * 锥形里看得见的生物，近的在前；{@code aimed}（对着的那个）不为 null 时排第一个、不再重复算。锥形以 {@code from} 为顶点、朝
     * {@code look}（单位向量），生物身上离视线最近的那一点要在 {@code reach} 内、与视线的夹角不超过张角的一半。
     */
    public static List<Entity> cone(Level level, Entity self, Vec3 from, Vec3 look, double reach, double degrees, @Nullable Entity aimed,
                                    Predicate<Entity> may) {
        List<Entity> out = new ArrayList<>();
        if (aimed != null) out.add(aimed);
        double cos = Math.cos(Math.toRadians(degrees / 2));
        List<Entity> cone = level.getEntities(self, self.getBoundingBox().inflate(reach + 1), e -> {
            if (e == aimed || !(e instanceof LivingEntity living) || !living.isAlive() || e.isSpectator() || !e.isPickable()) return false;
            if (!may.test(e)) return false;
            var near = nearest(e.getBoundingBox(), from, look, reach);
            var d = near.subtract(from);
            double dist = d.length();
            if (dist > reach) return false;
            if (dist > 1e-4 && d.dot(look) / dist < cos) return false;
            return self instanceof LivingEntity l && l.hasLineOfSight(e)
                    || level.clip(new ClipContext(from, near, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, self)).getType() == HitResult.Type.MISS;
        });
        cone.sort(Comparator.comparingDouble(e -> e.getBoundingBox().distanceToSqr(from)));
        for (var e : cone) if (out.size() < MAX_TARGETS) out.add(e);
        return out;
    }

    /** 盒子里离视线最近的一点：先取视线上离盒心最近的点（不超过攻击距离），再夹进盒子。 */
    public static Vec3 nearest(AABB box, Vec3 from, Vec3 look, double reach) {
        double t = Math.clamp(box.getCenter().subtract(from).dot(look), 0, reach);
        var p = from.add(look.scale(t));
        return new Vec3(Math.clamp(p.x, box.minX, box.maxX), Math.clamp(p.y, box.minY, box.maxY), Math.clamp(p.z, box.minZ, box.maxZ));
    }

    /** 开始结算招式 {@code move} 对 {@code target} 的这一下（之后必须 {@link #end}）。 */
    public static void begin(Moveset.Move move, Entity target) {
        MOVE.set(move);
        TARGET.set(target);
    }

    public static void end() {
        MOVE.remove();
        TARGET.remove();
    }

    /** 招式的伤害倍数（damage）与附加效果的倍数（例如连刺打浮空目标翻倍），只作用于这一刀的目标。 */
    @SubscribeEvent
    static void damage(LivingIncomingDamageEvent event) {
        var move = MOVE.get();
        if (move == null || event.getEntity() != TARGET.get()) return;
        float scale = (float) move.damage() * CombatEffects.multiplier(move, event.getEntity());
        if (scale != 1) event.setAmount(event.getAmount() * scale);
    }

    /** 招式不击退（knockback 为假）或目标浮空时，这一刀的击退取消：连刺不把目标推出枪尖，浮空目标停在原处。 */
    @SubscribeEvent
    static void knockback(LivingKnockBackEvent event) {
        var move = MOVE.get();
        if (move != null && event.getEntity() == TARGET.get() && (!move.knockback() || Airborne.is(event.getEntity()))) event.setCanceled(true);
    }

    /**
     * 一套连招最近打中各目标的记录：目标自己记下的「最后被生物打中」的时刻（它的 tickCount）与当时的游戏刻。
     * 下一刀前目标的最后一下若仍是这一刀留下的，受击冷却就是连招自己造成的，可以放过（取消点让连段间隔短于原版 10 刻受击冷却）。
     * 之后若被别人打过就照原版规则处理。不按游戏刻推算剩余冷却：目标所在区块没在 tick 时冷却不会递减，推算会误判成被别人刷新过。
     */
    public static final class Combo {
        private final Map<UUID, long[]> lastHits = new HashMap<>();

        /** 新的一刀开始：忘掉一秒以前的。 */
        public void forget(long now) {
            lastHits.values().removeIf(hit -> now - hit[1] > 20);
        }

        /** 打 {@code target} 之前：受击冷却是这套连招上一下自己留下的就清掉。 */
        public void clearOwn(Entity target, LivingEntity attacker) {
            long[] last = lastHits.get(target.getUUID());
            if (last != null && target instanceof LivingEntity living && living.invulnerableTime > 0
                    && living.getLastHurtByMob() == attacker && living.getLastHurtByMobTimestamp() == last[0]) living.invulnerableTime = 0;
        }

        /** 打完 {@code target}：这一下打进去了就记下。 */
        public void record(Entity target, LivingEntity attacker, long now) {
            if (target instanceof LivingEntity living && living.invulnerableTime >= 20 && living.getLastHurtByMob() == attacker)
                lastHits.put(target.getUUID(), new long[]{living.getLastHurtByMobTimestamp(), now});
        }
    }
}
