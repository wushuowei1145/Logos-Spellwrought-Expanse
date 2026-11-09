package dev.logos.dimension.foxfire;

import java.util.Collection;
import java.util.Map;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.logos.item.SpellPages;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * 试狐火的命令（管理员，docs/FOXFIRE.md 第 6 节）。编纂台里写不了 {@code foxfire}，调试时这样拿：
 * <pre>
 * /alchemy foxfire page &lt;wrap|sweep&gt;                    一张狐火术式页（施放者不是白梅，所以是幽蓝的）
 * /alchemy foxfire hit &lt;目标&gt; [点数] [shiraume]          以自己的名义对目标放一下狐火
 * /alchemy foxfire wrap &lt;目标&gt; [秒] [shiraume]           给目标的手缠上狐火（以后少女白梅给玩家缠的就是这种，带 shiraume 是她的淡粉）
 * /alchemy foxfire burn &lt;目标&gt; [shiraume]               让目标狐火缠身
 * </pre>
 * 也可以用 Logos 的 {@code /logos cast} 直接施放写着 foxfire 的术式。
 */
public final class FoxfireCommands {
    /** 调试用的狐火术式：神狐的狐火缠绕，和一道单独的狐火横扫游脉。 */
    public static final Map<String, String> PAGES = Map.of(
            "wrap", """
                    ; 狐火缠绕（调试）：和神狐的是同一条术式
                    (spell :name "狐火缠绕" :mana 100 :trigger cast
                      (imbue :life 60 :every 60
                        (on-hit (damage target 3 foxfire))
                        (on-swing
                          (vein :motion sweep :spread 120
                            :ccw (on-hit (damage target 5 foxfire))))))
                    """,
            "sweep", """
                    ; 狐火横扫（调试）：身前一道 120° 的狐火游脉
                    (spell :name "狐火横扫" :mana 40
                      (vein :motion sweep :spread 120
                        (on-hit (damage target 6 foxfire))))
                    """);

    private FoxfireCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        var page = Commands.literal("page");
        for (String id : PAGES.keySet()) page.then(Commands.literal(id).executes(c -> page(c.getSource(), id)));
        return Commands.literal("foxfire")
                .then(page)
                .then(Commands.literal("hit").then(flamed(Commands.argument("targets", EntityArgument.entities()), (c, flame) -> hit(c, flame, 4))
                        .then(flamed(Commands.argument("amount", DoubleArgumentType.doubleArg(0, 1000)),
                                (c, flame) -> hit(c, flame, DoubleArgumentType.getDouble(c, "amount"))))))
                .then(Commands.literal("wrap").then(flamed(Commands.argument("targets", EntityArgument.entities()), (c, flame) -> wrap(c, flame, 60))
                        .then(flamed(Commands.argument("seconds", DoubleArgumentType.doubleArg(0, 3600)),
                                (c, flame) -> wrap(c, flame, DoubleArgumentType.getDouble(c, "seconds"))))))
                .then(Commands.literal("burn").then(flamed(Commands.argument("targets", EntityArgument.entities()), FoxfireCommands::burn)));
    }

    private interface Run {
        int run(CommandContext<CommandSourceStack> c, FoxfireFlame flame) throws CommandSyntaxException;
    }

    /** 这一级可以直接执行（幽蓝），也可以再跟一个 {@code shiraume}（白梅的淡粉）。 */
    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T flamed(T node, Run run) {
        return node.executes(c -> run.run(c, FoxfireFlame.KITSUNE))
                .then(Commands.literal("shiraume").executes(c -> run.run(c, FoxfireFlame.SHIRAUME)));
    }

    private static int page(CommandSourceStack src, String id) throws CommandSyntaxException {
        var player = src.getPlayerOrException();
        ItemStack stack = SpellPages.fitting(PAGES.get(id));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("狐火术式 · " + (id.equals("wrap") ? "狐火缠绕" : "狐火横扫")));
        if (!player.getInventory().add(stack)) player.drop(stack, false);
        src.sendSuccess(() -> Component.literal("给了一张狐火术式页（编纂台里打开会显示成奥秘，存回去就不再是狐火）"), false);
        return 1;
    }

    private static int hit(CommandContext<CommandSourceStack> c, FoxfireFlame flame, double amount) throws CommandSyntaxException {
        if (!(c.getSource().getEntity() instanceof LivingEntity attacker)) {
            c.getSource().sendFailure(Component.literal("要由一个生物来放（玩家执行，或者 execute as）"));
            return 0;
        }
        int n = 0;
        for (Entity e : EntityArgument.getEntities(c, "targets")) {
            if (!(e instanceof LivingEntity t) || t == attacker) continue;
            t.invulnerableTime = 0;
            Foxfire.deal(attacker, t, amount, flame);
            n++;
        }
        int hit = n;
        c.getSource().sendSuccess(() -> Component.literal("对 " + hit + " 个目标放了 " + amount + " 点" + name(flame) + "的狐火"), false);
        return n;
    }

    private static int wrap(CommandContext<CommandSourceStack> c, FoxfireFlame flame, double seconds) throws CommandSyntaxException {
        Collection<? extends Entity> targets = EntityArgument.getEntities(c, "targets");
        int n = 0;
        for (Entity e : targets) {
            if (!(e instanceof LivingEntity t)) continue;
            FoxfireWraps.wrap(t, flame, 3, seconds);
            n++;
        }
        int done = n;
        c.getSource().sendSuccess(() -> Component.literal("给 " + done + " 个目标的手缠上了" + name(flame) + "的狐火（每下附带 3 点，" + seconds + " 秒）"), false);
        return n;
    }

    private static int burn(CommandContext<CommandSourceStack> c, FoxfireFlame flame) throws CommandSyntaxException {
        int n = 0;
        for (Entity e : EntityArgument.getEntities(c, "targets")) {
            if (!(e instanceof LivingEntity t) || FoxfireRules.kin(t)) continue;
            FoxfireRules.burn(t, flame, c.getSource().getEntity(), FoxfireRules.BURN_TICKS);
            n++;
        }
        int done = n;
        c.getSource().sendSuccess(() -> Component.literal("让 " + done + " 个目标" + name(flame) + "的狐火缠身（同族不会）"), false);
        return n;
    }

    private static String name(FoxfireFlame flame) {
        return flame == FoxfireFlame.SHIRAUME ? "淡粉" : "幽蓝";
    }
}
