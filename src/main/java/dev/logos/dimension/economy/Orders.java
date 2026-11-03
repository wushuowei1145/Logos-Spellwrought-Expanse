package dev.logos.dimension.economy;

/**
 * 挂单与价格提醒。挂单下单时就把钱冻结（现货从钱囊和散币扣，杠杆从钱庄帝国币扣），
 * 只在兑换商开门时按当时的市价成交（开盘跳空时可能比挂的价更好）；成交所得和撤单退回的钱都存入钱庄，
 * 这样玩家离线、钱囊放在箱子里也不影响。价格提醒随时检查，触发一次就删掉。
 */
public final class Orders {
    public static final int MAX_ORDERS = 20, MAX_ALERTS = 10;
    /** 现货买入（帝国币换成该货币）、现货卖出（该货币换成帝国币）、杠杆开仓。 */
    public static final int BUY = 0, SELL = 1, OPEN = 2;

    /**
     * 一张挂单。price 是触发的中间价（一个最小单位值多少铜分）。amount 是冻结的钱：
     * BUY 为帝国币铜分，SELL 为该货币最小单位，OPEN 为保证金铜分。OPEN 另带 side、leverage 和开仓后的止损止盈。
     */
    public record Pending(long id, int kind, Currency currency, int side, long amount, int leverage, double price,
                          double stop, double take, double trail, long placed) {
        /** 现价到了没有：买入或做多等跌到挂价以下，卖出或做空等涨到挂价以上。 */
        public boolean due(double rate) {
            boolean buys = kind == BUY || kind == OPEN && side > 0;
            return buys ? rate <= price : rate >= price;
        }
        /** 撤单时退回哪种货币。 */
        public Currency refundCurrency() { return kind == SELL ? currency : Currency.IMPERIAL; }
    }

    /** 价格提醒：above 为 true 时涨到 price 以上提醒，否则跌到以下提醒。 */
    public record Alert(long id, Currency currency, boolean above, double price) {
        public boolean due(double rate) { return above ? rate >= price : rate <= price; }
    }

    private Orders() {}
}
