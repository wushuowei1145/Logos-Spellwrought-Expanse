package dev.logos.dimension.dissolve;

import dev.logos.dimension.LogosDimension;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 服务端告诉客户端：有东西开始消散了（见 {@link Dissolving}）。方块连同它原来的状态一起发。 */
public record DissolvePayload(int target, int entity, List<BlockPos> positions, List<Integer> states, DissolveShape shape, int ticks, int color) implements CustomPacketPayload {
    public static final int ENTITY = 0, RIGHT = 1, LEFT = 2, BLOCKS = 3;

    public static final Type<DissolvePayload> TYPE = new Type<>(LogosDimension.id("dissolve"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DissolvePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, DissolvePayload::target, ByteBufCodecs.VAR_INT, DissolvePayload::entity,
            BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list()), DissolvePayload::positions,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), DissolvePayload::states,
            DissolveShape.CODEC.cast(), DissolvePayload::shape, ByteBufCodecs.VAR_INT, DissolvePayload::ticks, ByteBufCodecs.INT, DissolvePayload::color,
            DissolvePayload::new);

    @Override public Type<DissolvePayload> type() { return TYPE; }
}
