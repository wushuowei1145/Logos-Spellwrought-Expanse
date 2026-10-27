package dev.logos.dimension.item;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.mojang.serialization.Codec;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import io.netty.buffer.ByteBuf;

/** 萌芽印记记录施加者与绝对到期时间，重载实体不会刷新六秒期限。 */
public record SproutMark(UUID owner, long expiresAt) {
    public static final SproutMark EMPTY = new SproutMark(new UUID(0, 0), 0);
    public static final MapCodec<SproutMark> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            UUIDUtil.CODEC.fieldOf("owner").forGetter(SproutMark::owner),
            Codec.LONG.fieldOf("expires_at").forGetter(SproutMark::expiresAt)).apply(i, SproutMark::new));
    public static final StreamCodec<ByteBuf, SproutMark> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, SproutMark::owner, ByteBufCodecs.VAR_LONG, SproutMark::expiresAt, SproutMark::new);

    public boolean active(long now) { return expiresAt > now; }
    public boolean belongsTo(UUID attacker, long now) { return active(now) && owner.equals(attacker); }
}
