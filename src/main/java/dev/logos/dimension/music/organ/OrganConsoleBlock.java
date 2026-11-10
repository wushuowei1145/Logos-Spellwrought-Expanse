package dev.logos.dimension.music.organ;

import dev.logos.dimension.music.InstrumentBlock;
import dev.logos.dimension.music.InstrumentKind;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * 管风琴演奏台：2 宽 × 2 高。FACING 是键盘朝向（朝着放置的玩家）；主块（part 0）在左下，
 * part 的第 1 位表示向「侧边」（FACING 顺时针方向）偏一格，第 2 位表示向上一格。
 * 模型挂在主块上；拆任意一块整体拆掉，只有主块掉落。音管与风箱见 {@link OrganStructure}。
 */
public class OrganConsoleBlock extends InstrumentBlock {
    public static final IntegerProperty PART = IntegerProperty.create("part", 0, 3);
    private static final java.util.Map<Direction, VoxelShape> LOWER = Shapes.rotateHorizontal(
        Shapes.or(Block.box(0, 0, 4, 16, 16, 16), Block.box(0, 0, 0, 16, 2, 4)));
    private static final java.util.Map<Direction, VoxelShape> UPPER = Shapes.rotateHorizontal(Block.box(0, 0, 7, 16, 16, 16));

    public OrganConsoleBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, 0));
    }

    @Override
    public InstrumentKind kind() {
        return InstrumentKind.PIPE_ORGAN;
    }

    @Override
    public boolean isMain(BlockState state) {
        return state.getValue(PART) == 0;
    }

    private static BlockPos offset(BlockPos main, Direction facing, int part) {
        BlockPos p = main;
        if ((part & 1) != 0) p = p.relative(facing.getClockWise());
        if ((part & 2) != 0) p = p.above();
        return p;
    }

    @Override
    public BlockPos mainPos(BlockState state, BlockPos pos) {
        int part = state.getValue(PART);
        BlockPos p = pos;
        if ((part & 1) != 0) p = p.relative(state.getValue(FACING).getCounterClockWise());
        if ((part & 2) != 0) p = p.below();
        return p;
    }

    @Override
    public Iterable<BlockPos> cells(BlockState mainState, BlockPos main) {
        Direction facing = mainState.getValue(FACING);
        return List.of(main, offset(main, facing, 1), offset(main, facing, 2), offset(main, facing, 3));
    }

    @Override
    protected Vec3 centerOf(BlockState mainState, BlockPos main) {
        Direction facing = mainState.getValue(FACING);
        return Vec3.atCenterOf(main).add(Vec3.atLowerCornerOf(facing.getClockWise().getUnitVec3i()).scale(0.5)).add(0, 0.5, 0);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return ((state.getValue(PART) & 2) == 0 ? LOWER : UPPER).get(state.getValue(FACING));
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        Level level = context.getLevel();
        BlockPos main = context.getClickedPos();
        if (main.getY() >= level.getMaxY()) return null;
        for (int part = 1; part < 4; part++) {
            BlockPos p = offset(main, facing, part);
            if (!level.getBlockState(p).canBeReplaced(context) || !level.getWorldBorder().isWithinBounds(p)) return null;
        }
        return defaultBlockState().setValue(FACING, facing).setValue(PART, 0);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack stack) {
        Direction facing = state.getValue(FACING);
        for (int part = 1; part < 4; part++) level.setBlock(offset(pos, facing, part), state.setValue(PART, part), Block.UPDATE_ALL);
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
                                     Direction direction, BlockPos neighborPos, BlockState neighborState, RandomSource random) {
        Direction facing = state.getValue(FACING);
        int part = state.getValue(PART);
        int sibling = -1;
        if (direction == facing.getClockWise() && (part & 1) == 0) sibling = part | 1;
        else if (direction == facing.getCounterClockWise() && (part & 1) != 0) sibling = part & ~1;
        else if (direction == Direction.UP && (part & 2) == 0) sibling = part | 2;
        else if (direction == Direction.DOWN && (part & 2) != 0) sibling = part & ~2;
        if (sibling >= 0 && !(neighborState.is(this) && neighborState.getValue(PART) == sibling
                && neighborState.getValue(FACING) == facing)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, level, ticks, pos, direction, neighborPos, neighborState, random);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && player.isCreative() && !isMain(state)) {
            BlockPos main = mainPos(state, pos);
            BlockState mainState = level.getBlockState(main);
            if (mainState.is(this) && isMain(mainState)) {
                level.setBlock(main, Blocks.AIR.defaultBlockState(), Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_ALL);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART);
    }
}
