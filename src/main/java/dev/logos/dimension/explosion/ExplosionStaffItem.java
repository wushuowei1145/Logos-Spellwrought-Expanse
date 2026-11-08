package dev.logos.dimension.explosion;

import dev.logos.dimension.DimensionBlocks;
import dev.logos.item.WandItem;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * 爆裂魔杖：只能放一种魔法，爆裂魔法（{@link ExplosionMagic}）。按住右键吟唱，注满后松手释放。
 * <p>继承 Logos 的法杖只是为了让 Logos 记下杖头（模型里的 {@code wand_tip}，就是红色宝珠）在第一、第三人称里画在哪，
 * 蓄力特效挂在那里；没有术式槽，潜行右键也不会打开载页界面。
 */
public final class ExplosionStaffItem extends WandItem {
    public static final DeferredItem<ExplosionStaffItem> ITEM = DimensionBlocks.ITEMS.registerItem("explosion_staff",
            ExplosionStaffItem::new, p -> p.stacksTo(1).rarity(Rarity.EPIC).fireResistant());

    public ExplosionStaffItem(Properties properties) {
        super(properties, 0, 0, 0);
    }

    public static void init() {}

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer sp && !ExplosionMagic.begin(sp, player.getItemInHand(hand), hand)) return InteractionResult.FAIL;
        player.startUsingItem(hand);
        return InteractionResult.CONSUME;
    }

    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remaining) {
        if (entity instanceof ServerPlayer sp) ExplosionMagic.tick(sp, stack);
    }

    @Override
    public boolean releaseUsing(ItemStack stack, Level level, LivingEntity entity, int remaining) {
        return entity instanceof ServerPlayer sp && ExplosionMagic.release(sp);
    }

    @Override
    public void onStopUsing(ItemStack stack, LivingEntity entity, int count) {
        if (entity instanceof ServerPlayer sp) ExplosionMagic.cancel(sp);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return false;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
        builder.accept(Component.translatable("item.logos_dimension.explosion_staff.desc").withStyle(ChatFormatting.RED));
        builder.accept(Component.translatable("item.logos_dimension.explosion_staff.usage", (int) ExplosionMagic.MANA,
                ExplosionMagic.CHARGE_TICKS / 20).withStyle(ChatFormatting.GRAY));
        builder.accept(Component.translatable("item.logos_dimension.explosion_staff.warning").withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    protected String usage() {
        return "按住右键吟唱，蓄满后松开释放";
    }
}
