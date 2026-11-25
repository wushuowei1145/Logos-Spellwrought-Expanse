package dev.logos.dimension.ruins;

import dev.logos.dimension.DimensionBlocks;
import dev.logos.dimension.cloudsea.CloudseaBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.shapes.*;
import net.neoforged.neoforge.registries.DeferredBlock;

/** 旅途遗迹共用的旧石、花藤和小灯；全部静态，不增加方块实体或逐刻扫描。 */
public final class WaysideBlocks {
    public static final DeferredBlock<Block> STONE=stone("wayside_carved_stone",0);
    public static final DeferredBlock<Block> MOSS=stone("wayside_moss_stone",0);
    public static final DeferredBlock<Block> INLAY=stone("wayside_rune_tile",3);
    public static final DeferredBlock<Block> BLOSSOM=add("wayside_bloom_vine",DimensionBlocks.BLOCKS.<Block>registerBlock("wayside_bloom_vine",VineBlock::new,
            p->p.mapColor(MapColor.PLANT).strength(.2f).noCollision().noOcclusion().sound(SoundType.VINE).lightLevel(s->1)));
    public static final DeferredBlock<Block> LAMP=add("wayside_jade_lamp",DimensionBlocks.BLOCKS.<Block>registerBlock("wayside_jade_lamp",Lamp::new,
            p->p.mapColor(MapColor.COLOR_LIGHT_BLUE).strength(1.2f).noOcclusion().sound(SoundType.GLASS).lightLevel(s->9)));

    private static DeferredBlock<Block> stone(String name,int light){
        return add(name,DimensionBlocks.BLOCKS.registerSimpleBlock(name,p->p.mapColor(MapColor.STONE).strength(1.8f).sound(SoundType.STONE).lightLevel(s->light)));
    }
    private static DeferredBlock<Block> add(String name,DeferredBlock<Block> block){
        DimensionBlocks.ITEMS.registerSimpleBlockItem(name,block);CloudseaBlocks.PALETTE.add(block);return block;
    }
    private static final class Lamp extends Block implements SimpleWaterloggedBlock {
        private static final BooleanProperty WATERLOGGED=BlockStateProperties.WATERLOGGED;
        private static final VoxelShape SHAPE=Shapes.box(.25,.06,.25,.75,.94,.75);
        Lamp(Properties p){
            super(p);
            registerDefaultState(stateDefinition.any().setValue(WATERLOGGED,false));
        }
        @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){return SHAPE;}

        @Override public BlockState getStateForPlacement(BlockPlaceContext context){
            return defaultBlockState().setValue(WATERLOGGED,context.getLevel().getFluidState(context.getClickedPos()).is(Fluids.WATER));
        }

        @Override protected FluidState getFluidState(BlockState state){
            return state.getValue(WATERLOGGED)?Fluids.WATER.getSource(false):super.getFluidState(state);
        }

        @Override protected BlockState updateShape(BlockState state,LevelReader level,ScheduledTickAccess ticks,BlockPos pos,
                Direction direction,BlockPos neighborPos,BlockState neighborState,RandomSource random){
            if(state.getValue(WATERLOGGED))ticks.scheduleTick(pos,Fluids.WATER,Fluids.WATER.getTickDelay(level));
            return super.updateShape(state,level,ticks,pos,direction,neighborPos,neighborState,random);
        }

        @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder){
            builder.add(WATERLOGGED);
        }
    }
    public static void init(){}
    private WaysideBlocks(){}
}
