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
        event.register(dev.logos.dimension.companion.CompanionGuard.Saved.TYPE, (payload, context) -> context.enqueueWork(CompanionTalkClient::saved));
        // 藏着的词（狐火之言、龙炎之言）：编纂台在客户端编图、列卡片，问本机玩家学会了没有
        for (dev.logos.dimension.magic.HiddenWord w : dev.logos.dimension.magic.HiddenWord.all())
            w.viewerKnows = () -> Minecraft.getInstance().player != null && w.knows(Minecraft.getInstance().player);
    }

    @SubscribeEvent
    static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        hideGui(false);
    }

    /** 形代替身挡下了致命一击：同原版不死图腾，眼前转着飞出一张形代（写着你的名字）。花瓣由服务端在形代燃尽的地方放。 */
    static void saved() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.gameRenderer.displayItemActivation(dev.logos.dimension.companion.KatashiroItem.bound(mc.player));
        savedShown++;
    }

    /** 测试用：画过几次。 */
    private static int savedShown;

    public static int savedShown() {
        return savedShown;
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
