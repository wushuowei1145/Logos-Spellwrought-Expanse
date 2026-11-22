package dev.logos.dimension.jiansui;

import dev.logos.dimension.LogosDimension;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 剑祟终结里撬剑（docs/JIAN_SUI_BOSS.md 第 11 节）：打中剑祟的人在撬剑的窗口里按了一下攻击键（客户端到服务端）。
 * 算不算、什么时候撬由服务端定（{@link JianSuiFight#pryPress}）；不按也会自己撬完。
 */
public record JianSuiPry() implements CustomPacketPayload {
    public static final Type<JianSuiPry> TYPE = new Type<>(LogosDimension.id("jian_sui_pry"));
    public static final StreamCodec<ByteBuf, JianSuiPry> CODEC = StreamCodec.unit(new JianSuiPry());

    @Override public Type<JianSuiPry> type() { return TYPE; }

    /**
     * 终结开始（服务端到客户端，和黑白的「飞身刺」同一刻发）：打中的人、剑祟（实体编号），终结从哪一刻算起（服务端游戏刻，
     * 即 {@link JianSuiFight} 里时刻的零点）。客户端按它摆撬剑的姿势；打中的人那边还按它收攻击键。
     */
    public record Start(int hitter, int boss, long since) implements CustomPacketPayload {
        public static final Type<Start> TYPE = new Type<>(LogosDimension.id("jian_sui_pry_start"));
        public static final StreamCodec<ByteBuf, Start> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Start::hitter, ByteBufCodecs.VAR_INT, Start::boss, ByteBufCodecs.VAR_LONG, Start::since, Start::new);

        @Override public Type<Start> type() { return TYPE; }

        /** 发给 {@code at} 周围 {@code range} 格内的玩家（同 {@link JianSuiCue#near}）。 */
        public static void near(ServerLevel level, Vec3 at, double range, Start start) {
            for (ServerPlayer p : level.players())
                if (p.position().distanceToSqr(at) < range * range && p.connection.hasChannel(TYPE)) PacketDistributor.sendToPlayer(p, start);
        }
    }
}
