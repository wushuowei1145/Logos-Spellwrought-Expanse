package dev.logos.dimension.dragonfire;

import java.util.Map;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.logos.item.SpellPages;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * 试龙炎的命令（管理员，docs/DRAGONFIRE.md 第 6 节）。没学会时编纂台里写不了 {@code dragonfire}，调试时这样拿：
 * <pre>
 * /alchemy dragonfire page &lt;wrap|sweep&gt;     一张龙炎术式页
 * /alchemy dragonfire hit &lt;目标&gt; [点数]      以自己的名义对目标放一下龙炎
 * /alchemy dragonfire wrap &lt;目标&gt; [秒]       给目标的手缠上龙炎，每下附带 3 点
 * /alchemy dragonfire burn &lt;目标&gt;            让目标龙炎缠身（叠一次）
 * /alchemy dragonfire teach &lt;玩家&gt;           教会龙炎之言（自己写的术式里可以写 dragonfire）
 * /alchemy dragonfire forget &lt;玩家&gt;          忘掉龙炎之言
 * </pre>
 * 也可以用 Logos 的 {@code /logos cast} 直接施放写着 dragonfire 的术式。
 */
public final class DragonfireCommands {
    /** 调试用的龙炎术式：龙炎缠绕，和一道单独的龙炎横扫游脉。 */
    public static final Map<String, String> PAGES = Map.of(
            "wrap", """
                    ; 龙炎缠绕（调试）
                    (spell :name "龙炎缠绕" :mana 100 :trigger cast
                      (imbue :life 60 :every 60
                        (on-hit (damage target 3 dragonfire))
                        (on-swing
                          (vein :motion sweep :spread 120
                            :ccw (on-hit (damage target 5 dragonfire))))))
                    """,
            "sweep", """
                    ; 龙炎横扫（调试）：身前一道 120° 的龙炎游脉
                    (spell :name "龙炎横扫" :mana 40
                      (vein :motion sweep :spread 120
                        (on-hit (damage target 6 dragonfire))))
                    """);

    private DragonfireCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        var page = Commands.literal("page");
        for (String id : PAGES.keySet()) page.then(Commands.literal(id).executes(c -> page(c.getSource(), id)));
        return Commands.literal("dragonfire")
                .then(page)
                .then(Commands.literal("hit").then(Commands.argument("targets", EntityArgument.entities()).executes(c -> hit(c, 4))
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0, 1000)).executes(c -> hit(c, DoubleArgumentType.getDouble(c, "amount"))))))
                .then(Commands.literal("wrap").then(Commands.argument("targets", EntityArgument.entities()).executes(c -> wrap(c, 60))
                        .then(Commands.argument("seconds", DoubleArgumentType.doubleArg(0, 3600)).executes(c -> wrap(c, DoubleArgumentType.getDouble(c, "seconds"))))))
                .then(Commands.literal("burn").then(Commands.argument("targets", EntityArgument.entities()).executes(DragonfireCommands::burn)))
                .then(Commands.literal("teach").then(Commands.argument("players", EntityArgument.players()).executes(c -> teach(c, true))))
                .then(Commands.literal("forget").then(Commands.argument("players", EntityArgument.players()).executes(c -> teach(c, false))));
    }

    private static int page(CommandSourceStack src, String id) throws CommandSyntaxException {
        var player = src.getPlayerOrException();
        ItemStack stack = SpellPages.fitting(PAGES.get(id));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("龙炎术式 · " + (id.equals("wrap") ? "龙炎缠绕" : "龙炎横扫")));
        if (!player.getInventory().add(stack)) player.drop(stack, false);
        src.sendSuccess(() -> Component.literal("给了一张龙炎术式页（没学会龙炎之言时，编纂台里打开会显示成奥秘，存回去就不再是龙炎）"), false);
        return 1;
    }

    private static int hit(CommandContext<CommandSourceStack> c, double amount) throws CommandSyntaxException {
        if (!(c.getSource().getEntity() instanceof LivingEntity attacker)) {
            c.getSource().sendFailure(Component.literal("要由一个生物来放（玩家执行，或者 execute as）"));
            return 0;
        }
        int n = 0;
        for (Entity e : EntityArgument.getEntities(c, "targets")) {
            if (!(e instanceof LivingEntity t) || t == attacker) continue;
            t.invulnerableTime = 0;
            Dragonfire.deal(attacker, t, amount);
            n++;
        }
        int hit = n;
        c.getSource().sendSuccess(() -> Component.literal("对 " + hit + " 个目标放了 " + amount + " 点龙炎"), false);
        return n;
    }

    private static int wrap(CommandContext<CommandSourceStack> c, double seconds) throws CommandSyntaxException {
        int n = 0;
        for (Entity e : EntityArgument.getEntities(c, "targets")) {
            if (!(e instanceof LivingEntity t)) continue;
            Dragonfire.wrap(t, 3, seconds);
            n++;
        }
        int done = n;
        c.getSource().sendSuccess(() -> Component.literal("给 " + done + " 个目标的手缠上了龙炎（每下附带 3 点，" + seconds + " 秒）"), false);
        return n;
    }

    private static int burn(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        int n = 0;
        for (Entity e : EntityArgument.getEntities(c, "targets")) {
            if (!(e instanceof LivingEntity t) || DragonfireRules.kin(t)) continue;
            Dragonfire.burn(t, c.getSource().getEntity(), DragonfireRules.BURN_TICKS);
            n++;
        }
        int done = n;
        c.getSource().sendSuccess(() -> Component.literal("让 " + done + " 个目标龙炎缠身（龙族不会）"), false);
        return n;
    }

    private static int teach(CommandContext<CommandSourceStack> c, boolean teach) throws CommandSyntaxException {
        int n = 0;
        for (ServerPlayer p : EntityArgument.getPlayers(c, "players")) {
            if (teach) {
                if (Dragonfire.teach(p)) n++;
            } else if (Dragonfire.knows(p)) {
                Dragonfire.forget(p);
                n++;
            }
        }
        int done = n;
        c.getSource().sendSuccess(() -> Component.literal((teach ? "教会了 " : "让 ") + done + " 个玩家" + (teach ? "龙炎之言" : "忘掉了龙炎之言")), false);
        return n;
    }
}
