package dev.logos.dimension.economy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;

/**
 * 全服共享的外汇市场。每种可炒货币的对数汇率 x（相对锚定值）是一条会被拉回基本面 f 的随机游走：
 * <pre>x ← x + θ·(f − x)·dt + σ·√dt·ε + 玩家冲击 + 事件跳变</pre>
 * 基本面由剧情、探索和事件结果永久改变；玩家冲击（pressure）来自大额兑换，约一天后散去。
 * 交叉汇率全由同一组对帝国币的汇率推出，每次兑换都收点差，所以不存在三角套利。
 *
 * <p>时间用主世界时钟：每 100 刻走一步，6:00–18:00 兑换商开门，按游戏小时记 K 线；
 * 夜里汇率照走、事件照常揭晓，所以早上开盘会跳空。随机数自带状态并存档，读档不会重掷。
 */
public final class FxMarket {
    public static final long DAY = 24_000, HOUR = 1_000, STEP = 100, CLOSE = 12_000;
    static final int CANDLES = 240, NEWS = 40;
    /** 每游戏日把汇率拉回基本面的力度（半衰期约 4.6 天）。 */
    static final double REVERT = .15;
    /** 事件对基本面的影响淡去的时间常数（游戏日）。 */
    static final double EVENT_DAYS = 20;
    /** 玩家冲击散去的时间常数（游戏日）。 */
    static final double PRESSURE_DAYS = .75;
    /** 偏离锚定超过 ±BAND 时央行干预，超过 ±HARD 直接封顶。 */
    static final double BAND = .6, HARD = 1.0, INTERVENE = .5;
    /** 消息面里有多少会被「知情资金」提前买进价格。 */
    static final double PRICED_IN = .3;

    /** 一根 K 线：一个游戏小时的开高低收，单位是一个最小单位值多少铜分。 */
    public record Candle(long slot, float open, float high, float low, float close) {
        Candle with(float price) { return new Candle(slot, open, Math.max(high, price), Math.min(low, price), price); }
    }

    /** 一条消息：time 是主世界时钟刻，tier 是来源可信度（0 官方告示 … 3 小道消息），args 交给翻译键。 */
    public record News(long time, String key, List<String> args, int tier) {}

    static final class Line {
        /** x 当前对数汇率；f 剧情与探索定下的永久基本面；d 事件留下的基本面偏移，约二十天淡去。 */
        double x, f, d, pressure, boost = 1;
        final ArrayDeque<Candle> candles = new ArrayDeque<>();
    }

    /** 事件种类。perm 是揭晓时变动中永久计入基本面的比例，其余部分会慢慢回归。 */
    enum Kind {
        MINT(.8, 1), CALAMITY(.2, 1.2), FESTIVAL(0, 0), COUNCIL(.6, 2), WAR(.8, 1.5), HARVEST(.3, 3);
        final double perm, weight;
        Kind(double perm, double weight) { this.perm = perm; this.weight = weight; }
        String key() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    /** 传闻的可信度：说中方向的概率，以及根本是假消息的概率。 */
    static final double[] ACCURACY = {1, .85, .7, .55}, FAKE = {0, .05, .15, .35};

    static final class Pending {
        Kind kind; long announced, resolveAt; int tier, hint; boolean fake;
        String subject = "";
        final double[] moves = new double[Currency.values().length];
        final double[] priced = new double[Currency.values().length];
    }

    final Map<Currency, Line> lines = new EnumMap<>(Currency.class);
    final List<Pending> pending = new ArrayList<>();
    final ArrayDeque<News> news = new ArrayDeque<>();
    final Set<String> discovered = new HashSet<>();
    long last = Long.MIN_VALUE, seed;

    public FxMarket(long seed) {
        this.seed = seed == 0 ? 0x9E3779B97F4A7C15L : seed;
        for (var c : Currency.TRADED) lines.put(c, new Line());
    }

    // ---------------------------------------------------------------- 报价

    public static boolean open(long clock) { return Math.floorMod(clock, DAY) < CLOSE; }

    /** 一个最小单位值多少铜分（中间价）。 */
    public double rate(Currency c) {
        var l = lines.get(c);
        return l == null ? 1 : c.anchor * Math.exp(l.x + l.pressure);
    }

    /** 兑换的结果：收到多少，按中间价折合多少铜分，以及点差和冲击一共吃掉多少铜分。 */
    public record Quote(long received, double value, double cost) {}

    /** 只报价，不成交。 */
    public Quote quote(Currency from, Currency to, long amount) { return exchange(from, to, amount, false); }

    /** 成交：返回收到的最小单位数，并把冲击记进市场。 */
    public long trade(Currency from, Currency to, long amount) { return exchange(from, to, amount, true).received; }

    private Quote exchange(Currency from, Currency to, long amount, boolean apply) {
        var q = quote(from, to, amount, rate(from), rate(to));
        if (apply && amount > 0 && from != to) {
            push(from, -impact(from, amount));
            push(to, impact(to, q.received));
        }
        return q;
    }

    /** 单笔成交最多把汇率推动多少（对数，约 35%）；再大就拒绝，让玩家分批下单。 */
    public static final double MAX_IMPACT = .3;
    /** 冲击累计的上下限，兜底防止任何情况把汇率推到零或无穷。 */
    static final double MAX_PRESSURE = 1.5;

    /**
     * 一笔成交对某货币的对数冲击：按成交了多少枚（最小单位）、以锚定价折算，和成交那一刻的价格无关。
     * 这样买进再原数卖出正好抵消；若按当时的价值算，大单把价格砸到接近零后，原数买回几乎不推价，
     * 空单就能靠自己的冲击凭空赚钱。
     */
    public static double impact(Currency c, double units) { return c.impact * units * c.anchor; }

    /**
     * 价格沿着自己的冲击从 p 走到 p·e^delta 时的平均成交价倍数（对路径积分，不是取中点）。
     * 用积分而不是中点，任何一串自己的买卖加起来都正好抵消，拆单、换顺序都赚不到钱。
     */
    public static double along(double delta) { return Math.abs(delta) < 1e-12 ? 1 : Math.expm1(delta) / delta; }

    /** 这笔兑换会不会超出市场深度（任一边的冲击超过 MAX_IMPACT）。 */
    public static boolean tooLarge(Currency from, Currency to, long amount, double rateFrom, double rateTo) {
        return impact(from, amount) > MAX_IMPACT || impact(to, amount * rateFrom / rateTo) > MAX_IMPACT;
    }

    /** 留下冲击（对数，正数推高），杠杆单也走这里。累计冲击封顶。 */
    void push(Currency c, double delta) {
        var l = lines.get(c);
        if (l != null) l.pressure = Math.clamp(l.pressure + delta, -MAX_PRESSURE, MAX_PRESSURE);
    }

    /** 管理员调试：直接把某种货币的汇率推一把（对数，0.1 约等于涨 10%），它会照常慢慢回归。 */
    public void shock(Currency c, double delta) {
        var l = lines.get(c);
        if (l != null) l.x = clampLog(l.x + delta);
    }

    /** 报价公式本身，只依赖两边的中间价；客户端拿快照里的汇率算预览，和服务端成交一致。 */
    public static Quote quote(Currency from, Currency to, long amount, double rateFrom, double rateTo) {
        if (amount <= 0 || from == to) return new Quote(from == to ? Math.max(0, amount) : 0, 0, 0);
        double value = amount * rateFrom;
        // 沿冲击路径的平均价成交：立刻买进再卖出只亏点差，等冲击散了才多亏
        double bid = rateFrom * along(-impact(from, amount)) * (1 - from.halfSpread);
        double ask = rateTo * along(impact(to, value / rateTo)) * (1 + to.halfSpread);
        long received = (long) Math.floor(amount * bid / ask + 1e-9);
        return new Quote(received, value, value - received * rateTo);
    }

    // ---------------------------------------------------------------- 时间推进

    /** 追到主世界时钟 now。时钟倒退（/time set）只重新对齐，不倒推；一次最多补 60 天。 */
    public void advance(long now) {
        if (last == Long.MIN_VALUE || now < last) { last = now - Math.floorMod(now, STEP); return; }
        if (now - last > 60 * DAY) last = now - 60 * DAY - Math.floorMod(now, STEP);
        while (last + STEP <= now) step(last + STEP);
    }

    private void step(long t) {
        double dt = (double) STEP / DAY;
        if (Math.floorDiv(t, DAY) != Math.floorDiv(t - STEP, DAY)) newDay(Math.floorDiv(t, DAY), t);
        for (var it = pending.iterator(); it.hasNext(); ) {
            var e = it.next();
            if (t >= e.resolveAt) { resolve(e, t); it.remove(); }
            else anticipate(e, t);
        }
        for (var entry : lines.entrySet()) {
            var c = entry.getKey(); var l = entry.getValue();
            l.pressure *= Math.exp(-dt / PRESSURE_DAYS);
            l.boost = 1 + (l.boost - 1) * Math.exp(-dt / 1.5);
            l.d *= Math.exp(-dt / EVENT_DAYS);
            double pull = REVERT * (l.f + l.d - l.x);
            if (Math.abs(l.x) > BAND) pull -= INTERVENE * (l.x - Math.copySign(BAND, l.x));
            l.x += pull * dt + c.dailyVol * l.boost * Math.sqrt(dt) * gaussian();
            l.x = clampLog(l.x);
            l.f = Math.clamp(l.f, -HARD, HARD);
        }
        last = t;
        if (open(t)) record(t);
    }

    private static double clampLog(double x) { return Math.clamp(x, -HARD, HARD); }

    private void record(long t) {
        long slot = Math.floorDiv(t, HOUR);
        for (var entry : lines.entrySet()) {
            var q = entry.getValue().candles;
            float price = (float) rate(entry.getKey());
            if (!q.isEmpty() && q.getLast().slot() == slot) q.add(q.removeLast().with(price));
            else {
                q.add(new Candle(slot, price, price, price, price));
                while (q.size() > CANDLES) q.removeFirst();
            }
        }
    }

    // ---------------------------------------------------------------- 事件与消息

    private void newDay(long day, long t) {
        // 狐火祭每八天一次，提前两天贴出官方告示：人人都知道，所以一半会提前涨进价格
        if (Math.floorMod(day, 8) == 3) {
            var e = event(Kind.FESTIVAL, t, (day + 2) * DAY + 2 * HOUR, 0);
            e.moves[Currency.FOX.ordinal()] = .04 + .03 * uniform();
            post(t, "fx.news.festival.notice", 0);
        }
        if (uniform() > .75) return;
        double total = 0;
        for (var k : Kind.values()) total += k.weight;
        double pick = uniform() * total;
        Kind kind = Kind.HARVEST;
        for (var k : Kind.values()) { pick -= k.weight; if (pick <= 0) { kind = k; break; } }
        if (kind == Kind.FESTIVAL) kind = Kind.HARVEST;
        final Kind chosen = kind;
        if (chosen != Kind.HARVEST && pending.stream().anyMatch(p -> p.kind == chosen)) return;
        // 揭晓在一到两天后的任意时刻，可能在夜里，第二天开盘才看得到结果
        rumor(chosen, t, t + DAY + (long) (uniform() * DAY));
    }

    /** 放出一条传闻：先在暗中定好真实结果，再按可信度决定它说得对不对、是不是根本没这回事。 */
    private void rumor(Kind chosen, long t, long resolve) {
        int tier = 1 + (int) (uniform() * 3);
        var e = event(chosen, t, resolve, tier);
        e.fake = uniform() < FAKE[tier];
        int sign = uniform() < .5 ? 1 : -1;
        switch (chosen) {
            // 增发让帝国币贬值（其他货币涨），回收劣币则相反
            case MINT -> { for (var c : Currency.TRADED) e.moves[c.ordinal()] = sign * (.05 + .05 * uniform()); }
            case CALAMITY -> {
                e.moves[Currency.FOX.ordinal()] = .04 + .06 * uniform();
                e.moves[Currency.CAT.ordinal()] = -(.03 + .05 * uniform());
                e.moves[Currency.WOLF.ordinal()] = (uniform() * 2 - 1) * .06;
            }
            case COUNCIL -> e.moves[Currency.CAT.ordinal()] = sign * (.06 + .08 * uniform());
            case WAR -> e.moves[Currency.WOLF.ordinal()] = sign * (.10 + .12 * uniform());
            case HARVEST -> {
                var c = Currency.TRADED.get((int) (uniform() * 3));
                e.subject = c.id;
                e.moves[c.ordinal()] = sign * (.02 + .03 * uniform());
            }
            default -> {}
        }
        if (e.fake) java.util.Arrays.fill(e.moves, 0);
        // 有方向的传闻按可信度说中或说反；假消息照样说得有鼻子有眼
        e.hint = chosen == Kind.CALAMITY ? 1 : uniform() < ACCURACY[tier] ? sign : -sign;
        if (chosen == Kind.WAR) lines.get(Currency.WOLF).boost = Math.max(lines.get(Currency.WOLF).boost, 1.8);
        post(t, rumorKey(e), tier, e.subject.isEmpty() ? List.of() : List.of(Currency.byId(e.subject).key()));
    }

    private Pending event(Kind kind, long t, long resolve, int tier) {
        var e = new Pending();
        e.kind = kind; e.announced = t; e.resolveAt = resolve; e.tier = tier;
        pending.add(e);
        return e;
    }

    private static String rumorKey(Pending e) {
        String base = "fx.news." + e.kind.key() + ".rumor";
        return e.kind == Kind.CALAMITY ? base : base + (e.hint > 0 ? ".up" : ".down");
    }

    /** 揭晓前，知情资金按真实结果把一部分变动提前买进价格；假消息没有真实变动，价格也就不跟。 */
    private void anticipate(Pending e, long t) {
        double share = e.kind == Kind.FESTIVAL ? .5 : PRICED_IN;
        double progress = Math.clamp((double) (t - e.announced) / Math.max(1, e.resolveAt - e.announced), 0, 1);
        for (var entry : lines.entrySet()) {
            int i = entry.getKey().ordinal();
            double target = e.moves[i] * share * progress;
            entry.getValue().x += target - e.priced[i];
            e.priced[i] = target;
        }
    }

    private void resolve(Pending e, long t) {
        if (e.fake) {
            post(t, "fx.news." + e.kind.key() + ".fake", e.tier, e.subject.isEmpty() ? List.of() : List.of(Currency.byId(e.subject).key()));
            return;
        }
        String pct = "";
        for (var entry : lines.entrySet()) {
            int i = entry.getKey().ordinal();
            if (e.moves[i] == 0) continue;
            var l = entry.getValue();
            l.x = clampLog(l.x + e.moves[i] - e.priced[i]);
            l.d += e.moves[i] * e.kind.perm;
            if (pct.isEmpty()) pct = String.format(java.util.Locale.ROOT, "%.1f", Math.abs(e.moves[i]) * 100);
        }
        if (e.kind == Kind.CALAMITY) for (var l : lines.values()) l.boost = Math.max(l.boost, 2.5);
        String key = "fx.news." + e.kind.key() + switch (e.kind) {
            case MINT, COUNCIL, WAR, HARVEST -> e.moves[firstMoved(e)] > 0 ? ".up" : ".down";
            case FESTIVAL -> ".start";
            default -> ".real";
        };
        var args = new ArrayList<String>();
        if (!e.subject.isEmpty()) args.add(Currency.byId(e.subject).key());
        args.add(pct);
        post(t, key, 0, args);
    }

    private static int firstMoved(Pending e) {
        for (int i = 0; i < e.moves.length; i++) if (e.moves[i] != 0) return i;
        return 0;
    }

    /**
     * 剧情与探索推动基本面：同一个 key 全服只生效一次，例如「首次与神狐聚落通商」。
     * 返回这次是否真的生效。
     */
    public boolean milestone(String key, Currency c, double move, long t) {
        if (!lines.containsKey(c) || !discovered.add(key)) return false;
        lines.get(c).f = Math.clamp(lines.get(c).f + move, -HARD, HARD);
        post(t, "fx.news.milestone." + key, 0);
        return true;
    }

    /** 管理员调试：立刻放出一条指定种类的传闻（狐火祭除外），lead 刻后揭晓。 */
    public boolean force(String kind, long t, long lead) {
        Kind k;
        try { k = Kind.valueOf(kind.toUpperCase(java.util.Locale.ROOT)); } catch (IllegalArgumentException ex) { return false; }
        if (k == Kind.FESTIVAL) return false;
        rumor(k, t, t + Math.max(STEP, lead));
        return true;
    }

    /** 事件种类的小写名，给命令补全用。 */
    public static List<String> kinds() {
        return java.util.Arrays.stream(Kind.values()).filter(k -> k != Kind.FESTIVAL).map(Kind::key).toList();
    }

    private void post(long t, String key, int tier) { post(t, key, tier, List.of()); }
    private void post(long t, String key, int tier, List<String> args) {
        news.add(new News(t, key, List.copyOf(args), tier));
        while (news.size() > NEWS) news.removeFirst();
    }

    // ---------------------------------------------------------------- 随机数（自带状态，可存档）

    private double spare = Double.NaN;
    double uniform() {
        seed ^= seed << 13; seed ^= seed >>> 7; seed ^= seed << 17;
        return ((seed >>> 11) + .5) / (1L << 53);
    }
    double gaussian() {
        if (!Double.isNaN(spare)) { double s = spare; spare = Double.NaN; return s; }
        double r = Math.sqrt(-2 * Math.log(uniform())), a = 2 * Math.PI * uniform();
        spare = r * Math.sin(a);
        return r * Math.cos(a);
    }

    // ---------------------------------------------------------------- 读写

    public List<Candle> candles(Currency c) { var l = lines.get(c); return l == null ? List.of() : List.copyOf(l.candles); }
    public List<News> news() { return List.copyOf(news); }
    public long last() { return last; }

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putLong("last", last); tag.putLong("seed", seed);
        for (var entry : lines.entrySet()) {
            var l = entry.getValue(); var lt = new CompoundTag();
            lt.putDouble("x", l.x); lt.putDouble("f", l.f); lt.putDouble("d", l.d); lt.putDouble("pressure", l.pressure); lt.putDouble("boost", l.boost);
            var cs = new ListTag();
            for (var k : l.candles) {
                var ct = new CompoundTag();
                ct.putLong("slot", k.slot()); ct.putFloat("o", k.open()); ct.putFloat("h", k.high()); ct.putFloat("l", k.low()); ct.putFloat("c", k.close());
                cs.add(ct);
            }
            lt.put("candles", cs);
            tag.put(entry.getKey().id, lt);
        }
        var ps = new ListTag();
        for (var e : pending) {
            var et = new CompoundTag();
            et.putString("kind", e.kind.name()); et.putLong("announced", e.announced); et.putLong("resolve", e.resolveAt);
            et.putInt("tier", e.tier); et.putInt("hint", e.hint); et.putBoolean("fake", e.fake); et.putString("subject", e.subject);
            for (var c : Currency.values()) { et.putDouble("move_" + c.id, e.moves[c.ordinal()]); et.putDouble("priced_" + c.id, e.priced[c.ordinal()]); }
            ps.add(et);
        }
        tag.put("pending", ps);
        var ns = new ListTag();
        for (var n : news) {
            var nt = new CompoundTag();
            nt.putLong("time", n.time()); nt.putString("key", n.key()); nt.putInt("tier", n.tier());
            var as = new ListTag(); n.args().forEach(a -> as.add(StringTag.valueOf(a))); nt.put("args", as);
            ns.add(nt);
        }
        tag.put("news", ns);
        var ds = new ListTag(); discovered.stream().sorted().forEach(d -> ds.add(StringTag.valueOf(d))); tag.put("discovered", ds);
        return tag;
    }

    public static FxMarket load(CompoundTag tag) {
        var m = new FxMarket(tag.getLongOr("seed", 1));
        m.last = tag.getLongOr("last", Long.MIN_VALUE);
        for (var entry : m.lines.entrySet()) {
            var lt = tag.getCompoundOrEmpty(entry.getKey().id); var l = entry.getValue();
            l.x = lt.getDoubleOr("x", 0); l.f = lt.getDoubleOr("f", 0); l.d = lt.getDoubleOr("d", 0); l.pressure = Math.clamp(lt.getDoubleOr("pressure", 0), -MAX_PRESSURE, MAX_PRESSURE); l.boost = lt.getDoubleOr("boost", 1);
            var cs = lt.getListOrEmpty("candles");
            for (int i = 0; i < cs.size(); i++) {
                var ct = cs.getCompoundOrEmpty(i);
                l.candles.add(new Candle(ct.getLongOr("slot", 0), ct.getFloatOr("o", 0), ct.getFloatOr("h", 0), ct.getFloatOr("l", 0), ct.getFloatOr("c", 0)));
            }
        }
        var ps = tag.getListOrEmpty("pending");
        for (int i = 0; i < ps.size(); i++) {
            var et = ps.getCompoundOrEmpty(i); var e = new Pending();
            try { e.kind = Kind.valueOf(et.getStringOr("kind", "HARVEST")); } catch (IllegalArgumentException ex) { continue; }
            e.announced = et.getLongOr("announced", 0); e.resolveAt = et.getLongOr("resolve", 0);
            e.tier = et.getIntOr("tier", 2); e.hint = et.getIntOr("hint", 1); e.fake = et.getBooleanOr("fake", false); e.subject = et.getStringOr("subject", "");
            for (var c : Currency.values()) { e.moves[c.ordinal()] = et.getDoubleOr("move_" + c.id, 0); e.priced[c.ordinal()] = et.getDoubleOr("priced_" + c.id, 0); }
            m.pending.add(e);
        }
        var ns = tag.getListOrEmpty("news");
        for (int i = 0; i < ns.size(); i++) {
            var nt = ns.getCompoundOrEmpty(i); var as = nt.getListOrEmpty("args"); var args = new ArrayList<String>();
            for (int k = 0; k < as.size(); k++) args.add(as.getStringOr(k, ""));
            m.news.add(new News(nt.getLongOr("time", 0), nt.getStringOr("key", ""), args, nt.getIntOr("tier", 0)));
        }
        var ds = tag.getListOrEmpty("discovered");
        for (int i = 0; i < ds.size(); i++) m.discovered.add(ds.getStringOr(i, ""));
        return m;
    }
}
