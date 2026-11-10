package dev.logos.dimension.music;

import dev.logos.dimension.LogosDimension;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.jspecify.annotations.Nullable;

/**
 * 乐器的网络包。实时演奏传音符（每 tick 一批，带批内毫秒偏移）；纸带播放只传「从哪一刻开始放哪卷纸带」，
 * 音符由各客户端按本地时钟自己放。见 docs/HEXENWALD_INSTRUMENTS.md 第 5 节。
 */
public final class MusicPayloads {
    /** 一批里最多的事件数。 */
    public static final int MAX_BATCH = 64;
    /** 上传纸带时每块的字节数：serverbound 自定义包上限 32767 字节，留出包头。 */
    public static final int UPLOAD_CHUNK = 30_000;
    public static final int MAX_UPLOAD_CHUNKS = (MusicRoll.MAX_BYTES + UPLOAD_CHUNK - 1) / UPLOAD_CHUNK;
    /** pitch 取这个值表示延音踏板（velocity &gt; 0 踩下）。 */
    public static final int PEDAL = 128;

    private MusicPayloads() {}

    /** 一个实时事件：offsetMs 是相对本批开始的毫秒；velocity 0 表示松开。 */
    public record LiveNote(int offsetMs, int pitch, int velocity) {
        static void write(FriendlyByteBuf b, LiveNote n) {
            b.writeShort(n.offsetMs);
            b.writeByte(n.pitch);
            b.writeByte(n.velocity);
        }

        static LiveNote read(FriendlyByteBuf b) {
            return new LiveNote(Math.max(0, b.readShort()), b.readUnsignedByte(), b.readUnsignedByte());
        }
    }

    private static void writeNotes(FriendlyByteBuf b, List<LiveNote> notes) {
        b.writeVarInt(notes.size());
        for (LiveNote n : notes) LiveNote.write(b, n);
    }

    private static List<LiveNote> readNotes(FriendlyByteBuf b) {
        int n = b.readVarInt();
        if (n < 0 || n > MAX_BATCH) throw new IllegalArgumentException("too many notes: " + n);
        List<LiveNote> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(LiveNote.read(b));
        return list;
    }

    /** 演奏者 → 服务端：这一 tick 弹下和松开的音。 */
    public record LiveNotes(BlockPos pos, List<LiveNote> notes) implements CustomPacketPayload {
        public static final Type<LiveNotes> TYPE = new Type<>(LogosDimension.id("instrument_live_notes"));
        public static final StreamCodec<RegistryFriendlyByteBuf, LiveNotes> CODEC = StreamCodec.of(
            (b, p) -> { b.writeBlockPos(p.pos); writeNotes(b, p.notes); },
            b -> new LiveNotes(b.readBlockPos(), readNotes(b)));

        @Override public Type<LiveNotes> type() { return TYPE; }
    }

    /** 服务端 → 附近的听众：转发演奏者的一批音；allOff 表示演奏者离开，全部松开。 */
    public record LiveNotesOut(BlockPos pos, int performer, boolean allOff, List<LiveNote> notes) implements CustomPacketPayload {
        public static final Type<LiveNotesOut> TYPE = new Type<>(LogosDimension.id("instrument_live_notes_out"));
        public static final StreamCodec<RegistryFriendlyByteBuf, LiveNotesOut> CODEC = StreamCodec.of(
            (b, p) -> { b.writeBlockPos(p.pos); b.writeVarInt(p.performer); b.writeBoolean(p.allOff); writeNotes(b, p.notes); },
            b -> new LiveNotesOut(b.readBlockPos(), b.readVarInt(), b.readBoolean(), readNotes(b)));

        @Override public Type<LiveNotesOut> type() { return TYPE; }
    }

    /** 演奏界面上的操作。 */
    public record Control(BlockPos pos, int action, int arg) implements CustomPacketPayload {
        public static final int CLAIM = 0, RELEASE = 1, PLAY = 2, PAUSE = 3, STOP = 4, EJECT = 5, PART_MASK = 6, SIGNAL_MODE = 7;
        public static final Type<Control> TYPE = new Type<>(LogosDimension.id("instrument_control"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Control> CODEC = StreamCodec.of(
            (b, p) -> { b.writeBlockPos(p.pos); b.writeVarInt(p.action); b.writeVarInt(p.arg); },
            b -> new Control(b.readBlockPos(), b.readVarInt(), b.readVarInt()));

        @Override public Type<Control> type() { return TYPE; }
    }

    /**
     * 服务端 → 追踪这个区块的玩家：纸带播放状态。roll 为空表示停止；positionMs 是此刻的播放位置。
     * 开始、暂停、停止时各发一次，播放中每 5 秒发一次心跳。
     */
    public record Playback(BlockPos pos, @Nullable UUID roll, long hash, int positionMs, boolean playing, int partMask)
            implements CustomPacketPayload {
        public static final Type<Playback> TYPE = new Type<>(LogosDimension.id("instrument_playback"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Playback> CODEC = StreamCodec.of(
            (b, p) -> {
                b.writeBlockPos(p.pos);
                b.writeBoolean(p.roll != null);
                if (p.roll != null) b.writeUUID(p.roll);
                b.writeLong(p.hash);
                b.writeVarInt(p.positionMs);
                b.writeBoolean(p.playing);
                b.writeVarInt(p.partMask);
            },
            b -> new Playback(b.readBlockPos(), b.readBoolean() ? b.readUUID() : null, b.readLong(), b.readVarInt(),
                b.readBoolean(), b.readVarInt()));

        @Override public Type<Playback> type() { return TYPE; }
    }

    /** 客户端 → 服务端：要一卷纸带的内容。 */
    public record RollRequest(UUID id) implements CustomPacketPayload {
        public static final Type<RollRequest> TYPE = new Type<>(LogosDimension.id("roll_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RollRequest> CODEC = StreamCodec.of(
            (b, p) -> b.writeUUID(p.id), b -> new RollRequest(b.readUUID()));

        @Override public Type<RollRequest> type() { return TYPE; }
    }

    /** 服务端 → 客户端：纸带内容（找不到时为空数组）。 */
    public record RollData(UUID id, byte[] data) implements CustomPacketPayload {
        public static final Type<RollData> TYPE = new Type<>(LogosDimension.id("roll_data"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RollData> CODEC = StreamCodec.of(
            (b, p) -> { b.writeUUID(p.id); b.writeByteArray(p.data); },
            b -> new RollData(b.readUUID(), b.readByteArray(MusicRoll.MAX_BYTES)));

        @Override public Type<RollData> type() { return TYPE; }
    }

    /**
     * 客户端 → 服务端：上传一卷纸带（录制、打孔编辑器、MIDI 导入）的一块。全部到齐后服务端重新校验。
     * purpose：{@link #WRITE} 刻进乐器里的纸带，{@link #TEMPORARY} 只作临时纸带直接播放。
     */
    public record Upload(int uploadId, int index, int total, int purpose, BlockPos pos, byte[] chunk) implements CustomPacketPayload {
        public static final int WRITE = 0, TEMPORARY = 1;
        public static final Type<Upload> TYPE = new Type<>(LogosDimension.id("roll_upload"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Upload> CODEC = StreamCodec.of(
            (b, p) -> {
                b.writeVarInt(p.uploadId);
                b.writeVarInt(p.index);
                b.writeVarInt(p.total);
                b.writeVarInt(p.purpose);
                b.writeBlockPos(p.pos);
                b.writeByteArray(p.chunk);
            },
            b -> new Upload(b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readBlockPos(),
                b.readByteArray(UPLOAD_CHUNK)));

        @Override public Type<Upload> type() { return TYPE; }
    }
}
