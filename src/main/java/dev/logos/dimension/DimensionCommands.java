package dev.logos.dimension;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;

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
            if (!LogosDimension.isCrucible(player.level().dimension()) && !DimensionTravel.enter(player)) return 0;
            var target = player.level();
            var center = NativeWorlds.center();
            var landing = DimensionTravel.findLanding(target, center, region);
            return landing != null && DimensionTravel.teleport(player, target, landing, player.getYRot(), 0) ? 1 : 0;
        }));
        root.then(regions);
        var phases = Commands.literal("phase").executes(c -> {
            var player = c.getSource().getPlayerOrException();
            if (!LogosDimension.isCrucible(player.level().dimension())) return 0;
            player.sendSystemMessage(Component.translatable(OpusPhase.of(player.level()).key())); return 1;
        });
        for (var phase : OpusPhase.values()) phases.then(Commands.literal(phase.name().toLowerCase(java.util.Locale.ROOT)).executes(c -> {
            var level = c.getSource().getLevel();
            if(!LogosDimension.isCrucible(level.dimension()))return 0;
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
            if (!LogosDimension.isCrucible(p.level().dimension())) return 0;
            IslandProgress.of(p.level()).setDefeated(NativeWorlds.center(), defeated);
            return 1;
        }));
        root.then(bossState);
        // 特效预览：在准星指着的地面爆发金色光柱
        root.then(Commands.literal("pillar").executes(c -> pillar(c.getSource().getPlayerOrException(), 1))
                .then(Commands.argument("scale", FloatArgumentType.floatArg(.25f, 4))
                        .executes(c -> pillar(c.getSource().getPlayerOrException(), FloatArgumentType.getFloat(c, "scale")))));
        // 特效预览：自己身边卷起魔力龙卷。秒数 0 表示一直卷，stop 收掉；cycle 是颜色转一圈的秒数，0 保持原色
        root.then(Commands.literal("tornado").executes(c -> tornado(c.getSource().getPlayerOrException(), 8, 1, 12))
                .then(Commands.literal("stop").executes(c -> {
                    var player = c.getSource().getPlayerOrException();
                    dev.logos.dimension.vfx.ManaTornado.stop(player.level(), player);
                    return 1;
                }))
                .then(Commands.argument("seconds", IntegerArgumentType.integer(0, 600))
                        .executes(c -> tornado(c.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(c, "seconds"), 1, 12))
                        .then(Commands.argument("scale", FloatArgumentType.floatArg(.25f, 4))
                                .executes(c -> tornado(c.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(c, "seconds"),
                                        FloatArgumentType.getFloat(c, "scale"), 12))
                                .then(Commands.argument("cycle", IntegerArgumentType.integer(0, 600))
                                        .executes(c -> tornado(c.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(c, "seconds"),
                                                FloatArgumentType.getFloat(c, "scale"), IntegerArgumentType.getInteger(c, "cycle")))))));
        event.getDispatcher().register(root);
    }
    private static int pillar(net.minecraft.server.level.ServerPlayer player, float scale) {
        var hit = player.pick(64, 0, false);
        dev.logos.dimension.vfx.PillarBurst.play(player.level(), hit.getLocation(), scale);
        return 1;
    }
    private static int tornado(net.minecraft.server.level.ServerPlayer player, int seconds, float scale, int cycleSeconds) {
        dev.logos.dimension.vfx.ManaTornado.play(player.level(), player, seconds * 20, scale, cycleSeconds * 20);
        return 1;
    }
    private static int island(net.minecraft.server.level.ServerPlayer player, int gx, int gz) {
        if(gx!=0||gz!=0){player.sendSystemMessage(Component.literal("每个咒械原世界只有一座中心空岛。"));return 0;}
        if (!LogosDimension.isCrucible(player.level().dimension()) && !DimensionTravel.enter(player)) return 0;
        var target = player.level();
        return DimensionTravel.teleport(player,target,new Vec3(NativeWorlds.ISLAND_X+.5,NativeWorlds.ISLAND_TOP+1,NativeWorlds.ISLAND_Z+.5),player.getYRot(),0)?1:0;
    }
    private DimensionCommands() {}
}
