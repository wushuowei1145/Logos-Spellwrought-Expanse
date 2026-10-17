package dev.logos.dimension.cloudsea;

import com.mojang.serialization.MapCodec;
import dev.logos.dimension.LogosDimension;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

public final class SeasonalLeavesBlock extends LeavesBlock {
    public static final IntegerProperty SEASON=IntegerProperty.create("season",0,3);
    public static final MapCodec<SeasonalLeavesBlock> CODEC=simpleCodec(SeasonalLeavesBlock::new);
    public SeasonalLeavesBlock(Properties p){super(0,p);registerDefaultState(defaultBlockState().setValue(SEASON,0));}
    @Override protected void spawnFallingLeavesParticle(net.minecraft.world.level.Level level,BlockPos pos,RandomSource random){}
    @Override public MapCodec<? extends LeavesBlock> codec(){return CODEC;}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder){super.createBlockStateDefinition(builder);builder.add(SEASON);}
    @Override protected void randomTick(BlockState state,ServerLevel level,BlockPos pos,RandomSource random){
        if(level.dimension().equals(LogosDimension.CLOUDSEA)&&!CloudseaBlocks.isEvergreen(this)){
            int season=JourneySeasons.of(level);if(state.getValue(SEASON)!=season)level.setBlock(pos,state.setValue(SEASON,season),Block.UPDATE_CLIENTS);
        }
    }
}
