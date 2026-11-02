package dev.logos.dimension.economy;

import dev.logos.dimension.LogosDimension;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 钱庄面板上的操作，服务端逐条校验（人在钱庄旁、钱够、倍数不超上限）。
 * DEPOSIT / WITHDRAW：currency 存取 amount 个最小单位（存入时 0 表示全部）；
 * OPEN：开仓，side、leverage、amount（保证金铜分）、stop、take（中间价，0 不设）；CLOSE：平掉 id 那笔。
 */
public record BankOrderPayload(int action, int currency, int side, int leverage, long amount, long id, double stop, double take) implements CustomPacketPayload {
    public static final int DEPOSIT = 0, WITHDRAW = 1, OPEN = 2, CLOSE = 3;
    public static final Type<BankOrderPayload> TYPE = new Type<>(LogosDimension.id("fx_bank_order"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BankOrderPayload> CODEC = StreamCodec.of(
            (b, p) -> { b.writeVarInt(p.action); b.writeVarInt(p.currency); b.writeByte(p.side); b.writeVarInt(p.leverage);
                b.writeVarLong(p.amount); b.writeVarLong(p.id); b.writeDouble(p.stop); b.writeDouble(p.take); },
            b -> new BankOrderPayload(b.readVarInt(), b.readVarInt(), b.readByte(), b.readVarInt(), b.readVarLong(), b.readVarLong(), b.readDouble(), b.readDouble()));

    @Override public Type<BankOrderPayload> type() { return TYPE; }
}
