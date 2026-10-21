package dev.logos.dimension.elven;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.shapes.*;

/** 复用原版双格方块的成对放置和拆除，书架只是装饰，不带物品栏。 */
public final class ElvenBookshelfBlock extends DoublePlantBlock {
    public static final MapCodec<ElvenBookshelfBlock> CODEC=simpleCodec(ElvenBookshelfBlock::new);
    public static final EnumProperty<Direction> FACING=BlockStateProperties.HORIZONTAL_FACING;
    public ElvenBookshelfBlock(Properties p){super(p);registerDefaultState(defaultBlockState().setValue(FACING,Direction.NORTH));}
    @Override public MapCodec<ElvenBookshelfBlock> codec(){return CODEC;}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b){super.createBlockStateDefinition(b);b.add(FACING);}
    @Override public BlockState getStateForPlacement(BlockPlaceContext c){var s=super.getStateForPlacement(c);return s==null?null:s.setValue(FACING,c.getHorizontalDirection().getOpposite());}
    @Override protected boolean canSurvive(BlockState s,LevelReader l,BlockPos p){
        if(s.getValue(HALF)==DoubleBlockHalf.UPPER){var below=l.getBlockState(p.below());return below.is(this)&&below.getValue(HALF)==DoubleBlockHalf.LOWER&&below.getValue(FACING)==s.getValue(FACING);}
        return l.getBlockState(p.below()).isFaceSturdy(l,p.below(),Direction.UP);
    }
    @Override public void setPlacedBy(Level l,BlockPos p,BlockState s,LivingEntity e,ItemStack item){l.setBlock(p.above(),s.setValue(HALF,DoubleBlockHalf.UPPER),Block.UPDATE_ALL);}
    @Override protected BlockState rotate(BlockState s,Rotation r){return s.setValue(FACING,r.rotate(s.getValue(FACING)));}
    @Override protected BlockState mirror(BlockState s,Mirror m){return s.rotate(m.getRotation(s.getValue(FACING)));}
    @Override protected VoxelShape getShape(BlockState s,BlockGetter l,BlockPos p,CollisionContext c){
        return switch(s.getValue(FACING)){case NORTH->Block.box(0,0,5,16,16,16);case SOUTH->Block.box(0,0,0,16,16,11);case EAST->Block.box(0,0,0,11,16,16);default->Block.box(5,0,0,16,16,16);};
    }
}
