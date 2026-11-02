package dev.logos.dimension.economy;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

/**
 * 万国钱囊：装无限的钱，面板只显示数值。右键打开面板取钱（一枚或一组）、存入背包散币、锁住不想被自动换汇的货币。
 * 在物品栏里把钱币点到钱囊上（或拿着钱囊点钱币）直接存入。死亡照常掉落，防火——想保险就放家里的箱子。
 */
public final class WalletItem extends Item {
    public WalletItem(Properties properties) { super(properties); }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer server) Economy.openWallet(server, hand);
        return InteractionResult.SUCCESS;
    }

    /** 拿着钱囊点一格钱币。 */
    @Override
    public boolean overrideStackedOnOther(ItemStack self, Slot slot, ClickAction action, Player player) {
        if (action != ClickAction.PRIMARY || self.getCount() != 1 || !(slot.getItem().getItem() instanceof CoinItem)) return false;
        if (!slot.allowModification(player)) return false;
        var coins = slot.getItem();
        if (Purse.deposit(self, coins)) { slot.setChanged(); ding(player); }
        return true;
    }

    /** 拿着钱币点钱囊。 */
    @Override
    public boolean overrideOtherStackedOnMe(ItemStack self, ItemStack other, Slot slot, ClickAction action, Player player, SlotAccess carried) {
        if (action != ClickAction.PRIMARY || self.getCount() != 1 || !(other.getItem() instanceof CoinItem)) return false;
        if (!slot.allowModification(player)) return false;
        if (Purse.deposit(self, other)) { slot.setChanged(); ding(player); }
        return true;
    }

    static void ding(Player player) { player.playSound(SoundEvents.CHAIN_PLACE, .6f, 1.6f); }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
        var contents = Purse.contents(stack);
        if (contents.balances().isEmpty()) {
            out.accept(Component.translatable("tooltip.logos_dimension.wallet.empty").withStyle(ChatFormatting.GRAY));
        }
        for (var c : Currency.values()) {
            long amount = contents.get(c);
            if (amount == 0) continue;
            var line = Component.translatable(c.key()).withStyle(ChatFormatting.GOLD).append(" ").append(Money.format(c, amount).copy().withStyle(ChatFormatting.WHITE));
            if (contents.locked().contains(c)) line.append(Component.literal(" 🔒").withStyle(ChatFormatting.DARK_GRAY));
            out.accept(line);
        }
        out.accept(Component.translatable("tooltip.logos_dimension.wallet.use").withStyle(ChatFormatting.DARK_GRAY));
    }
}
