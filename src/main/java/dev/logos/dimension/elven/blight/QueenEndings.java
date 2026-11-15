package dev.logos.dimension.elven.blight;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.camera.*;
import java.util.*;
import net.minecraft.core.Direction;
import net.minecraft.server.level.*;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** 自爆是剧情爆发，不炸死锁镜头的玩家，也不破坏战场；保护随时钟结束。 */
@EventBusSubscriber(modid = LogosDimension.ID)
public final class QueenEndings {
    private record Viewing(ServerLevel level, long until) {}
    private static final Map<UUID, Viewing> VIEWERS = new HashMap<>();
    private QueenEndings() {}
    public static void register(IEventBus bus) { bus.addListener((RegisterPayloadHandlersEvent e) -> e.registrar("1").playToClient(QueenEndingPayload.TYPE, QueenEndingPayload.CODEC)); }
    public static void join(CorruptedElfQueen queen, int age, Set<UUID> sent) {
        if (!(queen.level() instanceof ServerLevel level) || age >= QueenBattleRules.END - 24) return;
        var heart = queen.heartPosition() == null ? queen.blockPosition().above(7) : queen.heartPosition();
        var state = level.getBlockState(heart);
        Vec3 front = state.hasProperty(QueenHeartBlock.FACING) ? state.getValue(QueenHeartBlock.FACING).getOpposite().getUnitVec3() : Direction.NORTH.getUnitVec3();
        for (ServerPlayer p : level.players()) {
            if (!p.isAlive() || p.isSpectator() || p.distanceToSqr(queen.arena()) > 48 * 48 || sent.contains(p.getUUID()) || !p.connection.hasChannel(QueenEndingPayload.TYPE)) continue;
            sent.add(p.getUUID());
            VIEWERS.put(p.getUUID(), new Viewing(level, level.getGameTime() + QueenBattleRules.END - age));
            Vec3 from = CameraShots.reach(level, queen.arena().add(0, 3, 0), queen.arena().add(front.scale(12)).add(0, 5, 0));
            CameraShots.play(p, CameraShot.of(CameraPoint.world(from), CameraPoint.world(queen.arena().add(0, 3, 0)))
                    .timing(Math.min(18, QueenBattleRules.END - age - 24), Math.max(0, QueenBattleRules.END - age - 42), 24).letterbox());
            PacketDistributor.sendToPlayer(p, new QueenEndingPayload(heart, queen.arena(), front, level.getGameTime() - age));
        }
    }
    public static boolean protectedNow(ServerPlayer p) { var v = VIEWERS.get(p.getUUID()); return v != null && p.isAlive() && p.level() == v.level && v.level.getGameTime() < v.until; }
    @SubscribeEvent static void protect(LivingIncomingDamageEvent e) { if (e.getEntity() instanceof ServerPlayer p && protectedNow(p) && !e.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) e.setCanceled(true); }
    @SubscribeEvent static void tick(LevelTickEvent.Post e) {
        if (e.getLevel() instanceof ServerLevel level) VIEWERS.entrySet().removeIf(entry -> entry.getValue().level == level && (level.getServer().getPlayerList().getPlayer(entry.getKey()) == null || !protectedNow(level.getServer().getPlayerList().getPlayer(entry.getKey()))));
    }
    @SubscribeEvent static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent e) { VIEWERS.clear(); }
}
