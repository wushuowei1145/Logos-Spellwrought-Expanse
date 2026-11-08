package dev.logos.dimension.explosion;

import dev.logos.dimension.LogosDimension;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/**
 * 爆裂魔法发给客户端的几种消息：
 * <ul>
 *   <li>{@code CHARGE}：吟唱中，每两刻一次，带进度、是否蓄满和目的地；</li>
 *   <li>{@code STOP}：没蓄满就松手、魔力断了或者被打断，客户端让特效熄灭；</li>
 *   <li>{@code RELEASE}：蓄满后松手，在 {@code target} 放出爆裂魔法，{@code radius} 是爆炸半径，{@code seed} 让各客户端的随机细节一致；</li>
 *   <li>{@code EXHAUST}：只发给施法者自己：魔力耗尽，{@code seed} 大于 0 时趴下（还剩多少刻），0 时起来（{@link ExplosionExhaustion}）。</li>
 * </ul>
 */
public record ExplosionPayload(byte kind, int caster, float progress, boolean full, Vec3 target, float radius, long seed)
        implements CustomPacketPayload {
    public static final byte CHARGE = 0, STOP = 1, RELEASE = 2, EXHAUST = 3;
    public static final Type<ExplosionPayload> TYPE = new Type<>(LogosDimension.id("explosion_magic"));
    public static final StreamCodec<ByteBuf, ExplosionPayload> CODEC = StreamCodec.of((b, p) -> {
        b.writeByte(p.kind);
        b.writeInt(p.caster);
        b.writeFloat(p.progress);
        b.writeBoolean(p.full);
        b.writeDouble(p.target.x);
        b.writeDouble(p.target.y);
        b.writeDouble(p.target.z);
        b.writeFloat(p.radius);
        b.writeLong(p.seed);
    }, b -> new ExplosionPayload(b.readByte(), b.readInt(), b.readFloat(), b.readBoolean(),
            new Vec3(b.readDouble(), b.readDouble(), b.readDouble()), b.readFloat(), b.readLong()));

    public static ExplosionPayload charge(int caster, float progress, boolean full, Vec3 target, float radius) {
        return new ExplosionPayload(CHARGE, caster, progress, full, target, radius, 0);
    }

    public static ExplosionPayload stop(int caster) {
        return new ExplosionPayload(STOP, caster, 0, false, Vec3.ZERO, 0, 0);
    }

    public static ExplosionPayload release(int caster, Vec3 target, float radius, long seed) {
        return new ExplosionPayload(RELEASE, caster, 1, true, target, radius, seed);
    }

    public static ExplosionPayload exhaust(int caster, int ticks) {
        return new ExplosionPayload(EXHAUST, caster, 0, false, Vec3.ZERO, 0, ticks);
    }

    @Override public Type<ExplosionPayload> type() { return TYPE; }
}
