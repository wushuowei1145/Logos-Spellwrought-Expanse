package dev.logos.dimension.taixuan;

import dev.logos.dimension.DimensionBlocks;
import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.registries.DeferredBlock;

/**
 * 太玄灵境专用构件：徽式花窗、云纹砖雕，黄铜筒瓦一套（瓦、楼梯、脊瓦）、黄铜块、玄铁，以及深渊里的渊雾。
 * 黄铜取偏黄的铜色，原版铜块太红。
 */
public final class TaixuanBlocks {
    public static final DeferredBlock<TaixuanWindowBlock> LATTICE = register("taixuan_lattice_window", TaixuanWindowBlock::new,
        p -> p.mapColor(MapColor.COLOR_BROWN).strength(1.5f).sound(SoundType.WOOD).noOcclusion());
    public static final DeferredBlock<Block> CARVED_BRICK = register("taixuan_carved_brick", Block::new,
        p -> p.mapColor(MapColor.COLOR_LIGHT_GRAY).strength(1.8f).sound(SoundType.STONE));
    /** 筒瓦按轴放，瓦垄顺着坡往下走：南北两坡用 z，东西两坡用 x。 */
    public static final DeferredBlock<RotatedPillarBlock> BRASS_TILES = register("taixuan_brass_tiles", RotatedPillarBlock::new,
        p -> p.mapColor(MapColor.GOLD).strength(3f, 6f).sound(SoundType.COPPER).requiresCorrectToolForDrops());
    public static final DeferredBlock<StairBlock> BRASS_TILE_STAIRS = register("taixuan_brass_tile_stairs",
        p -> new StairBlock(BRASS_TILES.get().defaultBlockState(), p),
        p -> p.mapColor(MapColor.GOLD).strength(3f, 6f).sound(SoundType.COPPER).requiresCorrectToolForDrops());
    public static final DeferredBlock<SlabBlock> BRASS_RIDGE_SLAB = register("taixuan_brass_ridge_slab", SlabBlock::new,
        p -> p.mapColor(MapColor.GOLD).strength(3f, 6f).sound(SoundType.COPPER).requiresCorrectToolForDrops());
    public static final DeferredBlock<Block> BRASS_BLOCK = register("taixuan_brass_block", Block::new,
        p -> p.mapColor(MapColor.GOLD).strength(3f, 6f).sound(SoundType.COPPER).requiresCorrectToolForDrops());
    /** 铁链的链环、链台的包边。 */
    /** 玄铁：深渊的铁链、链台的铁桩和八卦。微微发光（7 级）：深渊在浮岛底下照不到天光，不发光的话铁链只剩一团黑影，链台也是黑的。 */
    public static final DeferredBlock<Block> DARK_IRON = register("taixuan_dark_iron", Block::new,
        p -> p.mapColor(MapColor.COLOR_GRAY).strength(5f, 6f).sound(SoundType.METAL).requiresCorrectToolForDrops().lightLevel(s -> 7));
    /**
     * 深渊里的雾：穿得过、点不中、不挡光，外面看是一片云面。按全亮画（不发光、不改光照）：
     * 深渊底下照不到天光，按实际亮度画出来是一片灰石头，看不出是雾。
     */
    public static final DeferredBlock<TaixuanMistBlock> ABYSS_MIST = register("taixuan_abyss_mist", TaixuanMistBlock::new,
        p -> p.mapColor(MapColor.SNOW).sound(SoundType.WOOL).noCollision().noOcclusion().replaceable().noLootTable()
            .emissiveRendering((s, l, pos) -> true)
            .pushReaction(PushReaction.DESTROY).isViewBlocking((s, l, pos) -> false).isSuffocating((s, l, pos) -> false)
            .isValidSpawn((s, l, pos, type) -> false).isRedstoneConductor((s, l, pos) -> false));
    public static final List<DeferredBlock<?>> ALL = List.of(LATTICE, CARVED_BRICK, BRASS_TILES, BRASS_TILE_STAIRS,
        BRASS_RIDGE_SLAB, BRASS_BLOCK, DARK_IRON, ABYSS_MIST);

    private static <T extends Block> DeferredBlock<T> register(String name, Function<Block.Properties, T> factory,
            UnaryOperator<Block.Properties> properties) {
        var block = DimensionBlocks.BLOCKS.registerBlock(name, factory, properties);
        DimensionBlocks.ITEMS.registerSimpleBlockItem(name, block);
        return block;
    }
    public static void init() {}
    private TaixuanBlocks() {}
}
