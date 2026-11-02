package dev.logos.dimension.economy;

import java.util.List;

/**
 * 异世界的四种货币。余额一律按各自的最小单位（铜分、文、便士、阿斯）记成整数，面额只是换算。
 * 人类帝国币是基准：所有汇率都报成「一个最小单位值多少铜分」，帝国币自己恒为 1。
 * {@code anchor} 是开局的锚定汇率，四种金币大致等值（金冠 ≈ 小判 ≈ 金镑 ≈ 奥里斯）。
 */
public enum Currency {
    // 帝国：十进制，最好记。汇率由其他货币的波动体现，自身不动
    IMPERIAL("imperial", 1, 0, 0, 0, .0005, List.of(
            new Denomination("cent", 1), new Denomination("silver", 100), new Denomination("crown", 10_000))),
    // 神狐：避险货币，平日最稳，天下大乱时反而升值。一両 = 四分 = 四千文
    FOX("fox", 2.5, .025, .008, 1 / 1.0e8, .0001, List.of(
            new Denomination("mon", 1), new Denomination("ichibu", 1_000), new Denomination("koban", 4_000))),
    // 灵猫：事件驱动，议会和贸易一有风吹草动就大起大落。一镑 = 二十先令 = 二百四十便士
    CAT("cat", 40, .04, .01, 1 / 6.0e7, .0008, List.of(
            new Denomination("penny", 1), new Denomination("shilling", 12), new Denomination("sovereign", 240))),
    // 群狼：和战争绑在一起，高风险高回报。一奥里斯 = 二十五第纳尔 = 一百塞斯特斯 = 四百阿斯
    WOLF("wolf", 25, .06, .015, 1 / 4.0e7, .002, List.of(
            new Denomination("as", 1), new Denomination("sestertius", 4), new Denomination("denarius", 16), new Denomination("aureus", 400)));

    /** 面额：{@code value} 是这枚钱币值多少个最小单位。 */
    public record Denomination(String name, long value) {}

    public final String id;
    /** 开局时一个最小单位值多少铜分。 */
    public final double anchor;
    /** 平日每游戏日的对数波动率。 */
    public final double dailyVol;
    /** 每次兑换这一侧收的点差（买卖各一半）。帝国币是报价货币，不收。 */
    public final double halfSpread;
    /** 每成交一铜分，对汇率造成的对数冲击；越小代表市场越深。 */
    public final double impact;
    /** 钱庄的日利率：狐钱最低、狼币最高，借低息买高息就是套息交易。 */
    public final double dailyRate;
    public final List<Denomination> denominations;

    Currency(String id, double anchor, double dailyVol, double halfSpread, double impact, double dailyRate, List<Denomination> denominations) {
        this.id = id;
        this.anchor = anchor;
        this.dailyVol = dailyVol;
        this.halfSpread = halfSpread;
        this.impact = impact;
        this.dailyRate = dailyRate;
        this.denominations = denominations;
    }

    public Denomination top() { return denominations.getLast(); }

    /** 可以炒的三种货币；帝国币是它们的报价单位。 */
    public static final List<Currency> TRADED = List.of(FOX, CAT, WOLF);

    public static Currency byId(String id) {
        for (var c : values()) if (c.id.equals(id)) return c;
        throw new IllegalArgumentException("未知货币 " + id);
    }

    public String key() { return "currency.logos_dimension." + id; }
    public String coinKey(Denomination d) { return "item.logos_dimension." + id + "_" + d.name; }

    /**
     * 把最小单位的数额拆成各面额，从大到小，例如灵猫 883 便士 → [3, 8, 7]（镑、先令、便士）。
     * 返回的数组与 {@link #denominations} 反向对应。
     */
    public long[] split(long amount) {
        long[] parts = new long[denominations.size()];
        long rest = Math.max(0, amount);
        for (int i = denominations.size() - 1, k = 0; i >= 0; i--, k++) {
            long v = denominations.get(i).value;
            parts[k] = rest / v;
            rest %= v;
        }
        return parts;
    }
}
