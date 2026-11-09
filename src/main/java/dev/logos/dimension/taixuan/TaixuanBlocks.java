package dev.logos.dimension.taixuan;

import dev.logos.dimension.DimensionBlocks;
import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;

/** 徽式建筑专用构件；薄窗复用已有朝向和含水行为，模型不使用纸窗材质。 */
public final class TaixuanBlocks {
    public static final DeferredBlock<TaixuanWindowBlock> LATTICE = register("taixuan_lattice_window", TaixuanWindowBlock::new,
        p -> p.mapColor(MapColor.COLOR_BROWN).strength(1.5f).sound(SoundType.WOOD).noOcclusion());
    public static final DeferredBlock<Block> CARVED_BRICK = register("taixuan_carved_brick", Block::new,
        p -> p.mapColor(MapColor.COLOR_LIGHT_GRAY).strength(1.8f).sound(SoundType.STONE));
    public static final List<DeferredBlock<?>> ALL = List.of(LATTICE, CARVED_BRICK);

    private static <T extends Block> DeferredBlock<T> register(String name, Function<Block.Properties, T> factory,
            UnaryOperator<Block.Properties> properties) {
        var block = DimensionBlocks.BLOCKS.registerBlock(name, factory, properties);
        DimensionBlocks.ITEMS.registerSimpleBlockItem(name, block);
        return block;
    }
    public static void init() {}
    private TaixuanBlocks() {}
}
