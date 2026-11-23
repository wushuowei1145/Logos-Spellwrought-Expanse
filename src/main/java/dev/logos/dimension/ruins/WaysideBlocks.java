package dev.logos.dimension.ruins;

import dev.logos.dimension.DimensionBlocks;
import dev.logos.dimension.cloudsea.CloudseaBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
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
    private static final class Lamp extends Block {
        private static final VoxelShape SHAPE=Shapes.box(.25,.06,.25,.75,.94,.75);
        Lamp(Properties p){super(p);}
        @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){return SHAPE;}
    }
    public static void init(){}
    private WaysideBlocks(){}
}
