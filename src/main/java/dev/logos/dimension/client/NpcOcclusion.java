package dev.logos.dimension.client;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;

/**
 * 被实心方块完全挡住的 NPC 不画。原版只判断视锥，Sodium 也只按区块判断，所以墙后面、屋子里的神狐和眼前的一样贵，
 * 而神狐聚落正好全是屋子。
 *
 * <p>从镜头向 NPC 周围的一圈点发射线，只要有一条没碰到实心方块就算看得见。点的间距不超过约 1.3 格，
 * 留出的余量盖住尾巴和手里的武器，所以从一格宽的窗户也看得见里面。只有 {@code isSolidRender} 的完整方块才算挡住，
 * 玻璃、树叶、台阶、栅栏都透过去：判断只会偏向多画。
 *
 * <p>结果缓存一小会儿：刚才看得见的 NPC 迟一点才消失没关系；刚才看不见的查得勤，加上余量，走出拐角时不会晚于身体出现。
 * 光影包的阴影通道不做这个判断，太阳看到的和玩家看到的不是一回事，墙后面的神狐影子可能正落在玩家眼前。
 */
final class NpcOcclusion {
    /** 看得见的 NPC 多久重新查一次（毫秒）。 */
    private static final long VISIBLE_RECHECK = 100;
    /** 被挡住的 NPC 多久重新查一次（毫秒）。 */
    private static final long HIDDEN_RECHECK = 30;
    /** 镜头移动超过这个距离（格）的平方就马上重新查。 */
    private static final double CAMERA_MOVED_SQR = .25;
    /** 比碰撞箱多留的余量（格）：尾巴和手里的武器都伸在碰撞箱外面。 */
    private static final double MARGIN_XZ = 1, MARGIN_Y = .5;
    /** 离镜头这么近（格）就不查了，平方。 */
    private static final double NEAR_SQR = 3 * 3;
    private static final int RAYS_X = 3, RAYS_Y = 4, RAYS_Z = 3, RAYS = RAYS_X * RAYS_Y * RAYS_Z;
    /** 第一条射线打向中间那个点，最有可能直接看见。 */
    private static final int CENTER_RAY = RAYS_Y * RAYS_Z + RAYS_Z + 1;
    /** 存这么多只就清空重来：实体 id 只增不减，卸载的实体不会自己离开这张表。 */
    private static final int MAX_STATES = 4096;

    private static final Int2ObjectOpenHashMap<State> STATES = new Int2ObjectOpenHashMap<>();
    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();

    private static final class State {
        long checkedAt = Long.MIN_VALUE;
        boolean visible = true;
        double cameraX, cameraY, cameraZ;
        /** 上一次看见它的那条射线，下次先试它。 */
        int lastClearRay = CENTER_RAY;
    }

    private NpcOcclusion() {}

    /** 只在渲染线程上调用。 */
    static boolean hidden(Entity entity, double cameraX, double cameraY, double cameraZ) {
        if (ShaderCompat.renderingShadowPass() || entity.isCustomNameVisible() || Minecraft.getInstance().shouldEntityAppearGlowing(entity)
                || entity.distanceToSqr(cameraX, cameraY, cameraZ) < NEAR_SQR) {
            return false;
        }
        if (STATES.size() >= MAX_STATES) STATES.clear();
        State state = STATES.computeIfAbsent(entity.getId(), id -> new State());
        long now = Util.getMillis();
        double dx = cameraX - state.cameraX, dy = cameraY - state.cameraY, dz = cameraZ - state.cameraZ;
        if (now - state.checkedAt >= (state.visible ? VISIBLE_RECHECK : HIDDEN_RECHECK) || dx * dx + dy * dy + dz * dz > CAMERA_MOVED_SQR) {
            state.checkedAt = now;
            state.cameraX = cameraX;
            state.cameraY = cameraY;
            state.cameraZ = cameraZ;
            state.visible = visible(entity, cameraX, cameraY, cameraZ, state);
        }
        return !state.visible;
    }

    private static boolean visible(Entity entity, double cameraX, double cameraY, double cameraZ, State state) {
        BlockGetter level = entity.level();
        // 镜头卡在方块里（旁观者、第三人称）时，四周全是实心方块，但原版照样透过去看得见
        if (level.getBlockState(POS.set(cameraX, cameraY, cameraZ)).isSolidRender()) return true;
        AABB box = entity.getBoundingBox();
        double minX = box.minX - MARGIN_XZ, minY = box.minY - MARGIN_Y, minZ = box.minZ - MARGIN_XZ;
        double stepX = (box.getXsize() + 2 * MARGIN_XZ) / (RAYS_X - 1), stepY = (box.getYsize() + 2 * MARGIN_Y) / (RAYS_Y - 1), stepZ = (box.getZsize() + 2 * MARGIN_XZ) / (RAYS_Z - 1);
        for (int i = 0; i < RAYS; i++) {
            int ray = (state.lastClearRay + i) % RAYS;
            int z = ray % RAYS_Z, y = ray / RAYS_Z % RAYS_Y, x = ray / (RAYS_Y * RAYS_Z);
            if (!blocked(level, cameraX, cameraY, cameraZ, minX + x * stepX, minY + y * stepY, minZ + z * stepZ)) {
                state.lastClearRay = ray;
                return true;
            }
        }
        return false;
    }

    /** 沿线段逐格走（Amanatides–Woo），不查起点那一格。 */
    private static boolean blocked(BlockGetter level, double x0, double y0, double z0, double x1, double y1, double z1) {
        int x = Mth.floor(x0), y = Mth.floor(y0), z = Mth.floor(z0);
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
        double deltaX = dx == 0 ? Double.MAX_VALUE : Math.abs(1 / dx), deltaY = dy == 0 ? Double.MAX_VALUE : Math.abs(1 / dy), deltaZ = dz == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
        double nextX = dx == 0 ? Double.MAX_VALUE : (dx > 0 ? x + 1 - x0 : x0 - x) * deltaX;
        double nextY = dy == 0 ? Double.MAX_VALUE : (dy > 0 ? y + 1 - y0 : y0 - y) * deltaY;
        double nextZ = dz == 0 ? Double.MAX_VALUE : (dz > 0 ? z + 1 - z0 : z0 - z) * deltaZ;
        while (true) {
            if (nextX <= nextY && nextX <= nextZ) {
                if (nextX > 1) return false;
                x += stepX;
                nextX += deltaX;
            } else if (nextY <= nextZ) {
                if (nextY > 1) return false;
                y += stepY;
                nextY += deltaY;
            } else {
                if (nextZ > 1) return false;
                z += stepZ;
                nextZ += deltaZ;
            }
            if (level.getBlockState(POS.set(x, y, z)).isSolidRender()) return true;
        }
    }
}
