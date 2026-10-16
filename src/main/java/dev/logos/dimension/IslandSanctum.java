package dev.logos.dimension;

import dev.logos.dimension.world.AlchemicalChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;

/** Empty spawn tables plus placement guards reserve the island for future scripted bosses. */
public final class IslandSanctum {
    public static boolean contains(ServerLevel level, BlockPos pos) {
        if (!LogosDimension.isCrucible(level.dimension())
                || !(level.getChunkSource().getGenerator() instanceof AlchemicalChunkGenerator generator)) return false;
        var c = generator.terrain().column(pos.getX(), pos.getZ());
        return c.hasIsland() && pos.getY() >= c.islandBottom() - 8;
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
