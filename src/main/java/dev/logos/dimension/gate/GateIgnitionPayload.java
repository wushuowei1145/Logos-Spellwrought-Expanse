package dev.logos.dimension.gate;

import dev.logos.dimension.LogosDimension;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 门开始点亮，只给客户端画特效用。{@code x,y,z} 是门洞中心，{@code bottom} 是门洞底部（脚下地面）的高度，
 * {@code halfWidth,halfHeight} 是门洞的半宽半高，{@code alongX} 表示门面沿 X 轴展开。
 */
public record GateIgnitionPayload(double x, double y, double z, double bottom, float halfWidth, float halfHeight, boolean alongX) implements CustomPacketPayload {
    public static final Type<GateIgnitionPayload> TYPE = new Type<>(LogosDimension.id("gate_ignition"));
    public static final StreamCodec<ByteBuf, GateIgnitionPayload> CODEC = StreamCodec.of((b, p) -> {
        b.writeDouble(p.x); b.writeDouble(p.y); b.writeDouble(p.z); b.writeDouble(p.bottom);
        b.writeFloat(p.halfWidth); b.writeFloat(p.halfHeight); b.writeBoolean(p.alongX);
    }, b -> new GateIgnitionPayload(b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble(), b.readFloat(), b.readFloat(), b.readBoolean()));

    static GateIgnitionPayload of(GateShape.Found found) {
        boolean alongX = found.axis() == Direction.Axis.X;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE, min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, depth = 0;
        for (BlockPos c : found.cells()) {
            minY = Math.min(minY, c.getY());
            maxY = Math.max(maxY, c.getY());
            int along = alongX ? c.getX() : c.getZ();
            min = Math.min(min, along);
            max = Math.max(max, along);
            depth = alongX ? c.getZ() : c.getX();
        }
        double middle = (min + max + 1) / 2.0, cross = depth + .5;
        return new GateIgnitionPayload(alongX ? middle : cross, (minY + maxY + 1) / 2.0, alongX ? cross : middle, minY,
                (max - min + 1) / 2f, (maxY - minY + 1) / 2f, alongX);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
