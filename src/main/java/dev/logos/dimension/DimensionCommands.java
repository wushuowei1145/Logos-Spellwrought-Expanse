package dev.logos.dimension;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import dev.logos.dimension.camera.CameraPoint;
import dev.logos.dimension.camera.CameraShots;
import dev.logos.dimension.screenfx.ScreenEffects;
import dev.logos.dimension.screenfx.ScreenFlash;
import dev.logos.dimension.world.AlchemicalLayout;
import dev.logos.dimension.worldstyle.StyleRealmDemo;
import dev.logos.dimension.worldstyle.StyleRealms;
import dev.logos.dimension.worldstyle.WorldStyle;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Development navigation for continent-sized regions, separate from the survival vortex entrance. */
public final class DimensionCommands {
    /** /alchemy dissolve：试消散，可以对实体、手上的东西、一片方块。会真的删掉东西。 */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> dissolve() {
        return Commands.literal("dissolve")
                .then(Commands.literal("entity").then(Commands.argument("targets", net.minecraft.commands.arguments.EntityArgument.entities()).then(dissolveShape((c, s, t) -> {
                    for (var e : net.minecraft.commands.arguments.EntityArgument.getEntities(c, "targets")) dev.logos.dimension.dissolve.Dissolving.entity(e, s, t, dev.logos.dimension.dissolve.Dissolving.EMBER);
                }))))
                .then(Commands.literal("hand").then(dissolveShape((c, s, t) ->
                        dev.logos.dimension.dissolve.Dissolving.held(c.getSource().getPlayerOrException(), net.minecraft.world.InteractionHand.MAIN_HAND, s, t, dev.logos.dimension.dissolve.Dissolving.EMBER))))
                .then(Commands.literal("blocks").then(Commands.argument("from", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos()).then(Commands.argument("to", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos()).then(dissolveShape((c, s, t) -> {
                    var from = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(c, "from");
                    var to = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(c, "to");
                    var all = new java.util.ArrayList<net.minecraft.core.BlockPos>();
                    for (var p : net.minecraft.core.BlockPos.betweenClosed(from, to)) all.add(p.immutable());
                    if (all.size() > 4096) {
                        c.getSource().sendFailure(Component.literal("At most 4096 blocks"));
                        return;
                    }
                    dev.logos.dimension.dissolve.Dissolving.blocks(c.getSource().getLevel(), all, s, t, dev.logos.dimension.dissolve.Dissolving.EMBER);
                })))));
    }

    private interface DissolveAction {
        void run(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> c, dev.logos.dimension.dissolve.DissolveShape shape, int ticks) throws com.mojang.brigadier.exceptions.CommandSyntaxException;
    }

    /** 每条消散命令结尾的形状名和可选的秒数，默认 3 秒。 */
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<net.minecraft.commands.CommandSourceStack, String> dissolveShape(DissolveAction action) {
        return Commands.argument("shape", StringArgumentType.word())
                .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(dev.logos.dimension.dissolve.DissolveShape.NAMES, b))
                .executes(c -> startDissolve(c, action, 3))
                .then(Commands.argument("seconds", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(.1, 60))
                        .executes(c -> startDissolve(c, action, com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(c, "seconds"))));
    }

    private static int startDissolve(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> c, DissolveAction action, double seconds) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var shape = dev.logos.dimension.dissolve.DissolveShape.parse(StringArgumentType.getString(c, "shape"));
        if (shape == null) {
            c.getSource().sendFailure(Component.literal("Shapes: " + String.join(", ", dev.logos.dimension.dissolve.DissolveShape.NAMES)));
            return 0;
        }
        action.run(c, shape, (int) (seconds * 20));
        return 1;
    }

    public static void register(RegisterCommandsEvent event) {
        var root = Commands.literal("alchemy").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
        root.then(Commands.literal("enter").executes(c -> DimensionTravel.enter(c.getSource().getPlayerOrException()) ? 1 : 0));
        root.then(Commands.literal("return").executes(c -> DimensionTravel.leave(c.getSource().getPlayerOrException()) ? 1 : 0));
        var realm = Commands.literal("realm");
        for (var style : WorldStyle.values()) realm.then(Commands.literal("demo").then(Commands.literal(style.getSerializedName()).executes(c -> {
            StyleRealmDemo.build(c.getSource().getPlayerOrException(), style);
            return 1;
        })));
        realm.then(Commands.literal("clear").executes(c -> {
            StyleRealms.clear(c.getSource().getLevel());
            return 1;
        }));
        root.then(realm);
        root.then(Commands.literal("flash").executes(c -> flash(c.getSource().getPlayerOrException(), null))
                .then(Commands.argument("frames", StringArgumentType.word()).executes(c -> flash(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "frames")))));
        root.then(execution());
        root.then(dissolve());
        // 试爆裂魔法：不吟唱，直接在准星指的地方放一次（半径和是否破坏地形照服务端配置）
        root.then(Commands.literal("explosion").executes(c -> {
            var player = c.getSource().getPlayerOrException();
            var hit = player.pick(dev.logos.dimension.explosion.ExplosionMagic.RANGE, 0, true);
            dev.logos.dimension.explosion.ExplosionMagic.fire(player.level(), hit.getLocation(), player);
            return 1;
        }));
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
                    dev.logos.runtime.ManaTornado.stop(player.level(), player);
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
        // 特效预览：给手里的物品加上表面特效（冒火、缠着魔力），hue 是色相转多少圈；clear 去掉
        var aura = Commands.literal("aura").then(Commands.literal("clear").executes(c -> aura(c.getSource().getPlayerOrException(), null, 0)));
        for (var style : dev.logos.runtime.SurfaceAura.Style.values()) aura.then(Commands.literal(style.getSerializedName())
                .executes(c -> aura(c.getSource().getPlayerOrException(), style, 0))
                .then(Commands.argument("hue", FloatArgumentType.floatArg(-1, 1))
                        .executes(c -> aura(c.getSource().getPlayerOrException(), style, FloatArgumentType.getFloat(c, "hue")))));
        root.then(aura);
        event.getDispatcher().register(root);
    }
    private static int pillar(net.minecraft.server.level.ServerPlayer player, float scale) {
        var hit = player.pick(64, 0, false);
        dev.logos.runtime.PillarBurst.play(player.level(), hit.getLocation(), scale);
        return 1;
    }
    private static int tornado(net.minecraft.server.level.ServerPlayer player, int seconds, float scale, int cycleSeconds) {
        dev.logos.runtime.ManaTornado.play(player.level(), player, seconds * 20, scale, cycleSeconds * 20);
        return 1;
    }
    private static int aura(net.minecraft.server.level.ServerPlayer player, dev.logos.runtime.SurfaceAura.@org.jspecify.annotations.Nullable Style style, float hue) {
        var stack = player.getMainHandItem();
        if (stack.isEmpty()) { player.sendSystemMessage(Component.literal("手里没有物品。")); return 0; }
        dev.logos.runtime.SurfaceAura.set(stack, style, hue);
        return 1;
    }
    /** 试黑白闪：在准星指的地方闪一次。不给 {@code frames} 时放默认的四个阶段；给的话一个字母一拍，见 {@link ScreenFlash.Frame}。 */
    private static int flash(net.minecraft.server.level.ServerPlayer player, @org.jspecify.annotations.Nullable String frames) {
        Vec3 eye = player.getEyePosition();
        Vec3 at = CameraShots.reach(player.level(), eye, eye.add(player.getLookAngle().scale(48)));
        ScreenFlash flash = ScreenFlash.at(CameraPoint.world(at));
        ScreenEffects.flash(player, frames == null ? flash : new ScreenFlash(flash.center(), ScreenFlash.parse(frames)));
        return 1;
    }

    /**
     * /alchemy execution [目标] [刀数] [beats|every|steady] [black]：试处决特写，自己看。不给目标时处决自己。
     * {@code beats}（默认）只在节拍上对调，{@code every} 每刀都对调（第一版），{@code steady} 不对调；
     * {@code black} 换成黑底白身。只是画面，不扣血。
     */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> execution() {
        var slashes = Commands.argument("slashes", IntegerArgumentType.integer(dev.logos.dimension.execution.ExecutionShot.MIN_SLASHES, dev.logos.dimension.execution.ExecutionShot.MAX_SLASHES))
                .executes(c -> execution(c, IntegerArgumentType.getInteger(c, "slashes"), false, dev.logos.dimension.execution.ExecutionShot.Swaps.BEATS))
                .then(Commands.literal("black").executes(c -> execution(c, IntegerArgumentType.getInteger(c, "slashes"), true, dev.logos.dimension.execution.ExecutionShot.Swaps.BEATS)));
        for (var style : dev.logos.dimension.execution.ExecutionShot.Swaps.values()) {
            String name = style == dev.logos.dimension.execution.ExecutionShot.Swaps.NONE ? "steady" : style.name().toLowerCase(java.util.Locale.ROOT);
            slashes.then(Commands.literal(name)
                    .executes(c -> execution(c, IntegerArgumentType.getInteger(c, "slashes"), false, style))
                    .then(Commands.literal("black").executes(c -> execution(c, IntegerArgumentType.getInteger(c, "slashes"), true, style))));
        }
        return Commands.literal("execution")
                .executes(c -> execution(c, dev.logos.dimension.execution.ExecutionShot.DEFAULT_SLASHES, false, dev.logos.dimension.execution.ExecutionShot.Swaps.BEATS))
                .then(Commands.argument("target", net.minecraft.commands.arguments.EntityArgument.entity())
                        .executes(c -> execution(c, dev.logos.dimension.execution.ExecutionShot.DEFAULT_SLASHES, false, dev.logos.dimension.execution.ExecutionShot.Swaps.BEATS))
                        .then(slashes));
    }

    private static int execution(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> c, int slashes, boolean black, dev.logos.dimension.execution.ExecutionShot.Swaps swaps) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var viewer = c.getSource().getPlayerOrException();
        var entity = c.getNodes().stream().anyMatch(n -> n.getNode().getName().equals("target"))
                ? net.minecraft.commands.arguments.EntityArgument.getEntity(c, "target") : viewer;
        if (!(entity instanceof net.minecraft.world.entity.LivingEntity target)) {
            c.getSource().sendFailure(Component.literal("只能处决生物。"));
            return 0;
        }
        var shot = dev.logos.dimension.execution.ExecutionShot.of(viewer.getRandom().nextInt()).slashes(slashes).swaps(swaps);
        if (black) shot = shot.onBlack();
        dev.logos.dimension.execution.ExecutionShots.play(viewer, target, shot);
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
