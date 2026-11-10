package dev.logos.dimension.jinghong;

import dev.logos.dimension.LogosDimension;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 惊鸿剑阵的同步包（服务端 → 客户端），只带表现要用的东西：哪把剑、什么时候出发、打谁、从哪个方向来。
 * 伤害和概率都在服务端，客户端只按 {@link SwordFlight} 的时间轴画。
 */
public final class SwordArrayPayload {
    private SwordArrayPayload() {}

    /** 身后五把剑各自激活到哪一刻（游戏刻，不大于现在就是没激活）。 */
    public record Active(UUID player, long[] until) implements CustomPacketPayload {
        public static final Type<Active> TYPE = new Type<>(LogosDimension.id("sword_array_active"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Active> CODEC = StreamCodec.of((b, p) -> {
            b.writeUUID(p.player); b.writeVarInt(p.until.length);
            for (long v : p.until) b.writeVarLong(v);
        }, b -> {
            var player = b.readUUID(); int n = Math.min(SwordFlight.FAN, b.readVarInt());
            long[] until = new long[SwordFlight.FAN];
            for (int i = 0; i < n; i++) until[i] = b.readVarLong();
            return new Active(player, until);
        });
        @Override public Type<Active> type() { return TYPE; }
    }

    /**
     * 第 {@code sword} 把剑在 {@code start} 刻出发追击实体 {@code target}；{@code dirs} 是每一段穿过目标的方向（单位向量，
     * 每三个数一段），段数就是 {@code dirs.length / 3}。{@code lit} 为出发时这把剑已经激活（发光、带物理伤害）。
     */
    public record Strike(UUID player, int sword, int target, long start, float[] dirs, boolean lit) implements CustomPacketPayload {
        public static final Type<Strike> TYPE = new Type<>(LogosDimension.id("sword_array_strike"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Strike> CODEC = StreamCodec.of((b, p) -> {
            b.writeUUID(p.player); b.writeVarInt(p.sword); b.writeVarInt(p.target); b.writeVarLong(p.start);
            b.writeVarInt(p.dirs.length); for (float v : p.dirs) b.writeFloat(v);
            b.writeBoolean(p.lit);
        }, b -> {
            var player = b.readUUID(); int sword = b.readVarInt(), target = b.readVarInt(); long start = b.readVarLong();
            int n = Math.min(3 * 8, b.readVarInt());
            float[] dirs = new float[n - n % 3];
            for (int i = 0; i < n; i++) { float v = b.readFloat(); if (i < dirs.length) dirs[i] = v; }
            return new Strike(player, sword, target, start, dirs, b.readBoolean());
        });
        public int passes() { return dirs.length / 3; }
        @Override public Type<Strike> type() { return TYPE; }
    }

    /** 手里的剑在 {@code start} 刻掷出：打 {@code target}；没有目标（-1）时飞到 {@code x, y, z} 再回来。 */
    public record Throw(UUID player, int target, long start, double x, double y, double z) implements CustomPacketPayload {
        public static final Type<Throw> TYPE = new Type<>(LogosDimension.id("sword_array_throw"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Throw> CODEC = StreamCodec.of((b, p) -> {
            b.writeUUID(p.player); b.writeVarInt(p.target); b.writeVarLong(p.start);
            b.writeDouble(p.x); b.writeDouble(p.y); b.writeDouble(p.z);
        }, b -> new Throw(b.readUUID(), b.readVarInt(), b.readVarLong(), b.readDouble(), b.readDouble(), b.readDouble()));
        @Override public Type<Throw> type() { return TYPE; }
    }

    /** 万剑归宗：{@code start} 刻起，五把剑与虚影剑打 {@code target}；{@code seed} 定虚影剑的方位。 */
    public record Finale(UUID player, int target, long start, int seed) implements CustomPacketPayload {
        public static final Type<Finale> TYPE = new Type<>(LogosDimension.id("sword_array_finale"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Finale> CODEC = StreamCodec.of((b, p) -> {
            b.writeUUID(p.player); b.writeVarInt(p.target); b.writeVarLong(p.start); b.writeInt(p.seed);
        }, b -> new Finale(b.readUUID(), b.readVarInt(), b.readVarLong(), b.readInt()));
        @Override public Type<Finale> type() { return TYPE; }
    }
}
