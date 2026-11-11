package dev.logos.dimension.music.orchestra;

import dev.logos.dimension.LogosDimension;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.jspecify.annotations.Nullable;

/** 乐团的网络包（docs/ORCHESTRA.md 第 6 节）。 */
public final class EnsemblePayloads {
    private EnsemblePayloads() {}

    /** 一件成员乐器演奏哪几个声部。 */
    public record MemberPart(BlockPos pos, int mask) {}

    /**
     * 服务端 → 附近的玩家：乐团的播放状态。开始、停止、改速度 / 响度 / 静音时各发一次，播放中每 5 秒一次心跳。
     * positionMs 是此刻的位置（起奏前的预备拍里为负），tempo 是速度百分比；roll 为空表示乐团没有在演奏。
     * 一个乐团只发这一种包，成员再多也不多发。
     */
    public record EnsembleState(BlockPos podium, @Nullable UUID roll, long hash, int positionMs, int durationMs, int tempo,
                                List<MemberPart> members, byte[] gains, int muteMask, int soloMask) implements CustomPacketPayload {
        public static final Type<EnsembleState> TYPE = new Type<>(LogosDimension.id("ensemble_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, EnsembleState> CODEC = StreamCodec.of(
            (b, p) -> {
                b.writeBlockPos(p.podium);
                b.writeBoolean(p.roll != null);
                if (p.roll != null) b.writeUUID(p.roll);
                b.writeLong(p.hash);
                b.writeInt(p.positionMs);
                b.writeVarInt(p.durationMs);
                b.writeVarInt(p.tempo);
                b.writeVarInt(p.members.size());
                for (MemberPart m : p.members) {
                    b.writeBlockPos(m.pos());
                    b.writeVarInt(m.mask());
                }
                b.writeByteArray(p.gains);
                b.writeVarInt(p.muteMask);
                b.writeVarInt(p.soloMask);
            },
            b -> {
                BlockPos podium = b.readBlockPos();
                UUID roll = b.readBoolean() ? b.readUUID() : null;
                long hash = b.readLong();
                int pos = b.readInt();
                int duration = b.readVarInt();
                int tempo = b.readVarInt();
                int n = Math.min(PodiumBlockEntity.MAX_MEMBERS, b.readVarInt());
                List<MemberPart> members = new ArrayList<>(n);
                for (int i = 0; i < n; i++) members.add(new MemberPart(b.readBlockPos(), b.readVarInt()));
                byte[] gains = b.readByteArray(64);
                return new EnsembleState(podium, roll, hash, pos, duration, tempo, members, gains, b.readVarInt(), b.readVarInt());
            });

        public boolean playing() {
            return roll != null;
        }

        @Override public Type<EnsembleState> type() { return TYPE; }
    }

    /** 客户端 → 服务端：指挥台界面上的操作。 */
    public record PodiumControl(BlockPos podium, int action, int a, int b) implements CustomPacketPayload {
        /** a = 从第几小节（从 0 起）。 */
        public static final int START = 0;
        public static final int STOP = 1;
        public static final int GATHER = 2;
        public static final int REASSIGN = 3;
        /** a = 成员下标，b = 声部掩码（手动改派，锁定）。 */
        public static final int ASSIGN = 4;
        /** a = 成员下标：解锁，交给自动分派。 */
        public static final int UNLOCK = 5;
        /** a = 成员下标：退出乐团。 */
        public static final int REMOVE = 6;
        /** a = 速度百分比（50–150），从下一小节起。 */
        public static final int TEMPO = 7;
        /** a = 声部，b = 响度（-12 到 +6 dB）。 */
        public static final int GAIN = 8;
        /** a = 声部：切换静音。 */
        public static final int MUTE = 9;
        /** a = 声部：切换独奏。 */
        public static final int SOLO = 10;
        /** 取出总谱。 */
        public static final int EJECT = 11;

        public static final Type<PodiumControl> TYPE = new Type<>(LogosDimension.id("podium_control"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PodiumControl> CODEC = StreamCodec.of(
            (b, p) -> { b.writeBlockPos(p.podium); b.writeVarInt(p.action); b.writeInt(p.a); b.writeInt(p.b); },
            b -> new PodiumControl(b.readBlockPos(), b.readVarInt(), b.readInt(), b.readInt()));

        @Override public Type<PodiumControl> type() { return TYPE; }
    }
}
