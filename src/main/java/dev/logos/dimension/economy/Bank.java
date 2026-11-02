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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * 钱庄：全服所有账户存在主世界存档里。存取款、开平仓都要人在钱庄旁边；
 * 强平、止损、止盈和隔夜利息由服务端随市场一起结算，玩家离线时照样发生。
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

    /** 市场推进之后调用：跨过几夜就计几次利息，再逐笔检查强平、止损、止盈。 */
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
        for (var entry : bank.accounts.entrySet()) {
            var account = entry.getValue();
            for (var p : List.copyOf(account.positions)) {
                var trigger = Margin.check(p, market.rate(p.currency()));
                if (trigger == Margin.Trigger.NONE) continue;
                String key = switch (trigger) { case LIQUIDATE -> "liquidate"; case STOP -> "stop"; default -> "take"; };
                settle(server, bank, market, entry.getKey(), account, p, key, clock);
            }
        }
    }

    private static void settle(MinecraftServer server, Bank bank, FxMarket market, UUID owner, BankAccount account, Margin.Position p, String key, long clock) {
        long back = Margin.close(market, p);
        account.positions.remove(p);
        account.add(Currency.IMPERIAL, back);
        var args = List.of(p.currency().key(), p.side() > 0 ? "bank.side.long" : "bank.side.short", crowns(back - p.margin()));
        account.notice(clock, "bank.notice." + key, args);
        bank.setDirty();
        FxMarketData.touch(server);
        var player = server.getPlayerList().getPlayer(owner);
        if (player != null) {
            player.sendSystemMessage(text("bank.notice." + key, args).withStyle(key.equals("liquidate") ? ChatFormatting.RED : ChatFormatting.GOLD));
            if (key.equals("liquidate")) player.playSound(SoundEvents.GLASS_BREAK, .8f, .7f);
        }
    }

    /** 金冠数写成带正负号的两位小数，作为翻译参数。 */
    static String crowns(double cents) { return String.format(java.util.Locale.ROOT, "%+,.2f", cents / 10_000); }

    /** 「+1.23 金冠」。 */
    public static net.minecraft.network.chat.MutableComponent crownsText(double cents) {
        return Component.translatable("screen.logos_dimension.bank.crowns", crowns(cents));
    }

    /** 消息参数里的翻译键（货币名、多空）翻成文字，其余原样。 */
    public static net.minecraft.network.chat.MutableComponent text(String key, List<String> args) {
        var out = new ArrayList<Object>();
        for (var a : args) out.add(a.startsWith("currency.") || a.startsWith("bank.") ? Component.translatable(a) : a);
        return Component.translatable(key, out.toArray());
    }

    // ---------------------------------------------------------------- 玩家操作

    private static void say(ServerPlayer player, String key, Object... args) {
        player.sendOverlayMessage(Component.translatable("message.logos_dimension.bank." + key, args));
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

    /** 开仓：白天才行；倍数不超过玩家上限；保证金从帝国币存款里扣，不够就借，借款不超过授信额度。 */
    public static boolean open(ServerPlayer player, Currency c, int side, long margin, int leverage, double stop, double take) {
        var server = player.level().getServer();
        long clock = Economy.clock(server);
        if (!FxMarket.open(clock)) { say(player, "closed"); return false; }
        if (!Currency.TRADED.contains(c) || (side != 1 && side != -1) || margin <= 0) return false;
        if (leverage < 1 || leverage > Margin.PLAYER_MAX) { say(player, "leverage", Margin.PLAYER_MAX); return false; }
        var bank = of(server);
        var account = bank.account(player.getUUID());
        if (account.positions.size() >= Margin.MAX_POSITIONS) { say(player, "too_many", Margin.MAX_POSITIONS); return false; }
        if (!account.canPost(margin)) { say(player, "credit"); return false; }
        var market = FxMarketData.market(server);
        // 止损得在亏的那一边、止盈在赚的那一边，否则一开仓就被平掉
        double rate = market.rate(c);
        if (stop > 0 && (side > 0 ? stop >= rate : stop <= rate) || take > 0 && (side > 0 ? take <= rate : take >= rate)) {
            say(player, "bad_stop"); return false;
        }
        var p = Margin.open(market, bank.nextId++, c, side, margin, leverage, stop, take, clock, true);
        account.add(Currency.IMPERIAL, -margin);
        account.positions.add(p);
        bank.setDirty();
        FxMarketData.touch(server);
        player.playSound(SoundEvents.CHAIN_PLACE, .7f, 1.1f);
        return true;
    }

    /** 手动平仓：白天才行。 */
    public static boolean close(ServerPlayer player, long id) {
        var server = player.level().getServer();
        long clock = Economy.clock(server);
        if (!FxMarket.open(clock)) { say(player, "closed"); return false; }
        var bank = of(server);
        var account = bank.account(player.getUUID());
        var p = account.find(id);
        if (p == null) return false;
        settle(server, bank, FxMarketData.market(server), player.getUUID(), account, p, "close", clock);
        return true;
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
