package dev.logos.dimension.cloudsea;

import com.mojang.serialization.MapCodec;
import dev.logos.dimension.LogosDimension;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;

/**
 * 季节草皮。随机刻负责跟随季节换色与化雪；骨粉按原版草方块的做法，
 * 从所在群系的骨粉特征里随机铺一小片植被（候选来自 {@code minecraft:can_spawn_from_bone_meal} 标签）。
 */
public final class SeasonalTurfBlock extends Block implements BonemealableBlock {
    public static final IntegerProperty SEASON=SeasonalLeavesBlock.SEASON;
    public static final MapCodec<SeasonalTurfBlock> CODEC=simpleCodec(SeasonalTurfBlock::new);
    /** 骨粉一次最多尝试的次数：原版草方块是 128，这里取一半，草地上不会炸出一大片。 */
    private static final int BONEMEAL_ATTEMPTS=64;
    public SeasonalTurfBlock(Properties p){super(p);registerDefaultState(stateDefinition.any().setValue(SEASON,0));}
    @Override public MapCodec<? extends Block> codec(){return CODEC;}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder){builder.add(SEASON);}
    @Override protected void randomTick(BlockState state,ServerLevel level,BlockPos pos,RandomSource random){
        if(!level.dimension().equals(LogosDimension.CLOUDSEA))return;
        int season=JourneySeasons.of(level);
        if(state.getValue(SEASON)!=season)level.setBlock(pos,state.setValue(SEASON,season),Block.UPDATE_CLIENTS);
        if(season!=3&&level.getBlockState(pos.above()).is(Blocks.SNOW)&&level.getBiome(pos).value().getBaseTemperature()>.2f)level.removeBlock(pos.above(),false);
    }
    @Override public boolean isValidBonemealTarget(LevelReader level,BlockPos pos,BlockState state){
        return level.getBlockState(pos.above()).isAir()&&level.isInsideBuildHeight(pos.above());
    }
    @Override public boolean isBonemealSuccess(Level level,RandomSource random,BlockPos pos,BlockState state){
        // 群系里没有候选特征时不要白白消耗骨粉。
        return !level.getBiome(pos.above()).value().getGenerationSettings().getBoneMealFeatures().isEmpty();
    }
    @Override public void performBonemeal(ServerLevel level,RandomSource random,BlockPos pos,BlockState state){
        List<ConfiguredFeature<?,?>> features=level.getBiome(pos.above()).value().getGenerationSettings().getBoneMealFeatures();
        if(features.isEmpty())return;
        var generator=level.getChunkSource().getGenerator();
        BlockPos above=pos.above();
        for(int i=0;i<BONEMEAL_ATTEMPTS;i++){
            // 离起点越远命中率越低，和原版草方块的散布方式一致。
            BlockPos target=above;
            for(int k=0;k<i/16;k++){
                target=target.offset(random.nextInt(3)-1,(random.nextInt(3)-1)*random.nextInt(3)/2,random.nextInt(3)-1);
                if(!level.getBlockState(target.below()).is(this)||level.getBlockState(target).isCollisionShapeFullBlock(level,target))continue;
            }
            if(level.getBlockState(target).isAir()&&!level.isOutsideBuildHeight(target))
                features.get(random.nextInt(features.size())).place(level,generator,random,target);
        }
    }
}
