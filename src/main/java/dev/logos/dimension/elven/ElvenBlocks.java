package dev.logos.dimension.elven;

import dev.logos.dimension.DimensionBlocks;
import dev.logos.dimension.cloudsea.CloudseaBlocks;
import dev.logos.dimension.cloudsea.SeasonalLeavesBlock;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;

/** 精灵林地的静态材质、家具与方块光源，不创建方块实体。 */
public final class ElvenBlocks {
    public static final DeferredBlock<Block> LOG=add("elven_heartwood",RotatedPillarBlock::new,false,0);
    public static final DeferredBlock<Block> LEAVES=add("elven_canopy",SeasonalLeavesBlock::new,true,0);
    public static final DeferredBlock<Block> PLANKS=add("elven_pale_planks",Block::new,false,0);
    public static final DeferredBlock<Block> INLAY=add("elven_luminous_inlay",Block::new,false,15);
    public static final DeferredBlock<Block> BENCH=add("elven_leaf_seat",ElvenDecorBlock::new,true,0);
    public static final DeferredBlock<Block> DESK=add("elven_reading_table",ElvenDecorBlock::new,true,0);
    public static final DeferredBlock<Block> PLANTER=add("elven_flower_planter",ElvenDecorBlock::new,true,0);
    public static final DeferredBlock<Block> BOOKCASE=add("elven_bookshelf",ElvenBookshelfBlock::new,true,0);
    public static final DeferredBlock<Block> LAMP=add("elven_leaf_lamp",ElvenDecorBlock::new,true,15);
    public static final DeferredBlock<Block> VINE=add("elven_lamp_vine",ElvenDecorBlock::new,true,0);
    private static DeferredBlock<Block> add(String name,java.util.function.Function<net.minecraft.world.level.block.state.BlockBehaviour.Properties,Block> factory,boolean open,int light){
        var block=DimensionBlocks.BLOCKS.<Block>registerBlock(name,factory,p->{
            p.mapColor(MapColor.WOOD).strength(name.equals("elven_canopy")?.2f:1.8f).sound(name.equals("elven_canopy")?SoundType.GRASS:SoundType.WOOD).lightLevel(s->light);
            if(open)p.noOcclusion();
            if(name.equals("elven_canopy"))p.randomTicks();
            if(name.equals("elven_leaf_lamp")||name.equals("elven_lamp_vine"))p.noCollision();
            return p;
        });
        DimensionBlocks.ITEMS.registerSimpleBlockItem(name,block);CloudseaBlocks.PALETTE.add(block);return block;
    }
    public static void init(){}
    private ElvenBlocks(){}
}
