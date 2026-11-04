package dev.logos.dimension.cloudsea;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * 远行大陆的海带头。类内容照原版 KelpBlock 原样抄写，只把
 * Blocks.KELP_PLANT 换成本附属的海带茎。
 */
public class JourneyKelpBlock extends GrowingPlantHeadBlock implements LiquidBlockContainer {
    public static final MapCodec<JourneyKelpBlock> CODEC = simpleCodec(JourneyKelpBlock::new);
    private static final VoxelShape SHAPE = Block.column(16.0, 0.0, 9.0);

    @Override
    public MapCodec<JourneyKelpBlock> codec() {
        return CODEC;
    }

    public JourneyKelpBlock(BlockBehaviour.Properties properties) {
        super(properties, Direction.UP, SHAPE, true, 0.14);
    }

    @Override
    protected boolean canGrowInto(BlockState state) {
        return state.is(net.minecraft.world.level.block.Blocks.WATER);
    }

    @Override
    protected Block getBodyBlock() {
        return AquaticFlora.KELP_PLANT.get();
    }

    @Override
    protected boolean canAttachTo(BlockState state) {
        return !state.is(BlockTags.CANNOT_SUPPORT_KELP);
    }

    @Override
    public boolean canPlaceLiquid(@Nullable LivingEntity user, BlockGetter level, BlockPos pos, BlockState state, Fluid type) {
        return false;
    }

    @Override
    public boolean placeLiquid(LevelAccessor level, BlockPos pos, BlockState state, FluidState fluidState) {
        return false;
    }

    @Override
    protected int getBlocksToGrowWhenBonemealed(RandomSource random) {
        return 1;
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        FluidState fluidState = context.getLevel().getFluidState(context.getClickedPos());
        return fluidState.is(FluidTags.WATER) && fluidState.isFull() ? super.getStateForPlacement(context) : null;
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return Fluids.WATER.getSource(false);
    }
}
