package dev.logos.dimension.cloudsea;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.net.EmaWritePayload;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 绘马：拿在手上右键打开绘马书写页，写下的愿望像书与笔一样存在物品上，可以反复改写。
 * 写好的绘马右键绘马挂挂上去；空白绘马叠 16 块，写在一叠上时只取出一块写。
 */
public class EmaItem extends Item {
    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, LogosDimension.ID);
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<EmaWish>> WISH = COMPONENTS.registerComponentType(
            "ema_wish", b -> b.persistent(EmaWish.CODEC).networkSynchronized(EmaWish.STREAM_CODEC));

    public EmaItem(Properties properties) {
        super(properties);
    }

    public static void register(IEventBus bus) {
        COMPONENTS.register(bus);
        bus.addListener((RegisterPayloadHandlersEvent event) ->
                event.registrar("1").playToServer(EmaWritePayload.TYPE, EmaWritePayload.CODEC, EmaItem::handleWrite));
    }

    public static boolean isWritten(ItemStack stack) {
        return stack.has(WISH.get());
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            // 界面类只在客户端存在：经静态入口打开，本类不出现任何客户端类型，专用服务器才能加载
            dev.logos.dimension.client.EmaScreen.openWrite(player, hand);
        }
        return InteractionResult.SUCCESS;
    }

    private static void handleWrite(EmaWritePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            InteractionHand hand = payload.hand() == 0 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
            ItemStack stack = player.getItemInHand(hand);
            String text = payload.text().strip();
            if (!(stack.getItem() instanceof EmaItem) || !EmaWish.fits(text)) return;
            if (text.isEmpty()) {
                stack.remove(WISH.get());
                return;
            }
            EmaWish wish = new EmaWish(text, player.getName().getString());
            if (stack.getCount() == 1) {
                stack.set(WISH.get(), wish);
            } else {
                ItemStack written = stack.split(1);
                written.set(WISH.get(), wish);
                if (!player.getInventory().add(written)) player.drop(written, false);
            }
            player.level().playSound(null, player.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 0.8f, 1.1f);
        });
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
        EmaWish wish = stack.get(WISH.get());
        if (wish == null) {
            out.accept(Component.translatable("tooltip.logos_dimension.journey_ema.blank").withStyle(ChatFormatting.GRAY));
            return;
        }
        String first = wish.text().lines().findFirst().orElse("");
        if (first.length() > 18) first = first.substring(0, 18) + "…";
        out.accept(Component.literal(first).withStyle(ChatFormatting.GRAY));
        if (!wish.author().isEmpty()) {
            out.accept(Component.translatable("tooltip.logos_dimension.journey_ema.author", wish.author()).withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
