package dev.logos.dimension.companion;

import dev.logos.dimension.LogosDimension;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 真龙的宝藏窝（接入方案第七节第 2 条）：你在家闲着时，她认下你身边的一块宝藏方块——钻石块、金块、下界合金块（方块标签
 * {@code logos_dimension:lacerta_hoard}），坐到它上面守着：
 * <ul>
 *   <li>这块是凸出来的、有一边旁边空着：坐在那条边上，面朝外（有几边空着就挑朝着你的那一边）；</li>
 *   <li>四边都挨着方块（嵌在地里、墙里）：坐在方块顶上正中，面朝你。</li>
 * </ul>
 * 头一回认下时傲娇地宣告一句；每天头一次守满一会儿 +2 羁绊（记在你身上，{@link LacertaDays}）。巨龙形态盘成一圈把它圈在怀里（{@link LacertaRest}）。
 */
final class LacertaHoard {
    /** 算宝藏的方块。 */
    static final TagKey<Block> TAG = TagKey.create(Registries.BLOCK, LogosDimension.id("lacerta_hoard"));
    /** 在你身边几格内找；认下的离你多远还算「在家」。 */
    static final int FIND = 8, HOME = 16;
    /** 坐在边上时，身子离那条边往里多少格（她的落点在方块顶上、靠边）。 */
    static final double EDGE_IN = .22;
    /** 每天头一次守满多久（刻）加羁绊、加多少。 */
    static final int GUARD = 200;
    static final float BOND = 2;

    /** 坐在哪：方块顶上的一处、朝哪（yaw）、是不是坐在边上。 */
    record Seat(Vec3 at, float yaw, boolean edge) {}

    private LacertaHoard() {}

    static boolean isHoard(BlockGetter level, BlockPos pos) {
        return level.getBlockState(pos).is(TAG);
    }

    /** 方块上面坐得下（上面两格空着）。 */
    static boolean roomAbove(BlockGetter level, BlockPos pos) {
        return level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
                && level.getBlockState(pos.above(2)).getCollisionShape(level, pos.above(2)).isEmpty();
    }

    /** 这一边旁边是空的（同一层那一格没有碰撞）。 */
    static boolean sideFree(BlockGetter level, BlockPos pos, Direction side) {
        BlockPos at = pos.relative(side);
        return level.getBlockState(at).getCollisionShape(level, at).isEmpty();
    }

    /**
     * 坐在 {@code pos} 这块上：有一边空着就坐在那条边上、面朝外（几边空着挑朝着 {@code toward} 的），四边都挨着就坐在正中、面朝 {@code toward}。
     * 上面坐不下返回 null。
     */
    static @Nullable Seat seat(BlockGetter level, BlockPos pos, Vec3 toward) {
        if (!roomAbove(level, pos)) return null;
        Vec3 top = Vec3.atCenterOf(pos).add(0, .5, 0);
        Vec3 to = toward.subtract(top);
        Direction best = null;
        double bestDot = -Double.MAX_VALUE;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (!sideFree(level, pos, side)) continue;
            double dot = to.x * side.getStepX() + to.z * side.getStepZ();
            if (dot > bestDot) {
                bestDot = dot;
                best = side;
            }
        }
        if (best == null) {
            float yaw = to.horizontalDistanceSqr() < 1e-4 ? 0 : LacertaRest.yawFacing(to);
            return new Seat(top, yaw, false);
        }
        Vec3 out = new Vec3(best.getStepX(), 0, best.getStepZ());
        return new Seat(top.add(out.scale(.5 - EDGE_IN)), LacertaRest.yawFacing(out), true);
    }

    /** 她认下的宝藏（这个维度里、方块还在）；不然 null，方块没了顺便忘掉。 */
    static @Nullable BlockPos claimed(ServerLevel level, ServerPlayer p) {
        Optional<GlobalPos> g = LacertaDays.of(p).hoard();
        if (g.isEmpty() || !g.get().dimension().equals(level.dimension())) return null;
        BlockPos at = g.get().pos();
        if (!level.isLoaded(at)) return null;
        if (!isHoard(level, at)) {
            p.setData(Companions.LACERTA_DAYS, LacertaDays.of(p).withHoard(Optional.empty()));
            return null;
        }
        return at;
    }

    /** 你在家时守哪一块：认下的那块离你 {@link #HOME} 格内就是它；不然你身边 {@link #FIND} 格内上面坐得下、离你最近的一块。 */
    static @Nullable BlockPos near(ServerLevel level, ServerPlayer p) {
        BlockPos mine = claimed(level, p);
        if (mine != null && mine.distSqr(p.blockPosition()) <= HOME * HOME && Math.abs(mine.getY() - p.getY()) <= 4 && roomAbove(level, mine)) {
            return mine;
        }
        BlockPos base = p.blockPosition(), best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(base.offset(-FIND, -2, -FIND), base.offset(FIND, 2, FIND))) {
            if (!isHoard(level, pos) || !roomAbove(level, pos)) continue;
            double d = pos.distSqr(base);
            if (d >= bestD) continue;
            bestD = d;
            best = pos.immutable();
        }
        return best;
    }

    /** 认下 {@code pos}：是新的就记在你身上，返回 true（她宣告一句）。 */
    static boolean claim(ServerLevel level, ServerPlayer p, BlockPos pos) {
        Optional<GlobalPos> g = LacertaDays.of(p).hoard();
        GlobalPos at = GlobalPos.of(level.dimension(), pos.immutable());
        if (g.isPresent() && g.get().equals(at)) return false;
        p.setData(Companions.LACERTA_DAYS, LacertaDays.of(p).withHoard(Optional.of(at)));
        return true;
    }
}
