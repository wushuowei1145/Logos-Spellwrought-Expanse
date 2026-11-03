package dev.logos.dimension.dawnvow;

import dev.logos.lang.Element;
import dev.logos.runtime.Effects;
import dev.logos.runtime.PillarBurst;
import dev.logos.runtime.Vfx;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jspecify.annotations.Nullable;

/**
 * 曦誓劈出的剑气，服务端逐刻推进与结算。
 * <ul>
 *   <li>{@link Small}：蓄力斩带出的一道剑气。借 Logos 物理游脉的画面（{@link Vfx#vein}，放大到宽 {@link #SMALL_WIDTH}、
 *       高 {@link #SMALL_HEIGHT}），贴着地面往前走：上得了一格台阶、跟着地面下到四格，撞墙或走到 {@link #SMALL_RANGE} 格为止；
 *       沿途碰到的生物各受 {@link #SMALL_DAMAGE} 点物理伤害，不挡剑气。</li>
 *   <li>{@link Grand}：蓄满后的巨型金色剑气，不理会地形，水平直线每刻 {@link DawnvowCharge#GRAND_SPEED} 格。
 *       生命值不超过 {@link #KILL_BELOW} 的生物直接击杀，不超过 {@link #STOP_ABOVE} 的受 {@link #PIERCE_DAMAGE} 点伤害、
 *       剑气穿过；碰到生命值更高的生物或走满 {@link #GRAND_RANGE} 格时十字爆闪，随后金色光柱冲起，
 *       半径 {@link #BURST_RADIUS} 格内的生物受 {@link #BURST_DAMAGE} 点伤害。</li>
 * </ul>
 * 都不伤挥剑的人、同乘的和自己的宠物；都不破坏方块。
 */
public final class SwordWave {
    public static final double SMALL_RANGE = 16, SMALL_DAMAGE = 15, SMALL_WIDTH = 2.4, SMALL_HEIGHT = 2, SMALL_SPEED = 1.2;
    public static final double GRAND_RANGE = 64, KILL_BELOW = 50, PIERCE_DAMAGE = 50, STOP_ABOVE = 100;
    public static final double BURST_RADIUS = 16, BURST_DAMAGE = 200;
    /** 巨型剑气打到东西的范围：左右各半宽、脚下往上的高度。 */
    public static final double GRAND_HALF_WIDTH = 3.5, GRAND_HEIGHT = 9;
    /** 十字爆闪之后几刻光柱冲起、结算范围伤害。 */
    public static final int PILLAR_DELAY = 4;
    /** 光柱缩放：缩放 1 时柱半径 1.6 格，这里撑到 {@link #BURST_RADIUS}。 */
    public static final float PILLAR_SCALE = (float) (BURST_RADIUS / 1.6);
    /** 直接击杀用的伤害：护甲与保护附魔最多减到两成的两成，仍远超击杀线。 */
    private static final double KILL_DAMAGE = 10000;
    /** 游脉的台阶、跟随地面下降的深度、离地、撞墙前留的空隙（与 Logos 的游脉一致）。 */
    private static final double STEP = 1, DROP = 4, LIFT = 0.05, SKIN = 0.05, STRIDE = 0.5;
    /**
     * 游脉特效的 id。Logos 法术物体的 id 从 1 往上数，这里从负数往下数，两边永远不会撞上。
     * 主模组还没有给附属分配持续型特效 id 的公开方法，见 docs/VFX.md。
     */
    private static int nextVeinId = -1_000_000;

    private static final List<Wave> WAVES = new ArrayList<>();

    private SwordWave() {}

    private interface Wave {
        /** 推进一刻；结束返回 true。 */
        boolean step();
        ServerPlayer owner();
    }

    /** 挥剑的人看得见的、能被剑气伤到的生物。 */
    static boolean mayHit(ServerPlayer p, LivingEntity e) {
        if (e == p || !e.isAlive() || e.isSpectator() || e.isPassengerOfSameVehicle(p)) return false;
        return !(e instanceof TamableAnimal pet && pet.isOwnedBy(p));
    }

    /** 从脚前一格的地面放出蓄力斩的剑气；前面没有地面时不放。 */
    static void small(ServerLevel level, ServerPlayer p) {
        Vec3 dir = flat(p.getLookAngle());
        Vec3 start = groundBelow(level, p, p.position().add(dir.scale(.8)).add(0, .6, 0));
        if (start == null) return;
        WAVES.add(new Small(level, p, start, dir));
        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1, .7f);
    }

    /**
     * 放出巨型剑气。停在哪里在出手时就定下来（客户端按同一个长度画）：前方第一个生命值超过 {@link #STOP_ABOVE}
     * 的生物处，没有就走满射程。伤害随剑气头逐刻结算。返回剑气的长度。
     */
    static double grand(ServerLevel level, ServerPlayer p, Vec3 from, Vec3 dir) {
        double length = GRAND_RANGE;
        AABB path = new AABB(from, from.add(dir.scale(GRAND_RANGE))).inflate(GRAND_HALF_WIDTH, 0, GRAND_HALF_WIDTH).expandTowards(0, GRAND_HEIGHT, 0);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, path.inflate(0, 1, 0), e -> mayHit(p, e))) {
            if (e.getHealth() <= STOP_ABOVE || !inGrand(from, dir, e)) continue;
            length = Math.min(length, Math.max(1, e.position().subtract(from).dot(dir)));
        }
        WAVES.add(new Grand(level, p, from, dir, length));
        return length;
    }

    /** 生物在巨型剑气走过的那一条带子里（不论走到没有）。 */
    private static boolean inGrand(Vec3 from, Vec3 dir, LivingEntity e) {
        Vec3 side = new Vec3(-dir.z, 0, dir.x);
        Vec3 d = e.position().subtract(from);
        double across = Math.abs(d.dot(side)) - e.getBbWidth() / 2, up = d.y;
        return d.dot(dir) >= -1 && across <= GRAND_HALF_WIDTH && up <= GRAND_HEIGHT && up + e.getBbHeight() >= -1;
    }

    static Vec3 flat(Vec3 dir) {
        Vec3 f = new Vec3(dir.x, 0, dir.z);
        return f.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : f.normalize();
    }

    /** {@code at} 以下 {@link #STEP} + {@link #DROP} 格内第一个表面（液面也算），或 null。 */
    static @Nullable Vec3 groundBelow(ServerLevel level, ServerPlayer p, Vec3 at) {
        BlockHitResult hit = level.clip(new ClipContext(at, at.add(0, -(STEP + DROP + 1), 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, p));
        return hit.getType() == HitResult.Type.MISS || hit.isInside() ? null : hit.getLocation();
    }

    /** 蓄力斩的剑气：一头物理游脉，贴着地面走。 */
    private static final class Small implements Wave {
        final ServerLevel level;
        final ServerPlayer player;
        final int id = nextVeinId--;
        final Vec3 dir;
        final Set<UUID> struck = new HashSet<>();
        Vec3 pos;
        double travelled;

        Small(ServerLevel level, ServerPlayer player, Vec3 start, Vec3 dir) {
            this.level = level; this.player = player; this.pos = start; this.dir = dir;
            show();
        }

        @Override public ServerPlayer owner() { return player; }

        private void show() {
            Vfx.vein(level, id, 0, pos, dir, Direction.UP, Element.PHYSICAL, SMALL_WIDTH, SMALL_HEIGHT, 0);
        }

        private boolean stop(boolean wall) {
            Vfx.veinEnd(level, id, 0, pos, Element.PHYSICAL, wall);
            return true;
        }

        @Override public boolean step() {
            for (double left = SMALL_SPEED; left > 1e-9; ) {
                double ds = Math.min(STRIDE, left);
                left -= ds;
                // 先升到能迈上的高度（或刚好顶到天花板），往前，再落回地面
                BlockHitResult ceiling = level.clip(new ClipContext(pos.add(0, LIFT, 0), pos.add(0, STEP + LIFT, 0),
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
                Vec3 up = ceiling.getType() == HitResult.Type.MISS ? pos.add(0, STEP + LIFT, 0) : ceiling.getLocation().add(0, -LIFT, 0);
                Vec3 ahead = up.add(dir.scale(ds));
                BlockHitResult wall = level.clip(new ClipContext(up, ahead.add(dir.scale(SKIN)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
                boolean blocked = wall.getType() != HitResult.Type.MISS;
                Vec3 reach = blocked ? wall.getLocation().subtract(dir.scale(SKIN)) : ahead;
                BlockHitResult ground = level.clip(new ClipContext(reach, reach.add(0, -(STEP + DROP + LIFT), 0),
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, player));
                if (ground.isInside()) return stop(true);
                if (ground.getType() == HitResult.Type.MISS) return stop(false);
                Vec3 next = ground.getLocation();
                strike(pos, next);
                pos = next;
                travelled += ds;
                if (blocked) return stop(true);
                if (travelled >= SMALL_RANGE) return stop(false);
            }
            show();
            return false;
        }

        private void strike(Vec3 a, Vec3 b) {
            AABB box = new AABB(a, b).inflate(SMALL_WIDTH / 2, 0, SMALL_WIDTH / 2).expandTowards(0, SMALL_HEIGHT, 0);
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, e -> mayHit(player, e) && !struck.contains(e.getUUID()))) {
                struck.add(e.getUUID());
                Effects.followUp(player, e, SMALL_DAMAGE, Element.PHYSICAL);
            }
        }
    }

    /** 巨型剑气：沿水平直线推进，按顺序结算碰到的生物，走到头十字爆闪，再过几刻光柱冲起。 */
    private static final class Grand implements Wave {
        final ServerLevel level;
        final ServerPlayer player;
        final Vec3 from, dir;
        final double length;
        final Set<UUID> struck = new HashSet<>();
        double head;
        int after = -1;
        Vec3 burst;

        Grand(ServerLevel level, ServerPlayer player, Vec3 from, Vec3 dir, double length) {
            this.level = level; this.player = player; this.from = from; this.dir = dir; this.length = length;
        }

        @Override public ServerPlayer owner() { return player; }

        @Override public boolean step() {
            if (after >= 0) {
                if (++after < PILLAR_DELAY) return false;
                pillar();
                return true;
            }
            double next = Math.min(length, head + DawnvowCharge.GRAND_SPEED);
            sweep(head, next);
            head = next;
            if (head >= length) {
                burst = ground(from.add(dir.scale(length)));
                level.playSound(null, burst.x, burst.y, burst.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 4, .7f);
                after = 0;
            }
            return false;
        }

        /** 剑气头从 {@code a} 走到 {@code b}（离起点的距离）这一刻碰到的生物，近的先算。 */
        private void sweep(double a, double b) {
            Vec3 side = new Vec3(-dir.z, 0, dir.x);
            AABB box = new AABB(from.add(dir.scale(a)), from.add(dir.scale(b))).inflate(GRAND_HALF_WIDTH, 0, GRAND_HALF_WIDTH)
                    .expandTowards(0, GRAND_HEIGHT, 0).inflate(0, 1, 0);
            List<LivingEntity> hit = level.getEntitiesOfClass(LivingEntity.class, box, e -> mayHit(player, e) && !struck.contains(e.getUUID())
                    && inGrand(from, dir, e) && e.position().subtract(from).dot(dir) <= b + e.getBbWidth() / 2);
            hit.sort(Comparator.comparingDouble(e -> e.position().subtract(from).dot(dir)));
            for (LivingEntity e : hit) {
                struck.add(e.getUUID());
                // 生命值更高的留给爆闪后的光柱
                if (e.getHealth() > STOP_ABOVE) continue;
                Effects.followUp(player, e, e.getHealth() <= KILL_BELOW ? KILL_DAMAGE : PIERCE_DAMAGE, Element.PHYSICAL);
            }
        }

        /** 爆点落在剑气尽头的地面上（下方八格内没有地面就留在原处）。 */
        private Vec3 ground(Vec3 at) {
            BlockHitResult hit = level.clip(new ClipContext(at.add(0, 2, 0), at.add(0, -8, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, player));
            return hit.getType() == HitResult.Type.MISS || hit.isInside() ? at : hit.getLocation();
        }

        private void pillar() {
            PillarBurst.play(level, burst, PILLAR_SCALE);
            level.playSound(null, burst.x, burst.y, burst.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 5, .8f);
            AABB box = new AABB(burst, burst).inflate(BURST_RADIUS);
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, e -> mayHit(player, e))) {
                if (e.getBoundingBox().getCenter().distanceToSqr(burst) > BURST_RADIUS * BURST_RADIUS) continue;
                Effects.followUp(player, e, BURST_DAMAGE, Element.PHYSICAL);
            }
        }
    }

    static void tick(ServerTickEvent.Post event) {
        WAVES.removeIf(w -> w.owner().isRemoved() || w.step());
    }

    static void stopped(ServerStoppedEvent event) { WAVES.clear(); }

    /** 进行中的剑气数（世界测试用）。 */
    public static int live() { return WAVES.size(); }
}
