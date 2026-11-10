package dev.logos.dimension.companion;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * 试白梅同伴的命令（管理员）：
 * <pre>
 * /alchemy companion summon        召出自己的白梅（在身边，播入场；顺带立契）
 * /alchemy companion dismiss       收回（挥挥手，化作花瓣散去）
 * /alchemy companion info          她现在的数值、契、心智在做什么、各行为的分数
 * /alchemy companion rest          清掉打倒后的休息时间
 * /alchemy companion pact          在你面前放一位跪坐等你的白梅（不用打 Boss，试立契）
 * /alchemy companion seal [枚数]   盖契印（默认一枚）
 * /alchemy companion satiety 值    设她的饱腹（0–20，试吃东西、摘浆果）
 * /alchemy companion outfit 装束   换装束：miko（巫女服）、umeakari（梅灯夜渡，浴衣）
 * </pre>
 */
public final class CompanionCommands {
    private CompanionCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("companion")
                .then(Commands.literal("summon").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    Companions.grantPact(p);
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
                }))
                .then(Commands.literal("pact").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    Vec3 fwd = Vec3.directionFromRotation(0, p.getYRot());
                    Vec3 at = p.position().add(fwd.scale(3));
                    float yaw = p.getYRot() + 180;
                    ShiraumePact.spawn(p.level(), p.getUUID(), at, yaw);
                    ShiraumePact.gather(p.level(), p, at);
                    c.getSource().sendSuccess(() -> Component.literal("她在你面前跪坐着等你，右键和她说话"), false);
                    return 1;
                }))
                .then(Commands.literal("seal").executes(c -> seal(c.getSource(), 1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 20)).executes(c ->
                                seal(c.getSource(), IntegerArgumentType.getInteger(c, "count")))))
                .then(Commands.literal("satiety").then(Commands.argument("value", FloatArgumentType.floatArg(0, CompanionBond.SATIETY_MAX)).executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    Companions.setSatiety(p, FloatArgumentType.getFloat(c, "value"));
                    return 1;
                })))
                .then(outfits());
    }

    /** {@code outfit <装束>}：给外面的她换上（浴衣照常穿一阵、一打起来换回巫女服）。 */
    private static LiteralArgumentBuilder<CommandSourceStack> outfits() {
        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal("outfit");
        for (CompanionOutfit o : CompanionOutfit.values()) {
            node.then(Commands.literal(o.id).executes(c -> {
                ShiraumeCompanion s = Companions.out(c.getSource().getPlayerOrException());
                if (s == null) {
                    c.getSource().sendFailure(Component.literal("白梅不在外面"));
                    return 0;
                }
                s.dress(o, s.level().getGameTime());
                c.getSource().sendSuccess(() -> Component.literal("白梅换上了 " + o.id), false);
                return 1;
            }));
        }
        return node;
    }

    private static int seal(CommandSourceStack src, int count) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        Companions.grantPact(p);
        int n = 0;
        for (int i = 0; i < count; i++) n = Companions.addSeal(p);
        int seals = n;
        src.sendSuccess(() -> Component.literal("契印 " + seals + " 枚"), false);
        return 1;
    }

    private static int info(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        var bond = p.getData(Companions.BOND);
        CompanionStats.Owner o = CompanionStats.read(p);
        CompanionStats.Stats s = CompanionStats.of(o, bond.seals());
        src.sendSuccess(() -> Component.literal(String.format("立契 %s，契印 %d，继承 %.0f%%；好感 %.1f（%s），饱腹 %.1f；你的长期数值：生命 %.1f 护甲 %.1f 韧性 %.1f 近战 %.1f 法强 ×%.2f",
                bond.pact() ? "是" : "否", bond.seals(), s.ratio() * 100, bond.affection(), CompanionBond.Stage.of(bond.affection()), bond.satiety(),
                o.health(), o.armor(), o.toughness(), o.melee(), o.spellPower())), false);
        src.sendSuccess(() -> Component.literal(String.format("她：生命上限 %.1f 护甲 %.1f 韧性 %.1f 狐火弹 %.1f 刀 %.1f；休息还剩 %d 秒",
                s.health(), s.armor(), s.toughness(), s.foxfire(), s.foxfire() * CompanionBlade.BLADE, (Companions.restLeft(p) + 19) / 20)), false);
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
