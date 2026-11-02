package dev.logos.dimension.economy;

import dev.logos.dimension.LogosDimension;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 服务端发给客户端的行情快照：各货币中间价、三种可炒货币的 K 线、最近的消息。
 * screen 决定客户端收到后做什么：打开兑换台、打开钱囊，或只刷新已打开的面板。
 */
public record MarketPayload(int screen, int hand, BlockPos pos, long clock, double[] rates,
                            List<List<FxMarket.Candle>> candles, List<FxMarket.News> news) implements CustomPacketPayload {
    public static final int UPDATE = 0, EXCHANGE = 1, WALLET = 2;
    public static final Type<MarketPayload> TYPE = new Type<>(LogosDimension.id("fx_market"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MarketPayload> CODEC = StreamCodec.of(MarketPayload::write, MarketPayload::read);

    public static MarketPayload of(FxMarket market, int screen, int hand, BlockPos pos, long clock) {
        double[] rates = new double[Currency.values().length];
        for (var c : Currency.values()) rates[c.ordinal()] = market.rate(c);
        var candles = new ArrayList<List<FxMarket.Candle>>();
        for (var c : Currency.TRADED) candles.add(market.candles(c));
        return new MarketPayload(screen, hand, pos, clock, rates, candles, market.news());
    }

    public double rate(Currency c) { return rates[c.ordinal()]; }
    public List<FxMarket.Candle> candles(Currency c) { int i = Currency.TRADED.indexOf(c); return i < 0 ? List.of() : candles.get(i); }

    private static void write(RegistryFriendlyByteBuf b, MarketPayload p) {
        b.writeVarInt(p.screen); b.writeVarInt(p.hand); b.writeBlockPos(p.pos); b.writeLong(p.clock);
        for (double r : p.rates) b.writeDouble(r);
        for (var list : p.candles) {
            b.writeVarInt(list.size());
            for (var k : list) { b.writeLong(k.slot()); b.writeFloat(k.open()); b.writeFloat(k.high()); b.writeFloat(k.low()); b.writeFloat(k.close()); }
        }
        b.writeVarInt(p.news.size());
        for (var n : p.news) {
            b.writeLong(n.time()); b.writeUtf(n.key()); b.writeVarInt(n.tier());
            b.writeVarInt(n.args().size()); n.args().forEach(b::writeUtf);
        }
    }

    private static MarketPayload read(RegistryFriendlyByteBuf b) {
        int screen = b.readVarInt(), hand = b.readVarInt(); var pos = b.readBlockPos(); long clock = b.readLong();
        double[] rates = new double[Currency.values().length];
        for (int i = 0; i < rates.length; i++) rates[i] = b.readDouble();
        var candles = new ArrayList<List<FxMarket.Candle>>();
        for (int c = 0; c < Currency.TRADED.size(); c++) {
            int n = Math.min(b.readVarInt(), FxMarket.CANDLES);
            var list = new ArrayList<FxMarket.Candle>(n);
            for (int i = 0; i < n; i++) list.add(new FxMarket.Candle(b.readLong(), b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat()));
            candles.add(list);
        }
        int n = Math.min(b.readVarInt(), FxMarket.NEWS);
        var news = new ArrayList<FxMarket.News>(n);
        for (int i = 0; i < n; i++) {
            long time = b.readLong(); String key = b.readUtf(); int tier = b.readVarInt();
            int k = Math.min(b.readVarInt(), 8); var args = new ArrayList<String>(k);
            for (int j = 0; j < k; j++) args.add(b.readUtf());
            news.add(new FxMarket.News(time, key, args, tier));
        }
        return new MarketPayload(screen, hand, pos, clock, rates, candles, news);
    }

    @Override public Type<MarketPayload> type() { return TYPE; }
}
