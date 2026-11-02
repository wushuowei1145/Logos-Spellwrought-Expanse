package dev.logos.dimension.cloudsea;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 路边小石龛（Hokora）：供奉狐仙的迷你石龛神社。
 */
public class ShrineHokoraBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<ShrineHokoraBlock> CODEC = simpleCodec(ShrineHokoraBlock::new);
    private static final VoxelShape SHAPE = Block.box(1, 0, 1, 15, 14, 15);

    @Override
    public MapCodec<ShrineHokoraBlock> codec() {
        return CODEC;
    }

    public ShrineHokoraBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }
}
