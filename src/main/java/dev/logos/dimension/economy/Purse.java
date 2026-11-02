package dev.logos.dimension.economy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 玩家身上的钱：万国钱囊里的余额，加上背包里散放的钱币。
 * 收钱优先进钱囊（没有钱囊才发实体钱币），付钱先用钱囊再用散币，散币多付的部分找零。
 * 结账 {@link #pay} 先用商人的货币，不够时按钱囊的换汇顺序自动兑换，锁住的货币不动。
 */
public final class Purse {
    /** 主手、副手，再找背包里第一只钱囊。 */
    public static ItemStack wallet(Player player) {
        if (player.getMainHandItem().getItem() instanceof WalletItem) return player.getMainHandItem();
        if (player.getOffhandItem().getItem() instanceof WalletItem) return player.getOffhandItem();
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).getItem() instanceof WalletItem) return inv.getItem(i);
        return ItemStack.EMPTY;
    }

    public static WalletContents contents(ItemStack wallet) { return wallet.getOrDefault(Economy.WALLET.get(), WalletContents.EMPTY); }
    static void set(ItemStack wallet, WalletContents contents) { wallet.set(Economy.WALLET.get(), contents); }

    /** 把一叠钱币整叠存进钱囊；不是钱币或会溢出时不动。 */
    public static boolean deposit(ItemStack wallet, ItemStack coins) {
        if (!(coins.getItem() instanceof CoinItem coin) || coins.isEmpty()) return false;
        var next = contents(wallet).add(coin.currency, coin.value(coins));
        if (next == null) return false;
        set(wallet, next);
        coins.setCount(0);
        return true;
    }

    /** 背包里所有散币存进钱囊，返回存了几叠。 */
    public static int depositAll(Player player, ItemStack wallet) {
        int moved = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) if (deposit(wallet, inv.getItem(i))) moved++;
        return moved;
    }

    /** 从钱囊取出 count 枚某面额的钱币；余额不够就取到够为止。返回实际取出几枚。 */
    public static int withdraw(Player player, ItemStack wallet, Currency currency, int denomination, int count) {
        if (denomination < 0 || denomination >= currency.denominations.size() || count <= 0) return 0;
        long value = currency.denominations.get(denomination).value();
        int n = (int) Math.min(count, contents(wallet).get(currency) / value);
        if (n <= 0) return 0;
        set(wallet, contents(wallet).add(currency, -n * value));
        var coin = Economy.coin(currency, denomination);
        for (int left = n; left > 0; ) {
            int k = Math.min(left, coin.getDefaultMaxStackSize());
            player.getInventory().placeItemBackInInventory(new ItemStack(coin, k));
            left -= k;
        }
        return n;
    }

    /** 背包散币里某货币一共值多少个最小单位。 */
    public static long loose(Player player, Currency currency) {
        long sum = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++)
            if (inv.getItem(i).getItem() instanceof CoinItem coin && coin.currency == currency) sum += coin.value(inv.getItem(i));
        return sum;
    }

    /** 钱囊余额加散币。 */
    public static long available(Player player, Currency currency) {
        var wallet = wallet(player);
        return loose(player, currency) + (wallet.isEmpty() ? 0 : contents(wallet).get(currency));
    }

    /** 收钱：有钱囊进钱囊，没有就按面额从大到小发钱币，背包满了掉在脚下。 */
    public static void receive(Player player, Currency currency, long amount) {
        if (amount <= 0) return;
        var wallet = wallet(player);
        if (!wallet.isEmpty()) {
            var next = contents(wallet).add(currency, amount);
            if (next != null) { set(wallet, next); return; }
        }
        long rest = amount;
        for (int i = currency.denominations.size() - 1; i >= 0; i--) {
            long value = currency.denominations.get(i).value();
            long n = rest / value;
            rest %= value;
            var coin = Economy.coin(currency, i);
            while (n > 0) {
                int k = (int) Math.min(n, coin.getDefaultMaxStackSize());
                player.getInventory().placeItemBackInInventory(new ItemStack(coin, k));
                n -= k;
            }
        }
    }

    /** 付钱：先扣钱囊，再从小面额起拿散币，多拿的找零。不够时什么也不动，返回 false。 */
    public static boolean spend(Player player, Currency currency, long amount) {
        if (amount <= 0) return true;
        if (available(player, currency) < amount) return false;
        var wallet = wallet(player);
        long rest = amount;
        if (!wallet.isEmpty()) {
            long take = Math.min(rest, contents(wallet).get(currency));
            set(wallet, contents(wallet).add(currency, -take));
            rest -= take;
        }
        if (rest == 0) return true;
        var inv = player.getInventory();
        var slots = new ArrayList<Integer>();
        for (int i = 0; i < inv.getContainerSize(); i++)
            if (inv.getItem(i).getItem() instanceof CoinItem coin && coin.currency == currency) slots.add(i);
        slots.sort(Comparator.comparingLong(i -> ((CoinItem) inv.getItem(i).getItem()).denomination.value()));
        long taken = 0;
        for (int i : slots) {
            if (taken >= rest) break;
            var stack = inv.getItem(i);
            long value = ((CoinItem) stack.getItem()).denomination.value();
            int k = (int) Math.min(stack.getCount(), (rest - taken + value - 1) / value);
            stack.shrink(k);
            taken += k * value;
        }
        receive(player, currency, taken - rest);
        return true;
    }

    /**
     * 结账的结果：ok 是否付得起；direct 直接用商人货币付了多少；sold 为凑齐差额各卖出多少（最小单位）。
     * commit 为 false 时只是预览，不动钱也不动市场。
     */
    public record Payment(boolean ok, long direct, Map<Currency, Long> sold) {}

    public static Payment pay(ServerPlayer player, Currency currency, long price, boolean commit) {
        var market = FxMarketData.market(player.level().getServer());
        long have = available(player, currency);
        long direct = Math.min(have, price);
        long need = price - direct;
        var sold = new EnumMap<Currency, Long>(Currency.class);
        var wallet = wallet(player);
        if (need > 0 && !wallet.isEmpty()) {
            var contents = contents(wallet);
            for (var from : contents.payOrder(currency, market::rate)) {
                if (need <= 0) break;
                long balance = contents.get(from);
                long amount = cheapest(market, from, currency, need, balance);
                long got = market.quote(from, currency, amount).received();
                sold.put(from, amount);
                need -= Math.min(need, got);
            }
        }
        if (need > 0) return new Payment(false, direct, sold);
        if (commit) {
            // 换来的钱先进钱囊，再统一扣款：多换出的零头留在钱囊里
            for (var e : sold.entrySet()) {
                set(wallet, contents(wallet).add(e.getKey(), -e.getValue()));
                set(wallet, contents(wallet).add(currency, market.trade(e.getKey(), currency, e.getValue())));
            }
            if (!sold.isEmpty()) FxMarketData.touch(player.level().getServer());
            if (!spend(player, currency, price)) return new Payment(false, direct, sold);
        }
        return new Payment(true, direct, sold);
    }

    /** 最少卖出多少 from 才能换到 need 个 to；卖光也不够就返回全部余额。 */
    static long cheapest(FxMarket market, Currency from, Currency to, long need, long balance) {
        if (market.quote(from, to, balance).received() < need) return balance;
        long lo = 1, hi = balance;
        while (lo < hi) {
            long mid = lo + (hi - lo) / 2;
            if (market.quote(from, to, mid).received() >= need) hi = mid; else lo = mid + 1;
        }
        return lo;
    }

    private Purse() {}
}
