package dev.logos.dimension.elven.blight;

import com.mojang.serialization.MapCodec;
import dev.logos.dimension.corruption.CorruptionBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;

/** 同一种腐败的脓芽、骨刺与肋状增生，共用现有网络和接触伤害。 */
public class CorruptionGrowthBlock extends CorruptionBlock {
    public static final MapCodec<CorruptionGrowthBlock> CODEC = simpleCodec(CorruptionGrowthBlock::new);
    public CorruptionGrowthBlock(Properties p) { super(p); }
    @Override protected MapCodec<CorruptionGrowthBlock> codec() { return CODEC; }
    @Override protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        return below.is(BlockTags.DIRT) || below.is(ElvenBlight.WOOD.get()) || below.getBlock() instanceof CorruptionBlock;
    }
}
