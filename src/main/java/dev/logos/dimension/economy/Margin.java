package dev.logos.dimension.economy;

/**
 * 钱庄的杠杆交易：拿帝国币作保证金，对狐钱、猫币或狼币开多（赌它对帝国币涨）或开空（赌它跌）。
 * 名义金额 = 保证金 × 倍数，盈亏按名义金额算；净值跌到保证金的两成时强制平仓，亏损最多到保证金为止。
 *
 * <p>开仓、平仓都按名义金额推动市场，但杠杆单的冲击只有现货的一成（{@link #DERIVATIVE_DEPTH}），
 * 点差也只收兑换台的一成（{@link #DERIVATIVE_SPREAD}）：按兑换台的点差，25 倍狼币一开仓就亏掉七成半保证金。
 * 每过一夜按两种货币的利差收付利息，钱庄另收一点融资费。
 *
 * <p>持仓开出来以后还能追加保证金（把爆仓线推远）、加仓（按均价合并）、部分平仓，以及改止损止盈和移动止损。
 * 净值跌到保证金一半时预警一次，回到七成以上才会再次预警。
 *
 * <p>倍数上限：玩家能选到 {@link #PLAYER_MAX}，但存档、网络和计算都支持到 {@link #SUPPORTED_MAX}，
 * 以后放开（比如做成配置或声望奖励）不用改数据格式。
 */
public final class Margin {
    public static final int SUPPORTED_MAX = 500, PLAYER_MAX = 25;
    public static final double DERIVATIVE_DEPTH = .1, DERIVATIVE_SPREAD = .1;
    /** 净值低于保证金的这个比例就强平。 */
    public static final double MAINTENANCE = .2;
    /** 净值跌到保证金的这个比例时预警，回到 REARM 以上解除。 */
    public static final double WARNING = .5, REARM = .7;
    /** 移动止损的距离上限（离最好价的比例）。 */
    public static final double MAX_TRAIL = .5;
    /** 钱庄每日的融资费（按名义金额），多空都收。 */
    public static final double FINANCING_FEE = .0002;
    public static final int MAX_POSITIONS = 20;

    /**
     * 一笔持仓。side 为 +1 做多、−1 做空；entry 是含点差与冲击的开仓均价（一个最小单位值多少铜分）；
     * units 是折合多少个最小单位，名义金额 = units × entry；leverage 是开仓时选的倍数，追加保证金后实际倍数见 {@link #effectiveLeverage}。
     * stop、take 是止损、止盈的中间价，0 表示不设；trail 是移动止损离最好价的比例，best 是开仓以来对自己最有利的中间价；
     * accrued 是累计利息（铜分，可正可负）；warned 表示已经预警过。
     */
    public record Position(long id, Currency currency, int side, long margin, int leverage, double entry, double units,
                           long opened, double stop, double take, double accrued, double trail, double best, boolean warned) {
        public double notional() { return units * entry; }
        public double effectiveLeverage() { return margin <= 0 ? 0 : notional() / margin; }
        Position withAccrued(double value) { return new Position(id, currency, side, margin, leverage, entry, units, opened, stop, take, value, trail, best, warned); }
        Position withStops(double stop, double take, double trail) { return new Position(id, currency, side, margin, leverage, entry, units, opened, stop, take, accrued, trail, best, warned); }
        Position withMargin(long value) { return new Position(id, currency, side, value, leverage, entry, units, opened, stop, take, accrued, trail, best, warned); }
        Position withWarned(boolean value) { return new Position(id, currency, side, margin, leverage, entry, units, opened, stop, take, accrued, trail, best, value); }
        /** 按比例切下一部分（部分平仓用）。 */
        Position scaled(double f) {
            return new Position(id, currency, side, Math.round(margin * f), leverage, entry, units * f, opened, stop, take, accrued * f, trail, best, warned);
        }
    }

    public enum Trigger { NONE, LIQUIDATE, STOP, TAKE }

    /** 名义金额 notional 铜分进场的成交价：做多付卖价并往上推，做空拿买价并往下压，都按冲击中点成交。 */
    public static double entryPrice(Currency c, int side, double notional, double rate) {
        double impact = c.impact * DERIVATIVE_DEPTH * notional;
        double spread = c.halfSpread * DERIVATIVE_SPREAD;
        return side > 0 ? rate * Math.exp(impact / 2) * (1 + spread) : rate * Math.exp(-impact / 2) * (1 - spread);
    }

    /** 现在平仓的成交价：做多是卖出，做空是买回。 */
    public static double exitPrice(Position p, double rate) {
        double impact = p.currency.impact * DERIVATIVE_DEPTH * p.units * rate;
        double spread = p.currency.halfSpread * DERIVATIVE_SPREAD;
        return p.side > 0 ? rate * Math.exp(-impact / 2) * (1 - spread) : rate * Math.exp(impact / 2) * (1 + spread);
    }

    public static double pnl(Position p, double rate) { return p.side * p.units * (exitPrice(p, rate) - p.entry); }

    /** 净值：保证金加浮动盈亏加累计利息。 */
    public static double equity(Position p, double rate) { return p.margin + pnl(p, rate) + p.accrued; }

    /** 平仓能拿回多少铜分：亏损最多到保证金为止，不会倒欠。 */
    public static long payout(Position p, double rate) { return Math.max(0, (long) Math.floor(equity(p, rate))); }

    /** 爆仓价：净值正好等于维持保证金时的中间价。没有冲击时可直接解，这里用二分把冲击也算进去。 */
    public static double liquidationRate(Position p) {
        double floor = p.margin * MAINTENANCE;
        double lo = p.entry * 1e-4, hi = p.entry * 1e4;
        // 做多时价格越低净值越低，做空相反；在对数尺度上二分
        for (int i = 0; i < 80; i++) {
            double mid = Math.sqrt(lo * hi);
            boolean above = equity(p, mid) > floor;
            if (p.side > 0 == above) hi = mid; else lo = mid;
        }
        return Math.sqrt(lo * hi);
    }

    /** 约可承受的反向波动：从现价到爆仓价还差多少（比例）。已经过线时为 0。 */
    public static double room(Position p, double rate) {
        double liq = liquidationRate(p);
        return Math.max(0, p.side > 0 ? 1 - liq / rate : liq / rate - 1);
    }

    /** 当前中间价下这笔仓位要不要被自动平掉。强平优先于止损。 */
    public static Trigger check(Position p, double rate) {
        if (equity(p, rate) <= p.margin * MAINTENANCE) return Trigger.LIQUIDATE;
        if (p.stop > 0 && (p.side > 0 ? rate <= p.stop : rate >= p.stop)) return Trigger.STOP;
        if (p.take > 0 && (p.side > 0 ? rate >= p.take : rate <= p.take)) return Trigger.TAKE;
        return Trigger.NONE;
    }

    /** 随价格更新移动止损：记下最好价，止损跟在后面 trail 的距离，只进不退。 */
    public static Position trailed(Position p, double rate) {
        if (p.trail <= 0) return p;
        double best = p.best <= 0 ? rate : p.side > 0 ? Math.max(p.best, rate) : Math.min(p.best, rate);
        double stop = p.side > 0 ? best * (1 - p.trail) : best * (1 + p.trail);
        if (p.stop > 0) stop = p.side > 0 ? Math.max(p.stop, stop) : Math.min(p.stop, stop);
        if (best == p.best && stop == p.stop) return p;
        return new Position(p.id, p.currency, p.side, p.margin, p.leverage, p.entry, p.units, p.opened, stop, p.take, p.accrued, p.trail, best, p.warned);
    }

    /** 止损要在亏的一侧、止盈在赚的一侧，否则一设就被平掉；移动止损距离在 0 到 MAX_TRAIL 之间。 */
    public static boolean validStops(int side, double rate, double stop, double take, double trail) {
        if (stop > 0 && (side > 0 ? stop >= rate : stop <= rate)) return false;
        if (take > 0 && (side > 0 ? take <= rate : take >= rate)) return false;
        return trail >= 0 && trail <= MAX_TRAIL;
    }

    /** 隔夜一次的利息（铜分）：做多拿这种货币的利息、付帝国币的利息，做空反过来；再扣融资费。 */
    public static double overnight(Position p) {
        return p.notional() * (p.side * (p.currency.dailyRate - Currency.IMPERIAL.dailyRate) - FINANCING_FEE);
    }

    /** 开仓：apply 为 false 时只预览。倍数按 SUPPORTED_MAX 校验，玩家上限由调用方另查。 */
    public static Position open(FxMarket market, long id, Currency c, int side, long margin, int leverage, double stop, double take, long t, boolean apply) {
        return open(market, id, c, side, margin, leverage, stop, take, 0, t, apply);
    }

    public static Position open(FxMarket market, long id, Currency c, int side, long margin, int leverage, double stop, double take, double trail, long t, boolean apply) {
        if (!Currency.TRADED.contains(c) || (side != 1 && side != -1) || margin <= 0 || leverage < 1 || leverage > SUPPORTED_MAX)
            throw new IllegalArgumentException("开仓参数不对");
        double notional = (double) margin * leverage;
        double rate = market.rate(c);
        double entry = entryPrice(c, side, notional, rate);
        if (apply) market.push(c, side * c.impact * DERIVATIVE_DEPTH * notional);
        var p = new Position(id, c, side, margin, leverage, entry, notional / entry, t, Math.max(0, stop), Math.max(0, take), 0,
                Math.clamp(trail, 0, MAX_TRAIL), rate, false);
        return trailed(p, rate);
    }

    /** 加仓：再投 margin、按 leverage 倍买进同方向，和原仓按均价合并。 */
    public static Position increase(FxMarket market, Position p, long margin, int leverage, boolean apply) {
        var more = open(market, p.id, p.currency, p.side, margin, leverage, 0, 0, p.opened, apply);
        double units = p.units + more.units;
        double entry = (p.notional() + more.notional()) / units;
        return new Position(p.id, p.currency, p.side, p.margin + margin, Math.max(p.leverage, leverage), entry, units, p.opened,
                p.stop, p.take, p.accrued, p.trail, p.best, p.warned);
    }

    /** 平仓：把冲击记进市场，返回拿回的铜分。 */
    public static long close(FxMarket market, Position p) {
        double rate = market.rate(p.currency);
        long back = payout(p, rate);
        market.push(p.currency, -p.side * p.currency.impact * DERIVATIVE_DEPTH * p.units * rate);
        return back;
    }

    private Margin() {}
}
