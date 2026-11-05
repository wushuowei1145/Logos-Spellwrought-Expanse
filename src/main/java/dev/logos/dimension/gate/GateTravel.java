package dev.logos.dimension.gate;

import dev.logos.dimension.LogosDimension;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.portal.PortalShape;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 过门：和下界传送门一样按相同坐标到另一侧找最近的门，没有就当场生成一扇点亮的小号门。
 * 主世界和远行大陆 1:1 对应。到达时放传送票，让到达的区块保持加载一段时间（{@code TicketType.PORTAL}，300 刻）。
 */
public final class GateTravel {
    /** 另一侧找门的半径，同下界传送门回主世界。 */
    public static final int SEARCH_RADIUS = 128;
    /** 小号门模板：门面朝向 X 轴，9×9×5，启门石在 (4,0,2)。 */
    public static final Identifier SMALL_GATE = LogosDimension.id("gate/small_gate");
    private static final BlockPos PIVOT = new BlockPos(4, 0, 2);
    private static final int HALF_WIDTH = 4, HALF_DEPTH = 2, HEIGHT = 9;
    /** 找平地的范围（格）和最大高差。 */
    private static final int SPOT_RADIUS = 24, STEEP = 1;
    /** 地基最多往下垫几格。 */
    private static final int FOUNDATION = 8;

    private GateTravel() {}

    /** 门只连接主世界和远行大陆。 */
    public static boolean linked(ResourceKey<Level> dimension) {
        return dimension == Level.OVERWORLD || dimension == LogosDimension.CLOUDSEA;
    }

    public static @Nullable TeleportTransition destination(ServerLevel current, Entity entity, BlockPos entry) {
        if (!linked(current.dimension())) return null;
        ServerLevel target = current.getServer().getLevel(current.dimension() == Level.OVERWORLD ? LogosDimension.CLOUDSEA : Level.OVERWORLD);
        if (target == null) return null;
        BlockState from = current.getBlockState(entry);
        Direction.Axis fromAxis = from.getOptionalValue(GatePortalBlock.AXIS).orElse(Direction.Axis.X);
        BlockPos approximate = target.getWorldBorder().clampToBounds(entity.getX(), entity.getY(), entity.getZ());
        BlockPos exit = findExit(target, approximate).orElse(null);
        if (exit == null) exit = createSmallGate(target, approximate, fromAxis);
        if (exit == null) return null;
        BlockPos arrival = arrivalCell(target, exit);
        Vec3 position = PortalShape.findCollisionFreePosition(arrival.getBottomCenter(), target, entity, entity.getDimensions(entity.getPose()));
        Direction.Axis toAxis = target.getBlockState(exit).getOptionalValue(GatePortalBlock.AXIS).orElse(Direction.Axis.X);
        BlockPos ticket = exit;
        return new TeleportTransition(target, position, Vec3.ZERO, fromAxis == toAxis ? 0 : 90, 0, Relative.union(Relative.DELTA, Relative.ROTATION),
                TeleportTransition.PLAY_PORTAL_SOUND.then(e -> e.placePortalTicket(ticket)));
    }

    /** 离这个位置最近的传送面。 */
    public static Optional<BlockPos> findExit(ServerLevel level, BlockPos near) {
        PoiManager poi = level.getPoiManager();
        poi.ensureLoadedAndValid(level, near, SEARCH_RADIUS);
        return poi.getInSquare(type -> type.is(Gate.GATE_POI.getKey()), near, SEARCH_RADIUS, PoiManager.Occupancy.ANY)
                .map(PoiRecord::getPos)
                .filter(level.getWorldBorder()::isWithinBounds)
                .filter(pos -> level.getBlockState(pos).is(Gate.GATE_PORTAL.get()))
                .min(Comparator.<BlockPos>comparingDouble(pos -> pos.distSqr(near)).thenComparingInt(Vec3i::getY));
    }

    /** 到达哪一格：门洞最底下一排的中间，站在地上。 */
    public static BlockPos arrivalCell(Level level, BlockPos portal) {
        List<BlockPos> cells = GateShape.connected(level, portal);
        if (cells.isEmpty()) return portal;
        int bottom = cells.stream().mapToInt(BlockPos::getY).min().orElse(portal.getY());
        List<BlockPos> row = cells.stream().filter(c -> c.getY() == bottom).sorted(Comparator.<BlockPos>comparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ)).toList();
        return row.get(row.size() / 2);
    }

    /** 在目标附近放一扇点亮的小号门，门面和来时那扇同向。返回门洞里的一格；模板缺失时返回 null。 */
    public static @Nullable BlockPos createSmallGate(ServerLevel level, BlockPos near, Direction.Axis axis) {
        StructureTemplate template = level.getStructureManager().get(SMALL_GATE).orElse(null);
        if (template == null) return null;
        BlockPos stone = findSpot(level, near, axis);
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(axis == Direction.Axis.X ? Rotation.NONE : Rotation.CLOCKWISE_90)
                .setRotationPivot(PIVOT).setIgnoreEntities(true);
        BlockPos origin = stone.subtract(PIVOT);
        template.placeInWorld(level, origin, origin, settings, level.getRandom(), Block.UPDATE_CLIENTS);
        foundation(level, stone, axis);
        if (!(level.getBlockEntity(stone) instanceof GateStoneBlockEntity gate) || !gate.igniteNow()) return null;
        return stone.above();
    }

    /** 启门石放哪：由近到远找一块不在水上、四角高差不超过一格的地面；实在没有（比如大海中央）就放在目标点的水面上。 */
    private static BlockPos findSpot(ServerLevel level, BlockPos near, Direction.Axis axis) {
        int dx = axis == Direction.Axis.X ? HALF_WIDTH : HALF_DEPTH, dz = axis == Direction.Axis.X ? HALF_DEPTH : HALF_WIDTH;
        int ceiling = level.getMaxY() - HEIGHT - 1;
        for (BlockPos.MutableBlockPos column : BlockPos.spiralAround(near, SPOT_RADIUS, Direction.EAST, Direction.SOUTH)) {
            // 隔一格看一次，避免大海上算太多列
            if ((column.getX() & 1) != 0 || (column.getZ() & 1) != 0) continue;
            int x = column.getX(), z = column.getZ();
            if (!level.getWorldBorder().isWithinBounds(x - dx, z - dz) || !level.getWorldBorder().isWithinBounds(x + dx, z + dz)) continue;
            int top = surface(level, x, z);
            if (top > ceiling || top <= level.getMinY() + 1) continue;
            if (!level.getFluidState(new BlockPos(x, top, z)).isEmpty()) continue;
            if (Math.abs(surface(level, x - dx, z - dz) - top) > STEEP || Math.abs(surface(level, x + dx, z - dz) - top) > STEEP
                    || Math.abs(surface(level, x - dx, z + dz) - top) > STEEP || Math.abs(surface(level, x + dx, z + dz) - top) > STEEP) continue;
            return new BlockPos(x, top, z);
        }
        BlockPos clamped = level.getWorldBorder().clampToBounds(near);
        int top = Mth.clamp(surface(level, clamped.getX(), clamped.getZ()), level.getMinY() + 2, ceiling);
        return new BlockPos(clamped.getX(), top, clamped.getZ());
    }

    /** 这一列最高的地面方块（不算树叶）。 */
    private static int surface(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4); // 高度图只对加载好的区块有效，没加载时返回世界底部
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    /** 门建在缓坡上时，把地面底下悬空的地方用泥土垫实。水面上的门不垫。 */
    private static void foundation(ServerLevel level, BlockPos stone, Direction.Axis axis) {
        int dx = axis == Direction.Axis.X ? HALF_WIDTH : HALF_DEPTH, dz = axis == Direction.Axis.X ? HALF_DEPTH : HALF_WIDTH;
        for (int x = -dx; x <= dx; x++) for (int z = -dz; z <= dz; z++) {
            BlockPos.MutableBlockPos at = stone.offset(x, -1, z).mutable();
            for (int i = 0; i < FOUNDATION && level.getBlockState(at).canBeReplaced() && level.getFluidState(at).isEmpty(); i++, at.move(Direction.DOWN))
                level.setBlock(at, Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }
}
