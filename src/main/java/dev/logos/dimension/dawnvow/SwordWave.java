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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
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
 *   <li>{@link Grand}：蓄满后的巨型金色剑气，每刻 {@link DawnvowCharge#GRAND_SPEED} 格沿地面往前（见 {@link #path}）：
 *       一格高的坎迈上去，两格以上的墙劈开；剑气所在高度往上 {@link #GRAND_HEIGHT} 格、左右各 {@link #GRAND_HALF_WIDTH} 格内
 *       硬度不超过 {@link #SOFT} 的方块被削掉（不掉落），脚下的地面不动。正前方是劈不开的方块（更硬、带方块实体、受保护）时就停在那里。
 *       生命值不超过 {@link #KILL_BELOW} 的生物直接击杀，不超过 {@link #STOP_ABOVE} 的受 {@link #PIERCE_DAMAGE} 点伤害、
 *       剑气穿过；碰到生命值更高的生物、劈不开的方块或走满 {@link #GRAND_RANGE} 格时十字爆闪，随后金色光柱冲起，
 *       半径 {@link #BURST_RADIUS} 格内的生物受 {@link #BURST_DAMAGE} 点伤害。</li>
 * </ul>
 * 都不伤挥剑的人、同乘的和自己的宠物。
 */
public final class SwordWave {
    public static final double SMALL_RANGE = 16, SMALL_DAMAGE = 15, SMALL_WIDTH = 2.4, SMALL_HEIGHT = 2, SMALL_SPEED = 1.2;
    public static final double GRAND_RANGE = 64, KILL_BELOW = 50, PIERCE_DAMAGE = 50, STOP_ABOVE = 100;
    public static final double BURST_RADIUS = 16, BURST_DAMAGE = 200;
    /** 巨型剑气打到东西的范围：左右各半宽、脚下往上的高度。 */
    public static final double GRAND_HALF_WIDTH = 3.5, GRAND_HEIGHT = 9;
    /** 削得掉的最大硬度（与超电磁炮相同）：泥土、石头 1.5、木头 2、深板岩和矿石 3；铁块 5、黑曜石 50 劈不开。 */
    public static final float SOFT = 3;
    /** 巨型剑气的路线每隔多少格取一个点；一格高的坎迈上去，下坡最多跟着落 {@link #GRAND_DROP} 格，再深就悬空平着走。 */
    public static final double PATH_STEP = 0.5, GRAND_DROP = 8;
    /** 每刻最多放多少个方块碎裂的粒子和声音。 */
    private static final int BREAK_EFFECTS = 24;
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

    /** 从脚前一格的地面放出蓄力斩的剑气，最远走 {@code range} 格；前面没有地面时不放。 */
    static void small(ServerLevel level, ServerPlayer p, double range) {
        Vec3 dir = flat(p.getLookAngle());
        Vec3 start = groundBelow(level, p, p.position().add(dir.scale(.8)).add(0, .6, 0));
        if (start == null) return;
        WAVES.add(new Small(level, p, start, dir, range));
        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1, .7f);
    }

    /**
     * 放出巨型剑气：出手时就把整条路线定下来（客户端按同一条路线画），停在劈不开的方块前、前方第一个生命值超过
     * {@link #STOP_ABOVE} 的生物处，或者走满射程。伤害与削方块随剑气头逐刻结算。返回路线（每 {@link #PATH_STEP} 格的地面高度）。
     */
    public static float[] grand(ServerLevel level, ServerPlayer p, Vec3 from, Vec3 dir) {
        List<Float> heights = path(level, p, from, dir);
        double length = (heights.size() - 1) * PATH_STEP;
        AABB box = new AABB(from, from.add(dir.scale(length))).inflate(GRAND_HALF_WIDTH + 1, GRAND_DROP + 1, GRAND_HALF_WIDTH + 1).expandTowards(0, GRAND_HEIGHT, 0);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, e -> mayHit(p, e))) {
            double s = along(from, dir, e);
            if (e.getHealth() <= STOP_ABOVE || !inGrand(from, dir, heights, e) || s >= length) continue;
            length = Math.max(PATH_STEP, s);
        }
        int points = (int) Math.round(length / PATH_STEP) + 1;
        float[] path = new float[Math.min(points, heights.size())];
        for (int i = 0; i < path.length; i++) path[i] = heights.get(i);
        WAVES.add(new Grand(level, p, from, dir, path));
        return path;
    }

    /**
     * 剑气沿地面走的路线：从 {@code from}（挥剑的人脚下的高度）往 {@code dir} 每 {@link #PATH_STEP} 格看一次脚下那一格：
     * 空的就往下找地面（最多 {@link #GRAND_DROP} 格，找不到就平着走）；是一格高的坎就迈上去；两格以上的墙要劈开，
     * 正中那一列从脚下往上 {@link #GRAND_HEIGHT} 格里有劈不开的方块就停在这里。
     */
    static List<Float> path(ServerLevel level, ServerPlayer p, Vec3 from, Vec3 dir) {
        List<Float> heights = new ArrayList<>();
        double y = from.y;
        heights.add((float) y);
        for (double s = PATH_STEP; s <= GRAND_RANGE + 1e-6; s += PATH_STEP) {
            Vec3 at = from.add(dir.scale(s));
            BlockPos feet = feet(at.x, y, at.z);
            if (!level.isLoaded(feet)) break;
            if (solid(level, feet)) {
                if (!solid(level, feet.above())) {
                    y = top(level, feet);
                } else if (!cleared(level, p, feet)) {
                    break;
                }
            } else {
                double ground = y;
                for (int k = 1; k <= GRAND_DROP; k++) {
                    BlockPos below = feet.below(k);
                    if (solid(level, below)) { ground = top(level, below); break; }
                }
                y = ground;
            }
            heights.add((float) y);
        }
        return heights;
    }

    /** 挡路的墙在正中那一列从脚下往上都劈得开（空气、液体、植物也算）。 */
    private static boolean cleared(ServerLevel level, ServerPlayer p, BlockPos feet) {
        for (int h = 0; h < GRAND_HEIGHT; h++) {
            BlockPos pos = feet.above(h);
            BlockState state = level.getBlockState(pos);
            if (solid(level, pos) && !breakable(level, p, pos, state)) return false;
        }
        return true;
    }

    /** 站在高度 {@code y} 上时脚所在的那一格：地面是半砖、雪层时往上取整，不会算到脚下的方块。 */
    private static BlockPos feet(double x, double y, double z) {
        return BlockPos.containing(x, Math.ceil(y - 1e-3), z);
    }

    /** 挡得住剑气、能站上去的方块（有碰撞箱；草、花、液体不算）。 */
    private static boolean solid(ServerLevel level, BlockPos pos) {
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /** 站在这一格上时脚下的高度。 */
    private static double top(ServerLevel level, BlockPos pos) {
        return pos.getY() + level.getBlockState(pos).getCollisionShape(level, pos).max(net.minecraft.core.Direction.Axis.Y);
    }

    /** 挥剑的人能改这里（出生点保护、领地、冒险模式……），方块够软，也不是箱子之类带方块实体的。 */
    static boolean breakable(ServerLevel level, ServerPlayer p, BlockPos pos, BlockState state) {
        float hardness = state.getDestroySpeed(level, pos);
        return hardness >= 0 && hardness <= SOFT && !state.hasBlockEntity()
                && level.mayInteract(p, pos) && !p.blockActionRestricted(level, pos, p.gameMode.getGameModeForPlayer());
    }

    /** 生物在剑气路线上离起点多远。 */
    private static double along(Vec3 from, Vec3 dir, LivingEntity e) {
        return e.position().subtract(from).dot(dir);
    }

    /** 路线 {@code s} 处剑气所在地面的高度。 */
    public static double heightAt(float[] path, double s) {
        double k = Math.clamp(s / PATH_STEP, 0, path.length - 1);
        int i = (int) Math.floor(k);
        if (i >= path.length - 1) return path[path.length - 1];
        return path[i] + (path[i + 1] - path[i]) * (k - i);
    }

    /** 生物在巨型剑气走过的那一条带子里：左右不超过半宽，高度在那一处地面往下一格到往上 {@link #GRAND_HEIGHT} 格之间。 */
    private static boolean inGrand(Vec3 from, Vec3 dir, List<Float> heights, LivingEntity e) {
        double s = along(from, dir, e);
        if (s < -1 || s > (heights.size() - 1) * PATH_STEP + 1) return false;
        float[] path = new float[heights.size()];
        for (int i = 0; i < path.length; i++) path[i] = heights.get(i);
        return inBand(from, dir, path, e, s);
    }

    private static boolean inBand(Vec3 from, Vec3 dir, float[] path, LivingEntity e, double s) {
        Vec3 side = new Vec3(-dir.z, 0, dir.x);
        double across = Math.abs(e.position().subtract(from).dot(side)) - e.getBbWidth() / 2;
        double ground = heightAt(path, s), up = e.getY() - ground;
        return across <= GRAND_HALF_WIDTH && up <= GRAND_HEIGHT && up + e.getBbHeight() >= -1;
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
        final double range;
        Vec3 pos;
        double travelled;

        Small(ServerLevel level, ServerPlayer player, Vec3 start, Vec3 dir, double range) {
            this.level = level; this.player = player; this.pos = start; this.dir = dir; this.range = range;
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
                if (travelled >= range) return stop(false);
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

    /** 巨型剑气：沿定好的路线推进，削掉经过处的软方块，按顺序结算碰到的生物，走到头十字爆闪，再过几刻光柱冲起。 */
    private static final class Grand implements Wave {
        final ServerLevel level;
        final ServerPlayer player;
        final Vec3 from, dir, side;
        final float[] path;
        final double length;
        final Set<UUID> struck = new HashSet<>();
        final Set<BlockPos> cut = new HashSet<>();
        final AABB shooter;
        double head;
        int after = -1;
        Vec3 burst;

        Grand(ServerLevel level, ServerPlayer player, Vec3 from, Vec3 dir, float[] path) {
            this.level = level; this.player = player; this.from = from; this.dir = dir; this.path = path;
            this.side = new Vec3(-dir.z, 0, dir.x);
            this.length = (path.length - 1) * PATH_STEP;
            this.shooter = player.getBoundingBox().inflate(.6);
        }

        @Override public ServerPlayer owner() { return player; }

        @Override public boolean step() {
            if (after >= 0) {
                if (++after < PILLAR_DELAY) return false;
                pillar();
                return true;
            }
            double next = Math.min(length, head + DawnvowCharge.GRAND_SPEED);
            carve(head, next);
            sweep(head, next);
            head = next;
            if (head >= length) {
                burst = point(length);
                lastBurst = burst;
                level.playSound(null, burst.x, burst.y, burst.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 4, .7f);
                after = 0;
            }
            return false;
        }

        /** 路线上离起点 {@code s} 处的地面点。 */
        private Vec3 point(double s) {
            Vec3 at = from.add(dir.scale(s));
            return new Vec3(at.x, heightAt(path, s), at.z);
        }

        /** 削掉剑气头从 {@code a} 走到 {@code b} 经过的软方块：脚下那一格往上 {@link #GRAND_HEIGHT} 格、左右各半宽，不碰脚下的地面。 */
        private void carve(double a, double b) {
            int effects = 0;
            for (double s = a; s <= b; s += PATH_STEP / 2) {
                Vec3 ground = point(s);
                for (double across = -GRAND_HALF_WIDTH; across <= GRAND_HALF_WIDTH; across += .5) {
                    Vec3 column = ground.add(side.scale(across));
                    BlockPos base = feet(column.x, ground.y, column.z);
                    for (int h = 0; h < GRAND_HEIGHT; h++) {
                        BlockPos pos = base.above(h);
                        if (!cut.add(pos) || !level.isLoaded(pos) || shooter.contains(Vec3.atCenterOf(pos))) continue;
                        BlockState state = level.getBlockState(pos);
                        if (state.isAir() || !state.getFluidState().isEmpty() && state.getBlock() instanceof LiquidBlock) continue;
                        if (!breakable(level, player, pos, state)) continue;
                        level.setBlock(pos, state.getFluidState().createLegacyBlock(), Block.UPDATE_ALL);
                        if (effects < BREAK_EFFECTS && (pos.hashCode() & 7) == 0) {
                            level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, pos, Block.getId(state));
                            effects++;
                        }
                    }
                }
            }
        }

        /** 剑气头从 {@code a} 走到 {@code b}（离起点的距离）这一刻碰到的生物，近的先算。 */
        private void sweep(double a, double b) {
            AABB box = new AABB(point(a), point(b)).inflate(GRAND_HALF_WIDTH + 1, 1, GRAND_HALF_WIDTH + 1).expandTowards(0, GRAND_HEIGHT, 0);
            List<LivingEntity> hit = level.getEntitiesOfClass(LivingEntity.class, box, e -> {
                double s = along(from, dir, e);
                return mayHit(player, e) && !struck.contains(e.getUUID()) && s <= b + e.getBbWidth() / 2 && inBand(from, dir, path, e, s);
            });
            hit.sort(Comparator.comparingDouble(e -> along(from, dir, e)));
            for (LivingEntity e : hit) {
                struck.add(e.getUUID());
                // 生命值更高的留给爆闪后的光柱
                if (e.getHealth() > STOP_ABOVE) continue;
                Effects.followUp(player, e, e.getHealth() <= KILL_BELOW ? KILL_DAMAGE : PIERCE_DAMAGE, Element.PHYSICAL);
            }
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

    /** 最近一次十字爆闪的位置（世界测试用）。 */
    private static @Nullable Vec3 lastBurst;

    public static @Nullable Vec3 lastBurst() { return lastBurst; }

    static void tick(ServerTickEvent.Post event) {
        WAVES.removeIf(w -> w.owner().isRemoved() || w.step());
    }

    static void stopped(ServerStoppedEvent event) { WAVES.clear(); }

    /** 进行中的剑气数（世界测试用）。 */
    public static int live() { return WAVES.size(); }
}
