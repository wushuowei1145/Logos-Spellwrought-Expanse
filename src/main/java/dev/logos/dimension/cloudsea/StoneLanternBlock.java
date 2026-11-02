package dev.logos.dimension.cloudsea;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 矮石灯笼：日式和风庭院石雕小灯。
 */
public class StoneLanternBlock extends Block {
    public static final MapCodec<StoneLanternBlock> CODEC = simpleCodec(StoneLanternBlock::new);
    private static final VoxelShape SHAPE = Block.box(2, 0, 2, 14, 16, 14);

    @Override
    public MapCodec<StoneLanternBlock> codec() {
        return CODEC;
    }

    public StoneLanternBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }
}
