package dev.logos.dimension.longsword;

import dev.logos.dimension.LogosDimension;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 只同步动作快照；客户端不能指定目标、伤害或连招段数。 */
public record LongswordPayload(UUID player, int variant, LongswordPhase phase, double started, float rate, float reverse) implements CustomPacketPayload {
    public static final Type<LongswordPayload> TYPE = new Type<>(LogosDimension.id("longsword_pose"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LongswordPayload> CODEC = StreamCodec.of((b, p) -> {
        b.writeUUID(p.player); b.writeVarInt(p.variant); b.writeEnum(p.phase); b.writeDouble(p.started); b.writeFloat(p.rate); b.writeFloat(p.reverse);
    }, b -> new LongswordPayload(b.readUUID(), b.readVarInt(), b.readEnum(LongswordPhase.class), b.readDouble(), b.readFloat(), b.readFloat()));
    @Override public Type<LongswordPayload> type() { return TYPE; }

    public record Attack() implements CustomPacketPayload {
        public static final Type<Attack> TYPE = new Type<>(LogosDimension.id("longsword_attack"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Attack> CODEC = StreamCodec.unit(new Attack());
        @Override public Type<Attack> type() { return TYPE; }
    }
}
