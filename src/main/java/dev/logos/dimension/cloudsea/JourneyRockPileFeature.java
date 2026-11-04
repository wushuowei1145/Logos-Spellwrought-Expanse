package dev.logos.dimension.cloudsea;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.BlockPileConfiguration;

/**
 * 海底小石堆，照原版 BlockPileFeature 改写：原版只往空气里堆，这里改为只往水格里堆，
 * 于是石堆只落在水下海底，不会出现在陆地上。
 */
public class JourneyRockPileFeature extends Feature<BlockPileConfiguration> {
    public JourneyRockPileFeature() {
        super(BlockPileConfiguration.CODEC);
    }

    @Override
    public boolean place(FeaturePlaceContext<BlockPileConfiguration> context) {
        BlockPos origin = context.origin();
        WorldGenLevel level = context.level();
        RandomSource random = context.random();
        BlockPileConfiguration config = context.config();
        if (origin.getY() < level.getMinY() + 5) {
            return false;
        }
        int xr = 2 + random.nextInt(2);
        int zr = 2 + random.nextInt(2);
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-xr, 0, -zr), origin.offset(xr, 1, zr))) {
            int xd = origin.getX() - pos.getX();
            int zd = origin.getZ() - pos.getZ();
            if (xd * xd + zd * zd <= random.nextFloat() * 10.0F - random.nextFloat() * 6.0F) {
                tryPlaceBlock(level, pos, random, config);
            } else if (random.nextFloat() < 0.031F) {
                tryPlaceBlock(level, pos, random, config);
            }
        }
        return true;
    }

    private boolean mayPlaceOn(LevelAccessor level, BlockPos pos, RandomSource random) {
        BlockPos below = pos.below();
        BlockState belowState = level.getBlockState(below);
        return belowState.isFaceSturdy(level, below, Direction.UP);
    }

    private void tryPlaceBlock(WorldGenLevel level, BlockPos pos, RandomSource random, BlockPileConfiguration config) {
        // 只往水格里堆：石堆是海底景观，接在有陆地的群系上也不会堆到岸上
        if (level.getBlockState(pos).is(Blocks.WATER) && mayPlaceOn(level, pos, random)) {
            level.setBlock(pos, config.stateProvider.getState(level, random, pos), 260);
        }
    }
}
