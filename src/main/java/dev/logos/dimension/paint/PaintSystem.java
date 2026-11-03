package dev.logos.dimension.paint;

import com.mojang.serialization.Codec;
import dev.logos.dimension.DimensionBlocks;
import dev.logos.dimension.LogosDimension;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 油漆系统：漆面 + 调色盘 + 画刷 + 颜色数据组件。 */
public final class PaintSystem {
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, LogosDimension.ID);
    private static final DeferredRegister.DataComponents COMPONENTS =
        DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, LogosDimension.ID);

    /** 漆面：贴在方块表面的薄油漆层，无碰撞、不遮挡、即敲即碎。 */
    public static final DeferredBlock<PaintSurfaceBlock> SURFACE = DimensionBlocks.BLOCKS.registerBlock(
        "paint_surface",
        PaintSurfaceBlock::new,
        p -> p.noCollision().noOcclusion().instabreak().sound(SoundType.WOOL)
    );

    /** 可放置的调色盘：盘面显示颜色，画刷右键它蘸色。 */
    public static final DeferredBlock<PaletteBlock> PALETTE = DimensionBlocks.BLOCKS.registerBlock(
        "paint_palette",
        PaletteBlock::new,
        p -> p.mapColor(MapColor.WOOD).strength(1.5f).sound(SoundType.WOOD)
    );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PaintSurfaceBlockEntity>> SURFACE_ENTITY =
        BLOCK_ENTITIES.register("paint_surface",
            () -> new BlockEntityType<>(PaintSurfaceBlockEntity::new, SURFACE.get(), PALETTE.get()));

    /** 颜色数据组件：ARGB int。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> PAINT_COLOR =
        COMPONENTS.registerComponentType("paint_color",
            builder -> builder.persistent(Codec.INT).networkSynchronized(ByteBufCodecs.INT));

    /** 画刷：抄原版刷子模型与动作，右键刷漆。 */
    public static final DeferredItem<Item> BRUSH =
        DimensionBlocks.ITEMS.registerItem("paint_brush", p -> new PaintBrushItem(p.stacksTo(1)));

    /** 调色盘物品：右键空气打开调配页，右键方块放置。 */
    public static final DeferredItem<Item> PALETTE_ITEM =
        DimensionBlocks.ITEMS.registerItem("paint_palette", p -> new PaletteItem(PALETTE.get(), p.stacksTo(1)));

    public static void register(IEventBus bus) {
        BLOCK_ENTITIES.register(bus);
        COMPONENTS.register(bus);
    }

    private PaintSystem() {}
}
