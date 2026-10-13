package dev.logos.dimension;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.logos.dimension.world.AlchemicalChunkGenerator;
import dev.logos.dimension.world.AlchemicalLayout;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Development navigation for continent-sized regions, separate from the survival vortex entrance. */
public final class DimensionCommands {
    public static void register(RegisterCommandsEvent event) {
        var root = Commands.literal("alchemy").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
        root.then(Commands.literal("enter").executes(c -> DimensionTravel.enter(c.getSource().getPlayerOrException()) ? 1 : 0));
        root.then(Commands.literal("return").executes(c -> DimensionTravel.leave(c.getSource().getPlayerOrException()) ? 1 : 0));
        root.then(Commands.literal("island").executes(c -> island(c.getSource().getPlayerOrException(), 0, 0))
                .then(Commands.argument("grid_x", IntegerArgumentType.integer(-1000, 1000))
                        .then(Commands.argument("grid_z", IntegerArgumentType.integer(-1000, 1000))
                                .executes(c -> island(c.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(c, "grid_x"), IntegerArgumentType.getInteger(c, "grid_z"))))));
        var regions = Commands.literal("region");
        for (var region : AlchemicalLayout.Region.values()) regions.then(Commands.literal(region.name().toLowerCase(java.util.Locale.ROOT)).executes(c -> {
            var player = c.getSource().getPlayerOrException();
            if (!player.level().dimension().equals(LogosDimension.CRUCIBLE) && !DimensionTravel.enter(player)) return 0;
            var target = player.level();
            var generator = (AlchemicalChunkGenerator) target.getChunkSource().getGenerator();
            var center = generator.terrain().layout().nearest(player.getBlockX(), player.getBlockZ());
            var landing = DimensionTravel.findLanding(target, center, region);
            return landing != null && DimensionTravel.teleport(player, target, landing, player.getYRot(), 0) ? 1 : 0;
        }));
        root.then(regions);
        var phases = Commands.literal("phase").executes(c -> {
            var player = c.getSource().getPlayerOrException();
            if (!player.level().dimension().equals(LogosDimension.CRUCIBLE)) return 0;
            player.sendSystemMessage(Component.translatable(OpusPhase.of(player.level()).key())); return 1;
        });
        for (var phase : OpusPhase.values()) phases.then(Commands.literal(phase.name().toLowerCase(java.util.Locale.ROOT)).executes(c -> {
            var level = c.getSource().getServer().getLevel(LogosDimension.CRUCIBLE);
            if (level == null) return 0;
            var clock = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_CLOCK).getOrThrow(OpusPhase.CLOCK);
            var manager = c.getSource().getServer().clockManager();
            long current = manager.getTotalTicks(clock);
            manager.setTotalTicks(clock, Math.floorDiv(current, OpusPhase.PERIOD_TICKS) * OpusPhase.PERIOD_TICKS + (long) phase.ordinal() * OpusPhase.PHASE_TICKS);
            return 1;
        }));
        root.then(phases);
        var bossState = Commands.literal("boss");
        for (boolean defeated : new boolean[] {true, false}) bossState.then(Commands.literal(defeated ? "defeated" : "reset").executes(c -> {
            var p = c.getSource().getPlayerOrException();
            if (!p.level().dimension().equals(LogosDimension.CRUCIBLE)) return 0;
            var gen = (AlchemicalChunkGenerator) p.level().getChunkSource().getGenerator();
            IslandProgress.of(p.level()).setDefeated(gen.terrain().layout().nearest(p.getBlockX(), p.getBlockZ()), defeated);
            return 1;
        }));
        root.then(bossState);
        event.getDispatcher().register(root);
    }
    private static int island(net.minecraft.server.level.ServerPlayer player, int gx, int gz) {
        if (!player.level().dimension().equals(LogosDimension.CRUCIBLE) && !DimensionTravel.enter(player)) return 0;
        var target = player.level();
        var terrain = ((AlchemicalChunkGenerator) target.getChunkSource().getGenerator()).terrain();
        var center = terrain.layout().center(gx, gz);
        var c = terrain.column(center.x(), center.z());
        return DimensionTravel.teleport(player, target, new Vec3(center.x() + .5, c.islandTop() + 1, center.z() + .5), player.getYRot(), 0) ? 1 : 0;
    }
    private DimensionCommands() {}
}
