package dev.logos.dimension.music;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 打孔纸带物品上的数据组件：只存引用和摘要（照原版地图的做法），内容在服务端存档里，
 * 见 {@link RollStore}。纸带写好后内容不变，所以客户端可以按 ID + 哈希缓存。
 */
public record RollInfo(UUID id, String title, String author, int durationMs, int noteCount, List<String> parts, long hash) {
    public static final Codec<RollInfo> CODEC = RecordCodecBuilder.create(i -> i.group(
        UUIDUtil.CODEC.fieldOf("id").forGetter(RollInfo::id),
        Codec.STRING.optionalFieldOf("title", "").forGetter(RollInfo::title),
        Codec.STRING.optionalFieldOf("author", "").forGetter(RollInfo::author),
        Codec.INT.optionalFieldOf("duration_ms", 0).forGetter(RollInfo::durationMs),
        Codec.INT.optionalFieldOf("notes", 0).forGetter(RollInfo::noteCount),
        Codec.STRING.listOf().optionalFieldOf("parts", List.of()).forGetter(RollInfo::parts),
        Codec.LONG.optionalFieldOf("hash", 0L).forGetter(RollInfo::hash)
    ).apply(i, RollInfo::new));

    public static final StreamCodec<ByteBuf, RollInfo> STREAM_CODEC = StreamCodec.of(
        (b, r) -> {
            FriendlyByteBuf f = new FriendlyByteBuf(b);
            f.writeUUID(r.id);
            f.writeUtf(r.title, MusicRoll.MAX_TITLE * 4);
            f.writeUtf(r.author, MusicRoll.MAX_TITLE * 4);
            f.writeVarInt(r.durationMs);
            f.writeVarInt(r.noteCount);
            f.writeVarInt(r.parts.size());
            for (String p : r.parts) f.writeUtf(p, MusicRoll.MAX_PART_NAME * 4);
            f.writeLong(r.hash);
        },
        b -> {
            FriendlyByteBuf f = new FriendlyByteBuf(b);
            UUID id = f.readUUID();
            String title = f.readUtf(MusicRoll.MAX_TITLE * 4);
            String author = f.readUtf(MusicRoll.MAX_TITLE * 4);
            int dur = f.readVarInt();
            int notes = f.readVarInt();
            int n = Math.min(MusicRoll.MAX_PARTS, f.readVarInt());
            List<String> parts = new java.util.ArrayList<>(n);
            for (int k = 0; k < n; k++) parts.add(f.readUtf(MusicRoll.MAX_PART_NAME * 4));
            return new RollInfo(id, title, author, dur, notes, List.copyOf(parts), f.readLong());
        });

    public static RollInfo of(UUID id, MusicRoll roll, long hash) {
        return new RollInfo(id, roll.title(), roll.author(), roll.durationMs(), roll.noteCount(),
            roll.parts().stream().map(MusicRoll.Part::name).toList(), hash);
    }

    /** 「3:25」这样的时长文字。 */
    public static String clock(int ms) {
        int s = Math.max(0, ms) / 1000;
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }
}
