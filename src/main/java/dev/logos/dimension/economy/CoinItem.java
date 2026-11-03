package dev.logos.dimension.economy;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

/** 一枚实体钱币。价值就是面额，汇率只在兑换和结账时起作用。银元另有一个彩蛋，见 {@link Railgun}。 */
public final class CoinItem extends Item {
    private static final int USE_TICKS = 72000;
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
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        return Railgun.coin(player.getItemInHand(hand)) ? Railgun.use(level, player, hand) : super.use(level, player, hand);
    }

    @Override public int getUseDuration(ItemStack stack, LivingEntity user) { return Railgun.coin(stack) ? USE_TICKS : 0; }

    @Override public ItemUseAnimation getUseAnimation(ItemStack stack) { return ItemUseAnimation.NONE; }

    @Override
    public void onUseTick(Level level, LivingEntity user, ItemStack stack, int remaining) {
        if (Railgun.coin(stack)) Railgun.tick(level, user, stack, USE_TICKS - remaining);
    }

    @Override
    public boolean releaseUsing(ItemStack stack, Level level, LivingEntity user, int remaining) {
        if (Railgun.coin(stack)) Railgun.release(user, USE_TICKS - remaining);
        return false;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
        out.accept(Component.translatable(currency.key()).withStyle(ChatFormatting.GOLD));
        var smallest = currency.denominations.getFirst();
        if (denomination != smallest) out.accept(Component.translatable("tooltip.logos_dimension.coin.worth",
                denomination.value(), Component.translatable(currency.coinKey(smallest))).withStyle(ChatFormatting.GRAY));
    }
}
