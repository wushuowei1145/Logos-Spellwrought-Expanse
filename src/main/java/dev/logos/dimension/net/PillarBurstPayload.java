package dev.logos.dimension.net;

import dev.logos.dimension.LogosDimension;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 服务端只报告“某处爆发了金色光柱”：位置与缩放，画面全由客户端决定。 */
public record PillarBurstPayload(double x, double y, double z, float scale) implements CustomPacketPayload {
    public static final Type<PillarBurstPayload> TYPE = new Type<>(LogosDimension.id("pillar_burst"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PillarBurstPayload> CODEC = StreamCodec.of((b, p) -> {
        b.writeDouble(p.x); b.writeDouble(p.y); b.writeDouble(p.z); b.writeFloat(p.scale);
    }, b -> new PillarBurstPayload(b.readDouble(), b.readDouble(), b.readDouble(), b.readFloat()));
    @Override public Type<PillarBurstPayload> type() { return TYPE; }
}
