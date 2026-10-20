package dev.logos.dimension.cloudsea;

import com.mojang.serialization.MapCodec;
import dev.logos.dimension.LogosDimension;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.*;

/** Dormant shoots, flowers, fruit and winter silhouettes share one persistent plant identity. */
public final class SeasonalPlantBlock extends JourneyPlantBlock {
    public static final MapCodec<SeasonalPlantBlock> CODEC=simpleCodec(SeasonalPlantBlock::new);
    public SeasonalPlantBlock(Properties p){super(p);registerDefaultState(stateDefinition.any().setValue(SeasonalLeavesBlock.SEASON,0));}
    @Override public MapCodec<SeasonalPlantBlock> codec(){return CODEC;}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder){builder.add(SeasonalLeavesBlock.SEASON);}
    @Override protected void randomTick(BlockState state,ServerLevel level,BlockPos pos,RandomSource random){
        if(!ensureSupported(state,level,pos))return;
        if(level.dimension().equals(LogosDimension.CLOUDSEA)){
            int season=JourneySeasons.of(level);if(state.getValue(SeasonalLeavesBlock.SEASON)!=season)level.setBlock(pos,state.setValue(SeasonalLeavesBlock.SEASON,season),Block.UPDATE_CLIENTS);
        }
    }
}
