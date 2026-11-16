package dev.logos.dimension.client;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.camera.CameraDirector;
import dev.logos.dimension.elven.blight.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.minecraft.world.level.material.FogType;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import org.jspecify.annotations.Nullable;

/** 先看女王抱住核心，再沿树洞出口升至林冠上方；不移动玩家实体。 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class QueenEndingClient {
    private static @Nullable ClientLevel level;
    private static @Nullable QueenEndingPayload cue;
    private static @Nullable Float held;
    private QueenEndingClient() {}
    @SubscribeEvent static void packets(RegisterClientPayloadHandlersEvent e) {
        e.register(QueenEndingPayload.TYPE, (p, ctx) -> { cue = p; level = Minecraft.getInstance().level; held = null; CameraDirector.attachRig(QueenEndingClient::aim); });
    }
    public static float age() {
        if (cue == null || level == null) return -1;
        return held == null ? level.getGameTime() - cue.began() + Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false) : held;
    }
    public static boolean active() { var mc = Minecraft.getInstance(); return cue != null && mc.level == level && mc.player != null && mc.player.isAlive() && age() >= 0 && age() < QueenBattleRules.END; }
    public static Vec3 center() { return cue == null ? Vec3.ZERO : cue.floor().add(0, 2, 0); }
    public static void hold(@Nullable Float age) { held = age; }
    /** 结局的大范围净化：波内显露已还原的地景，波外暂染腐败色（世界球体着色的显露模式）。 */
    public static void tints(java.util.List<WorldTintScreen.Sphere> out) {
        if (!active()) return;
        float age = age();
        if (age < QueenBattleRules.EXPLODE) return;
        float fade = 1 - QueenBattleRules.smooth((age - 270) / 40), rim = QueenBattleRules.smooth((age - 98) / 12) * fade;
        out.add(new WorldTintScreen.Sphere(center(), QueenBattleRules.radius(age), WorldTintScreen.Mode.REVEAL, fade, QueenBattleRules.PURIFY_RADIUS, rim));
    }
    /** 结局全局的一次收缩和眩光（爆开前后）。 */
    public static WorldTintScreen.@Nullable Ending ending() {
        if (!active() || age() < 45) return null;
        float age = age();
        return new WorldTintScreen.Ending((float) Math.exp(-Math.pow((age - 72) / 12, 2)), (float) Math.exp(-Math.pow((age - 84) / 7, 2)) * .55f, center());
    }
    @SubscribeEvent static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post e) {
        var mc = Minecraft.getInstance();
        if (level != null && (mc.level != level || mc.player == null || !mc.player.isAlive())) { level = null; cue = null; held = null; }
    }
    /** 只在升空镜头里推远空气雾，避免仍按地面玩家群系计算的浓雾遮住整个林冠。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void aerialFog(ViewportEvent.RenderFog e) {
        if (!active() || (e.getType() != FogType.ATMOSPHERIC && e.getType() != FogType.NONE)) return;
        float blend = QueenBattleRules.smooth((age() - 115) / 65) * CameraDirector.weight();
        if (blend <= 0) return;
        var fog = e.getFogData();
        fog.environmentalStart += (Math.max(fog.environmentalStart, 340) - fog.environmentalStart) * blend;
        fog.environmentalEnd += (Math.max(fog.environmentalEnd, 600) - fog.environmentalEnd) * blend;
        fog.renderDistanceStart += (Math.max(fog.renderDistanceStart, 340) - fog.renderDistanceStart) * blend;
        fog.renderDistanceEnd += (Math.max(fog.renderDistanceEnd, 600) - fog.renderDistanceEnd) * blend;
    }
    private static CameraDirector.Aim aim(float ignored, float partial) {
        if (!active()) return null;
        float age = age();
        Vec3 floor = cue.floor(), front = cue.front();
        float rise = QueenBattleRules.smooth((age - 125) / 150);
        Vec3 from;
        if (age < 70) from = floor.add(front.scale(12 - 3 * QueenBattleRules.smooth(age / 70))).add(0, 5, 0);
        else if (age < 130) { float t = QueenBattleRules.smooth((age - 70) / 60); from = floor.add(front.scale(9 + t * 31)).add(0, 5 + t * 7, 0); }
        else from = floor.add(front.scale(40 + 125 * rise)).add(0, 12 + rise * 215, 0);
        Vec3 look = floor.add(0, 3 + rise * 130, 0);
        return new CameraDirector.Aim(from, look, 0);
    }
}
