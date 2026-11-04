package dev.logos.dimension.economy;

import dev.logos.dimension.LogosDimension;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * 钱庄：全服所有账户存在主世界存档里。存取款、开平仓和管理持仓都要人在钱庄旁边（现货挂单在兑换台下）；
 * 撤单和价格提醒在哪都能设。强平、止损、止盈、移动止损、爆仓预警、挂单成交、提醒和隔夜利息
 * 由服务端随市场一起结算，玩家离线时照样发生，结果记成通知，上线时补发。
 */
public final class Bank extends SavedData {
    private static final SavedDataType<Bank> TYPE = new SavedDataType<>(LogosDimension.id("fx_bank"), Bank::new,
            CompoundTag.CODEC.xmap(Bank::load, Bank::save));
    final Map<UUID, BankAccount> accounts = new HashMap<>();
    long nextId = 1, lastDay = Long.MIN_VALUE;

    private Bank() {}

    public static Bank of(MinecraftServer server) { return server.overworld().getDataStorage().computeIfAbsent(TYPE); }

    public BankAccount account(UUID player) { return accounts.computeIfAbsent(player, k -> new BankAccount()); }

    // ---------------------------------------------------------------- 随市场结算

    /** 市场推进之后调用：跨过几夜就计几次利息，再逐笔更新移动止损、预警、强平止损；开市时撮合挂单；最后看价格提醒。 */
    public static void update(MinecraftServer server, FxMarket market, long clock) {
        var bank = of(server);
        long day = Math.floorDiv(clock, FxMarket.DAY);
        if (bank.lastDay == Long.MIN_VALUE || day < bank.lastDay) { bank.lastDay = day; bank.setDirty(); }
        long nights = Math.min(60, day - bank.lastDay);
        if (nights > 0) {
            for (var account : bank.accounts.values())
                account.positions.replaceAll(p -> p.withAccrued(p.accrued() + Margin.overnight(p) * nights));
            bank.lastDay = day;
            bank.setDirty();
        }
        boolean open = FxMarket.open(clock);
        for (var entry : bank.accounts.entrySet()) {
            var owner = entry.getKey();
            var account = entry.getValue();
            for (var p0 : List.copyOf(account.positions)) {
                double rate = market.rate(p0.currency());
                var p = Margin.trailed(p0, rate);
                if (p != p0) { account.replace(p0, p); bank.setDirty(); }
                var trigger = Margin.check(p, rate);
                if (trigger != Margin.Trigger.NONE) {
                    String key = switch (trigger) { case LIQUIDATE -> "liquidate"; case STOP -> "stop"; default -> "take"; };
                    settle(server, bank, market, owner, account, p, key, clock);
                    continue;
                }
                double ratio = Margin.equity(p, rate) / Math.max(1, p.margin());
                if (!p.warned() && ratio <= Margin.WARNING) {
                    account.replace(p, p.withWarned(true));
                    notify(server, bank, owner, account, clock, "warning", List.of(p.currency().key(), side(p.side()), silver(p.currency(), Margin.liquidationRate(p))), ChatFormatting.RED);
                } else if (p.warned() && ratio >= Margin.REARM) {
                    account.replace(p, p.withWarned(false));
                    bank.setDirty();
                }
            }
            if (open) for (var o : List.copyOf(account.orders)) if (o.due(market.rate(o.currency()))) fill(server, bank, market, owner, account, o, clock);
            for (var a : List.copyOf(account.alerts)) {
                if (!a.due(market.rate(a.currency()))) continue;
                account.alerts.remove(a);
                notify(server, bank, owner, account, clock, "alert", List.of(a.currency().key(), a.above() ? "bank.above" : "bank.below", silver(a.currency(), a.price())), ChatFormatting.AQUA);
            }
        }
    }

    private static void settle(MinecraftServer server, Bank bank, FxMarket market, UUID owner, BankAccount account, Margin.Position p, String key, long clock) {
        long back = Margin.close(market, p);
        account.positions.remove(p);
        account.add(Currency.IMPERIAL, back);
        FxMarketData.touch(server);
        notify(server, bank, owner, account, clock, key, List.of(p.currency().key(), side(p.side()), crowns(back - p.margin())),
                key.equals("liquidate") ? ChatFormatting.RED : ChatFormatting.GOLD);
    }

    /** 挂单到价：按当时的市价成交，所得存入钱庄。 */
    private static void fill(MinecraftServer server, Bank bank, FxMarket market, UUID owner, BankAccount account, Orders.Pending o, long clock) {
        account.orders.remove(o);
        var c = o.currency();
        switch (o.kind()) {
            case Orders.BUY -> {
                long got = market.trade(Currency.IMPERIAL, c, o.amount());
                account.add(c, got);
                notify(server, bank, owner, account, clock, "buy", List.of(plain(o.amount()), coins(c, got), c.coinKey(c.top())), ChatFormatting.GOLD);
            }
            case Orders.SELL -> {
                long got = market.trade(c, Currency.IMPERIAL, o.amount());
                account.add(Currency.IMPERIAL, got);
                notify(server, bank, owner, account, clock, "sell", List.of(coins(c, o.amount()), c.coinKey(c.top()), plain(got)), ChatFormatting.GOLD);
            }
            default -> {
                if (account.positions.size() >= Margin.MAX_POSITIONS) {
                    account.add(Currency.IMPERIAL, o.amount());
                    notify(server, bank, owner, account, clock, "unfilled", List.of(c.key(), side(o.side())), ChatFormatting.GRAY);
                    break;
                }
                // 跳空成交时原先的止损止盈可能已在错的一侧，那就不设
                double rate = market.rate(c);
                double stop = Margin.validStops(o.side(), rate, o.stop(), 0, 0) ? o.stop() : 0;
                double take = Margin.validStops(o.side(), rate, 0, o.take(), 0) ? o.take() : 0;
                account.positions.add(Margin.open(market, o.id(), c, o.side(), o.amount(), o.leverage(), stop, take, o.trail(), clock, true));
                notify(server, bank, owner, account, clock, "filled", List.of(c.key(), side(o.side()), silver(c, rate)), ChatFormatting.GOLD);
            }
        }
        FxMarketData.touch(server);
    }

    /** 记一条通知；玩家在线就同时发到聊天栏（并算作看过）。 */
    private static void notify(MinecraftServer server, Bank bank, UUID owner, BankAccount account, long clock, String key, List<String> args, ChatFormatting color) {
        account.notice(clock, "bank.notice." + key, args);
        bank.setDirty();
        var player = server.getPlayerList().getPlayer(owner);
        if (player == null) return;
        account.seen = clock;
        player.sendSystemMessage(text("bank.notice." + key, args).withStyle(color));
        if (key.equals("liquidate")) player.playSound(SoundEvents.GLASS_BREAK, .8f, .7f);
        else if (key.equals("warning") || key.equals("alert")) player.playSound(SoundEvents.NOTE_BLOCK_BELL.value(), .8f, key.equals("warning") ? .7f : 1.4f);
    }

    /** 上线时补发离线期间的通知（最多五条，再多给个数）。 */
    public static void login(ServerPlayer player) {
        var bank = of(player.level().getServer());
        var account = bank.accounts.get(player.getUUID());
        if (account == null) return;
        var unseen = account.notices.stream().filter(n -> n.time() > account.seen).toList();
        if (unseen.isEmpty()) return;
        player.sendSystemMessage(Component.translatable("message.logos_dimension.bank.offline", unseen.size()).withStyle(ChatFormatting.GOLD));
        for (var n : unseen.subList(Math.max(0, unseen.size() - 5), unseen.size()))
            player.sendSystemMessage(Component.literal(" · ").append(text(n.key(), n.args())).withStyle(n.key().endsWith("liquidate") || n.key().endsWith("warning") ? ChatFormatting.RED : ChatFormatting.GRAY));
        account.seen = unseen.getLast().time();
        bank.setDirty();
    }

    static String side(int side) { return side > 0 ? "bank.side.long" : "bank.side.short"; }

    /** 金冠数写成带正负号的两位小数，作为翻译参数。 */
    static String crowns(double cents) { return String.format(java.util.Locale.ROOT, "%+,.2f", cents / 10_000); }
    static String plain(double cents) { return String.format(java.util.Locale.ROOT, "%,.2f", cents / 10_000); }
    /** 某货币多少枚最大面额。 */
    static String coins(Currency c, long amount) { return String.format(java.util.Locale.ROOT, "%,.2f", amount / (double) c.top().value()); }
    /** 中间价写成「一枚最大面额值多少银元」。 */
    static String silver(Currency c, double rate) { return String.format(java.util.Locale.ROOT, "%,.2f", rate * c.top().value() / 100); }

    /** 「+1.23 金冠」。 */
    public static MutableComponent crownsText(double cents) {
        return Component.translatable("screen.logos_dimension.bank.crowns", crowns(cents));
    }

    /** 消息参数里的翻译键（货币名、钱币名、多空、涨跌）翻成文字，其余原样。 */
    public static MutableComponent text(String key, List<String> args) {
        var out = new ArrayList<Object>();
        for (var a : args) out.add(a.startsWith("currency.") || a.startsWith("bank.") || a.startsWith("item.") ? Component.translatable(a) : a);
        return Component.translatable(key, out.toArray());
    }

    // ---------------------------------------------------------------- 玩家操作

    private static void say(ServerPlayer player, String key, Object... args) {
        player.sendOverlayMessage(Component.translatable("message.logos_dimension.bank." + key, args));
    }

    private static boolean dayOnly(ServerPlayer player) {
        if (FxMarket.open(Economy.clock(player.level().getServer()))) return true;
        say(player, "closed");
        return false;
    }

    /** 打开钱庄面板时，通知都算看过了。 */
    static void seen(ServerPlayer player) {
        var bank = of(player.level().getServer());
        var account = bank.account(player.getUUID());
        if (!account.notices.isEmpty() && account.notices.getLast().time() > account.seen) { account.seen = account.notices.getLast().time(); bank.setDirty(); }
    }

    /** 从钱囊和背包存入；amount 为 0 表示这种货币有多少存多少。 */
    public static boolean deposit(ServerPlayer player, Currency c, long amount) {
        long have = Purse.available(player, c);
        long take = amount <= 0 ? have : amount;
        if (take <= 0) return false;
        if (take > have) { say(player, "not_enough", Component.translatable(c.key())); return false; }
        var bank = of(player.level().getServer());
        var account = bank.account(player.getUUID());
        try { account.add(c, take); } catch (ArithmeticException e) { return false; }
        if (!Purse.spend(player, c, take)) { account.add(c, -take); return false; }
        bank.setDirty();
        WalletItem.ding(player);
        return true;
    }

    /** 取到钱囊（没有钱囊就发钱币）。 */
    public static boolean withdraw(ServerPlayer player, Currency c, long amount) {
        var server = player.level().getServer();
        var bank = of(server);
        var account = bank.account(player.getUUID());
        var market = FxMarketData.market(server);
        if (!account.canWithdraw(c, amount, market::rate)) { say(player, account.debt() > 0 ? "in_debt" : "not_enough", Component.translatable(c.key())); return false; }
        account.add(c, -amount);
        Purse.receive(player, c, amount);
        bank.setDirty();
        WalletItem.ding(player);
        return true;
    }

    public static boolean open(ServerPlayer player, Currency c, int side, long margin, int leverage, double stop, double take) {
        return open(player, c, side, margin, leverage, stop, take, 0);
    }

    /** 开仓：白天才行；倍数不超过玩家上限；保证金从帝国币存款里扣，不够就借，借款不超过授信额度。 */
    public static boolean open(ServerPlayer player, Currency c, int side, long margin, int leverage, double stop, double take, double trail) {
        if (!dayOnly(player)) return false;
        var server = player.level().getServer();
        var bank = of(server);
        var account = bank.account(player.getUUID());
        var market = FxMarketData.market(server);
        if (!checkOpen(player, account, c, side, margin, leverage, market.rate(c), stop, take, trail)) return false;
        if (account.positions.size() >= Margin.MAX_POSITIONS) { say(player, "too_many", Margin.MAX_POSITIONS); return false; }
        var p = Margin.open(market, bank.nextId++, c, side, margin, leverage, stop, take, trail, Economy.clock(server), true);
        account.add(Currency.IMPERIAL, -margin);
        account.positions.add(p);
        bank.setDirty();
        FxMarketData.touch(server);
        player.playSound(SoundEvents.CHAIN_PLACE, .7f, 1.1f);
        return true;
    }

    /** 开仓与杠杆挂单共用的校验；price 是止损止盈要对照的价（市价单为现价，挂单为挂价）。 */
    private static boolean checkOpen(ServerPlayer player, BankAccount account, Currency c, int side, long margin, int leverage,
                                     double price, double stop, double take, double trail) {
        if (!Currency.TRADED.contains(c) || (side != 1 && side != -1) || margin <= 0) return false;
        if (leverage < 1 || leverage > Margin.PLAYER_MAX) { say(player, "leverage", Margin.PLAYER_MAX); return false; }
        if (!account.canPost(margin)) { say(player, "credit"); return false; }
        if (!Margin.validStops(side, price, stop, take, trail)) { say(player, "bad_stop"); return false; }
        return true;
    }

    /** 手动平仓：白天才行。 */
    public static boolean close(ServerPlayer player, long id) {
        return reduce(player, id, 1);
    }

    /** 平掉 fraction（0–1]；剩下的部分保证金、利息按比例保留。 */
    public static boolean reduce(ServerPlayer player, long id, double fraction) {
        if (!dayOnly(player) || !(fraction > 0)) return false;
        var server = player.level().getServer();
        var bank = of(server);
        var account = bank.account(player.getUUID());
        var p = account.find(id);
        if (p == null) return false;
        long clock = Economy.clock(server);
        var market = FxMarketData.market(server);
        if (fraction >= .999) { settle(server, bank, market, player.getUUID(), account, p, "close", clock); return true; }
        var part = p.scaled(fraction);
        var rest = p.scaled(1 - fraction).withMargin(p.margin() - part.margin());
        long back = Margin.close(market, part);
        account.replace(p, rest);
        account.add(Currency.IMPERIAL, back);
        FxMarketData.touch(server);
        notify(server, bank, player.getUUID(), account, clock, "partial",
                List.of(p.currency().key(), side(p.side()), String.valueOf(Math.round(fraction * 100)), crowns(back - part.margin())), ChatFormatting.GOLD);
        return true;
    }

    /** 一键全平。 */
    public static boolean closeAll(ServerPlayer player) {
        if (!dayOnly(player)) return false;
        var account = of(player.level().getServer()).account(player.getUUID());
        boolean any = false;
        for (var p : List.copyOf(account.positions)) any |= reduce(player, p.id(), 1);
        return any;
    }

    /** 追加保证金：随时可以（夜里快爆仓时也能补），钱从帝国币存款出，不够就借。 */
    public static boolean addMargin(ServerPlayer player, long id, long amount) {
        if (amount <= 0) return false;
        var bank = of(player.level().getServer());
        var account = bank.account(player.getUUID());
        var p = account.find(id);
        if (p == null) return false;
        if (!account.canPost(amount)) { say(player, "credit"); return false; }
        account.add(Currency.IMPERIAL, -amount);
        account.replace(p, p.withMargin(p.margin() + amount));
        bank.setDirty();
        WalletItem.ding(player);
        return true;
    }

    /** 加仓：白天才行，同方向按均价合并。 */
    public static boolean increase(ServerPlayer player, long id, long margin, int leverage) {
        if (!dayOnly(player) || margin <= 0) return false;
        if (leverage < 1 || leverage > Margin.PLAYER_MAX) { say(player, "leverage", Margin.PLAYER_MAX); return false; }
        var server = player.level().getServer();
        var bank = of(server);
        var account = bank.account(player.getUUID());
        var p = account.find(id);
        if (p == null) return false;
        if (!account.canPost(margin)) { say(player, "credit"); return false; }
        account.add(Currency.IMPERIAL, -margin);
        account.replace(p, Margin.increase(FxMarketData.market(server), p, margin, leverage, true));
        bank.setDirty();
        FxMarketData.touch(server);
        player.playSound(SoundEvents.CHAIN_PLACE, .7f, 1.1f);
        return true;
    }

    /** 改止损、止盈、移动止损（0 表示取消）。随时可以。 */
    public static boolean modify(ServerPlayer player, long id, double stop, double take, double trail) {
        var server = player.level().getServer();
        var bank = of(server);
        var account = bank.account(player.getUUID());
        var p = account.find(id);
        if (p == null) return false;
        double rate = FxMarketData.market(server).rate(p.currency());
        if (!Margin.validStops(p.side(), rate, stop, take, trail)) { say(player, "bad_stop"); return false; }
        // 新设或改了移动止损，就从现价重新起算，否则会按开仓以来的最好价立刻把单子止损掉
        var restarted = trail == p.trail() ? p : new Margin.Position(p.id(), p.currency(), p.side(), p.margin(), p.leverage(), p.entry(), p.units(),
                p.opened(), p.stop(), p.take(), p.accrued(), p.trail(), rate, p.warned());
        var next = Margin.trailed(restarted.withStops(Math.max(0, stop), Math.max(0, take), trail), rate);
        account.replace(p, next);
        bank.setDirty();
        say(player, "modified");
        return true;
    }

    /** 杠杆挂单：随时可下，开市时到价才成交；保证金现在就冻结。 */
    public static boolean placeOpen(ServerPlayer player, Currency c, int side, long margin, int leverage, double price, double stop, double take, double trail) {
        if (!(price > 0)) return false;
        var bank = of(player.level().getServer());
        var account = bank.account(player.getUUID());
        if (account.orders.size() >= Orders.MAX_ORDERS) { say(player, "too_many_orders", Orders.MAX_ORDERS); return false; }
        if (!checkOpen(player, account, c, side, margin, leverage, price, stop, take, trail)) return false;
        account.add(Currency.IMPERIAL, -margin);
        account.orders.add(new Orders.Pending(bank.nextId++, Orders.OPEN, c, side, margin, leverage, price, stop, take, trail, Economy.clock(player.level().getServer())));
        bank.setDirty();
        say(player, "placed");
        return true;
    }

    /** 现货挂单（在兑换台下）：side 为 +1 用 amount 铜分买 c，−1 卖出 amount 个 c；钱从钱囊和散币冻结。 */
    public static boolean placeSpot(ServerPlayer player, Currency c, int side, long amount, double price) {
        if (!Currency.TRADED.contains(c) || amount <= 0 || !(price > 0) || (side != 1 && side != -1)) return false;
        var bank = of(player.level().getServer());
        var account = bank.account(player.getUUID());
        if (account.orders.size() >= Orders.MAX_ORDERS) { say(player, "too_many_orders", Orders.MAX_ORDERS); return false; }
        var pay = side > 0 ? Currency.IMPERIAL : c;
        if (!Purse.spend(player, pay, amount)) { say(player, "not_enough", Component.translatable(pay.key())); return false; }
        account.orders.add(new Orders.Pending(bank.nextId++, side > 0 ? Orders.BUY : Orders.SELL, c, side, amount, 1, price, 0, 0, 0, Economy.clock(player.level().getServer())));
        bank.setDirty();
        say(player, "placed");
        return true;
    }

    /** 撤单：在哪都行，冻结的钱退回钱庄。 */
    public static boolean cancel(ServerPlayer player, long id) {
        var bank = of(player.level().getServer());
        var account = bank.account(player.getUUID());
        var o = account.findOrder(id);
        if (o == null) return false;
        account.orders.remove(o);
        account.add(o.refundCurrency(), o.amount());
        bank.setDirty();
        say(player, "cancelled");
        return true;
    }

    /** 价格提醒：比现价高就等涨到，低就等跌到。 */
    public static boolean addAlert(ServerPlayer player, Currency c, double price) {
        if (!Currency.TRADED.contains(c) || !(price > 0)) return false;
        var server = player.level().getServer();
        var bank = of(server);
        var account = bank.account(player.getUUID());
        if (account.alerts.size() >= Orders.MAX_ALERTS) { say(player, "too_many_alerts", Orders.MAX_ALERTS); return false; }
        account.alerts.add(new Orders.Alert(bank.nextId++, c, price > FxMarketData.market(server).rate(c), price));
        bank.setDirty();
        return true;
    }

    public static boolean removeAlert(ServerPlayer player, long id) {
        var bank = of(player.level().getServer());
        boolean removed = bank.account(player.getUUID()).alerts.removeIf(a -> a.id() == id);
        if (removed) bank.setDirty();
        return removed;
    }

    // ---------------------------------------------------------------- 读写

    private CompoundTag save() {
        var tag = new CompoundTag();
        tag.putLong("next_id", nextId); tag.putLong("last_day", lastDay);
        var list = new ListTag();
        accounts.forEach((id, a) -> { var t = a.save(); t.putString("owner", id.toString()); list.add(t); });
        tag.put("accounts", list);
        return tag;
    }

    private static Bank load(CompoundTag tag) {
        var bank = new Bank();
        bank.nextId = tag.getLongOr("next_id", 1); bank.lastDay = tag.getLongOr("last_day", Long.MIN_VALUE);
        var list = tag.getListOrEmpty("accounts");
        for (int i = 0; i < list.size(); i++) {
            var t = list.getCompoundOrEmpty(i);
            try { bank.accounts.put(UUID.fromString(t.getStringOr("owner", "")), BankAccount.load(t)); }
            catch (IllegalArgumentException ignored) {}
        }
        return bank;
    }
}
