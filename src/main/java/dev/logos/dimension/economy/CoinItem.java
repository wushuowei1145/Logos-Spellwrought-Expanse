package dev.logos.dimension.economy;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/** 一枚实体钱币。价值就是面额，汇率只在兑换和结账时起作用。 */
public final class CoinItem extends Item {
    public final Currency currency;
    public final Currency.Denomination denomination;

    public CoinItem(Properties properties, Currency currency, Currency.Denomination denomination) {
        super(properties);
        this.currency = currency;
        this.denomination = denomination;
    }

    /** 一叠钱币一共值多少个最小单位。 */
    public long value(ItemStack stack) { return denomination.value() * stack.getCount(); }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
        out.accept(Component.translatable(currency.key()).withStyle(ChatFormatting.GOLD));
        var smallest = currency.denominations.getFirst();
        if (denomination != smallest) out.accept(Component.translatable("tooltip.logos_dimension.coin.worth",
                denomination.value(), Component.translatable(currency.coinKey(smallest))).withStyle(ChatFormatting.GRAY));
    }
}
