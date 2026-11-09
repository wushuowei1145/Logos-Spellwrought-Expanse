package dev.logos.dimension.reifu;

import java.util.function.Supplier;

import dev.logos.item.PageTier;
import dev.logos.item.SpellPageItem;
import net.minecraft.world.item.Item;

/**
 * 霊符（れいふ）：和风外观的术式载页。品级、承载、回收、施放、挂杖、放进典籍、上编纂台，全部就是 Logos 的术式载页，
 * 只有名字和样子不同；空白的写上术式后变成同品级写好的霊符（{@link #writtenForm}），不会变回普通载页。
 */
public class ReifuItem extends SpellPageItem {
    private final Supplier<? extends Item> written;

    /** {@code written}：空白霊符写好后变成的物品；写好的霊符传 null，写了还是自己。 */
    public ReifuItem(Properties properties, PageTier tier, Supplier<? extends Item> written) {
        super(properties, tier, written == null);
        this.written = written;
    }

    @Override
    public Item writtenForm() {
        return written == null ? this : written.get();
    }
}
