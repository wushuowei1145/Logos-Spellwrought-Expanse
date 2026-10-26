package dev.logos.dimension.npc;

import dev.logos.dimension.elven.ElvenBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.Vec3;

/**
 * 森林精灵的寻路：在普通地面寻路之外，把精灵绳梯当作可以上下走的路径点（树屋只靠绳梯连到地面）。
 *
 * <p>绳梯格算作可站立的格子，绳梯格之间加上竖直的邻接；爬上去时原版移动控制看到目标在头顶会「跳」，
 * 人在可攀爬方块里跳就是向上爬；往下时攀爬方块限制下落速度，慢慢滑下去。
 * 绳梯方块没有碰撞（只有选中框），精灵可以站进绳梯格里攀爬。
 */
public final class ElfNavigation extends GroundPathNavigation {
    public ElfNavigation(Mob mob, Level level) {
        super(mob, level);
    }

    @Override
    protected PathFinder createPathFinder(int maxVisitedNodes) {
        nodeEvaluator = new RopeNodeEvaluator();
        nodeEvaluator.setCanPassDoors(true);
        return new PathFinder(nodeEvaluator, maxVisitedNodes);
    }

    /** 地面寻路只在脚踏实地时跟路径；挂在绳梯上也要继续跟，否则爬到一半就停下滑回去。 */
    @Override
    protected boolean canUpdatePath() {
        return super.canUpdatePath() || mob.onClimbable();
    }

    /**
     * 下一个路径点是正上方或正下方的绳梯格时，由这里接管：贴着绳梯居中、竖直爬升或下滑，到了就换下一个点。
     * 原版移动控制对几乎在头顶的点会全速朝前走，爬到一半就从绳梯上滑出去。
     */
    @Override
    public void tick() {
        if (!climbRope()) super.tick();
    }

    private boolean climbRope() {
        if (path == null || path.isDone() || !mob.onClimbable()) return false;
        BlockPos next = path.getNextNodePos();
        if (next.getX() != mob.getBlockX() || next.getZ() != mob.getBlockZ() || !level.getBlockState(next).is(ElvenBlocks.ROPE.get())) return false;
        double dy = next.getY() - mob.getY();
        if (Math.abs(dy) < .2) {
            path.advance();
            return true;
        }
        mob.setDeltaMovement((next.getX() + .5 - mob.getX()) * .3, dy > 0 ? .2 : -.15, (next.getZ() + .5 - mob.getZ()) * .3);
        mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0);
        return true;
    }

    /** 地面寻路把路径点投到脚下的地面；绳梯格里的路径点保留原高度，移动控制才会看到目标在头顶而向上爬。 */
    @Override
    protected double getGroundY(Vec3 target) {
        BlockPos pos = BlockPos.containing(target);
        return level.getBlockState(pos).is(ElvenBlocks.ROPE.get()) ? pos.getY() : super.getGroundY(target);
    }

    static boolean isRope(PathfindingContext ctx, int x, int y, int z) {
        return ctx.getBlockState(new BlockPos(x, y, z)).is(ElvenBlocks.ROPE.get());
    }

    static final class RopeNodeEvaluator extends WalkNodeEvaluator {
        @Override
        public PathType getPathType(PathfindingContext ctx, int x, int y, int z) {
            return isRope(ctx, x, y, z) ? PathType.WALKABLE : super.getPathType(ctx, x, y, z);
        }

        @Override
        public int getNeighbors(Node[] out, Node node) {
            int n = super.getNeighbors(out, node);
            if (!isRope(currentContext, node.x, node.y, node.z)) return n;
            for (int dy : new int[] {1, -1}) {
                int y = node.y + dy;
                if (!isRope(currentContext, node.x, y, node.z)) continue;
                Node next = getNode(node.x, y, node.z);
                next.type = PathType.WALKABLE;
                next.costMalus = Math.max(next.costMalus, 0);
                if (!next.closed && n < out.length) out[n++] = next;
            }
            return n;
        }
    }
}
