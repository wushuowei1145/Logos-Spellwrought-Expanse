package dev.logos.dimension.economy;

import dev.logos.dimension.LogosDimension;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/**
 * 超电磁炮的三个时刻，只给客户端画特效用：{@code CHARGE} 抛起硬币开始蓄力，{@code FIRE} 发射（{@code from} 是眼睛，
 * {@code to} 是射线的终点，{@code struck} 表示被硬方块挡住），{@code CANCEL} 松手作罢。{@code offhand} 表示用的是副手。
 */
public record RailgunPayload(byte kind, int entity, boolean offhand, Vec3 from, Vec3 to, boolean struck) implements CustomPacketPayload {
    public static final byte CHARGE = 0, FIRE = 1, CANCEL = 2;
    public static final Type<RailgunPayload> TYPE = new Type<>(LogosDimension.id("railgun"));
    public static final StreamCodec<ByteBuf, RailgunPayload> CODEC = StreamCodec.of((b, p) -> {
        b.writeByte(p.kind); b.writeInt(p.entity); b.writeBoolean(p.offhand);
        b.writeDouble(p.from.x); b.writeDouble(p.from.y); b.writeDouble(p.from.z);
        b.writeDouble(p.to.x); b.writeDouble(p.to.y); b.writeDouble(p.to.z);
        b.writeBoolean(p.struck);
    }, b -> new RailgunPayload(b.readByte(), b.readInt(), b.readBoolean(),
            new Vec3(b.readDouble(), b.readDouble(), b.readDouble()), new Vec3(b.readDouble(), b.readDouble(), b.readDouble()), b.readBoolean()));

    public static RailgunPayload of(byte kind, int entity, boolean offhand) {
        return new RailgunPayload(kind, entity, offhand, Vec3.ZERO, Vec3.ZERO, false);
    }

    @Override public Type<RailgunPayload> type() { return TYPE; }
}
