package dev.logos.dimension.dawnvow;

import dev.logos.dimension.LogosDimension;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/**
 * 曦誓大型蓄力的几个时刻，只给客户端画特效用（伤害、魔力都在服务端）：
 * <ul>
 *   <li>{@code GRAND}：进入大型蓄力；{@code tick} 是这次蓄力开始（按下右键）的游戏刻，客户端据此算进度；</li>
 *   <li>{@code FULL}：蓄满；</li>
 *   <li>{@code STOP}：大型蓄力结束（松手放出、中途松手或魔力不足），身上的效果收掉；</li>
 *   <li>{@code RELEASE}：巨型剑气放出：{@code from} 起点（脚下），{@code dir} 水平方向，{@code length} 走多远，
 *       {@code tick} 放出的游戏刻；剑气每刻走 {@link DawnvowCharge#GRAND_SPEED} 格，到头时十字爆闪。</li>
 * </ul>
 */
public record DawnvowPayload(byte kind, int entity, long tick, Vec3 from, Vec3 dir, float length) implements CustomPacketPayload {
    public static final byte GRAND = 0, FULL = 1, STOP = 2, RELEASE = 3;
    public static final Type<DawnvowPayload> TYPE = new Type<>(LogosDimension.id("dawnvow"));
    public static final StreamCodec<ByteBuf, DawnvowPayload> CODEC = StreamCodec.of((b, p) -> {
        b.writeByte(p.kind); b.writeInt(p.entity); b.writeLong(p.tick);
        b.writeDouble(p.from.x); b.writeDouble(p.from.y); b.writeDouble(p.from.z);
        b.writeDouble(p.dir.x); b.writeDouble(p.dir.y); b.writeDouble(p.dir.z);
        b.writeFloat(p.length);
    }, b -> new DawnvowPayload(b.readByte(), b.readInt(), b.readLong(),
            new Vec3(b.readDouble(), b.readDouble(), b.readDouble()), new Vec3(b.readDouble(), b.readDouble(), b.readDouble()), b.readFloat()));

    public static DawnvowPayload of(byte kind, int entity, long tick) {
        return new DawnvowPayload(kind, entity, tick, Vec3.ZERO, Vec3.ZERO, 0);
    }

    @Override public Type<DawnvowPayload> type() { return TYPE; }
}
