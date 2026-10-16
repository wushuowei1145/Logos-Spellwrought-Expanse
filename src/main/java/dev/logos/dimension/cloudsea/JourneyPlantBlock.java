package dev.logos.dimension.cloudsea;

import net.minecraft.core.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.*;

/** Small selectable foliage, with proper support removal rather than a full-cube outline. */
public final class JourneyPlantBlock extends Block {
    private static final VoxelShape SHAPE=Shapes.box(.12,0,.12,.88,.94,.88);
    public JourneyPlantBlock(Properties properties){super(properties);}
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){return SHAPE;}
    @Override protected boolean canSurvive(BlockState state,LevelReader level,BlockPos pos){return level.getBlockState(pos.below()).isFaceSturdy(level,pos.below(),Direction.UP);}
    @Override protected BlockState updateShape(BlockState state,LevelReader level,ScheduledTickAccess ticks,BlockPos pos,Direction direction,BlockPos neighbor,BlockState neighborState,RandomSource random){
        return direction==Direction.DOWN&&!canSurvive(state,level,pos)?Blocks.AIR.defaultBlockState():super.updateShape(state,level,ticks,pos,direction,neighbor,neighborState,random);
    }
}
