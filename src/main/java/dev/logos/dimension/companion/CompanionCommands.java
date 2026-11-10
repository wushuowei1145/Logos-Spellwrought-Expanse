package dev.logos.dimension.companion;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 试白梅同伴的命令（管理员；第一版还没有加入方式，用命令召唤）：
 * <pre>
 * /alchemy companion summon    召出自己的白梅（在身边，播入场）
 * /alchemy companion dismiss   收回（挥挥手，化作花瓣散去）
 * /alchemy companion info      她现在的数值、心智在做什么、各行为的分数
 * /alchemy companion rest      清掉打倒后的休息时间
 * </pre>
 */
public final class CompanionCommands {
    private CompanionCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("companion")
                .then(Commands.literal("summon").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    Component failed = Companions.summon(p, false);
                    if (failed != null) c.getSource().sendFailure(failed);
                    return failed == null ? 1 : 0;
                }))
                .then(Commands.literal("dismiss").executes(c -> Companions.dismiss(c.getSource().getPlayerOrException()) ? 1 : 0))
                .then(Commands.literal("info").executes(c -> info(c.getSource())))
                .then(Commands.literal("rest").executes(c -> {
                    Companions.wake(c.getSource().getPlayerOrException());
                    c.getSource().sendSuccess(() -> Component.literal("白梅不用再休息了"), false);
                    return 1;
                }));
    }

    private static int info(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        var bond = p.getData(Companions.BOND);
        CompanionStats.Owner o = CompanionStats.read(p);
        CompanionStats.Stats s = CompanionStats.of(o, bond.tails());
        src.sendSuccess(() -> Component.literal(String.format("尾 %d，继承 %.0f%%；你的长期数值：生命 %.1f 护甲 %.1f 韧性 %.1f 近战 %.1f 法强 ×%.2f",
                bond.tails(), s.ratio() * 100, o.health(), o.armor(), o.toughness(), o.melee(), o.spellPower())), false);
        src.sendSuccess(() -> Component.literal(String.format("她：生命上限 %.1f 护甲 %.1f 韧性 %.1f 狐火弹 %.1f；休息还剩 %d 秒",
                s.health(), s.armor(), s.toughness(), s.foxfire(), (Companions.restLeft(p) + 19) / 20)), false);
        ShiraumeCompanion c = Companions.out(p);
        if (c == null) {
            src.sendSuccess(() -> Component.literal("她现在不在外面"), false);
            return 1;
        }
        CompanionMind mind = c.mind();
        OwnerWatch.Activity doing = OwnerWatch.of(p).activity(p.level().getGameTime(), p.isSleeping());
        src.sendSuccess(() -> Component.literal(String.format("生命 %.1f/%.1f；你在做：%s；表情 %s；心智：%s", c.getHealth(), c.getMaxHealth(),
                doing, c.npcMiscAnimation(), mind == null ? "-" : mind.describe())), false);
        return 1;
    }
}
