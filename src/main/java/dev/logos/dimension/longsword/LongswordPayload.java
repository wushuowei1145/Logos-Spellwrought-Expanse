package dev.logos.dimension.longsword;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.Moveset;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 只同步表现所需的动作快照：招式名、动作片段、开始刻、播放速度与方式、进入时的过渡时长（秒）；
 * 客户端不需要招式文件，也不能指定目标、伤害或连招段数。move 为空表示动作结束、恢复原版。
 */
public record LongswordPayload(UUID player, int variant, String move, String clip, double started, float speed, float offset,
                               Moveset.Playback playback, float blend, byte flags) implements CustomPacketPayload {
    /** 当前招式接受右键（可开始蓄力）。 */
    public static final byte ACCEPTS_USE = 1;
    /** 正在收剑：拦截攻击与使用，避免新换上的物品被误用。 */
    public static final byte SHEATHING = 2;
    /** 当前招式接受左键；按住右键时客户端也要把左键发给服务端（例如弹刀后接突刺）。 */
    public static final byte ACCEPTS_ATTACK = 4;
    public static final Type<LongswordPayload> TYPE = new Type<>(LogosDimension.id("longsword_pose"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LongswordPayload> CODEC = StreamCodec.of((b, p) -> {
        b.writeUUID(p.player); b.writeVarInt(p.variant); b.writeUtf(p.move); b.writeUtf(p.clip); b.writeDouble(p.started);
        b.writeFloat(p.speed); b.writeFloat(p.offset); b.writeEnum(p.playback); b.writeFloat(p.blend); b.writeByte(p.flags);
    }, b -> new LongswordPayload(b.readUUID(), b.readVarInt(), b.readUtf(), b.readUtf(), b.readDouble(), b.readFloat(), b.readFloat(),
            b.readEnum(Moveset.Playback.class), b.readFloat(), b.readByte()));
    public static LongswordPayload clear(UUID player) { return new LongswordPayload(player, 0, "", "", 0, 1, 0, Moveset.Playback.ONCE, 0, (byte) 0); }
    public boolean cleared() { return move.isEmpty(); }
    public boolean acceptsUse() { return (flags & ACCEPTS_USE) != 0; }
    public boolean sheathing() { return (flags & SHEATHING) != 0; }
    public boolean acceptsAttack() { return (flags & ACCEPTS_ATTACK) != 0; }
    @Override public Type<LongswordPayload> type() { return TYPE; }

    /** 松开左键（招式文件的 {@code attack_release}）。 */
    public record AttackRelease() implements CustomPacketPayload {
        public static final Type<AttackRelease> TYPE = new Type<>(LogosDimension.id("longsword_attack_release"));
        public static final StreamCodec<RegistryFriendlyByteBuf, AttackRelease> CODEC = StreamCodec.unit(new AttackRelease());
        @Override public Type<AttackRelease> type() { return TYPE; }
    }
    public record Attack() implements CustomPacketPayload {
        public static final Type<Attack> TYPE = new Type<>(LogosDimension.id("longsword_attack"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Attack> CODEC = StreamCodec.unit(new Attack());
        @Override public Type<Attack> type() { return TYPE; }
    }
}
