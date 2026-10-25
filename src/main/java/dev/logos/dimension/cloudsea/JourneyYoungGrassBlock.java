package dev.logos.dimension.cloudsea;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * 幼年原野牧草：比成年牧草更矮更嫩，带 {@code age}（0–2）。
 *
 * <p>光照足够时随机刻自己长一档，长满（age=2）之后再随机换成成年原野牧草；
 * 撒骨粉直接催熟一档。带 age 也让衰退符文按级倒退，不会一下铲掉。
 */
public final class JourneyYoungGrassBlock extends VegetationBlock implements BonemealableBlock {
    public static final IntegerProperty AGE=IntegerProperty.create("age",0,2);
    public static final MapCodec<JourneyYoungGrassBlock> CODEC=simpleCodec(JourneyYoungGrassBlock::new);
    /** 长到下一档所需的最低光照：和原版作物/草类保持一致。 */
    private static final int MIN_LIGHT=9;
    public JourneyYoungGrassBlock(Properties p){super(p);registerDefaultState(stateDefinition.any().setValue(AGE,0));}
    @Override public MapCodec<? extends VegetationBlock> codec(){return CODEC;}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block,BlockState> builder){builder.add(AGE);}
    @Override protected void randomTick(BlockState state,ServerLevel level,BlockPos pos,RandomSource random){
        if(level.getMaxLocalRawBrightness(pos.above())<MIN_LIGHT)return;
        grow(state,level,pos,random);
    }
    private void grow(BlockState state,ServerLevel level,BlockPos pos,RandomSource random){
        int age=state.getValue(AGE);
        if(age<2){
            level.setBlock(pos,state.setValue(AGE,age+1),net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
            return;
        }
        // 长满之后慢慢换成年体；留一点概率，免得整片草地同一刻集体换代。
        if(random.nextInt(4)==0)
            level.setBlock(pos,CloudseaBlocks.MEADOW_GRASS.get().defaultBlockState(),net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
    }
    @Override public boolean isValidBonemealTarget(LevelReader level,BlockPos pos,BlockState state){
        return level.getBlockState(pos.above()).isAir()&&level.isInsideBuildHeight(pos.above());
    }
    @Override public boolean isBonemealSuccess(Level level,RandomSource random,BlockPos pos,BlockState state){return true;}
    @Override public void performBonemeal(ServerLevel level,RandomSource random,BlockPos pos,BlockState state){
        int age=state.getValue(AGE);
        if(age<2)level.setBlock(pos,state.setValue(AGE,age+1),net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        else level.setBlock(pos,CloudseaBlocks.MEADOW_GRASS.get().defaultBlockState(),net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
    }
}
