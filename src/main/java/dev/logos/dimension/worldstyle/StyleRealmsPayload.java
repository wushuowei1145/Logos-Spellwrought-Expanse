package dev.logos.dimension.worldstyle;

import dev.logos.dimension.LogosDimension;
import io.netty.buffer.ByteBuf;
import java.util.List;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 玩家所在世界的全部画风区域，整份发，收到就替换。 */
public record StyleRealmsPayload(List<StyleRealm> realms) implements CustomPacketPayload {
    public static final Type<StyleRealmsPayload> TYPE = new Type<>(LogosDimension.id("style_realms"));
    public static final StreamCodec<ByteBuf, StyleRealmsPayload> CODEC = ByteBufCodecs.fromCodec(StyleRealm.CODEC.listOf()).map(StyleRealmsPayload::new, StyleRealmsPayload::realms);

    @Override public Type<StyleRealmsPayload> type() { return TYPE; }
}
