package dev.logos.dimension.execution;

import dev.logos.dimension.LogosDimension;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 开始一次处决特写。{@code viewer} 为 true 的玩家看特写（镜头和画面）；其余看得见目标的玩家只看到它被抛起、挨打、拍下。
 *
 * @param yaw 开始时目标的朝向：机位和身体被打歪的方向都按它算，每个客户端都一样
 * @param clicker 可交互的处决里，收到的这名玩家就是处决的人：由他的左键出刀（{@link Click}）
 */
public record ExecutionPayload(int target, ExecutionShot shot, float yaw, boolean viewer, boolean clicker) implements CustomPacketPayload {
    public static final Type<ExecutionPayload> TYPE = new Type<>(LogosDimension.id("execution_shot"));
    public static final StreamCodec<ByteBuf, ExecutionPayload> CODEC = StreamCodec.of((b, p) -> {
        b.writeInt(p.target);
        ExecutionShot.CODEC.encode(b, p.shot);
        b.writeFloat(p.yaw);
        b.writeByte((p.viewer ? 1 : 0) | (p.clicker ? 2 : 0));
    }, b -> {
        int target = b.readInt();
        ExecutionShot shot = ExecutionShot.CODEC.decode(b);
        float yaw = b.readFloat();
        int flags = b.readByte();
        return new ExecutionPayload(target, shot, yaw, (flags & 1) != 0, (flags & 2) != 0);
    });

    public ExecutionPayload(int target, ExecutionShot shot, float yaw, boolean viewer) {
        this(target, shot, yaw, viewer, false);
    }

    @Override public Type<ExecutionPayload> type() {
        return TYPE;
    }

    /** 可交互的处决里砍了一刀：{@code age} 是服务端从开始算起的刻数（处决的人自己那边已经先画了，不发给他）。 */
    public record Slash(int target, float age) implements CustomPacketPayload {
        public static final Type<Slash> TYPE = new Type<>(LogosDimension.id("execution_slash"));
        public static final StreamCodec<ByteBuf, Slash> CODEC = StreamCodec.of((b, p) -> {
            b.writeInt(p.target);
            b.writeFloat(p.age);
        }, b -> new Slash(b.readInt(), b.readFloat()));

        @Override public Type<Slash> type() {
            return TYPE;
        }
    }

    /** 处决的人点了一下左键（客户端到服务端）。 */
    public record Click() implements CustomPacketPayload {
        public static final Type<Click> TYPE = new Type<>(LogosDimension.id("execution_click"));
        public static final StreamCodec<ByteBuf, Click> CODEC = StreamCodec.unit(new Click());

        @Override public Type<Click> type() {
            return TYPE;
        }
    }
}
