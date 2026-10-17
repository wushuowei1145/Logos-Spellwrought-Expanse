package dev.logos.dimension;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;

/** Empty spawn tables plus placement guards reserve the island for future scripted bosses. */
public final class IslandSanctum {
    public static boolean contains(ServerLevel level, BlockPos pos) {
        return LogosDimension.isCrucible(level.dimension())&&pos.getY()>=NativeWorlds.ISLAND_TOP-72
                &&Math.hypot(pos.getX()-NativeWorlds.ISLAND_X,pos.getZ()-NativeWorlds.ISLAND_Z)<=NativeWorlds.ISLAND_RADIUS;
    }
    private static boolean automatic(EntitySpawnReason reason) {
        return reason != EntitySpawnReason.COMMAND && reason != EntitySpawnReason.TRIGGERED;
    }
    public static void placement(MobSpawnEvent.SpawnPlacementCheck event) {
        if (automatic(event.getSpawnType()) && contains(event.getLevel().getLevel(), event.getPos()))
            event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
    }
    public static void position(MobSpawnEvent.PositionCheck event) {
        if (automatic(event.getSpawnType()) && contains(event.getLevel().getLevel(), BlockPos.containing(event.getX(), event.getY(), event.getZ())))
            event.setResult(MobSpawnEvent.PositionCheck.Result.FAIL);
    }
    private IslandSanctum() {}
}
