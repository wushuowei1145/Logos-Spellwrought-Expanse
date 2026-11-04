package dev.logos.dimension.cloudsea;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.SeagrassBlock;
import net.minecraft.world.level.block.TallSeagrassBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * 远行大陆的海草（萤光海草也用这个类）。继承原版 SeagrassBlock，生长、存活、含水与剪刀行为全部沿用原版，
 * 只把骨粉催出的 Blocks.TALL_SEAGRASS 换成本附属的高海草。
 */
public class JourneySeagrassBlock extends SeagrassBlock {
    public static final MapCodec<SeagrassBlock> CODEC = simpleCodec(JourneySeagrassBlock::new);

    public JourneySeagrassBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<SeagrassBlock> codec() {
        return CODEC;
    }

    @Override
    public void performBonemeal(ServerLevel level, RandomSource random, BlockPos pos, BlockState state) {
        BlockState lowerState = AquaticFlora.TALL_SEAGRASS.get().defaultBlockState();
        BlockState upperState = lowerState.setValue(TallSeagrassBlock.HALF, DoubleBlockHalf.UPPER);
        BlockPos above = pos.above();
        level.setBlock(pos, lowerState, 2);
        level.setBlock(above, upperState, 2);
    }
}
