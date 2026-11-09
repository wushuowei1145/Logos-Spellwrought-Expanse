package dev.logos.dimension.shiraume;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.phys.Vec3;

/**
 * 试白梅战的命令（管理员）：
 * <pre>
 * /alchemy shiraume awaken   在脚下这处封印之间里直接从「最后一道封印断开」开始（拆掉剩下的封印杭）
 * /alchemy shiraume spawn    在准星指的地方召出人形（不在封印之间里，没有开场过场）
 * /alchemy shiraume beast    在准星指的地方召出狐形
 * /alchemy shiraume phase2   当前的人形直接开始变身
 * /alchemy shiraume reset    清掉所有场地记录与白梅（不还原杀生石）
 * </pre>
 */
public final class ShiraumeCommands {
    private ShiraumeCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("shiraume")
                .then(Commands.literal("awaken").executes(c -> {
                    var src = c.getSource();
                    boolean ok = ShiraumeFight.awakenNearest(src.getLevel(), net.minecraft.core.BlockPos.containing(src.getPosition()));
                    if (!ok) src.sendFailure(Component.literal("这里不是还没觉醒的封印之间"));
                    return ok ? 1 : 0;
                }))
                .then(Commands.literal("spawn").executes(c -> spawn(c.getSource(), false)))
                .then(Commands.literal("beast").executes(c -> spawn(c.getSource(), true)))
                .then(Commands.literal("phase2").executes(c -> ShiraumeFight.skipToPhaseTwo(c.getSource().getLevel()) ? 1 : 0))
                .then(Commands.literal("reset").executes(c -> {
                    int n = ShiraumeFight.reset(c.getSource().getLevel());
                    c.getSource().sendSuccess(() -> Component.literal("清掉了 " + n + " 处场地"), true);
                    return n;
                }));
    }

    private static int spawn(CommandSourceStack src, boolean beast) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = src.getPlayerOrException();
        ServerLevel level = src.getLevel();
        Vec3 at = player.pick(24, 0, false).getLocation();
        var mob = beast ? ShiraumeEntities.BEAST.get().create(level, EntitySpawnReason.COMMAND)
                : ShiraumeEntities.SHIRAUME.get().create(level, EntitySpawnReason.COMMAND);
        if (mob == null) return 0;
        float yaw = player.getYRot() + 180;
        mob.snapTo(at.x, at.y, at.z, yaw, 0);
        mob.setYHeadRot(yaw);
        mob.setYBodyRot(yaw);
        level.addFreshEntity(mob);
        ShiraumeFight.arenaOf(level, mob);
        ((ShiraumeBoss) mob).showBar(true, 1);
        return 1;
    }
}
