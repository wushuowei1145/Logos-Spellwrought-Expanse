package dev.logos.dimension.gate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * 门洞判定：从启门石正上方在竖直面内泛洪，碰到方块就停；能合拢才算门框。
 *
 * <p>不要求矩形，也不要求特定材质，所以蓝图的八边形和玩家自己搭的门框都走同一套规则；
 * 两个方向各试一次，合拢的那个就是门面，结构怎么旋转都不用另外记录朝向。
 */
public final class GateShape {
    /** 门洞最多这么多格，超过就当没有合拢（在开阔地上泛洪会一直漏出去）。 */
    public static final int MAX_CELLS = 128;

    /** @param axis 门面沿哪个水平轴展开（同下界传送门的 axis）；@param cells 门洞的格子，由下往上、由近到远 */
    public record Found(Direction.Axis axis, List<BlockPos> cells) {}

    private GateShape() {}

    /** 这一格能不能被传送面占据：空气、草和雪这类可替换方块，以及自然生长、长进门洞的树叶。玩家放的树叶算门框。 */
    public static boolean fillable(BlockState state) {
        if (state.isAir() || state.is(Gate.GATE_PORTAL.get()) || state.canBeReplaced()) return true;
        return state.is(BlockTags.LEAVES) && state.hasProperty(LeavesBlock.PERSISTENT) && !state.getValue(LeavesBlock.PERSISTENT);
    }

    public static @Nullable Found find(Level level, BlockPos stone) {
        BlockPos start = stone.above();
        // 至少要有两格高，不然人过不去
        if (!fillable(level.getBlockState(start)) || !fillable(level.getBlockState(start.above()))) return null;
        Found x = flood(level, start, Direction.Axis.X), z = flood(level, start, Direction.Axis.Z);
        if (x == null) return z;
        if (z == null) return x;
        return x.cells().size() >= z.cells().size() ? x : z;
    }

    private static @Nullable Found flood(Level level, BlockPos start, Direction.Axis axis) {
        LinkedHashSet<BlockPos> seen = new LinkedHashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        Direction[] plane = plane(axis);
        while (!queue.isEmpty()) {
            BlockPos at = queue.poll();
            for (Direction d : plane) {
                BlockPos next = at.relative(d);
                if (seen.contains(next) || !fillable(level.getBlockState(next))) continue;
                if (seen.size() >= MAX_CELLS) return null;
                seen.add(next);
                queue.add(next);
            }
        }
        return new Found(axis, new ArrayList<>(seen));
    }

    /** 门面内的四个方向。顺序决定楼梯这类半格由哪一格负责补（见 {@link #withEdges}），必须固定。 */
    public static Direction[] plane(Direction.Axis axis) {
        return axis == Direction.Axis.X
                ? new Direction[]{Direction.DOWN, Direction.UP, Direction.WEST, Direction.EAST}
                : new Direction[]{Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH};
    }

    /** 楼梯、台阶、活板门：占不满一格，空出来的部分要由传送面补上。 */
    public static boolean partial(BlockState state) {
        return state.is(BlockTags.STAIRS) || state.is(BlockTags.SLABS) || state.is(BlockTags.TRAPDOORS);
    }

    /**
     * 算这一格传送面要往哪些方向多画一格，盖住相邻半格的空缺。
     *
     * <p>一个半格可能同时挨着两三格传送面，都画的话半透明会叠两层，所以每个半格只归一格管：
     * 按 {@link #plane} 的顺序，从半格看过去最先碰到的那格传送面。
     */
    public static BlockState withEdges(Level level, BlockPos pos, BlockState state) {
        Direction.Axis axis = state.getValue(GatePortalBlock.AXIS);
        Block portal = state.getBlock();
        for (Direction d : plane(axis)) {
            BlockPos neighbour = pos.relative(d);
            boolean owner = false;
            if (partial(level.getBlockState(neighbour))) {
                for (Direction back : plane(axis)) {
                    BlockPos candidate = neighbour.relative(back);
                    if (level.getBlockState(candidate).is(portal)) {
                        owner = candidate.equals(pos);
                        break;
                    }
                }
            }
            state = state.setValue(GatePortalBlock.edge(d), owner);
        }
        return state;
    }

    /** 把这些格子的补缝状态刷新一遍。只改外观，不通知邻居。 */
    public static void refreshEdges(Level level, List<BlockPos> cells) {
        for (BlockPos cell : cells) {
            BlockState state = level.getBlockState(cell);
            if (!state.is(Gate.GATE_PORTAL.get())) continue;
            BlockState edged = withEdges(level, cell, state);
            if (edged != state) level.setBlock(cell, edged, Block.UPDATE_CLIENTS);
        }
    }

    /** 这格传送面所在的整扇门，用来算到达位置。 */
    public static List<BlockPos> connected(Level level, BlockPos from) {
        BlockState state = level.getBlockState(from);
        if (!state.is(Gate.GATE_PORTAL.get())) return List.of();
        Direction[] plane = plane(state.getValue(GatePortalBlock.AXIS));
        LinkedHashSet<BlockPos> seen = new LinkedHashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        seen.add(from);
        queue.add(from);
        while (!queue.isEmpty() && seen.size() < MAX_CELLS) {
            BlockPos at = queue.poll();
            for (Direction d : plane) {
                BlockPos next = at.relative(d);
                if (!seen.contains(next) && level.getBlockState(next).is(Gate.GATE_PORTAL.get())) {
                    seen.add(next);
                    queue.add(next);
                }
            }
        }
        return new ArrayList<>(seen);
    }
}
