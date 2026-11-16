package dev.logos.dimension.elven.blight;

import dev.logos.lang.*;
import dev.logos.runtime.Vfx;
import java.util.LinkedHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * 换色的网络边界：腐败一方放的 Logos 生命法术在这里换成腐败的样子（{@code CorruptionVfxMixin} 调进来）。
 * 弹体模型继续走公开 Vfx 接口，叶片、花瓣、法阵、荆棘、领域由附属画。
 */
public final class QueenSpellEffects {
    private QueenSpellEffects() {}
    private static long replaced, thorns;
    private static Vec3 lastField = Vec3.ZERO;
    /** 已经告诉过客户端的游脉、领域（每条只说一次）。 */
    private static final LinkedHashSet<Integer> CUED = new LinkedHashSet<>();
    /** 换成腐败特效的次数，只给测试看。 */
    public static long replaced() { return replaced; }
    /** 画成向上刺出的荆棘的射线数，只给测试看。 */
    public static long thorns() { return thorns; }
    /** 最近一块腐败领域的中心，只给测试看。 */
    public static Vec3 lastField() { return lastField; }
    public static void register(IEventBus bus) { bus.addListener((RegisterPayloadHandlersEvent e) -> e.registrar("1").playToClient(QueenSpellPayload.TYPE, QueenSpellPayload.CODEC)); }
    public static void send(ServerLevel world, int kind, Vec3 at, Vec3 aux, float scale, int ticks) {
        var packet = new QueenSpellPayload(kind, at, aux, scale, ticks);
        for (var p : world.players()) if (p.distanceToSqr(at) < 96 * 96 && p.connection.hasChannel(QueenSpellPayload.TYPE)) PacketDistributor.sendToPlayer(p, packet);
    }
    private static synchronized boolean first(int kind, int id) {
        int key = id * 31 + kind;
        if (!CUED.add(key)) return false;
        if (CUED.size() > 512) CUED.removeFirst();
        return true;
    }
    /** 返回 true 时原来的生命特效不再发出去。 */
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
            case "BEAM" -> {
                Vec3 d = aux.subtract(at);
                double length = d.length();
                // 向上的射线（荆棘穿刺）画成从地里刺出的荆棘，其余的照旧是猩红的一线。
                boolean up = d.y >= -.05 && Math.hypot(d.x, d.z) < Math.max(.3, length * .3);
                if (up) { send(world, QueenSpellPayload.THORN, at, aux, (float) Math.min(3.2, Math.max(1.8, length)), 16); thorns++; }
                else send(world, QueenSpellPayload.RAY, at, aux, 1, 14);
            }
            case "VEIN_HOLD" -> {
                // 游脉的头仍由 Logos 同步，只先告诉客户端这一条画成腐败荆棘（客户端 CorruptionVeinVisualsMixin 换样子）。
                if (first(QueenSpellPayload.VEIN, id)) { send(world, QueenSpellPayload.VEIN, at, aux, scale, id); replaced++; }
                return false;
            }
            case "FIELD_HOLD" -> {
                // aux 是（已经过了几刻，一共几刻，几刻一跳）。
                if (first(QueenSpellPayload.FIELD, id)) { send(world, QueenSpellPayload.FIELD, at, Vec3.ZERO, scale, (int) Math.max(10, aux.y - aux.x)); lastField = at; }
            }
            case "FIELD_PULSE", "FIELD_ENTER", "FIELD_END" -> { }
            default -> { return false; }
        }
        replaced++;
        return true;
    }
}
