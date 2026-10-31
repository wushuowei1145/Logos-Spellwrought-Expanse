package dev.logos.dimension.vfx;

import dev.logos.dimension.net.PillarBurstPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 服务端入口：在 {@code at}（通常是地面上的一点）爆发一道直冲天际的金色光柱，带冲击波、尘土和碎石。
 * 只有画面，没有伤害或破坏方块；以后圣剑蓄力劈到某处后由它触发。见 docs/VFX.md。
 */
public final class PillarBurst {
    /** 光柱高 64 格，远处的玩家也该看到。 */
    private static final double RANGE = 160;

    private PillarBurst() {}

    public static void play(ServerLevel level, Vec3 at, float scale) {
        PillarBurstPayload payload = new PillarBurstPayload(at.x, at.y, at.z, scale);
        for (ServerPlayer player : level.players()) {
            // 只发给协商过这个频道的连接（假玩家、没装附属的客户端没有）
            if (player.distanceToSqr(at) <= RANGE * RANGE && player.connection.hasChannel(PillarBurstPayload.TYPE)) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }
}
