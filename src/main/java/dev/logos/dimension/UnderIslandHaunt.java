package dev.logos.dimension;

import dev.logos.dimension.net.HauntPayload;
import dev.logos.dimension.world.AlchemicalChunkGenerator;
import dev.logos.dimension.world.IslandMistGeometry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

public final class UnderIslandHaunt {
    public static final TagKey<EntityType<?>> ISLAND_BOSSES = TagKey.create(Registries.ENTITY_TYPE, LogosDimension.id("island_bosses"));

    public static HauntPayload state(ServerLevel level, double x, double y, double z) {
        if (!LogosDimension.isCrucible(level.dimension())
                || !(level.getChunkSource().getGenerator() instanceof AlchemicalChunkGenerator gen)) return HauntPayload.CLEAR;
        var terrain = gen.terrain(); var center = terrain.layout().nearest((int) Math.floor(x), (int) Math.floor(z));
        double radius = terrain.layout().islandRadius();
        var middle = terrain.column(center.x(), center.z());
        if (Math.hypot(x - center.x(), z - center.z()) > radius + 256 || y > middle.islandTop() + 80
                || IslandProgress.of(level).defeated(center)) return HauntPayload.CLEAR;
        float density = IslandMistGeometry.density(terrain, (int) Math.floor(x), y, (int) Math.floor(z));
        return new HauntPayload(true, density, center.x(), center.z(), (float) radius,
                middle.ground() + 8, middle.islandBottom() - 20);
    }
    public static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.tickCount % 20 != 0) return;
        PacketDistributor.sendToPlayer(p, state(p.level(), p.getX(), p.getY(), p.getZ()));
    }
    public static void death(LivingDeathEvent event) {
        // LOGOS_DIMENSION_ISLAND_BOSS_HOOK: add the future Boss entity to this tag, no placeholder mob is registered.
        if (!(event.getEntity().level() instanceof ServerLevel level)
                || !event.getEntity().getType().builtInRegistryHolder().is(ISLAND_BOSSES)
                || !LogosDimension.isCrucible(level.dimension())
                || !(level.getChunkSource().getGenerator() instanceof AlchemicalChunkGenerator gen)) return;
        var boss = event.getEntity(); var center = gen.terrain().layout().nearest(boss.getBlockX(), boss.getBlockZ());
        if (Math.hypot(boss.getX() - center.x(), boss.getZ() - center.z()) <= gen.terrain().layout().islandRadius())
            IslandProgress.of(level).setDefeated(center, true);
    }
    private UnderIslandHaunt() {}
}
