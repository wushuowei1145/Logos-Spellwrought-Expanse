package dev.logos.dimension.economy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.function.ToDoubleFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;

/**
 * 一位玩家在钱庄的账户：各货币存款、杠杆持仓和最近的通知。存在钱庄里，死亡不掉。
 * 帝国币可以是负数：保证金不够时向钱庄借，眼下只记成负数，没有利息也不催收，额度见 {@link #CREDIT_LIMIT}。
 * 借来的钱只能当保证金，不能取现：欠着钱时，取款后的总净值不能为负。
 */
public final class BankAccount {
    /** 授信额度（铜分）：帝国币最多欠到负一百金冠。没有上限的话，输了只是个负数、赢了能取现，等于白送彩票。 */
    public static final long CREDIT_LIMIT = 1_000_000;
    static final int NOTICES = 12;

    final EnumMap<Currency, Long> balances = new EnumMap<>(Currency.class);
    final List<Margin.Position> positions = new ArrayList<>();
    final ArrayDeque<FxMarket.News> notices = new ArrayDeque<>();

    public long balance(Currency c) { return balances.getOrDefault(c, 0L); }
    public List<Margin.Position> positions() { return List.copyOf(positions); }
    public List<FxMarket.News> notices() { return List.copyOf(notices); }

    void add(Currency c, long delta) { balances.put(c, Math.addExact(balance(c), delta)); }

    /** 欠钱庄多少铜分（没有欠就是 0）。 */
    public long debt() { return Math.max(0, -balance(Currency.IMPERIAL)); }

    /** 从帝国币里拿出 margin 作保证金后，欠款还在额度内。 */
    public boolean canPost(long margin) {
        try { return Math.subtractExact(balance(Currency.IMPERIAL), margin) >= -CREDIT_LIMIT; }
        catch (ArithmeticException e) { return false; }
    }

    /** 存款按中间价折合的净值（不含持仓）。 */
    public double net(ToDoubleFunction<Currency> rate) {
        double v = 0;
        for (var e : balances.entrySet()) v += e.getValue() * rate.applyAsDouble(e.getKey());
        return v;
    }

    /** 取款：这种货币的余额要够；欠着钱时，取完后的净值不能为负，借款不能变现。 */
    public boolean canWithdraw(Currency c, long amount, ToDoubleFunction<Currency> rate) {
        if (amount <= 0 || balance(c) < amount) return false;
        return debt() == 0 || net(rate) - amount * rate.applyAsDouble(c) >= 0;
    }

    void notice(long time, String key, List<String> args) {
        notices.add(new FxMarket.News(time, key, List.copyOf(args), 0));
        while (notices.size() > NOTICES) notices.removeFirst();
    }

    Margin.Position find(long id) {
        for (var p : positions) if (p.id() == id) return p;
        return null;
    }

    // ---------------------------------------------------------------- 读写

    CompoundTag save() {
        var tag = new CompoundTag();
        var bs = new CompoundTag();
        balances.forEach((c, v) -> { if (v != 0) bs.putLong(c.id, v); });
        tag.put("balances", bs);
        var ps = new ListTag();
        for (var p : positions) {
            var pt = new CompoundTag();
            pt.putLong("id", p.id()); pt.putString("currency", p.currency().id); pt.putInt("side", p.side());
            pt.putLong("margin", p.margin()); pt.putInt("leverage", p.leverage()); pt.putDouble("entry", p.entry());
            pt.putDouble("units", p.units()); pt.putLong("opened", p.opened()); pt.putDouble("stop", p.stop());
            pt.putDouble("take", p.take()); pt.putDouble("accrued", p.accrued());
            ps.add(pt);
        }
        tag.put("positions", ps);
        var ns = new ListTag();
        for (var n : notices) {
            var nt = new CompoundTag();
            nt.putLong("time", n.time()); nt.putString("key", n.key());
            var as = new ListTag(); n.args().forEach(a -> as.add(StringTag.valueOf(a))); nt.put("args", as);
            ns.add(nt);
        }
        tag.put("notices", ns);
        return tag;
    }

    static BankAccount load(CompoundTag tag) {
        var a = new BankAccount();
        var bs = tag.getCompoundOrEmpty("balances");
        for (var c : Currency.values()) { long v = bs.getLongOr(c.id, 0); if (v != 0) a.balances.put(c, v); }
        var ps = tag.getListOrEmpty("positions");
        for (int i = 0; i < ps.size(); i++) {
            var pt = ps.getCompoundOrEmpty(i);
            Currency c;
            try { c = Currency.byId(pt.getStringOr("currency", "")); } catch (IllegalArgumentException e) { continue; }
            int leverage = Math.clamp(pt.getIntOr("leverage", 1), 1, Margin.SUPPORTED_MAX);
            a.positions.add(new Margin.Position(pt.getLongOr("id", 0), c, pt.getIntOr("side", 1) > 0 ? 1 : -1, pt.getLongOr("margin", 0), leverage,
                    pt.getDoubleOr("entry", c.anchor), pt.getDoubleOr("units", 0), pt.getLongOr("opened", 0),
                    pt.getDoubleOr("stop", 0), pt.getDoubleOr("take", 0), pt.getDoubleOr("accrued", 0)));
        }
        var ns = tag.getListOrEmpty("notices");
        for (int i = 0; i < ns.size(); i++) {
            var nt = ns.getCompoundOrEmpty(i); var as = nt.getListOrEmpty("args"); var args = new ArrayList<String>();
            for (int k = 0; k < as.size(); k++) args.add(as.getStringOr(k, ""));
            a.notices.add(new FxMarket.News(nt.getLongOr("time", 0), nt.getStringOr("key", ""), args, 0));
        }
        return a;
    }
}
