package dev.logos.dimension.economy;

import dev.logos.dimension.LogosDimension;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 钱庄账户快照，跟在行情快照后面发：各货币存款、持仓、通知，以及倍数上限和授信额度。
 * open 为 true 时客户端打开钱庄面板。
 */
public record BankPayload(boolean open, BlockPos pos, long[] balances, List<Margin.Position> positions, List<FxMarket.News> notices,
                          int playerMax, long credit) implements CustomPacketPayload {
    public static final Type<BankPayload> TYPE = new Type<>(LogosDimension.id("fx_bank"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BankPayload> CODEC = StreamCodec.of(BankPayload::write, BankPayload::read);

    public static BankPayload of(BankAccount account, boolean open, BlockPos pos) {
        long[] balances = new long[Currency.values().length];
        for (var c : Currency.values()) balances[c.ordinal()] = account.balance(c);
        return new BankPayload(open, pos, balances, account.positions(), account.notices(), Margin.PLAYER_MAX, BankAccount.CREDIT_LIMIT);
    }

    public long balance(Currency c) { return balances[c.ordinal()]; }

    private static void write(RegistryFriendlyByteBuf b, BankPayload p) {
        b.writeBoolean(p.open); b.writeBlockPos(p.pos);
        for (long v : p.balances) b.writeVarLong(v >= 0 ? v * 2 : -v * 2 - 1);
        b.writeVarInt(p.positions.size());
        for (var x : p.positions) {
            b.writeVarLong(x.id()); b.writeVarInt(x.currency().ordinal()); b.writeByte(x.side()); b.writeVarLong(x.margin());
            b.writeVarInt(x.leverage()); b.writeDouble(x.entry()); b.writeDouble(x.units()); b.writeLong(x.opened());
            b.writeDouble(x.stop()); b.writeDouble(x.take()); b.writeDouble(x.accrued());
        }
        b.writeVarInt(p.notices.size());
        for (var n : p.notices) { b.writeLong(n.time()); b.writeUtf(n.key()); b.writeVarInt(n.args().size()); n.args().forEach(b::writeUtf); }
        b.writeVarInt(p.playerMax); b.writeVarLong(p.credit);
    }

    private static BankPayload read(RegistryFriendlyByteBuf b) {
        boolean open = b.readBoolean(); var pos = b.readBlockPos();
        long[] balances = new long[Currency.values().length];
        for (int i = 0; i < balances.length; i++) { long z = b.readVarLong(); balances[i] = (z & 1) == 0 ? z >>> 1 : -(z >>> 1) - 1; }
        int n = Math.min(b.readVarInt(), Margin.MAX_POSITIONS * 2);
        var positions = new ArrayList<Margin.Position>(n);
        var currencies = Currency.values();
        for (int i = 0; i < n; i++) {
            long id = b.readVarLong(); var c = currencies[Math.clamp(b.readVarInt(), 0, currencies.length - 1)]; int side = b.readByte();
            long margin = b.readVarLong(); int leverage = Math.clamp(b.readVarInt(), 1, Margin.SUPPORTED_MAX);
            positions.add(new Margin.Position(id, c, side, margin, leverage, b.readDouble(), b.readDouble(), b.readLong(), b.readDouble(), b.readDouble(), b.readDouble()));
        }
        int k = Math.min(b.readVarInt(), BankAccount.NOTICES);
        var notices = new ArrayList<FxMarket.News>(k);
        for (int i = 0; i < k; i++) {
            long time = b.readLong(); String key = b.readUtf(); int m = Math.min(b.readVarInt(), 8); var args = new ArrayList<String>(m);
            for (int j = 0; j < m; j++) args.add(b.readUtf());
            notices.add(new FxMarket.News(time, key, args, 0));
        }
        return new BankPayload(open, pos, balances, positions, notices, b.readVarInt(), b.readVarLong());
    }

    @Override public Type<BankPayload> type() { return TYPE; }
}
