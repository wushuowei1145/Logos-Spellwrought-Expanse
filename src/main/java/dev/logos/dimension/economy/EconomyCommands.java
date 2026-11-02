package dev.logos.dimension.economy;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 管理员调试命令 /fx：
 * <ul>
 *   <li>/fx —— 列出当前汇率和待揭晓的事件数</li>
 *   <li>/fx give &lt;货币&gt; &lt;最小单位数&gt; —— 发钱（有钱囊进钱囊）</li>
 *   <li>/fx pay &lt;货币&gt; &lt;最小单位数&gt; [confirm] —— 模拟向商人结账，不加 confirm 只预览</li>
 *   <li>/fx event &lt;种类&gt; [小时] —— 立刻放出一条传闻，默认一天后揭晓</li>
 *   <li>/fx reset —— 换新种子，汇率回到锚定值</li>
 * </ul>
 */
public final class EconomyCommands {
    public static void register(RegisterCommandsEvent event) {
        var root = Commands.literal("fx").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> status(c.getSource()));
        var currency = Commands.argument("currency", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(java.util.Arrays.stream(Currency.values()).map(x -> x.id), b));
        root.then(Commands.literal("give").then(currency.then(Commands.argument("amount", LongArgumentType.longArg(1, 1_000_000_000_000L))
                .executes(c -> {
                    var player = c.getSource().getPlayerOrException();
                    var cur = parse(c.getSource(), StringArgumentType.getString(c, "currency"));
                    if (cur == null) return 0;
                    long amount = LongArgumentType.getLong(c, "amount");
                    Purse.receive(player, cur, amount);
                    c.getSource().sendSuccess(() -> Component.literal("发给 ").append(player.getDisplayName()).append(" ").append(Money.format(cur, amount)), false);
                    return 1;
                }))));
        var payCurrency = Commands.argument("currency", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(java.util.Arrays.stream(Currency.values()).map(x -> x.id), b));
        var payAmount = Commands.argument("amount", LongArgumentType.longArg(1, 1_000_000_000_000L));
        root.then(Commands.literal("pay").then(payCurrency.then(payAmount
                .executes(c -> pay(c.getSource(), StringArgumentType.getString(c, "currency"), LongArgumentType.getLong(c, "amount"), false))
                .then(Commands.literal("confirm")
                        .executes(c -> pay(c.getSource(), StringArgumentType.getString(c, "currency"), LongArgumentType.getLong(c, "amount"), true))))));
        var kind = Commands.argument("kind", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(FxMarket.kinds(), b));
        root.then(Commands.literal("event").then(kind
                .executes(c -> event(c.getSource(), StringArgumentType.getString(c, "kind"), 24))
                .then(Commands.argument("hours", IntegerArgumentType.integer(1, 96))
                        .executes(c -> event(c.getSource(), StringArgumentType.getString(c, "kind"), IntegerArgumentType.getInteger(c, "hours"))))));
        root.then(Commands.literal("reset").executes(c -> {
            var server = c.getSource().getServer();
            FxMarketData.reset(server, Economy.clock(server));
            c.getSource().sendSuccess(() -> Component.literal("市场已重置"), true);
            return 1;
        }));
        event.getDispatcher().register(root);
    }

    private static Currency parse(CommandSourceStack source, String id) {
        try { return Currency.byId(id); }
        catch (IllegalArgumentException e) { source.sendFailure(Component.literal("未知货币：" + id)); return null; }
    }

    private static int status(CommandSourceStack source) {
        var server = source.getServer();
        var market = FxMarketData.market(server);
        market.advance(Economy.clock(server));
        long clock = Economy.clock(server);
        source.sendSuccess(() -> Component.literal("外汇市场 · 第 " + Math.floorDiv(clock, FxMarket.DAY) + " 天 · "
                + (FxMarket.open(clock) ? "开市" : "休市") + " · 待揭晓 " + market.pending.size() + " 件"), false);
        for (var c : Currency.TRADED) {
            double perTop = market.rate(c) * c.top().value();
            source.sendSuccess(() -> Component.literal("  1 ").append(Component.translatable(c.coinKey(c.top())))
                    .append(" = ").append(Money.top(Currency.IMPERIAL, perTop)), false);
        }
        return 1;
    }

    private static int pay(CommandSourceStack source, String id, long amount, boolean commit) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = source.getPlayerOrException();
        var cur = parse(source, id);
        if (cur == null) return 0;
        var result = Purse.pay(player, cur, amount, commit);
        var line = Component.literal(result.ok() ? (commit ? "已付 " : "可以付 ") : "付不起 ").append(Money.format(cur, amount));
        if (!result.sold().isEmpty()) {
            line.append("，自动换汇卖出");
            result.sold().forEach((c, v) -> line.append(" ").append(Money.format(c, v)));
        }
        source.sendSuccess(() -> line, false);
        return result.ok() ? 1 : 0;
    }

    private static int event(CommandSourceStack source, String kind, int hours) {
        var server = source.getServer();
        var market = FxMarketData.market(server);
        long clock = Economy.clock(server);
        market.advance(clock);
        if (!market.force(kind, clock, hours * FxMarket.HOUR)) { source.sendFailure(Component.literal("未知事件：" + kind)); return 0; }
        FxMarketData.touch(server);
        source.sendSuccess(() -> Component.literal("已放出传闻，" + hours + " 小时后揭晓"), true);
        return 1;
    }

    private EconomyCommands() {}
}
