package dev.logos.dimension.cloudsea;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.*;

/** Soil-rooted foliage using vanilla vegetation support and NeoForge soil decisions. */
public class JourneyPlantBlock extends VegetationBlock {
    public static final MapCodec<JourneyPlantBlock> CODEC=simpleCodec(JourneyPlantBlock::new);
    private static final VoxelShape SHAPE=Shapes.box(.12,0,.12,.88,.94,.88);
    public JourneyPlantBlock(Properties properties){super(properties);}
    @Override public MapCodec<? extends JourneyPlantBlock> codec(){return CODEC;}
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){return SHAPE;}
    protected boolean ensureSupported(BlockState state,ServerLevel level,BlockPos pos){
        if(state.canSurvive(level,pos))return true;
        // Recheck old-save plants locally, without scanning chunks or rewriting terrain.
        level.destroyBlock(pos,true);return false;
    }
    @Override protected void randomTick(BlockState state,ServerLevel level,BlockPos pos,RandomSource random){ensureSupported(state,level,pos);}
}
