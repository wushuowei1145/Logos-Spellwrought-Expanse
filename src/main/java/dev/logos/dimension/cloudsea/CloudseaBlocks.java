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
    public static final EnumMap<CloudseaBiome,DeferredBlock<Block>> GROUND=new EnumMap<>(CloudseaBiome.class),LEAVES=new EnumMap<>(CloudseaBiome.class),TRUNKS=new EnumMap<>(CloudseaBiome.class);
    public static final DeferredBlock<Block> PORCELAIN=solid("cloudsea_porcelain",MapColor.QUARTZ,SoundType.CALCITE,0);
    public static final DeferredBlock<Block> MANTLE=solid("cloudsea_mantle",MapColor.COLOR_LIGHT_BLUE,SoundType.STONE,0);
    public static final DeferredBlock<Block> STEM=wood("cloudsea_stem"),LANTERN=flora("cloudsea_lantern_bud",8);
    public static final DeferredBlock<Block> ROOT=flora("cloudsea_hanging_root",4),EYE_FERN=flora("cloudsea_eye_fern",4),DEMON_REED=flora("cloudsea_demon_reed",6);
    public static final DeferredBlock<Block> DEMONSTONE=solid("cloudsea_demonstone",MapColor.COLOR_PURPLE,SoundType.STONE,1);
    public static final DeferredBlock<Block> IVORY_BRICKS=solid("cloudsea_ivory_bricks",MapColor.QUARTZ,SoundType.STONE,0);
    public static final DeferredBlock<Block> DARK_TIMBER=solid("cloudsea_dark_timber",MapColor.COLOR_BROWN,SoundType.WOOD,0);
    public static final DeferredBlock<Block> SLATE=solid("cloudsea_blue_slate",MapColor.COLOR_BLUE,SoundType.STONE,0);
    public static final DeferredBlock<Block> WINDOW=solid("cloudsea_lit_window",MapColor.SAND,SoundType.GLASS,9);
    public static final DeferredBlock<Block> FERN=flora("journey_woodland_fern",0),BRACKEN=flora("journey_bracken",0),BLUEBELL=flora("journey_bluebell",0),FOXGLOVE=flora("journey_foxglove",0),LUPINE=flora("journey_lupine",0),MEADOW_GRASS=flora("journey_meadow_grass",0),REED=flora("journey_river_reed",0);
    public static final DeferredBlock<Block> GRAPEVINE=grapevine();
    public static final net.neoforged.neoforge.registries.DeferredItem<net.minecraft.world.item.Item> GRAPES=DimensionBlocks.ITEMS.registerSimpleItem("journey_grapes",p->p.food(new net.minecraft.world.food.FoodProperties.Builder().nutrition(2).saturationModifier(.2f).build()));
    public static final net.neoforged.neoforge.registries.DeferredItem<net.minecraft.world.item.Item> GRAPE_LEAF=DimensionBlocks.ITEMS.registerSimpleItem("journey_grape_leaf");
    public static final DeferredBlock<Block> ANEMONE=seasonal("journey_silver_anemone"),MOONBELL=seasonal("journey_moonbell"),GLOWCAP=seasonal("journey_glowcap"),HEATHER=seasonal("journey_heather"),STAGHORN=seasonal("journey_amber_fern"),HELLEBORE=seasonal("journey_winter_hellebore"),FIREWEED=seasonal("journey_fireweed"),BRAMBLE=seasonal("journey_berry_bramble");
    public static final List<DeferredBlock<Block>> SEASONAL_PLANTS=List.of(ANEMONE,MOONBELL,GLOWCAP,HEATHER,STAGHORN,HELLEBORE,FIREWEED,BRAMBLE);
    public static final DeferredBlock<Block> LIMESTONE=solid("journey_limestone",MapColor.QUARTZ,SoundType.STONE,0),PLASTER=solid("journey_lime_plaster",MapColor.SAND,SoundType.STONE,0),TIMBER_FRAME=solid("journey_timber_frame",MapColor.COLOR_BROWN,SoundType.WOOD,0),ROOF_TILE=solid("journey_roof_tile",MapColor.TERRACOTTA_RED,SoundType.STONE,0);
    static { for(var b:CloudseaBiome.values()){
        var ground=DimensionBlocks.BLOCKS.<Block>registerBlock(b.id()+"_ground",SeasonalTurfBlock::new,p->p.mapColor(MapColor.GRASS).strength(.6f).sound(SoundType.GRASS).randomTicks());
        DimensionBlocks.ITEMS.registerSimpleBlockItem(b.id()+"_ground",ground);PALETTE.add(ground);GROUND.put(b,ground);
        LEAVES.put(b,canopy(b.id()+"_leaf"));
        TRUNKS.put(b,wood(b.id()+"_log"));
    } }
    public static boolean isEvergreen(Block block){for(var b:CloudseaBiome.values())if(b.conifer()&&LEAVES.get(b).get()==block)return true;return false;}
    private static DeferredBlock<Block> wood(String id){
        var block=DimensionBlocks.BLOCKS.<Block>registerBlock(id,RotatedPillarBlock::new,p->p.mapColor(MapColor.COLOR_BROWN).strength(2f).sound(SoundType.WOOD));
        DimensionBlocks.ITEMS.registerSimpleBlockItem(id,block);PALETTE.add(block);return block;
    }
    private static DeferredBlock<Block> grapevine(){
        var block=DimensionBlocks.BLOCKS.<Block>registerBlock("journey_grapevine",GrapeVineBlock::new,p->p.mapColor(MapColor.PLANT).strength(.2f).sound(SoundType.VINE).noCollision().noOcclusion().randomTicks().replaceable());
        DimensionBlocks.ITEMS.registerSimpleBlockItem("journey_grapevine",block);PALETTE.add(block);return block;
    }
    private static DeferredBlock<Block> canopy(String id){
        var block=DimensionBlocks.BLOCKS.<Block>registerBlock(id,SeasonalLeavesBlock::new,p->p.mapColor(MapColor.PLANT).strength(.2f).sound(SoundType.GRASS).noOcclusion().randomTicks());
        DimensionBlocks.ITEMS.registerSimpleBlockItem(id,block);PALETTE.add(block);return block;
    }
    private static DeferredBlock<Block> solid(String id,MapColor color,SoundType sound,int light) {
        var block=DimensionBlocks.BLOCKS.registerSimpleBlock(id,p->p.mapColor(color).strength(1.5f).sound(sound).lightLevel(s->light));
        DimensionBlocks.ITEMS.registerSimpleBlockItem(id,block);PALETTE.add(block);return block;
    }
    private static DeferredBlock<Block> flora(String id,int light) {
        var block=DimensionBlocks.BLOCKS.<Block>registerBlock(id,JourneyPlantBlock::new,p->p.mapColor(MapColor.PLANT).strength(.25f).sound(SoundType.GRASS).noCollision().noOcclusion().randomTicks().lightLevel(s->light));
        DimensionBlocks.ITEMS.registerSimpleBlockItem(id,block);PALETTE.add(block);return block;
    }
    private static DeferredBlock<Block> seasonal(String id){
        var block=DimensionBlocks.BLOCKS.<Block>registerBlock(id,SeasonalPlantBlock::new,p->p.mapColor(MapColor.PLANT).strength(.15f).sound(SoundType.GRASS).noCollision().noOcclusion().randomTicks()
                .lightLevel(s->{int season=s.getValue(SeasonalLeavesBlock.SEASON);return id.endsWith("glowcap")&&(season==1||season==2)?4:id.endsWith("moonbell")&&season<2?2:id.endsWith("hellebore")&&season==3?3:0;}));
        DimensionBlocks.ITEMS.registerSimpleBlockItem(id,block);PALETTE.add(block);return block;
    }
    public static void init() {}
    private CloudseaBlocks() {}
}
