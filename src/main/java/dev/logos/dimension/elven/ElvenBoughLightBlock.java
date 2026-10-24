package dev.logos.dimension.elven;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.*;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.*;

/** 可长在土壤或木质枝条上的叶辉；照明恒定，呼吸光晕在客户端绘制。 */
public final class ElvenBoughLightBlock extends Block {
    public static final MapCodec<ElvenBoughLightBlock> CODEC=simpleCodec(ElvenBoughLightBlock::new);
    public ElvenBoughLightBlock(Properties p){super(p);}
    @Override public MapCodec<ElvenBoughLightBlock> codec(){return CODEC;}
    @Override protected VoxelShape getShape(BlockState s,BlockGetter l,BlockPos p,CollisionContext c){return Block.box(4,0,4,12,14,12);}
    @Override protected boolean canSurvive(BlockState s,LevelReader l,BlockPos p){
        var below=l.getBlockState(p.below());
        if(below.is(BlockTags.SUPPORTS_VEGETATION))return true;
        for(var direction:Direction.values()){
            var support=l.getBlockState(p.relative(direction));
            if(support.is(BlockTags.LOGS)||support.is(BlockTags.LEAVES))return true;
        }
        return false;
    }
    @Override protected BlockState updateShape(BlockState s,LevelReader l,ScheduledTickAccess ticks,BlockPos p,Direction direction,BlockPos other,BlockState neighbor,RandomSource random){
        if(!s.canSurvive(l,p))return Blocks.AIR.defaultBlockState();
        return super.updateShape(s,l,ticks,p,direction,other,neighbor,random);
    }
}
