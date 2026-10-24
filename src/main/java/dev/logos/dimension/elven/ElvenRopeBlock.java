package dev.logos.dimension.elven;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.*;

/** 自上而下悬挂的绳梯；攀爬和潜行停留使用原版 climbable 标签。 */
public final class ElvenRopeBlock extends Block {
    public static final MapCodec<ElvenRopeBlock> CODEC=simpleCodec(ElvenRopeBlock::new);
    public ElvenRopeBlock(Properties p){super(p);}
    @Override public MapCodec<ElvenRopeBlock> codec(){return CODEC;}
    @Override protected VoxelShape getShape(BlockState s,BlockGetter l,BlockPos p,CollisionContext c){return Block.box(3,0,6,13,16,10);}
    @Override protected boolean canSurvive(BlockState s,LevelReader l,BlockPos p){
        var top=p.above();var support=l.getBlockState(top);
        return support.is(this)||support.isFaceSturdy(l,top,Direction.DOWN);
    }
    @Override protected BlockState updateShape(BlockState s,LevelReader l,ScheduledTickAccess ticks,BlockPos p,Direction direction,BlockPos other,BlockState neighbor,RandomSource random){
        if(direction==Direction.UP&&!s.canSurvive(l,p))return Blocks.AIR.defaultBlockState();
        return super.updateShape(s,l,ticks,p,direction,other,neighbor,random);
    }
}
