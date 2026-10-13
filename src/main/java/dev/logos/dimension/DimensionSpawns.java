package dev.logos.dimension;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;

/** The parent provides the leech entity; the addon provides its wilderness habitat. */
final class DimensionSpawns {
    @SuppressWarnings("unchecked")
    static void register(RegisterSpawnPlacementsEvent event) {
        var key = Identifier.parse("logos:mana_leech");
        if (!BuiltInRegistries.ENTITY_TYPE.containsKey(key)) return;
        EntityType<Mob> type = (EntityType<Mob>) BuiltInRegistries.ENTITY_TYPE.getValue(key);
        if (SpawnPlacements.getPlacementType(type) == SpawnPlacementTypes.NO_RESTRICTIONS) {
            event.register(type, SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    (entity, level, reason, pos, random) -> level.getLevel().dimension().equals(LogosDimension.CRUCIBLE)
                            && !IslandSanctum.contains(level.getLevel(), pos)
                            && level.getDifficulty() != Difficulty.PEACEFUL
                            && level.getRawBrightness(pos, 0) <= 7,
                    RegisterSpawnPlacementsEvent.Operation.AND);
        } else {
            // Preserve habitat predicates added by a newer Logos version or another addon.
            event.register(type, (entity, level, reason, pos, random) -> !IslandSanctum.contains(level.getLevel(), pos),
                    RegisterSpawnPlacementsEvent.Operation.AND);
        }
    }
    private DimensionSpawns() {}
}
