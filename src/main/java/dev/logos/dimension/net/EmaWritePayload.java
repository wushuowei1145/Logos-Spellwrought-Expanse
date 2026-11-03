package dev.logos.dimension.net;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.cloudsea.EmaWish;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 绘马书写页合上时把写下的愿望交给服务端：hand 0=主手 1=副手，text 为空表示擦掉。 */
public record EmaWritePayload(int hand, String text) implements CustomPacketPayload {
    public static final Type<EmaWritePayload> TYPE = new Type<>(LogosDimension.id("ema_write"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EmaWritePayload> CODEC = StreamCodec.of(
        (b, p) -> { b.writeVarInt(p.hand); b.writeUtf(p.text, EmaWish.MAX_CHARS * 4); },
        b -> new EmaWritePayload(b.readVarInt(), b.readUtf(EmaWish.MAX_CHARS * 4)));

    @Override
    public Type<EmaWritePayload> type() {
        return TYPE;
    }
}
