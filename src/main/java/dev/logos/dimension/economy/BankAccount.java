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
 * 一位玩家在钱庄的账户：各货币存款、杠杆持仓、挂单、价格提醒和最近的通知。存在钱庄里，死亡不掉。
 * 帝国币可以是负数：保证金不够时向钱庄借，眼下只记成负数，没有利息也不催收，额度见 {@link #CREDIT_LIMIT}。
 * 借来的钱只能当保证金，不能取现：欠着钱时，取款后的总净值不能为负。
 */
public final class BankAccount {
    /** 授信额度（铜分）：帝国币最多欠到负一百金冠。没有上限的话，输了只是个负数、赢了能取现，等于白送彩票。 */
    public static final long CREDIT_LIMIT = 1_000_000;
    static final int NOTICES = 12;

    final EnumMap<Currency, Long> balances = new EnumMap<>(Currency.class);
    final List<Margin.Position> positions = new ArrayList<>();
    final List<Orders.Pending> orders = new ArrayList<>();
    final List<Orders.Alert> alerts = new ArrayList<>();
    final ArrayDeque<FxMarket.News> notices = new ArrayDeque<>();
    /** 玩家看过的最后一条通知的时间；登录时补发比它新的。 */
    long seen = Long.MIN_VALUE;

    public long balance(Currency c) { return balances.getOrDefault(c, 0L); }
    public List<Margin.Position> positions() { return List.copyOf(positions); }
    public List<Orders.Pending> orders() { return List.copyOf(orders); }
    public List<Orders.Alert> alerts() { return List.copyOf(alerts); }
    public List<FxMarket.News> notices() { return List.copyOf(notices); }

    void add(Currency c, long delta) { balances.put(c, Math.addExact(balance(c), delta)); }

    /** 欠钱庄多少铜分（没有欠就是 0）。 */
    public long debt() { return Math.max(0, -balance(Currency.IMPERIAL)); }

    /** 从帝国币里拿出 margin 作保证金后，欠款还在额度内。 */
    public boolean canPost(long margin) {
        try { return Math.subtractExact(balance(Currency.IMPERIAL), margin) >= -CREDIT_LIMIT; }
        catch (ArithmeticException e) { return false; }
    }

    /** 存款按中间价折合的净值（不含持仓和挂单）。 */
    public double net(ToDoubleFunction<Currency> rate) {
        double v = 0;
        for (var e : balances.entrySet()) v += e.getValue() * rate.applyAsDouble(e.getKey());
        return v;
    }

    /** 账户总览：总权益（存款 + 持仓按现价能拿回的 + 挂单冻结）、已用保证金、浮动盈亏。 */
    public record Summary(double equity, long margin, double pnl, double frozen) {}

    public static Summary summary(long[] balances, List<Margin.Position> positions, List<Orders.Pending> orders, ToDoubleFunction<Currency> rate) {
        double equity = 0, pnl = 0, frozen = 0; long margin = 0;
        for (var c : Currency.values()) equity += balances[c.ordinal()] * rate.applyAsDouble(c);
        for (var p : positions) {
            double r = rate.applyAsDouble(p.currency());
            equity += Margin.payout(p, r);
            pnl += Margin.pnl(p, r) + p.accrued();
            margin += p.margin();
        }
        for (var o : orders) frozen += o.amount() * rate.applyAsDouble(o.refundCurrency());
        return new Summary(equity + frozen, margin, pnl, frozen);
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

    Orders.Pending findOrder(long id) {
        for (var o : orders) if (o.id() == id) return o;
        return null;
    }

    void replace(Margin.Position old, Margin.Position next) {
        int i = positions.indexOf(old);
        if (i >= 0) positions.set(i, next);
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
            pt.putDouble("trail", p.trail()); pt.putDouble("best", p.best()); pt.putBoolean("warned", p.warned());
            ps.add(pt);
        }
        tag.put("positions", ps);
        var os = new ListTag();
        for (var o : orders) {
            var ot = new CompoundTag();
            ot.putLong("id", o.id()); ot.putInt("kind", o.kind()); ot.putString("currency", o.currency().id); ot.putInt("side", o.side());
            ot.putLong("amount", o.amount()); ot.putInt("leverage", o.leverage()); ot.putDouble("price", o.price());
            ot.putDouble("stop", o.stop()); ot.putDouble("take", o.take()); ot.putDouble("trail", o.trail()); ot.putLong("placed", o.placed());
            os.add(ot);
        }
        tag.put("orders", os);
        var al = new ListTag();
        for (var a : alerts) {
            var at = new CompoundTag();
            at.putLong("id", a.id()); at.putString("currency", a.currency().id); at.putBoolean("above", a.above()); at.putDouble("price", a.price());
            al.add(at);
        }
        tag.put("alerts", al);
        var ns = new ListTag();
        for (var n : notices) {
            var nt = new CompoundTag();
            nt.putLong("time", n.time()); nt.putString("key", n.key());
            var as = new ListTag(); n.args().forEach(a -> as.add(StringTag.valueOf(a))); nt.put("args", as);
            ns.add(nt);
        }
        tag.put("notices", ns);
        tag.putLong("seen", seen);
        return tag;
    }

    private static Currency currency(CompoundTag t) {
        try { return Currency.byId(t.getStringOr("currency", "")); } catch (IllegalArgumentException e) { return null; }
    }

    static BankAccount load(CompoundTag tag) {
        var a = new BankAccount();
        var bs = tag.getCompoundOrEmpty("balances");
        for (var c : Currency.values()) { long v = bs.getLongOr(c.id, 0); if (v != 0) a.balances.put(c, v); }
        var ps = tag.getListOrEmpty("positions");
        for (int i = 0; i < ps.size(); i++) {
            var pt = ps.getCompoundOrEmpty(i);
            var c = currency(pt);
            if (c == null) continue;
            int leverage = Math.clamp(pt.getIntOr("leverage", 1), 1, Margin.SUPPORTED_MAX);
            double entry = pt.getDoubleOr("entry", c.anchor);
            a.positions.add(new Margin.Position(pt.getLongOr("id", 0), c, pt.getIntOr("side", 1) > 0 ? 1 : -1, pt.getLongOr("margin", 0), leverage,
                    entry, pt.getDoubleOr("units", 0), pt.getLongOr("opened", 0),
                    pt.getDoubleOr("stop", 0), pt.getDoubleOr("take", 0), pt.getDoubleOr("accrued", 0),
                    Math.clamp(pt.getDoubleOr("trail", 0), 0, Margin.MAX_TRAIL), pt.getDoubleOr("best", entry), pt.getBooleanOr("warned", false)));
        }
        var os = tag.getListOrEmpty("orders");
        for (int i = 0; i < os.size(); i++) {
            var ot = os.getCompoundOrEmpty(i);
            var c = currency(ot);
            if (c == null) continue;
            a.orders.add(new Orders.Pending(ot.getLongOr("id", 0), Math.clamp(ot.getIntOr("kind", 0), 0, 2), c, ot.getIntOr("side", 1) > 0 ? 1 : -1,
                    ot.getLongOr("amount", 0), Math.clamp(ot.getIntOr("leverage", 1), 1, Margin.SUPPORTED_MAX), ot.getDoubleOr("price", 0),
                    ot.getDoubleOr("stop", 0), ot.getDoubleOr("take", 0), Math.clamp(ot.getDoubleOr("trail", 0), 0, Margin.MAX_TRAIL), ot.getLongOr("placed", 0)));
        }
        var al = tag.getListOrEmpty("alerts");
        for (int i = 0; i < al.size(); i++) {
            var at = al.getCompoundOrEmpty(i);
            var c = currency(at);
            if (c != null) a.alerts.add(new Orders.Alert(at.getLongOr("id", 0), c, at.getBooleanOr("above", true), at.getDoubleOr("price", 0)));
        }
        var ns = tag.getListOrEmpty("notices");
        for (int i = 0; i < ns.size(); i++) {
            var nt = ns.getCompoundOrEmpty(i); var as = nt.getListOrEmpty("args"); var args = new ArrayList<String>();
            for (int k = 0; k < as.size(); k++) args.add(as.getStringOr(k, ""));
            a.notices.add(new FxMarket.News(nt.getLongOr("time", 0), nt.getStringOr("key", ""), args, 0));
        }
        a.seen = tag.getLongOr("seen", Long.MIN_VALUE);
        return a;
    }
}
