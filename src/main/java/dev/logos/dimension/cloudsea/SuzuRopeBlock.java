package dev.logos.dimension.cloudsea;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 鈴緒：从神社的铃下垂下的红白麻绳，可以接成几格长，最下一格带穗。
 * 右键绳子会摇响上方四格内的铃。
 */
public class SuzuRopeBlock extends Block {
    public static final MapCodec<SuzuRopeBlock> CODEC = simpleCodec(SuzuRopeBlock::new);
    public static final BooleanProperty BOTTOM = BooleanProperty.create("bottom");
    private static final VoxelShape SHAPE = Block.box(6, 0, 6, 10, 16, 10);

    public SuzuRopeBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(BOTTOM, true));
    }

    @Override
    public MapCodec<SuzuRopeBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BOTTOM);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockState above = level.getBlockState(pos.above());
        return above.is(this) || above.is(Blocks.BELL) || above.isFaceSturdy(level, pos.above(), Direction.DOWN);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(BOTTOM, !context.getLevel().getBlockState(context.getClickedPos().below()).is(this));
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction direction,
                                     BlockPos neighborPos, BlockState neighborState, RandomSource random) {
        if (direction == Direction.UP && !state.canSurvive(level, pos)) return Blocks.AIR.defaultBlockState();
        if (direction == Direction.DOWN) return state.setValue(BOTTOM, !neighborState.is(this));
        return state;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        BlockPos.MutableBlockPos cursor = pos.mutable();
        for (int i = 0; i < 4; i++) {
            cursor.move(Direction.UP);
            BlockState above = level.getBlockState(cursor);
            if (above.getBlock() instanceof BellBlock bell) {
                if (!level.isClientSide()) bell.attemptToRing(player, level, cursor.immutable(), null);
                return InteractionResult.SUCCESS;
            }
            if (!above.is(this)) break;
        }
        return InteractionResult.PASS;
    }
}
