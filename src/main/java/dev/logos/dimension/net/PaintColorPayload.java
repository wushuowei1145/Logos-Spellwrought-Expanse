package dev.logos.dimension.net;

import dev.logos.dimension.LogosDimension;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 客户端调色盘把选中的颜色写回服务端：color 为 ARGB，hand 0=主手 1=副手。 */
public record PaintColorPayload(int color, int hand) implements CustomPacketPayload {
    public static final Type<PaintColorPayload> TYPE = new Type<>(LogosDimension.id("paint_color"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PaintColorPayload> CODEC = StreamCodec.of(
        (b, p) -> { b.writeVarInt(p.color); b.writeVarInt(p.hand); },
        b -> new PaintColorPayload(b.readVarInt(), b.readVarInt()));

    @Override
    public Type<PaintColorPayload> type() {
        return TYPE;
    }
}
