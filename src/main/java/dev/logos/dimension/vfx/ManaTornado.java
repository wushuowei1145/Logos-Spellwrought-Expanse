package dev.logos.dimension.vfx;

import dev.logos.dimension.net.ManaTornadoPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 服务端入口：魔力围着施法者盘旋上升，卷成一道螺旋龙卷，脚下有旋转的地涡、上溅的光点、扬起的烟尘和被卷起的碎石。
 * 龙卷跟着施法者走，整体颜色可以随时间慢慢转色相。只有画面，没有伤害或破坏方块。见 docs/VFX.md。
 */
public final class ManaTornado {
    /** 龙卷高十几格，周围的玩家都该看到。 */
    private static final double RANGE = 128;
    /** 默认颜色转一圈用 12 秒。 */
    public static final int CYCLE = 240;

    private ManaTornado() {}

    /** 在 {@code caster} 身边卷 {@code ticks} 刻（负数表示一直卷到 {@link #stop}）；{@code cycle} 是颜色转一圈的刻数，0 保持原色。 */
    public static void play(ServerLevel level, Entity caster, int ticks, float scale, int cycle) {
        Vec3 at = caster.position();
        send(level, at, new ManaTornadoPayload(caster.getId(), at.x, at.y, at.z, ticks == 0 ? -1 : ticks, scale, cycle));
    }

    /** 不跟随任何实体，停在 {@code at}。 */
    public static void play(ServerLevel level, Vec3 at, int ticks, float scale, int cycle) {
        send(level, at, new ManaTornadoPayload(-1, at.x, at.y, at.z, ticks == 0 ? -1 : ticks, scale, cycle));
    }

    /** 收掉 {@code caster} 身上的龙卷（散开淡出，不是立刻消失）。 */
    public static void stop(ServerLevel level, Entity caster) {
        Vec3 at = caster.position();
        send(level, at, new ManaTornadoPayload(caster.getId(), at.x, at.y, at.z, 0, 1, 0));
    }

    private static void send(ServerLevel level, Vec3 at, ManaTornadoPayload payload) {
        for (ServerPlayer player : level.players()) {
            // 只发给协商过这个频道的连接（假玩家、没装附属的客户端没有）
            if (player.distanceToSqr(at) <= RANGE * RANGE && player.connection.hasChannel(ManaTornadoPayload.TYPE)) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }
}
