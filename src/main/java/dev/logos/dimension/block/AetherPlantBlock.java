package dev.logos.dimension.block;

import com.mojang.serialization.MapCodec;
import dev.logos.dimension.DimensionBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Ground cover belongs to the dimension's soil, rather than depending on vanilla grass. */
public final class AetherPlantBlock extends VegetationBlock {
    public static final MapCodec<AetherPlantBlock> CODEC = simpleCodec(AetherPlantBlock::new);
    private static final VoxelShape SHAPE = column(12, 0, 12);
    public AetherPlantBlock(BlockBehaviour.Properties properties) { super(properties); }
    @Override public MapCodec<AetherPlantBlock> codec() { return CODEC; }
    @Override protected boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos) {
        return state.is(DimensionBlocks.AETHER_TURF.get()) || state.is(DimensionBlocks.AETHER_SOIL.get());
    }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, net.minecraft.world.phys.shapes.CollisionContext context) { return SHAPE; }
}
