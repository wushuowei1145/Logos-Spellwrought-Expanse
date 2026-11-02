package dev.logos.dimension.cloudsea;

import com.mojang.serialization.MapCodec;
import dev.logos.dimension.DimensionParticles;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 竹出水口（添水）：
 * 倾斜探出的竹筒，潺潺流淌清泉细流与温泉水汽。
 */
public class BambooSpoutBlock extends HorizontalDirectionalBlock implements SimpleWaterloggedBlock {
    public static final MapCodec<BambooSpoutBlock> CODEC = simpleCodec(BambooSpoutBlock::new);
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;

    private static final VoxelShape SHAPE_NS = Block.box(5, 0, 0, 11, 12, 16);
    private static final VoxelShape SHAPE_EW = Block.box(0, 0, 5, 16, 12, 11);

    @Override
    public MapCodec<BambooSpoutBlock> codec() {
        return CODEC;
    }

    public BambooSpoutBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(WATERLOGGED, false));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(FACING).getAxis() == Direction.Axis.X ? SHAPE_EW : SHAPE_NS;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        FluidState fluid = context.getLevel().getFluidState(context.getClickedPos());
        return defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(WATERLOGGED, fluid.getType() == Fluids.WATER);
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
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
        if (state.getValue(WATERLOGGED)) {
            ticks.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        return super.updateShape(state, level, ticks, pos, direction, neighborPos, neighborState, random);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        Direction dir = state.getValue(FACING);
        // 竹筒口位置
        double tipX = pos.getX() + 0.5 + dir.getStepX() * 0.42;
        double tipY = pos.getY() + 0.42;
        double tipZ = pos.getZ() + 0.5 + dir.getStepZ() * 0.42;

        if (random.nextFloat() < 0.8f) {
            level.addParticle(ParticleTypes.FALLING_WATER, tipX, tipY, tipZ, 0.0, -0.05, 0.0);
        }
        if (random.nextFloat() < 0.25f) {
            level.addParticle(DimensionParticles.STEAM.get(), tipX, tipY + 0.05, tipZ, 0.0, 0.015, 0.0);
        }
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, WATERLOGGED);
    }
}
