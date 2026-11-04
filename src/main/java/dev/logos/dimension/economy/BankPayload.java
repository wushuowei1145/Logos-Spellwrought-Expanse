package dev.logos.dimension.economy;

import dev.logos.dimension.LogosDimension;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 钱庄账户快照，跟在行情快照后面发：各货币存款、持仓、挂单、价格提醒、通知，以及倍数上限和授信额度。
 * open 为 true 时客户端打开钱庄面板；钱囊面板也会收到它，用来随身查看持仓。
 */
public record BankPayload(boolean open, BlockPos pos, long[] balances, List<Margin.Position> positions, List<Orders.Pending> orders,
                          List<Orders.Alert> alerts, List<FxMarket.News> notices, int playerMax, long credit) implements CustomPacketPayload {
    public static final Type<BankPayload> TYPE = new Type<>(LogosDimension.id("fx_bank"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BankPayload> CODEC = StreamCodec.of(BankPayload::write, BankPayload::read);

    public static BankPayload of(BankAccount account, boolean open, BlockPos pos) {
        long[] balances = new long[Currency.values().length];
        for (var c : Currency.values()) balances[c.ordinal()] = account.balance(c);
        return new BankPayload(open, pos, balances, account.positions(), account.orders(), account.alerts(), account.notices(),
                Margin.PLAYER_MAX, BankAccount.CREDIT_LIMIT);
    }

    public long balance(Currency c) { return balances[c.ordinal()]; }

    private static void zigzag(RegistryFriendlyByteBuf b, long v) { b.writeVarLong(v >= 0 ? v * 2 : -v * 2 - 1); }
    private static long zigzag(RegistryFriendlyByteBuf b) { long z = b.readVarLong(); return (z & 1) == 0 ? z >>> 1 : -(z >>> 1) - 1; }
    private static Currency currency(RegistryFriendlyByteBuf b) { var all = Currency.values(); return all[Math.clamp(b.readVarInt(), 0, all.length - 1)]; }

    private static void write(RegistryFriendlyByteBuf b, BankPayload p) {
        b.writeBoolean(p.open); b.writeBlockPos(p.pos);
        for (long v : p.balances) zigzag(b, v);
        b.writeVarInt(p.positions.size());
        for (var x : p.positions) {
            b.writeVarLong(x.id()); b.writeVarInt(x.currency().ordinal()); b.writeByte(x.side()); b.writeVarLong(x.margin());
            b.writeVarInt(x.leverage()); b.writeDouble(x.entry()); b.writeDouble(x.units()); b.writeLong(x.opened());
            b.writeDouble(x.stop()); b.writeDouble(x.take()); b.writeDouble(x.accrued());
            b.writeDouble(x.trail()); b.writeDouble(x.best()); b.writeBoolean(x.warned());
        }
        b.writeVarInt(p.orders.size());
        for (var o : p.orders) {
            b.writeVarLong(o.id()); b.writeVarInt(o.kind()); b.writeVarInt(o.currency().ordinal()); b.writeByte(o.side());
            b.writeVarLong(o.amount()); b.writeVarInt(o.leverage()); b.writeDouble(o.price());
            b.writeDouble(o.stop()); b.writeDouble(o.take()); b.writeDouble(o.trail()); b.writeLong(o.placed());
        }
        b.writeVarInt(p.alerts.size());
        for (var a : p.alerts) { b.writeVarLong(a.id()); b.writeVarInt(a.currency().ordinal()); b.writeBoolean(a.above()); b.writeDouble(a.price()); }
        b.writeVarInt(p.notices.size());
        for (var n : p.notices) { b.writeLong(n.time()); b.writeUtf(n.key()); b.writeVarInt(n.args().size()); n.args().forEach(b::writeUtf); }
        b.writeVarInt(p.playerMax); b.writeVarLong(p.credit);
    }

    private static BankPayload read(RegistryFriendlyByteBuf b) {
        boolean open = b.readBoolean(); var pos = b.readBlockPos();
        long[] balances = new long[Currency.values().length];
        for (int i = 0; i < balances.length; i++) balances[i] = zigzag(b);
        int n = Math.min(b.readVarInt(), Margin.MAX_POSITIONS * 2);
        var positions = new ArrayList<Margin.Position>(n);
        for (int i = 0; i < n; i++) {
            long id = b.readVarLong(); var c = currency(b); int side = b.readByte();
            long margin = b.readVarLong(); int leverage = Math.clamp(b.readVarInt(), 1, Margin.SUPPORTED_MAX);
            positions.add(new Margin.Position(id, c, side, margin, leverage, b.readDouble(), b.readDouble(), b.readLong(), b.readDouble(),
                    b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble(), b.readBoolean()));
        }
        int m = Math.min(b.readVarInt(), Orders.MAX_ORDERS * 2);
        var orders = new ArrayList<Orders.Pending>(m);
        for (int i = 0; i < m; i++) {
            long id = b.readVarLong(); int kind = b.readVarInt(); var c = currency(b); int side = b.readByte();
            orders.add(new Orders.Pending(id, kind, c, side, b.readVarLong(), Math.clamp(b.readVarInt(), 1, Margin.SUPPORTED_MAX), b.readDouble(),
                    b.readDouble(), b.readDouble(), b.readDouble(), b.readLong()));
        }
        int a = Math.min(b.readVarInt(), Orders.MAX_ALERTS * 2);
        var alerts = new ArrayList<Orders.Alert>(a);
        for (int i = 0; i < a; i++) alerts.add(new Orders.Alert(b.readVarLong(), currency(b), b.readBoolean(), b.readDouble()));
        int k = Math.min(b.readVarInt(), BankAccount.NOTICES);
        var notices = new ArrayList<FxMarket.News>(k);
        for (int i = 0; i < k; i++) {
            long time = b.readLong(); String key = b.readUtf(); int args = Math.min(b.readVarInt(), 8); var list = new ArrayList<String>(args);
            for (int j = 0; j < args; j++) list.add(b.readUtf());
            notices.add(new FxMarket.News(time, key, list, 0));
        }
        return new BankPayload(open, pos, balances, positions, orders, alerts, notices, b.readVarInt(), b.readVarLong());
    }

    @Override public Type<BankPayload> type() { return TYPE; }
}
