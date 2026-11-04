package dev.logos.dimension.economy;

import dev.logos.dimension.LogosDimension;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 屏幕角落的行情条：三种可炒货币的中间价和一天前的收盘价，持仓的浮动盈亏和最危险那笔的净值比例
 * （净值 ÷ 保证金，1 以上安全，跌近 0.2 就要爆仓；没有持仓时为 −1）。只发给打开了行情条、身上带着钱囊的玩家。
 */
public record TickerPayload(double[] rates, double[] dayAgo, double pnl, double worst, int positions) implements CustomPacketPayload {
    public static final Type<TickerPayload> TYPE = new Type<>(LogosDimension.id("fx_ticker"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TickerPayload> CODEC = StreamCodec.of((b, p) -> {
        for (int i = 0; i < 3; i++) { b.writeDouble(p.rates[i]); b.writeDouble(p.dayAgo[i]); }
        b.writeDouble(p.pnl); b.writeDouble(p.worst); b.writeVarInt(p.positions);
    }, b -> {
        double[] r = new double[3], d = new double[3];
        for (int i = 0; i < 3; i++) { r[i] = b.readDouble(); d[i] = b.readDouble(); }
        return new TickerPayload(r, d, b.readDouble(), b.readDouble(), b.readVarInt());
    });

    public static TickerPayload of(FxMarket market, BankAccount account) {
        double[] r = new double[3], d = new double[3];
        for (int i = 0; i < 3; i++) {
            var c = Currency.TRADED.get(i);
            r[i] = market.rate(c);
            var candles = market.candles(c);
            d[i] = candles.isEmpty() ? r[i] : candles.get(Math.max(0, candles.size() - 13)).close();
        }
        double pnl = 0, worst = -1;
        for (var p : account.positions) {
            double rate = market.rate(p.currency());
            pnl += Margin.pnl(p, rate) + p.accrued();
            double ratio = Margin.equity(p, rate) / Math.max(1, p.margin());
            worst = worst < 0 ? ratio : Math.min(worst, ratio);
        }
        return new TickerPayload(r, d, pnl, worst, account.positions.size());
    }

    @Override public Type<TickerPayload> type() { return TYPE; }
}
