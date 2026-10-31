package dev.logos.dimension.net;

import dev.logos.dimension.LogosDimension;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 服务端只报告“谁身边卷起了魔力龙卷”：跟随的实体（-1 表示不跟随，停在 {@code x, y, z}）、持续刻数（0 表示收掉这个实体身上的，
 * 负数表示一直卷到收掉为止）、缩放和颜色转一圈的刻数（0 表示保持原色）。画面全由客户端决定。
 */
public record ManaTornadoPayload(int entity, double x, double y, double z, int ticks, float scale, int cycle) implements CustomPacketPayload {
    public static final Type<ManaTornadoPayload> TYPE = new Type<>(LogosDimension.id("mana_tornado"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ManaTornadoPayload> CODEC = StreamCodec.of((b, p) -> {
        b.writeVarInt(p.entity); b.writeDouble(p.x); b.writeDouble(p.y); b.writeDouble(p.z);
        b.writeVarInt(p.ticks); b.writeFloat(p.scale); b.writeVarInt(p.cycle);
    }, b -> new ManaTornadoPayload(b.readVarInt(), b.readDouble(), b.readDouble(), b.readDouble(), b.readVarInt(), b.readFloat(), b.readVarInt()));
    @Override public Type<ManaTornadoPayload> type() { return TYPE; }
}
