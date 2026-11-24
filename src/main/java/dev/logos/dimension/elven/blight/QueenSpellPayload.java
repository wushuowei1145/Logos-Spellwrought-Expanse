package dev.logos.dimension.elven.blight;

import dev.logos.dimension.LogosDimension;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/** 附属自己的猩红生命特效提示，不复用或伪造 Logos 的网络包。 */
public record QueenSpellPayload(int kind, Vec3 at, Vec3 aux, float scale, int ticks) implements CustomPacketPayload {
    /**
     * {@code VEIN}：这一条游脉（编号在 {@code ticks}）画成腐败荆棘；{@code THORN}：{@code at} 处地里向上刺出一簇荆棘；
     * {@code FIELD}：{@code at} 处半径 {@code scale} 的腐败领域（{@code ticks} 刻）；{@code PILLAR_MARK}：光柱落点的预警；
     * {@code PILLAR}：腐败之心从 {@code at} 轰到 {@code aux} 的光柱和落点的飞溅；{@code CUT}：处决腐败之心的一刀（{@code aux} 是刀的方向）。
     */
    public static final int CAST = 0, TRAIL = 1, HIT = 2, RAY = 3, SIGIL = 4, VEIN = 5, THORN = 6, FIELD = 7, PILLAR_MARK = 8, PILLAR = 9, CUT = 10;
    public static final Type<QueenSpellPayload> TYPE = new Type<>(LogosDimension.id("queen_spell"));
    public static final StreamCodec<ByteBuf, QueenSpellPayload> CODEC = StreamCodec.of((b, p) -> {
        b.writeInt(p.kind); b.writeDouble(p.at.x); b.writeDouble(p.at.y); b.writeDouble(p.at.z);
        b.writeDouble(p.aux.x); b.writeDouble(p.aux.y); b.writeDouble(p.aux.z); b.writeFloat(p.scale); b.writeInt(p.ticks);
    }, b -> new QueenSpellPayload(b.readInt(), new Vec3(b.readDouble(), b.readDouble(), b.readDouble()), new Vec3(b.readDouble(), b.readDouble(), b.readDouble()), b.readFloat(), b.readInt()));
    @Override public Type<QueenSpellPayload> type() { return TYPE; }
}
