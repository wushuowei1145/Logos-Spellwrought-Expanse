package dev.logos.dimension.cloudsea;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.ProbabilityFeatureConfiguration;

/** 萤光海草生成：与海草同款逻辑（照原版 SeagrassFeature），只放单格萤光海草。 */
public class JourneyGlowSeagrassFeature extends Feature<ProbabilityFeatureConfiguration> {
    public JourneyGlowSeagrassFeature() {
        super(ProbabilityFeatureConfiguration.CODEC);
    }

    @Override
    public boolean place(FeaturePlaceContext<ProbabilityFeatureConfiguration> context) {
        RandomSource random = context.random();
        WorldGenLevel level = context.level();
        BlockPos origin = context.origin();
        int x = random.nextInt(8) - random.nextInt(8);
        int z = random.nextInt(8) - random.nextInt(8);
        int y = level.getHeight(Heightmap.Types.OCEAN_FLOOR, origin.getX() + x, origin.getZ() + z);
        BlockPos pos = new BlockPos(origin.getX() + x, y, origin.getZ() + z);
        if (level.getBlockState(pos).is(Blocks.WATER)) {
            BlockState state = AquaticFlora.GLOW_SEAGRASS.get().defaultBlockState();
            if (state.canSurvive(level, pos)) {
                level.setBlock(pos, state, 2);
                return true;
            }
        }
        return false;
    }
}
