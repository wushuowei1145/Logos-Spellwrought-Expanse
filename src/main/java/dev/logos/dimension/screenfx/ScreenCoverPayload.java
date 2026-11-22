package dev.logos.dimension.screenfx;

import dev.logos.dimension.LogosDimension;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 让收到的玩家盖上一次全屏白或黑。 */
public record ScreenCoverPayload(ScreenCover cover) implements CustomPacketPayload {
    public static final Type<ScreenCoverPayload> TYPE = new Type<>(LogosDimension.id("screen_cover"));
    public static final StreamCodec<ByteBuf, ScreenCoverPayload> CODEC = ScreenCover.CODEC.map(ScreenCoverPayload::new, ScreenCoverPayload::cover);

    @Override public Type<ScreenCoverPayload> type() { return TYPE; }
}
