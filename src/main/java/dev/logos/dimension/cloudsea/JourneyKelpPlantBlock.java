package dev.logos.dimension.cloudsea;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.GrowingPlantBodyBlock;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.Shapes;
import org.jspecify.annotations.Nullable;

/**
 * 远行大陆的海带茎。类内容照原版 KelpPlantBlock 原样抄写，只把
 * Blocks.KELP 换成本附属的海带头。
 */
public class JourneyKelpPlantBlock extends GrowingPlantBodyBlock implements LiquidBlockContainer {
    public static final MapCodec<JourneyKelpPlantBlock> CODEC = simpleCodec(JourneyKelpPlantBlock::new);

    @Override
    public MapCodec<JourneyKelpPlantBlock> codec() {
        return CODEC;
    }

    public JourneyKelpPlantBlock(BlockBehaviour.Properties properties) {
        super(properties, net.minecraft.core.Direction.UP, Shapes.block(), true);
    }

    @Override
    protected GrowingPlantHeadBlock getHeadBlock() {
        return (GrowingPlantHeadBlock) AquaticFlora.KELP.get();
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return Fluids.WATER.getSource(false);
    }

    @Override
    protected boolean canAttachTo(BlockState state) {
        // 原版在同包里直接复用海带头的判断；跨包要自己写一遍同样的规则
        return !state.is(net.minecraft.tags.BlockTags.CANNOT_SUPPORT_KELP);
    }

    @Override
    public boolean canPlaceLiquid(@Nullable LivingEntity user, BlockGetter level, BlockPos pos, BlockState state, Fluid type) {
        return false;
    }

    @Override
    public boolean placeLiquid(LevelAccessor level, BlockPos pos, BlockState state, FluidState fluidState) {
        return false;
    }
}
