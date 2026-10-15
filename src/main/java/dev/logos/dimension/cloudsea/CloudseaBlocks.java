package dev.logos.dimension.cloudsea;

import dev.logos.dimension.DimensionBlocks;
import java.util.EnumMap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;

/** Decorative materials and living flora only; this catalogue deliberately registers no ores or entities. */
public final class CloudseaBlocks {
    public static final List<DeferredBlock<Block>> PALETTE=new ArrayList<>();
    public static final EnumMap<CloudseaBiome,DeferredBlock<Block>> GROUND=new EnumMap<>(CloudseaBiome.class),LEAVES=new EnumMap<>(CloudseaBiome.class);
    public static final DeferredBlock<Block> PORCELAIN=solid("cloudsea_porcelain",MapColor.QUARTZ,SoundType.CALCITE,0);
    public static final DeferredBlock<Block> MANTLE=solid("cloudsea_mantle",MapColor.COLOR_LIGHT_BLUE,SoundType.STONE,0);
    public static final DeferredBlock<Block> STEM=wood("cloudsea_stem"),LANTERN=flora("cloudsea_lantern_bud",8);
    public static final DeferredBlock<Block> ROOT=flora("cloudsea_hanging_root",4),EYE_FERN=flora("cloudsea_eye_fern",4),DEMON_REED=flora("cloudsea_demon_reed",6);
    public static final DeferredBlock<Block> DEMONSTONE=solid("cloudsea_demonstone",MapColor.COLOR_PURPLE,SoundType.STONE,1);
    public static final DeferredBlock<Block> IVORY_BRICKS=solid("cloudsea_ivory_bricks",MapColor.QUARTZ,SoundType.STONE,0);
    public static final DeferredBlock<Block> DARK_TIMBER=solid("cloudsea_dark_timber",MapColor.COLOR_BROWN,SoundType.WOOD,0);
    public static final DeferredBlock<Block> SLATE=solid("cloudsea_blue_slate",MapColor.COLOR_BLUE,SoundType.STONE,0);
    public static final DeferredBlock<Block> WINDOW=solid("cloudsea_lit_window",MapColor.SAND,SoundType.GLASS,9);
    static { for(var b:CloudseaBiome.values()){GROUND.put(b,solid(b.id()+"_ground",MapColor.COLOR_CYAN,SoundType.STONE,0));LEAVES.put(b,canopy(b.id()+"_leaf"));} }
    private static DeferredBlock<Block> wood(String id){
        var block=DimensionBlocks.BLOCKS.<Block>registerBlock(id,RotatedPillarBlock::new,p->p.mapColor(MapColor.COLOR_BROWN).strength(2f).sound(SoundType.WOOD));
        DimensionBlocks.ITEMS.registerSimpleBlockItem(id,block);PALETTE.add(block);return block;
    }
    private static DeferredBlock<Block> canopy(String id){
        var block=DimensionBlocks.BLOCKS.<Block>registerBlock(id,TransparentBlock::new,p->p.mapColor(MapColor.COLOR_CYAN).strength(.2f).sound(SoundType.GRASS).noOcclusion());
        DimensionBlocks.ITEMS.registerSimpleBlockItem(id,block);PALETTE.add(block);return block;
    }
    private static DeferredBlock<Block> solid(String id,MapColor color,SoundType sound,int light) {
        var block=DimensionBlocks.BLOCKS.registerSimpleBlock(id,p->p.mapColor(color).strength(1.5f).sound(sound).lightLevel(s->light));
        DimensionBlocks.ITEMS.registerSimpleBlockItem(id,block);PALETTE.add(block);return block;
    }
    private static DeferredBlock<Block> flora(String id,int light) {
        var block=DimensionBlocks.BLOCKS.registerSimpleBlock(id,p->p.mapColor(MapColor.COLOR_CYAN).strength(.25f).sound(SoundType.GRASS).noCollision().noOcclusion().lightLevel(s->light));
        DimensionBlocks.ITEMS.registerSimpleBlockItem(id,block);PALETTE.add(block);return block;
    }
    public static void init() {}
    private CloudseaBlocks() {}
}
