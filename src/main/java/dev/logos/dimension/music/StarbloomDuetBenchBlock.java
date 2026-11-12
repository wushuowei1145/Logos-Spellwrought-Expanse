package dev.logos.dimension.music;

import java.util.List;
import java.util.Map;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * 星辰花双人琴凳（docs/ORCHESTRA.md 第 14 节）：两格宽，坐的人坐在两格交界的正中，两格宽的钢琴前用，手往两边都够得着。
 * 主块是放置时点的那格，另一格（{@link #SIDE}）在坐的人右手边（FACING 顺时针方向）。关联、坐下、加入乐团都同单人琴凳，
 * 任一格前面是乐器的演奏那一面就关联；拆任一格整张拆掉，只掉一张。
 */
public class StarbloomDuetBenchBlock extends StarbloomBenchBlock {
    public static final BooleanProperty SIDE = BooleanProperty.create("side");
    private static final Map<Direction, VoxelShape> MAIN_SHAPES = Shapes.rotateHorizontal(Block.box(1, 0, FRONT_EDGE, 16, TOP, 13));
    private static final Map<Direction, VoxelShape> SIDE_SHAPES = Shapes.rotateHorizontal(Block.box(0, 0, FRONT_EDGE, 15, TOP, 13));

    public StarbloomDuetBenchBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(SIDE, false));
    }

    @Override
    protected float seatX() {
        return 16;
    }

    @Override
    public boolean isMain(BlockState state) {
        return !state.getValue(SIDE);
    }

    @Override
    public BlockPos mainPos(BlockState state, BlockPos pos) {
        return state.getValue(SIDE) ? pos.relative(state.getValue(FACING).getCounterClockWise()) : pos;
    }

    @Override
    public Iterable<BlockPos> cells(BlockState mainState, BlockPos main) {
        return List.of(main, main.relative(mainState.getValue(FACING).getClockWise()));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return (state.getValue(SIDE) ? SIDE_SHAPES : MAIN_SHAPES).get(state.getValue(FACING));
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection();
        BlockPos side = context.getClickedPos().relative(facing.getClockWise());
        Level level = context.getLevel();
        if (!level.getBlockState(side).canBeReplaced(context) || !level.getWorldBorder().isWithinBounds(side)) return null;
        return defaultBlockState().setValue(FACING, facing).setValue(SIDE, false);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack stack) {
        level.setBlock(pos.relative(state.getValue(FACING).getClockWise()), state.setValue(SIDE, true), Block.UPDATE_ALL);
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
                                     Direction direction, BlockPos neighborPos, BlockState neighborState, RandomSource random) {
        boolean side = state.getValue(SIDE);
        Direction toOther = side ? state.getValue(FACING).getCounterClockWise() : state.getValue(FACING).getClockWise();
        if (direction == toOther && !(neighborState.is(this) && neighborState.getValue(SIDE) != side
                && neighborState.getValue(FACING) == state.getValue(FACING))) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, level, ticks, pos, direction, neighborPos, neighborState, random);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && player.isCreative() && state.getValue(SIDE)) {
            BlockPos main = mainPos(state, pos);
            if (level.getBlockState(main).is(this)) {
                level.setBlock(main, Blocks.AIR.defaultBlockState(), Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_ALL);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(SIDE);
    }
}
