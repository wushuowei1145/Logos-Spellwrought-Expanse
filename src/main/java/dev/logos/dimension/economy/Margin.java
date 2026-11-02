package dev.logos.dimension.economy;

/**
 * 钱庄的杠杆交易：拿帝国币作保证金，对狐钱、猫币或狼币开多（赌它对帝国币涨）或开空（赌它跌）。
 * 名义金额 = 保证金 × 倍数，盈亏按名义金额算；净值跌到保证金的两成时强制平仓，亏损最多到保证金为止。
 *
 * <p>开仓、平仓都按名义金额推动市场，但杠杆单的冲击只有现货的一成（{@link #DERIVATIVE_DEPTH}），
 * 点差也只收兑换台的一成（{@link #DERIVATIVE_SPREAD}）：按兑换台的点差，25 倍狼币一开仓就亏掉七成半保证金。
 * 每过一夜按两种货币的利差收付利息，钱庄另收一点融资费。
 *
 * <p>倍数上限：玩家能选到 {@link #PLAYER_MAX}，但存档、网络和计算都支持到 {@link #SUPPORTED_MAX}，
 * 以后放开（比如做成配置或声望奖励）不用改数据格式。
 */
public final class Margin {
    public static final int SUPPORTED_MAX = 500, PLAYER_MAX = 25;
    public static final double DERIVATIVE_DEPTH = .1, DERIVATIVE_SPREAD = .1;
    /** 净值低于保证金的这个比例就强平。 */
    public static final double MAINTENANCE = .2;
    /** 钱庄每日的融资费（按名义金额），多空都收。 */
    public static final double FINANCING_FEE = .0002;
    public static final int MAX_POSITIONS = 20;

    /**
     * 一笔持仓。side 为 +1 做多、−1 做空；entry 是含点差与冲击的开仓价（一个最小单位值多少铜分）；
     * units 是折合多少个最小单位；stop、take 是止损、止盈的中间价，0 表示不设；accrued 是累计利息（铜分，可正可负）。
     */
    public record Position(long id, Currency currency, int side, long margin, int leverage, double entry, double units,
                           long opened, double stop, double take, double accrued) {
        public double notional() { return (double) margin * leverage; }
        Position withAccrued(double value) { return new Position(id, currency, side, margin, leverage, entry, units, opened, stop, take, value); }
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

    /** 当前中间价下这笔仓位要不要被自动平掉。强平优先于止损。 */
    public static Trigger check(Position p, double rate) {
        if (equity(p, rate) <= p.margin * MAINTENANCE) return Trigger.LIQUIDATE;
        if (p.stop > 0 && (p.side > 0 ? rate <= p.stop : rate >= p.stop)) return Trigger.STOP;
        if (p.take > 0 && (p.side > 0 ? rate >= p.take : rate <= p.take)) return Trigger.TAKE;
        return Trigger.NONE;
    }

    /** 隔夜一次的利息（铜分）：做多拿这种货币的利息、付帝国币的利息，做空反过来；再扣融资费。 */
    public static double overnight(Position p) {
        return p.notional() * (p.side * (p.currency.dailyRate - Currency.IMPERIAL.dailyRate) - FINANCING_FEE);
    }

    /** 开仓：apply 为 false 时只预览。倍数按 SUPPORTED_MAX 校验，玩家上限由调用方另查。 */
    public static Position open(FxMarket market, long id, Currency c, int side, long margin, int leverage, double stop, double take, long t, boolean apply) {
        if (!Currency.TRADED.contains(c) || (side != 1 && side != -1) || margin <= 0 || leverage < 1 || leverage > SUPPORTED_MAX)
            throw new IllegalArgumentException("开仓参数不对");
        double notional = (double) margin * leverage;
        double entry = entryPrice(c, side, notional, market.rate(c));
        if (apply) market.push(c, side * c.impact * DERIVATIVE_DEPTH * notional);
        return new Position(id, c, side, margin, leverage, entry, notional / entry, t, Math.max(0, stop), Math.max(0, take), 0);
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
