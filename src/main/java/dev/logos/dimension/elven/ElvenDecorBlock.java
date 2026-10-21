package dev.logos.dimension.elven;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.phys.shapes.*;

/** 家具按玩家朝向放置，灯与吊藤的碰撞由注册属性关闭。 */
public final class ElvenDecorBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<ElvenDecorBlock> CODEC=simpleCodec(ElvenDecorBlock::new);
    public ElvenDecorBlock(Properties p){super(p);registerDefaultState(defaultBlockState().setValue(FACING,Direction.NORTH));}
    @Override public MapCodec<ElvenDecorBlock> codec(){return CODEC;}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b){b.add(FACING);}
    @Override public BlockState getStateForPlacement(BlockPlaceContext c){return defaultBlockState().setValue(FACING,c.getHorizontalDirection().getOpposite());}
    @Override protected VoxelShape getShape(BlockState s,BlockGetter l,BlockPos p,CollisionContext c){
        String id=BuiltInRegistries.BLOCK.getKey(this).getPath();
        if(id.endsWith("lamp_vine"))return Block.box(7,0,7,9,16,9);
        if(id.endsWith("leaf_lamp"))return Block.box(3,0,3,13,16,13);
        if(id.endsWith("leaf_seat"))return Block.box(1,0,2,15,14,15);
        return Block.box(1,0,1,15,16,15);
    }
}
