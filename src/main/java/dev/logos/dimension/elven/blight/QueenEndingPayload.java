package dev.logos.dimension.elven.blight;

import dev.logos.dimension.LogosDimension;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/** 一次同步结局原点、出口方向和服务器时钟，客户端自行平滑推进。 */
public record QueenEndingPayload(BlockPos heart, Vec3 floor, Vec3 front, long began) implements CustomPacketPayload {
    public static final Type<QueenEndingPayload> TYPE = new Type<>(LogosDimension.id("queen_ending"));
    public static final StreamCodec<ByteBuf, QueenEndingPayload> CODEC = StreamCodec.of((b, p) -> {
        b.writeLong(p.heart.asLong()); b.writeDouble(p.floor.x); b.writeDouble(p.floor.y); b.writeDouble(p.floor.z);
        b.writeDouble(p.front.x); b.writeDouble(p.front.y); b.writeDouble(p.front.z); b.writeLong(p.began);
    }, b -> new QueenEndingPayload(BlockPos.of(b.readLong()), new Vec3(b.readDouble(), b.readDouble(), b.readDouble()), new Vec3(b.readDouble(), b.readDouble(), b.readDouble()), b.readLong()));
    @Override public Type<QueenEndingPayload> type() { return TYPE; }
}
