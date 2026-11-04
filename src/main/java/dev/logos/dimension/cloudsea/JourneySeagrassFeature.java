package dev.logos.dimension.cloudsea;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.ProbabilityFeatureConfiguration;

/**
 * 远行大陆的海草生成。类内容照原版 SeagrassFeature 原样抄写，只把
 * Blocks.SEAGRASS / Blocks.TALL_SEAGRASS 换成本附属的海草与高海草。
 */
public class JourneySeagrassFeature extends Feature<ProbabilityFeatureConfiguration> {
    public JourneySeagrassFeature() {
        super(ProbabilityFeatureConfiguration.CODEC);
    }

    @Override
    public boolean place(FeaturePlaceContext<ProbabilityFeatureConfiguration> context) {
        boolean placedAny = false;
        RandomSource random = context.random();
        WorldGenLevel level = context.level();
        BlockPos origin = context.origin();
        ProbabilityFeatureConfiguration config = context.config();
        int x = random.nextInt(8) - random.nextInt(8);
        int z = random.nextInt(8) - random.nextInt(8);
        int y = level.getHeight(Heightmap.Types.OCEAN_FLOOR, origin.getX() + x, origin.getZ() + z);
        BlockPos grassPos = new BlockPos(origin.getX() + x, y, origin.getZ() + z);
        if (level.getBlockState(grassPos).is(Blocks.WATER)) {
            boolean isTall = random.nextDouble() < config.probability;
            BlockState state = isTall
                ? AquaticFlora.TALL_SEAGRASS.get().defaultBlockState()
                : AquaticFlora.SEAGRASS.get().defaultBlockState();
            if (state.canSurvive(level, grassPos)) {
                if (isTall) {
                    BlockState upperState = state.setValue(JourneyTallSeagrassBlock.HALF, DoubleBlockHalf.UPPER);
                    BlockPos above = grassPos.above();
                    if (level.getBlockState(above).is(Blocks.WATER)) {
                        level.setBlock(grassPos, state, 2);
                        level.setBlock(above, upperState, 2);
                    }
                } else {
                    level.setBlock(grassPos, state, 2);
                }
                placedAny = true;
            }
        }
        return placedAny;
    }
}
