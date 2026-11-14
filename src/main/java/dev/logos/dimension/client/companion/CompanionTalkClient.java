package dev.logos.dimension.client.companion;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.companion.CompanionTalk;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

/**
 * 客户端这边的对话（docs/SHIRAUME_COMPANION.md 第 8.13 节）：服务端说开就打开对话，说话、档案更新时交给开着的对话和面板，
 * 说结束就关掉。说着话时把状态栏藏起来（像过场一样），关掉时照原样放回去。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class CompanionTalkClient {
    private static boolean hidden, before;

    private CompanionTalkClient() {}

    @SubscribeEvent
    static void packets(RegisterClientPayloadHandlersEvent event) {
        event.register(CompanionTalk.Update.TYPE, (payload, context) -> context.enqueueWork(() -> receive(payload)));
    }

    @SubscribeEvent
    static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        hideGui(false);
    }

    static void receive(CompanionTalk.Update u) {
        Minecraft mc = Minecraft.getInstance();
        CompanionTalkScreen talk = mc.screen instanceof CompanionTalkScreen t ? t
                : mc.screen instanceof CompanionPanelScreen p ? p.parent() : null;
        if (talk != null && talk.sheet().entity() != u.sheet().entity()) talk = null;
        if (u.ends()) {
            if (mc.screen instanceof CompanionPanelScreen p && talk != null) p.end();
            else if (talk != null) talk.end();
            return;
        }
        if (u.opens()) {
            if (mc.screen instanceof CompanionTalkScreen old) old.replaced();
            if (mc.screen == null || mc.screen instanceof CompanionTalkScreen || mc.screen instanceof CompanionPanelScreen) {
                mc.setScreen(new CompanionTalkScreen(u.sheet(), u.lines()));
            }
            return;
        }
        if (talk != null) talk.receive(u.sheet(), u.lines());
    }

    /** 藏起、放回状态栏（只放回自己藏的）。 */
    static void hideGui(boolean hide) {
        var options = Minecraft.getInstance().options;
        if (hide && !hidden) {
            before = options.hideGui;
            options.hideGui = true;
            hidden = true;
        } else if (!hide && hidden) {
            options.hideGui = before;
            hidden = false;
        }
    }
}
