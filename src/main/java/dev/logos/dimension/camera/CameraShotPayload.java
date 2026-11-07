package dev.logos.dimension.camera;

import dev.logos.dimension.LogosDimension;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 让收到的玩家播一段运镜。 */
public record CameraShotPayload(CameraShot shot) implements CustomPacketPayload {
    public static final Type<CameraShotPayload> TYPE = new Type<>(LogosDimension.id("camera_shot"));
    public static final StreamCodec<ByteBuf, CameraShotPayload> CODEC = CameraShot.CODEC.map(CameraShotPayload::new, CameraShotPayload::shot);

    @Override public Type<CameraShotPayload> type() { return TYPE; }
}
