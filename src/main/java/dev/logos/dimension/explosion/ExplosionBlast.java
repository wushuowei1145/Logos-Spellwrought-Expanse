package dev.logos.dimension.explosion;

import static dev.logos.dimension.explosion.ExplosionTimeline.FULL_AT;
import static dev.logos.dimension.explosion.ExplosionTimeline.PILLAR_RADIUS;
import static dev.logos.dimension.explosion.ExplosionTimeline.PILLAR_TICKS;
import static dev.logos.dimension.explosion.ExplosionTimeline.SPHERE_AT;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 服务端的一次爆裂魔法：按 {@link ExplosionTimeline} 杀伤、掘坑、推开外圈的东西。
 * <ul>
 *   <li>火柱落地时，火柱里的东西直接没了；</li>
 *   <li>球体扩大时，球面扫过的一切都被强制击杀：玩家（创造模式也算，旁观者不算）、生物、掉落物、矿车、画……
 *       伤害类型无视无敌、护甲、抗性和图腾；掉落物、经验球只清掉爆炸前就在的，死者掉出来的东西留着；</li>
 *   <li>球体到达最大时，半径一到两倍之间的东西被气浪向外掀飞；</li>
 *   <li>开了「破坏地形」时由 {@link Crater} 跟着球面挖坑。</li>
 * </ul>
 */
final class ExplosionBlast {
    /** 气浪能掀到多远（爆炸半径的倍数）、最猛时多快（格每刻）。 */
    private static final double PUSH_REACH = 2.2, PUSH_SPEED = 2.6;
    /** 坑没挖完时最多再跟多久。 */
    private static final int LINGER = 260;

    final ServerLevel level;
    final Vec3 center;
    final double radius;
    final @Nullable UUID caster;
    final long seed;
    final @Nullable Crater crater;
    private final Set<UUID> struck = new HashSet<>();
    /** 爆炸开始时就在范围里的掉落物和经验球：只清它们，死者之后掉出来的留着。 */
    private final Set<UUID> loose = new HashSet<>();
    int age;

    ExplosionBlast(ServerLevel level, Vec3 center, double radius, @Nullable UUID caster, long seed, boolean terrain) {
        this.level = level;
        this.center = center;
        this.radius = radius;
        this.caster = caster;
        this.seed = seed;
        this.crater = terrain ? new Crater(level, center, radius, caster, seed) : null;
        double r = radius + 2;
        for (Entity e : level.getEntities((Entity) null, new AABB(center.subtract(r, r, r), center.add(r, r, r)),
                e -> e instanceof ItemEntity || e instanceof ExperienceOrb)) loose.add(e.getUUID());
    }

    /** 每刻一次；返回 false 时这次爆炸结束。 */
    boolean tick() {
        age++;
        float scale = ExplosionTimeline.scale((float) radius);
        if (age >= PILLAR_TICKS && age < SPHERE_AT) pillar(PILLAR_RADIUS * scale);
        if (age >= SPHERE_AT) {
            double reach = radius * ExplosionTimeline.front(age) + 1.5;
            if (age <= FULL_AT + 1) sweep(reach);
            if (crater != null) crater.advance(reach, age > FULL_AT);
            if (age == FULL_AT) push();
        }
        return age <= FULL_AT || crater != null && !crater.done() && age < FULL_AT + LINGER;
    }

    /** 火柱里（目的地上方一根竖直的柱子）的东西。 */
    private void pillar(double r) {
        double top = center.y + ExplosionTimeline.CIRCLE_HEIGHT[ExplosionTimeline.CIRCLE_HEIGHT.length - 1] * ExplosionTimeline.scale((float) radius);
        AABB box = new AABB(center.x - r, center.y - r, center.z - r, center.x + r, top, center.z + r);
        for (Entity e : level.getEntities((Entity) null, box, e -> {
            double dx = e.getX() - center.x, dz = e.getZ() - center.z;
            return dx * dx + dz * dz <= r * r;
        })) obliterate(e);
    }

    /** 球面扫到 {@code reach} 格之内的一切。 */
    private void sweep(double reach) {
        AABB box = new AABB(center.subtract(reach, reach, reach), center.add(reach, reach, reach));
        for (Entity e : level.getEntities((Entity) null, box, e -> e.position().distanceToSqr(center) <= reach * reach)) obliterate(e);
    }

    private void obliterate(Entity e) {
        if (!e.isAlive() || e instanceof ServerPlayer p && p.isSpectator()) return;
        // 爆炸之后才出现的掉落物和经验球是死者掉出来的，留着
        if ((e instanceof ItemEntity || e instanceof ExperienceOrb) && !loose.contains(e.getUUID())) return;
        if (e instanceof LivingEntity living) {
            if (!struck.add(e.getUUID())) {
                if (living.isAlive()) living.kill(level);
                return;
            }
            living.hurtServer(level, damage(), Float.MAX_VALUE);
            // 被别的模组拦下来了：再用原版的击杀补一刀
            if (living.isAlive()) living.kill(level);
            return;
        }
        if (e.getPassengers().stream().anyMatch(p -> p instanceof ServerPlayer sp && !sp.isSpectator())) e.ejectPassengers();
        e.discard();
    }

    private DamageSource damage() {
        var type = level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(ExplosionMagic.DAMAGE);
        Entity attacker = caster == null ? null : level.getServer().getPlayerList().getPlayer(caster);
        return attacker != null ? new DamageSource(type, attacker) : new DamageSource(type, center);
    }

    /** 球体到达最大：半径一到 {@link #PUSH_REACH} 倍之间的东西被向外掀飞，越近越猛。 */
    private void push() {
        double reach = radius * PUSH_REACH;
        AABB box = new AABB(center.subtract(reach, reach, reach), center.add(reach, reach, reach));
        for (Entity e : level.getEntities((Entity) null, box, e -> e.isAlive() && !e.isSpectator())) {
            Vec3 away = e.position().subtract(center);
            double d = away.length();
            if (d < radius || d > reach || e.isPassenger()) continue;
            double k = 1 - (d - radius) / (reach - radius);
            Vec3 dir = new Vec3(away.x, 0, away.z).normalize();
            e.push(dir.x * PUSH_SPEED * k, .45 + .7 * k, dir.z * PUSH_SPEED * k);
            e.hurtMarked = true;
        }
    }
}
