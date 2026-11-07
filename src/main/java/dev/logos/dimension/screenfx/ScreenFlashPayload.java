package dev.logos.dimension.screenfx;

import dev.logos.dimension.LogosDimension;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 让收到的玩家闪一次。 */
public record ScreenFlashPayload(ScreenFlash flash) implements CustomPacketPayload {
    public static final Type<ScreenFlashPayload> TYPE = new Type<>(LogosDimension.id("screen_flash"));
    public static final StreamCodec<ByteBuf, ScreenFlashPayload> CODEC = ScreenFlash.CODEC.map(ScreenFlashPayload::new, ScreenFlashPayload::flash);

    @Override public Type<ScreenFlashPayload> type() { return TYPE; }
}
