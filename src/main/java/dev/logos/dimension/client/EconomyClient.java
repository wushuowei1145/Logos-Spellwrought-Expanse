package dev.logos.dimension.client;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.economy.Currency;
import dev.logos.dimension.economy.EconomyActionPayload;
import dev.logos.dimension.economy.FxMarket;
import dev.logos.dimension.economy.MarketPayload;
import java.util.ArrayList;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

/** 客户端只缓存最新一份行情快照；面板每帧读它，服务端每五秒推一次。 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class EconomyClient {
    static MarketPayload latest;

    @SubscribeEvent static void payloads(RegisterClientPayloadHandlersEvent event) {
        event.register(MarketPayload.TYPE, (p, context) -> {
            latest = p;
            var mc = Minecraft.getInstance();
            switch (p.screen()) {
                case MarketPayload.EXCHANGE -> mc.setScreen(new ExchangeScreen(p.pos()));
                case MarketPayload.WALLET -> mc.setScreen(new WalletScreen(p.hand()));
                default -> {}
            }
        });
    }

    static void send(int action, int hand, int a, int b, long amount) {
        ClientPacketDistributor.sendToServer(new EconomyActionPayload(action, hand, a, b, amount));
    }

    static double rate(Currency c) { return latest == null ? c.anchor : latest.rate(c); }

    /** 「第 3 天 14:00」：主世界时钟 0 是早上六点。short 用在消息列表里，写成「3日14:00」。 */
    static Component when(long clock, boolean short_) {
        long day = Math.floorDiv(clock, FxMarket.DAY);
        long hour = (Math.floorMod(clock, FxMarket.DAY) / FxMarket.HOUR + 6) % 24;
        return Component.translatable(short_ ? "screen.logos_dimension.fx.when_short" : "screen.logos_dimension.fx.when", day, String.format("%02d:00", hour));
    }

    /** 一条消息的完整文字：来源标签加正文。参数里的货币键翻译成名字。 */
    static MutableComponent news(FxMarket.News n) {
        var args = new ArrayList<Object>();
        for (var a : n.args()) args.add(a.startsWith("currency.") ? Component.translatable(a) : a);
        var tier = Component.translatable("fx.tier." + n.tier()).withStyle(switch (n.tier()) {
            case 0 -> ChatFormatting.GOLD; case 1 -> ChatFormatting.AQUA; case 2 -> ChatFormatting.GRAY; default -> ChatFormatting.DARK_GRAY;
        });
        return Component.empty().append(tier).append(" ").append(Component.translatable(n.key(), args.toArray()));
    }

    private EconomyClient() {}
}
