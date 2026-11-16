package dev.logos.dimension.net;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * 只发给协商过这个通道的玩家。
 *
 * <p>NeoForge 在 {@code connection.send} 里检查通道，没有协商的连接（GameTest 的模拟玩家、别的模组的假玩家、
 * 没装本附属的客户端）收到会抛 {@code UnsupportedOperationException}，在区块追踪事件里抛出来就把整个服务端崩掉。
 * {@link PacketDistributor} 的广播方法同样不过滤，所以这里逐个玩家检查后再发。
 */
public final class ToClients {
    private ToClients() {}

    /** 发给一个玩家；没有通道就不发。 */
    public static void player(ServerPlayer player, CustomPacketPayload payload) {
        if (player.connection != null && player.connection.hasChannel(payload.type())) PacketDistributor.sendToPlayer(player, payload);
    }

    /** 发给追踪这个区块的玩家。 */
    public static void trackingChunk(ServerLevel level, ChunkPos chunk, CustomPacketPayload payload) {
        for (ServerPlayer player : level.getChunkSource().chunkMap.getPlayers(chunk, false)) player(player, payload);
    }

    /** 同原版 {@code PlayerList.broadcast}：同一维度、距离小于 radius、排除 excluded。 */
    public static void near(ServerLevel level, @Nullable ServerPlayer excluded, double x, double y, double z, double radius, CustomPacketPayload payload) {
        for (ServerPlayer player : level.players()) {
            if (player == excluded) continue;
            double dx = x - player.getX(), dy = y - player.getY(), dz = z - player.getZ();
            if (dx * dx + dy * dy + dz * dz < radius * radius) player(player, payload);
        }
    }
}
