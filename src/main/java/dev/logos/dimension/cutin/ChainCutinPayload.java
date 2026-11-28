package dev.logos.dimension.cutin;

import dev.logos.dimension.LogosDimension;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 让收到的玩家放一次连携切入。 */
public record ChainCutinPayload(ChainCutin cutin) implements CustomPacketPayload {
    public static final Type<ChainCutinPayload> TYPE = new Type<>(LogosDimension.id("chain_cutin"));
    public static final StreamCodec<ByteBuf, ChainCutinPayload> CODEC = ChainCutin.CODEC.map(ChainCutinPayload::new, ChainCutinPayload::cutin);

    @Override public Type<ChainCutinPayload> type() { return TYPE; }
}
