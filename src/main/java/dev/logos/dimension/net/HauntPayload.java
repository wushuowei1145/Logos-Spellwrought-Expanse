package dev.logos.dimension.net;

import dev.logos.dimension.LogosDimension;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Only the server decides whether an island is still haunted. No client-to-server progression message. */
public record HauntPayload(boolean veiled, float density, int x, int z, float radius, float bottom, float top) implements CustomPacketPayload {
    public static final HauntPayload CLEAR = new HauntPayload(false, 0, 0, 0, 0, 0, 0);
    public static final Type<HauntPayload> TYPE = new Type<>(LogosDimension.id("island_haunt"));
    public static final StreamCodec<RegistryFriendlyByteBuf, HauntPayload> CODEC = StreamCodec.of((b, p) -> {
        b.writeBoolean(p.veiled); b.writeFloat(p.density); b.writeInt(p.x); b.writeInt(p.z);
        b.writeFloat(p.radius); b.writeFloat(p.bottom); b.writeFloat(p.top);
    }, b -> new HauntPayload(b.readBoolean(), b.readFloat(), b.readInt(), b.readInt(), b.readFloat(), b.readFloat(), b.readFloat()));
    @Override public Type<HauntPayload> type() { return TYPE; }
}
