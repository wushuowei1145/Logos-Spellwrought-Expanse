package dev.logos.dimension.elven.blight;

import dev.logos.lang.*;
import dev.logos.runtime.Vfx;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** 换色的网络边界。弹体模型继续走公开 Vfx 接口，叶片、花瓣与法阵由附属画。 */
public final class QueenSpellEffects {
    private QueenSpellEffects() {}
    private static long replaced;
    /** 换成腐败特效的次数，只给测试看。 */
    public static long replaced() { return replaced; }
    public static void register(IEventBus bus) { bus.addListener((RegisterPayloadHandlersEvent e) -> e.registrar("1").playToClient(QueenSpellPayload.TYPE, QueenSpellPayload.CODEC)); }
    public static void send(ServerLevel world, int kind, Vec3 at, Vec3 aux, float scale, int ticks) {
        var packet = new QueenSpellPayload(kind, at, aux, scale, ticks);
        for (var p : world.players()) if (p.distanceToSqr(at) < 96 * 96 && p.connection.hasChannel(QueenSpellPayload.TYPE)) PacketDistributor.sendToPlayer(p, packet);
    }
    public static boolean replace(ServerLevel world, String kind, int variant, Vec3 at, Vec3 aux, float scale, int id, Vfx.Guise guise) {
        switch (kind) {
            case "TRAIL" -> {
                // 公开接口保留真实飞剑的物品外观，用中性的白热核心替代绿色弹体底层。
                Vfx.trail(world, id, at, aux, Element.PHYSICAL, Motion.values()[Math.clamp(variant, 0, Motion.values().length - 1)], scale, guise);
                if (world.getGameTime() % 2 == 0) send(world, QueenSpellPayload.TRAIL, at, aux, scale, 12);
            }
            case "TRAIL_END" -> Vfx.trailEnd(world, id, at, Element.PHYSICAL);
            case "PROJECTILE_IMPACT", "BURST" -> send(world, QueenSpellPayload.HIT, at, aux, Math.max(.6f, scale), 18);
            case "CAST", "RING", "PULSE", "MARK" -> send(world, QueenSpellPayload.CAST, at, aux, Math.max(1, scale), 24);
            case "BEAM" -> send(world, QueenSpellPayload.RAY, at, aux, 1, 14);
            default -> { return false; }
        }
        replaced++;
        return true;
    }
}
