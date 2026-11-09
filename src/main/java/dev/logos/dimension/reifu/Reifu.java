package dev.logos.dimension.reifu;

import java.util.EnumMap;
import java.util.Map;

import dev.logos.LogosMod;
import dev.logos.dimension.DimensionBlocks;
import dev.logos.item.PageTier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BlockEntityTypeAddBlocksEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * 霊符与符札书案：Logos 术式载页和法式编纂台的和风变体，只换名字和外观，用法、界面、数值完全一样。
 *
 * <p>每个载页品级各有一对霊符（空白 / 写好），品级、稀有度、叠放数与同级载页相同。符札书案继承编纂台，
 * 并登记进编纂台的方块实体类型，所以 Logos 里认编纂台的地方（放页、打开工坊、写入、AI Player）都认它。
 */
public final class Reifu {
    /** 每个品级的空白与写好的霊符。 */
    public static final Map<PageTier, Pair> REIFU = new EnumMap<>(PageTier.class);

    public static final DeferredBlock<FudaDeskBlock> FUDA_DESK = DimensionBlocks.BLOCKS.registerBlock("fuda_desk", FudaDeskBlock::new,
            p -> p.mapColor(MapColor.WOOD).strength(2.5f).noOcclusion().sound(SoundType.WOOD));
    public static final DeferredItem<BlockItem> FUDA_DESK_ITEM = DimensionBlocks.ITEMS.registerSimpleBlockItem("fuda_desk", FUDA_DESK);

    static {
        for (PageTier t : PageTier.values()) {
            // 稀有度分档照抄 Logos 的载页
            Rarity rarity = t.ordinal() >= PageTier.SAGE.ordinal() ? Rarity.EPIC : t.ordinal() >= PageTier.CHALK.ordinal() ? Rarity.RARE
                    : t.ordinal() >= PageTier.CRYSTAL.ordinal() ? Rarity.UNCOMMON : Rarity.COMMON;
            DeferredItem<ReifuItem> inscribed = DimensionBlocks.ITEMS.registerItem(inscribedId(t),
                    p -> new ReifuItem(p, t, null), p -> p.stacksTo(1).rarity(rarity));
            DeferredItem<ReifuItem> blank = DimensionBlocks.ITEMS.registerItem(blankId(t),
                    p -> new ReifuItem(p, t, inscribed), p -> p.rarity(rarity));
            REIFU.put(t, new Pair(blank, inscribed));
        }
    }

    public static String blankId(PageTier t) {
        return t.id + "_reifu";
    }

    public static String inscribedId(PageTier t) {
        return t.id + "_inscribed_reifu";
    }

    public static void register(IEventBus bus) {
        bus.addListener((BlockEntityTypeAddBlocksEvent event) -> event.modify(LogosMod.INSCRIPTION_TABLE_BE.get(), FUDA_DESK.get()));
    }

    /** 一个品级的两件霊符。 */
    public record Pair(DeferredItem<ReifuItem> blank, DeferredItem<ReifuItem> inscribed) {}

    private Reifu() {}
}
