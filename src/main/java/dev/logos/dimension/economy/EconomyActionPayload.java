package dev.logos.dimension.economy;

import dev.logos.dimension.LogosDimension;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 客户端面板上的操作。服务端逐条重新校验：钱囊得还在那只手上，兑换台得在身边且开门。
 * <ul>
 *   <li>WITHDRAW：从钱囊取 count 枚 a 货币第 b 种面额</li>
 *   <li>DEPOSIT：背包散币全部存进钱囊</li>
 *   <li>LOCK：切换 a 货币的「不参与自动换汇」</li>
 *   <li>AUTO：切换捡钱自动入囊</li>
 *   <li>TRADE：在兑换台把 amount 个 a 货币最小单位换成 b 货币</li>
 *   <li>CLOSE：面板关了，不再推送行情</li>
 * </ul>
 */
public record EconomyActionPayload(int action, int hand, int a, int b, long amount) implements CustomPacketPayload {
    public static final int WITHDRAW = 0, DEPOSIT = 1, LOCK = 2, AUTO = 3, TRADE = 4, CLOSE = 5;
    public static final Type<EconomyActionPayload> TYPE = new Type<>(LogosDimension.id("fx_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EconomyActionPayload> CODEC = StreamCodec.of(
            (buf, p) -> { buf.writeVarInt(p.action); buf.writeVarInt(p.hand); buf.writeVarInt(p.a); buf.writeVarInt(p.b); buf.writeVarLong(p.amount); },
            buf -> new EconomyActionPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarLong()));

    @Override public Type<EconomyActionPayload> type() { return TYPE; }
}
