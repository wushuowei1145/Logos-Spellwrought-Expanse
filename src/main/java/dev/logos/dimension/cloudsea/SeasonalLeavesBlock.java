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
    private final boolean petals;
    public SeasonalLeavesBlock(Properties p){this(p,false);}
    public SeasonalLeavesBlock(Properties p,boolean petals){super(0,p);this.petals=petals;registerDefaultState(defaultBlockState().setValue(SEASON,0));}
    /** 只有白樱叶飘瓣：用原版樱花叶的粒子贴图，落瓣量压低，别把画面糊住。 */
    @Override protected void spawnFallingLeavesParticle(net.minecraft.world.level.Level level,BlockPos pos,RandomSource random){
        if(!petals)return;
        if(random.nextInt(12)!=0)return;
        double x=pos.getX()+random.nextDouble();
        double y=pos.getY()-0.05;
        double z=pos.getZ()+random.nextDouble();
        level.addParticle(net.minecraft.core.particles.ParticleTypes.CHERRY_LEAVES,x,y,z,0,0,0);
    }
    @Override public MapCodec<? extends LeavesBlock> codec(){return CODEC;}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder){super.createBlockStateDefinition(builder);builder.add(SEASON);}
    // Native leaves only tick when eligible to decay; supported and placed leaves must also change season.
    @Override protected boolean isRandomlyTicking(BlockState state){return true;}
    @Override protected boolean skipRendering(BlockState state,BlockState neighbor,net.minecraft.core.Direction direction){
        return neighbor.getBlock() instanceof SeasonalLeavesBlock||super.skipRendering(state,neighbor,direction);
    }
    @Override protected void randomTick(BlockState state,ServerLevel level,BlockPos pos,RandomSource random){
        if(level.dimension().equals(LogosDimension.CLOUDSEA)&&!CloudseaBlocks.isEvergreen(this)){
            int season=JourneySeasons.of(level);if(state.getValue(SEASON)!=season)level.setBlock(pos,state.setValue(SEASON,season),Block.UPDATE_CLIENTS);
        }
    }
}
