package dev.logos.dimension.music;

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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * 两格高的乐器（灵猫竖琴、管钟）：像门一样，下半是主块。FACING 朝着放置的玩家；
 * 碰撞形状按 FACING=north 给出，其余朝向自动旋转，上下两格相同。
 */
public class TallInstrumentBlock extends InstrumentBlock {
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;
    private final InstrumentKind kind;
    private final java.util.Map<Direction, VoxelShape> shapes;

    public TallInstrumentBlock(Properties properties, InstrumentKind kind, VoxelShape north) {
        super(properties);
        this.kind = kind;
        this.shapes = net.minecraft.world.phys.shapes.Shapes.rotateHorizontal(north);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(HALF, DoubleBlockHalf.LOWER));
    }

    @Override
    public InstrumentKind kind() {
        return kind;
    }

    @Override
    public boolean isMain(BlockState state) {
        return state.getValue(HALF) == DoubleBlockHalf.LOWER;
    }

    @Override
    public BlockPos mainPos(BlockState state, BlockPos pos) {
        return isMain(state) ? pos : pos.below();
    }

    @Override
    public Iterable<BlockPos> cells(BlockState mainState, BlockPos main) {
        return List.of(main, main.above());
    }

    @Override
    protected Vec3 centerOf(BlockState mainState, BlockPos main) {
        return Vec3.atCenterOf(main).add(0, 0.5, 0);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapes.get(state.getValue(FACING));
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        Level level = context.getLevel();
        if (pos.getY() < level.getMaxY() && level.getBlockState(pos.above()).canBeReplaced(context)) {
            return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(HALF, DoubleBlockHalf.LOWER);
        }
        return null;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack stack) {
        level.setBlock(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
                                     Direction direction, BlockPos neighborPos, BlockState neighborState, RandomSource random) {
        DoubleBlockHalf half = state.getValue(HALF);
        if (direction.getAxis() == Direction.Axis.Y && (half == DoubleBlockHalf.LOWER) == (direction == Direction.UP)) {
            if (!neighborState.is(this) || neighborState.getValue(HALF) == half) return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, level, ticks, pos, direction, neighborPos, neighborState, random);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && player.isCreative() && !isMain(state)) {
            BlockPos below = pos.below();
            BlockState belowState = level.getBlockState(below);
            if (belowState.is(this) && isMain(belowState)) {
                level.setBlock(below, Blocks.AIR.defaultBlockState(), Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_ALL);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF);
    }
}
