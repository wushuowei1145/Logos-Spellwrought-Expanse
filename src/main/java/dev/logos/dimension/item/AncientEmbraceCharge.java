package dev.logos.dimension.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** 盾牌自己的五片叶与绝对到期时间；存档和网络使用同一份状态。 */
public record AncientEmbraceCharge(int leaves, long expiresAt) {
    public static final AncientEmbraceCharge EMPTY = new AncientEmbraceCharge(0, 0);
    public static final int MAX = 5;
    public static final int LIFETIME = 160;
    public static final Codec<AncientEmbraceCharge> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.intRange(0, MAX).fieldOf("leaves").forGetter(AncientEmbraceCharge::leaves),
            Codec.LONG.fieldOf("expires_at").forGetter(AncientEmbraceCharge::expiresAt)).apply(i, AncientEmbraceCharge::new));
    public static final StreamCodec<ByteBuf, AncientEmbraceCharge> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, AncientEmbraceCharge::leaves, ByteBufCodecs.VAR_LONG, AncientEmbraceCharge::expiresAt, AncientEmbraceCharge::new);

    public int activeLeaves(long now) { return expiresAt > now ? leaves : 0; }
    public AncientEmbraceCharge block(long now, boolean perfect) {
        int active = activeLeaves(now);
        // 蓄满后的额外格挡不延长生机期限，也不储存第二次治疗。
        return active == MAX ? this : new AncientEmbraceCharge(Math.min(MAX, active + (perfect ? 2 : 1)), now + LIFETIME);
    }
}
