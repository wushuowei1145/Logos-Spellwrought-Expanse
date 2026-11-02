package dev.logos.dimension.cloudsea;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 灵狐樱野落樱瓣方块：
 * <ul>
 *   <li>支持 1~4 层叠加（AMOUNT）</li>
 *   <li>无碰撞、贴地/水面薄片</li>
 *   <li>周围有同类落樱瓣时无缝相连（NORTH / EAST / SOUTH / WEST）</li>
 *   <li>可在陆地（泥土/树叶/实心表面）以及水面（静止水/水流源头）放置与存活</li>
 *   <li>可被骨粉催长，满层继续催长掉落物</li>
 * </ul>
 */
public class JourneyPetalsBlock extends Block implements BonemealableBlock {
    public static final MapCodec<JourneyPetalsBlock> CODEC = simpleCodec(JourneyPetalsBlock::new);

    public static final IntegerProperty AMOUNT = BlockStateProperties.FLOWER_AMOUNT;
    public static final BooleanProperty NORTH = BlockStateProperties.NORTH;
    public static final BooleanProperty EAST = BlockStateProperties.EAST;
    public static final BooleanProperty SOUTH = BlockStateProperties.SOUTH;
    public static final BooleanProperty WEST = BlockStateProperties.WEST;

    private static final VoxelShape SHAPE = box(0.0, 0.0, 0.0, 16.0, 1.0, 16.0);

    @Override
    public MapCodec<JourneyPetalsBlock> codec() {
        return CODEC;
    }

    public JourneyPetalsBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(AMOUNT, 1)
                .setValue(NORTH, false)
                .setValue(EAST, false)
                .setValue(SOUTH, false)
                .setValue(WEST, false));
    }

    public static BooleanProperty getPropertyForDirection(Direction direction) {
        return switch (direction) {
            case NORTH -> NORTH;
            case EAST -> EAST;
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            default -> throw new IllegalArgumentException("Unsupported horizontal direction: " + direction);
        };
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos belowPos = pos.below();
        BlockState belowState = level.getBlockState(belowPos);
        FluidState belowFluid = level.getFluidState(belowPos);

        // 浮于水面
        if (belowFluid.is(FluidTags.WATER) && (belowFluid.isSource() || belowFluid.getAmount() >= 8)) {
            return true;
        }
        // 陆地坚实顶面、泥土草方块类、树叶
        return belowState.isFaceSturdy(level, belowPos, Direction.UP, SupportType.CENTER)
                || belowState.is(BlockTags.DIRT)
                || belowState.is(BlockTags.LEAVES);
    }

    @Override
    protected BlockState updateShape(
            BlockState state,
            LevelReader level,
            ScheduledTickAccess ticks,
            BlockPos pos,
            Direction direction,
            BlockPos neighborPos,
            BlockState neighborState,
            RandomSource random
    ) {
        if (!state.canSurvive(level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        if (direction.getAxis().isHorizontal()) {
            boolean connects = neighborState.is(this);
            return state.setValue(getPropertyForDirection(direction), connects);
        }
        return super.updateShape(state, level, ticks, pos, direction, neighborPos, neighborState, random);
    }

    @Override
    public boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
        return (!context.isSecondaryUseActive() && context.getItemInHand().is(this.asItem()) && state.getValue(AMOUNT) < 4)
                || super.canBeReplaced(state, context);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos clickedPos = context.getClickedPos();
        Level level = context.getLevel();
        BlockState existing = level.getBlockState(clickedPos);
        if (existing.is(this)) {
            return existing.setValue(AMOUNT, Math.min(4, existing.getValue(AMOUNT) + 1));
        }
        BlockState state = defaultBlockState();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockState neighbor = level.getBlockState(clickedPos.relative(dir));
            if (neighbor.is(this)) {
                state = state.setValue(getPropertyForDirection(dir), true);
            }
        }
        return state;
    }

    @Override
    public boolean isValidBonemealTarget(LevelReader level, BlockPos pos, BlockState state) {
        return true;
    }

    @Override
    public boolean isBonemealSuccess(Level level, RandomSource random, BlockPos pos, BlockState state) {
        return true;
    }

    @Override
    public void performBonemeal(ServerLevel level, RandomSource random, BlockPos pos, BlockState state) {
        int current = state.getValue(AMOUNT);
        if (current < 4) {
            level.setBlock(pos, state.setValue(AMOUNT, current + 1), Block.UPDATE_ALL);
        } else {
            popResource(level, pos, new ItemStack(this));
        }
    }

    @Override
    public int getFlammability(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return 60;
    }

    @Override
    public int getFireSpreadSpeed(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return 30;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AMOUNT, NORTH, EAST, SOUTH, WEST);
    }
}
