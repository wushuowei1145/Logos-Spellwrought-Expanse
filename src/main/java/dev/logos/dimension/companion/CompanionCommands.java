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
                .then(outfits())
                .then(princess())
                .then(kohaku());
    }

    /**
     * {@code kohaku ...}：琥珀（docs/KOHAKU_COMPANION.md）。
     * <pre>
     * /alchemy companion kohaku summon        召出她（顺带立契，给一枚压着你名字的爪印金镑）
     * /alchemy companion kohaku dismiss       收回
     * /alchemy companion kohaku info          她的数值、法术强度、魔力、长杖、心智
     * /alchemy companion kohaku rest          清掉撤退后的休息时间
     * /alchemy companion kohaku seal [枚数]   盖爪之契
     * /alchemy companion kohaku satiety 值    设她的饱腹
     * /alchemy companion kohaku mana 值       设她的魔力（试魔力少时的打法）
     * /alchemy companion kohaku withdraw      让她撤退（试倒下、法阵卷走的样子）
     * </pre>
     */
    private static LiteralArgumentBuilder<CommandSourceStack> kohaku() {
        CompanionDef d = Companions.KOHAKU_DEF;
        return Commands.literal("kohaku")
                .then(Commands.literal("summon").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    Companions.grantPact(p, d);
                    if (!PawSovereignItem.carries(p)) {
                        var coin = PawSovereignItem.bound(p);
                        if (!p.getInventory().add(coin)) p.drop(coin, false);
                    }
                    Component failed = Companions.summon(p, d, null);
                    if (failed != null) c.getSource().sendFailure(failed);
                    return failed == null ? 1 : 0;
                }))
                .then(Commands.literal("dismiss").executes(c -> Companions.dismiss(c.getSource().getPlayerOrException(), d) ? 1 : 0))
                .then(Commands.literal("rest").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    Companions.wake(p, d);
                    p.getCooldowns().removeCooldown(p.getCooldowns().getCooldownGroup(PawSovereignItem.blank()));
                    c.getSource().sendSuccess(() -> Component.literal("琥珀不用再休息了"), false);
                    return 1;
                }))
                .then(Commands.literal("seal").executes(c -> kohakuSeal(c.getSource(), 1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 20)).executes(c ->
                                kohakuSeal(c.getSource(), IntegerArgumentType.getInteger(c, "count")))))
                .then(Commands.literal("satiety").then(Commands.argument("value", FloatArgumentType.floatArg(0, CompanionBond.SATIETY_MAX)).executes(c -> {
                    Companions.setSatiety(c.getSource().getPlayerOrException(), d, FloatArgumentType.getFloat(c, "value"));
                    return 1;
                })))
                .then(Commands.literal("mana").then(Commands.argument("value", FloatArgumentType.floatArg(0)).executes(c -> {
                    KohakuCompanion e = Companions.kohaku(c.getSource().getPlayerOrException());
                    if (e == null) {
                        c.getSource().sendFailure(Component.literal("琥珀不在外面"));
                        return 0;
                    }
                    e.setMana(FloatArgumentType.getFloat(c, "value"));
                    e.staff.setStore(0);
                    return 1;
                })))
                .then(Commands.literal("withdraw").executes(c -> {
                    KohakuCompanion e = Companions.kohaku(c.getSource().getPlayerOrException());
                    if (e == null || !(e.level() instanceof net.minecraft.server.level.ServerLevel level)) {
                        c.getSource().sendFailure(Component.literal("琥珀不在外面"));
                        return 0;
                    }
                    e.startWithdraw(level);
                    return 1;
                }))
                .then(Commands.literal("info").executes(c -> kohakuInfo(c.getSource())));
    }

    private static int kohakuSeal(CommandSourceStack src, int count) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        Companions.grantPact(p, Companions.KOHAKU_DEF);
        int n = 0;
        for (int i = 0; i < count; i++) n = Companions.addSeal(p, Companions.KOHAKU_DEF);
        int seals = n;
        src.sendSuccess(() -> Component.literal("爪之契 " + seals + " 枚"), false);
        return 1;
    }

    private static int kohakuInfo(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        CompanionDef d = Companions.KOHAKU_DEF;
        var bond = Companions.bond(p, d);
        int stage = CompanionBond.Stage.of(bond.affection()).ordinal();
        CompanionStats.Part power = CompanionStats.power(d.growth(), KohakuCompanion.ownerPower(p), bond.seals(), stage);
        CompanionStats.Part mana = KohakuCompanion.manaParts(KohakuCompanion.ownerMana(p), bond.seals(), stage);
        src.sendSuccess(() -> Component.literal(String.format("立契 %s，爪之契 %d，继承 %.0f%%（生命 %.0f%%）；好感 %.1f（%s），饱腹 %.1f；你的奥术法强 ×%.2f；她的法强 ×%.2f（自己 %.2f 羁绊 %.2f 继承 %.2f）；魔力上限 %.0f（自己 %.0f 羁绊 %.0f 继承 %.0f，不算杖）；休息还剩 %d 秒",
                bond.pact() ? "是" : "否", bond.seals(), d.growth().ratio(bond.seals()) * 100, d.growth().healthRatio(bond.seals()) * 100, bond.affection(),
                CompanionBond.Stage.of(bond.affection()), bond.satiety(), KohakuCompanion.ownerPower(p), power.total(), power.own(), power.bond(),
                power.inherited(), mana.total(), mana.own(), mana.bond(), mana.inherited(), (Companions.restLeft(p, d) + 19) / 20)), false);
        KohakuCompanion c = Companions.kohaku(p);
        if (c == null) {
            src.sendSuccess(() -> Component.literal("她现在不在外面"), false);
            return 1;
        }
        CompanionMind mind = c.mind();
        src.sendSuccess(() -> Component.literal(String.format("生命 %.1f/%.1f 护盾 %.1f；魔力 %.0f/%.0f（每秒 %.1f）；表情 %s；心智：%s", c.getHealth(), c.getMaxHealth(),
                c.getAbsorptionAmount(), c.mana(), c.maxMana(), c.regenPerSecond(), c.npcMiscAnimation(), mind == null ? "-" : mind.describe())), false);
        return 1;
    }

    /**
     * {@code princess ...}：精灵公主（docs/ELF_PRINCESS_COMPANION.md）。
     * <pre>
     * /alchemy companion princess summon        召出她（顺带立契，给一袋写着你名字的袋装世界树）
     * /alchemy companion princess dismiss       收回
     * /alchemy companion princess info          她的数值、法术强度、心智、弓和施法
     * /alchemy companion princess rest          清掉撤退后的休息时间
     * /alchemy companion princess seal [枚数]   盖叶之契
     * /alchemy companion princess satiety 值    设她的饱腹
     * /alchemy companion princess withdraw      让她撤退（试撤退的样子）
     * </pre>
     */
    private static LiteralArgumentBuilder<CommandSourceStack> princess() {
        CompanionDef d = Companions.PRINCESS_DEF;
        return Commands.literal("princess")
                .then(Commands.literal("summon").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    Companions.grantPact(p, d);
                    if (!BaggedWorldTreeItem.carries(p)) {
                        var bag = BaggedWorldTreeItem.bound(p);
                        if (!p.getInventory().add(bag)) p.drop(bag, false);
                    }
                    Component failed = Companions.summon(p, d, null);
                    if (failed != null) c.getSource().sendFailure(failed);
                    return failed == null ? 1 : 0;
                }))
                .then(Commands.literal("dismiss").executes(c -> Companions.dismiss(c.getSource().getPlayerOrException(), d) ? 1 : 0))
                .then(Commands.literal("rest").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    Companions.wake(p, d);
                    p.getCooldowns().removeCooldown(p.getCooldowns().getCooldownGroup(BaggedWorldTreeItem.blank()));
                    c.getSource().sendSuccess(() -> Component.literal("精灵公主不用再休息了"), false);
                    return 1;
                }))
                .then(Commands.literal("seal").executes(c -> princessSeal(c.getSource(), 1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 20)).executes(c ->
                                princessSeal(c.getSource(), IntegerArgumentType.getInteger(c, "count")))))
                .then(Commands.literal("satiety").then(Commands.argument("value", FloatArgumentType.floatArg(0, CompanionBond.SATIETY_MAX)).executes(c -> {
                    Companions.setSatiety(c.getSource().getPlayerOrException(), d, FloatArgumentType.getFloat(c, "value"));
                    return 1;
                })))
                .then(Commands.literal("withdraw").executes(c -> {
                    ElfPrincessCompanion e = Companions.princess(c.getSource().getPlayerOrException());
                    if (e == null || !(e.level() instanceof net.minecraft.server.level.ServerLevel level)) {
                        c.getSource().sendFailure(Component.literal("精灵公主不在外面"));
                        return 0;
                    }
                    e.startWithdraw(level);
                    return 1;
                }))
                .then(Commands.literal("info").executes(c -> princessInfo(c.getSource())));
    }

    private static int princessSeal(CommandSourceStack src, int count) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        Companions.grantPact(p, Companions.PRINCESS_DEF);
        int n = 0;
        for (int i = 0; i < count; i++) n = Companions.addSeal(p, Companions.PRINCESS_DEF);
        int seals = n;
        src.sendSuccess(() -> Component.literal("叶之契 " + seals + " 枚"), false);
        return 1;
    }

    private static int princessInfo(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        CompanionDef d = Companions.PRINCESS_DEF;
        var bond = Companions.bond(p, d);
        CompanionStats.Part power = CompanionStats.power(d.growth(), ElfPrincessCompanion.ownerPower(p), bond.seals(),
                CompanionBond.Stage.of(bond.affection()).ordinal());
        src.sendSuccess(() -> Component.literal(String.format("立契 %s，叶之契 %d，继承 %.0f%%；好感 %.1f（%s），饱腹 %.1f；你的生命法强 ×%.2f；她的法强 ×%.2f（自己 %.2f 羁绊 %.2f 继承 %.2f）；休息还剩 %d 秒",
                bond.pact() ? "是" : "否", bond.seals(), d.growth().ratio(bond.seals()) * 100, bond.affection(), CompanionBond.Stage.of(bond.affection()),
                bond.satiety(), ElfPrincessCompanion.ownerPower(p), power.total(), power.own(), power.bond(), power.inherited(),
                (Companions.restLeft(p, d) + 19) / 20)), false);
        ElfPrincessCompanion c = Companions.princess(p);
        if (c == null) {
            src.sendSuccess(() -> Component.literal("她现在不在外面"), false);
            return 1;
        }
        CompanionMind mind = c.mind();
        src.sendSuccess(() -> Component.literal(String.format("生命 %.1f/%.1f；表情 %s；心智：%s", c.getHealth(), c.getMaxHealth(),
                c.npcMiscAnimation(), mind == null ? "-" : mind.describe())), false);
        return 1;
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
                s.health(), s.armor(), s.toughness(), s.foxfire(), s.foxfire() * CompanionStats.blade(bond.seals()), (Companions.restLeft(p) + 19) / 20)), false);
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
