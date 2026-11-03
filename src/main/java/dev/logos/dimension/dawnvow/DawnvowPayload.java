package dev.logos.dimension.dawnvow;

import dev.logos.dimension.LogosDimension;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/**
 * 曦誓大型蓄力的几个时刻，只给客户端画特效用（伤害、魔力、拆方块都在服务端）：
 * <ul>
 *   <li>{@code GRAND}：进入大型蓄力；{@code tick} 是这次蓄力开始（按下右键）的游戏刻，客户端据此算进度；</li>
 *   <li>{@code FULL}：蓄满；</li>
 *   <li>{@code STOP}：大型蓄力结束（中途松手、魔力不足、换手或离开），身上的效果收掉；</li>
 *   <li>{@code RELEASE}：巨型剑气放出：{@code from} 起点，{@code dir} 水平方向，{@code tick} 放出的游戏刻；
 *       {@code path} 是沿路每 {@link SwordWave#PATH_STEP} 格剑气所在地面的高度（贴着地形起伏），走完这一串就十字爆闪。
 *       剑气每刻走 {@link DawnvowCharge#GRAND_SPEED} 格；</li>
 *   <li>{@code BLESS}：巨型剑气放出后剑身的长金光保留到 {@code tick} 这一刻。</li>
 * </ul>
 */
public record DawnvowPayload(byte kind, int entity, long tick, Vec3 from, Vec3 dir, float[] path) implements CustomPacketPayload {
    public static final byte GRAND = 0, FULL = 1, STOP = 2, RELEASE = 3, BLESS = 4;
    public static final Type<DawnvowPayload> TYPE = new Type<>(LogosDimension.id("dawnvow"));
    public static final StreamCodec<ByteBuf, DawnvowPayload> CODEC = StreamCodec.of((b, p) -> {
        b.writeByte(p.kind); b.writeInt(p.entity); b.writeLong(p.tick);
        b.writeDouble(p.from.x); b.writeDouble(p.from.y); b.writeDouble(p.from.z);
        b.writeDouble(p.dir.x); b.writeDouble(p.dir.y); b.writeDouble(p.dir.z);
        b.writeShort(p.path.length);
        for (float y : p.path) b.writeFloat(y);
    }, b -> {
        byte kind = b.readByte(); int entity = b.readInt(); long tick = b.readLong();
        Vec3 from = new Vec3(b.readDouble(), b.readDouble(), b.readDouble()), dir = new Vec3(b.readDouble(), b.readDouble(), b.readDouble());
        float[] path = new float[b.readShort()];
        for (int i = 0; i < path.length; i++) path[i] = b.readFloat();
        return new DawnvowPayload(kind, entity, tick, from, dir, path);
    });

    public static DawnvowPayload of(byte kind, int entity, long tick) {
        return new DawnvowPayload(kind, entity, tick, Vec3.ZERO, Vec3.ZERO, new float[0]);
    }

    /** 剑气走多远：路线有几段。 */
    public double length() {
        return Math.max(0, path.length - 1) * SwordWave.PATH_STEP;
    }

    @Override public Type<DawnvowPayload> type() { return TYPE; }
}
