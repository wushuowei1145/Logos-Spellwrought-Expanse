package dev.logos.dimension.cloudsea;

import com.mojang.serialization.MapCodec;
import dev.logos.dimension.LogosDimension;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

public final class SeasonalTurfBlock extends Block {
    public static final IntegerProperty SEASON=SeasonalLeavesBlock.SEASON;
    public static final MapCodec<SeasonalTurfBlock> CODEC=simpleCodec(SeasonalTurfBlock::new);
    public SeasonalTurfBlock(Properties p){super(p);registerDefaultState(stateDefinition.any().setValue(SEASON,0));}
    @Override public MapCodec<? extends Block> codec(){return CODEC;}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder){builder.add(SEASON);}
    @Override protected void randomTick(BlockState state,ServerLevel level,BlockPos pos,RandomSource random){
        if(!level.dimension().equals(LogosDimension.CLOUDSEA))return;
        int season=JourneySeasons.of(level);
        if(state.getValue(SEASON)!=season)level.setBlock(pos,state.setValue(SEASON,season),Block.UPDATE_CLIENTS);
        if(season!=3&&level.getBlockState(pos.above()).is(Blocks.SNOW)&&level.getBiome(pos).value().getBaseTemperature()>.2f)level.removeBlock(pos.above(),false);
    }
}
