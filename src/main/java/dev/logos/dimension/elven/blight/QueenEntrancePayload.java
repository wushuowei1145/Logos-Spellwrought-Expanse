package dev.logos.dimension.elven.blight;

import dev.logos.dimension.LogosDimension;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 只同步入场的目标和核心；运镜仍由现有 CameraShots 发送。 */
public record QueenEntrancePayload(int queen, BlockPos heart, net.minecraft.world.phys.Vec3 front) implements CustomPacketPayload {
    public static final Type<QueenEntrancePayload> TYPE = new Type<>(LogosDimension.id("queen_entrance"));
    public static final StreamCodec<ByteBuf, QueenEntrancePayload> CODEC = StreamCodec.of((b,p)->{
        b.writeInt(p.queen); b.writeLong(p.heart.asLong());
        b.writeDouble(p.front.x); b.writeDouble(p.front.y); b.writeDouble(p.front.z);
    },b->new QueenEntrancePayload(b.readInt(),BlockPos.of(b.readLong()),new net.minecraft.world.phys.Vec3(b.readDouble(),b.readDouble(),b.readDouble())));
    @Override public Type<QueenEntrancePayload> type(){return TYPE;}
}
