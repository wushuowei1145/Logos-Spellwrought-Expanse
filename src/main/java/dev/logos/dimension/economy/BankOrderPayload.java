package dev.logos.dimension.economy;

import dev.logos.dimension.LogosDimension;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 钱庄、兑换台和钱囊面板上与账户有关的操作，服务端逐条校验（人在柜台旁、钱够、倍数不超上限）。
 * <ul>
 *   <li>DEPOSIT / WITHDRAW：currency 存取 amount 个最小单位（存入时 0 表示全部）——钱庄</li>
 *   <li>OPEN：市价开仓，side、leverage、amount（保证金铜分）、stop、take、trail——钱庄</li>
 *   <li>CLOSE：平掉 id；REDUCE：平掉 id 的 amount‰；CLOSE_ALL：全部平仓——钱庄</li>
 *   <li>ADD_MARGIN：给 id 追加 amount 铜分保证金；INCREASE：给 id 加仓，amount 保证金、leverage 倍——钱庄</li>
 *   <li>MODIFY：改 id 的 stop、take、trail——钱庄</li>
 *   <li>LIMIT_OPEN：杠杆挂单，参数同 OPEN 外加 price——钱庄</li>
 *   <li>LIMIT_SPOT：现货挂单，side +1 用 amount 铜分买 currency、−1 卖出 amount 个 currency，到 price 成交——兑换台</li>
 *   <li>CANCEL：撤掉挂单 id；ALERT：currency 到 price 时提醒；UNALERT：删掉提醒 id——在哪个面板都行</li>
 * </ul>
 * 价格一律是中间价（一个最小单位值多少铜分），0 表示不设；trail 是移动止损的比例。
 */
public record BankOrderPayload(int action, int currency, int side, int leverage, long amount, long id,
                               double price, double stop, double take, double trail) implements CustomPacketPayload {
    public static final int DEPOSIT = 0, WITHDRAW = 1, OPEN = 2, CLOSE = 3, REDUCE = 4, CLOSE_ALL = 5, ADD_MARGIN = 6, INCREASE = 7,
            MODIFY = 8, LIMIT_OPEN = 9, LIMIT_SPOT = 10, CANCEL = 11, ALERT = 12, UNALERT = 13;
    public static final Type<BankOrderPayload> TYPE = new Type<>(LogosDimension.id("fx_bank_order"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BankOrderPayload> CODEC = StreamCodec.of(
            (b, p) -> { b.writeVarInt(p.action); b.writeVarInt(p.currency); b.writeByte(p.side); b.writeVarInt(p.leverage);
                b.writeVarLong(p.amount); b.writeVarLong(p.id); b.writeDouble(p.price); b.writeDouble(p.stop); b.writeDouble(p.take); b.writeDouble(p.trail); },
            b -> new BankOrderPayload(b.readVarInt(), b.readVarInt(), b.readByte(), b.readVarInt(), b.readVarLong(), b.readVarLong(),
                    b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble()));

    /** 只带一个 id 的操作（平仓、撤单、删提醒）。 */
    public static BankOrderPayload of(int action, long id) { return new BankOrderPayload(action, 0, 0, 0, 0, id, 0, 0, 0, 0); }

    @Override public Type<BankOrderPayload> type() { return TYPE; }
}
