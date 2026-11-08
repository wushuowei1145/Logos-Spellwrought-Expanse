package dev.logos.dimension.execution;

import dev.logos.dimension.LogosDimension;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 开始一次处决特写。{@code viewer} 为 true 的玩家看特写（镜头和画面）；其余看得见目标的玩家只看到它被抛起、挨打、拍下。
 *
 * @param yaw 开始时目标的朝向：机位和身体被打歪的方向都按它算，每个客户端都一样
 */
public record ExecutionPayload(int target, ExecutionShot shot, float yaw, boolean viewer) implements CustomPacketPayload {
    public static final Type<ExecutionPayload> TYPE = new Type<>(LogosDimension.id("execution_shot"));
    public static final StreamCodec<ByteBuf, ExecutionPayload> CODEC = StreamCodec.of((b, p) -> {
        b.writeInt(p.target);
        ExecutionShot.CODEC.encode(b, p.shot);
        b.writeFloat(p.yaw);
        b.writeBoolean(p.viewer);
    }, b -> new ExecutionPayload(b.readInt(), ExecutionShot.CODEC.decode(b), b.readFloat(), b.readBoolean()));

    @Override public Type<ExecutionPayload> type() {
        return TYPE;
    }
}
