package dev.logos.dimension.client.companion;

import com.mojang.blaze3d.platform.InputConstants;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.companion.CompanionBook;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

/**
 * 客户端这边的友人帐（{@link CompanionBook}）：按键（默认 K，「同伴」一栏里可以改）问服务端要每一页，回来了就翻开；
 * 叫出来、收回以后服务端回一份新的，合上友人帐好看她出来。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class CompanionBookClient {
    public static final KeyMapping.Category CATEGORY = new KeyMapping.Category(LogosDimension.id("companions"));
    public static final KeyMapping KEY = new KeyMapping("key.logos_dimension.companion_book", InputConstants.KEY_K, CATEGORY);

    private CompanionBookClient() {}

    @SubscribeEvent
    static void keys(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        event.register(KEY);
    }

    @SubscribeEvent
    static void packets(RegisterClientPayloadHandlersEvent event) {
        event.register(CompanionBook.State.TYPE, (payload, context) -> context.enqueueWork(() -> receive(payload)));
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        while (KEY.consumeClick()) {
            if (mc.player != null && mc.screen == null) open();
        }
    }

    /** 问服务端要每一页（回来了就翻开）；测试也用。 */
    public static void open() {
        ClientPacketDistributor.sendToServer(new CompanionBook.Ask(CompanionBook.OPEN_BOOK, ""));
    }

    static void call(String companion) {
        ClientPacketDistributor.sendToServer(new CompanionBook.Ask(CompanionBook.CALL, companion));
    }

    static void receive(CompanionBook.State s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof CompanionBookScreen book) {
            if (s.done()) mc.setScreen(null);
            else book.update(s.pages());
            return;
        }
        if (s.opens() && mc.screen == null) mc.setScreen(new CompanionBookScreen(s.pages()));
    }
}
