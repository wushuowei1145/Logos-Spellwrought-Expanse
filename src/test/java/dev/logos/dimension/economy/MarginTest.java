package dev.logos.dimension.economy;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarginTest {
    private static final long CROWN = 10_000;

    private static FxMarket market(long seed) {
        var m = new FxMarket(seed);
        m.advance(FxMarket.DAY + 1_000);
        return m;
    }

    @Test void openingAndClosingAtOnceOnlyCostsTheSpread() {
        var m = market(1);
        var p = Margin.open(m, 1, Currency.FOX, 1, 100 * CROWN, 1, 0, 0, 0, false);
        long back = Margin.payout(p, m.rate(Currency.FOX));
        assertTrue(back < 100 * CROWN && back > 100 * CROWN * .97, "一倍开平只亏点差：" + back);
    }

    @Test void twentyFiveTimesIsLiquidatedAfterAFewPercent() {
        var m = market(2);
        double rate = m.rate(Currency.WOLF);
        var p = Margin.open(m, 1, Currency.WOLF, 1, 10 * CROWN, 25, 0, 0, 0, false);
        double liq = Margin.liquidationRate(p);
        // 净值剩两成即强平：25 倍下约跌 3.2%，再减去点差与冲击
        assertTrue(liq < rate && liq > rate * .95, "爆仓价 " + liq / rate);
        assertEquals(Margin.Trigger.LIQUIDATE, Margin.check(p, liq * .999));
        assertEquals(Margin.Trigger.NONE, Margin.check(p, liq * 1.001));
        assertEquals(0, Margin.payout(p, rate * .5), "亏损最多到保证金为止");
    }

    @Test void shortsWinWhenTheCurrencyFalls() {
        var m = market(3);
        double rate = m.rate(Currency.CAT);
        var p = Margin.open(m, 1, Currency.CAT, -1, 10 * CROWN, 5, 0, 0, 0, false);
        assertTrue(Margin.payout(p, rate * .95) > 10 * CROWN * 1.15, "做空五倍，跌 5% 应赚约 25%");
        assertTrue(Margin.liquidationRate(p) > rate, "做空的爆仓价在上方");
    }

    @Test void stopsAndTakesFireOnTheRightSide() {
        var m = market(4);
        double rate = m.rate(Currency.FOX);
        var p = Margin.open(m, 1, Currency.FOX, 1, 10 * CROWN, 2, rate * .98, rate * 1.03, 0, false);
        assertEquals(Margin.Trigger.STOP, Margin.check(p, rate * .975));
        assertEquals(Margin.Trigger.TAKE, Margin.check(p, rate * 1.031));
        assertEquals(Margin.Trigger.NONE, Margin.check(p, rate));
    }

    @Test void carryPaysTheHighYielder() {
        var m = market(5);
        var longWolf = Margin.open(m, 1, Currency.WOLF, 1, 10 * CROWN, 10, 0, 0, 0, false);
        var shortWolf = Margin.open(m, 2, Currency.WOLF, -1, 10 * CROWN, 10, 0, 0, 0, false);
        var longFox = Margin.open(m, 3, Currency.FOX, 1, 10 * CROWN, 10, 0, 0, 0, false);
        assertTrue(Margin.overnight(longWolf) > 0, "买高息狼币隔夜收利息");
        assertTrue(Margin.overnight(shortWolf) < 0 && Margin.overnight(longFox) < 0, "做空高息或买低息要付利息");
    }

    @Test void fiveHundredTimesIsSupportedButNotBeyond() {
        var m = market(6);
        var p = Margin.open(m, 1, Currency.FOX, 1, CROWN, Margin.SUPPORTED_MAX, 0, 0, 0, false);
        // 五百倍时点差本身就吃掉大半保证金，爆仓价紧贴开仓价（可能就在现价上方，开仓即被强平）
        double liq = Margin.liquidationRate(p);
        assertTrue(Math.abs(liq / p.entry() - 1) < .01, "五百倍的爆仓价紧贴开仓价：" + liq / p.entry());
        assertThrows(IllegalArgumentException.class, () -> Margin.open(m, 2, Currency.FOX, 1, CROWN, Margin.SUPPORTED_MAX + 1, 0, 0, 0, false));
        assertTrue(Margin.PLAYER_MAX <= Margin.SUPPORTED_MAX);
    }

    @Test void loansOnlyPostMargin() {
        var a = new BankAccount();
        a.add(Currency.FOX, 40_000);
        assertTrue(a.canPost(BankAccount.CREDIT_LIMIT) && !a.canPost(BankAccount.CREDIT_LIMIT + 1), "帝国币最多欠到授信额度");
        a.add(Currency.IMPERIAL, -50 * CROWN);
        // 欠着五十金冠，狐钱十小判约值十金冠：取一分也会让净值为负
        assertFalse(a.canWithdraw(Currency.FOX, 1_000, c -> c.anchor), "借款不能变现");
        a.add(Currency.FOX, 400_000);
        assertTrue(a.canWithdraw(Currency.FOX, 4_000, c -> c.anchor), "净值为正时可以取");
        var copy = BankAccount.load(a.save());
        assertEquals(a.balance(Currency.IMPERIAL), copy.balance(Currency.IMPERIAL));
        assertEquals(a.balance(Currency.FOX), copy.balance(Currency.FOX));
    }

    // ---------------------------------------------------------------- 杠杆策略模拟

    private interface Strategy { void act(FxMarket m, double[] cash, java.util.List<Margin.Position> open, java.util.List<FxMarket.News> fresh, int hour, Random r); }

    /** 100 金冠起步，跑 days 天；每小时检查强平与止损，每夜计利息，最后全部平仓。 */
    private static double[] simulate(java.util.function.Supplier<Strategy> make, int runs, int days) {
        double[] out = new double[runs];
        for (int run = 0; run < runs; run++) {
            var s = make.get(); var m = new FxMarket(5000 + run); var r = new Random(run);
            m.advance(0);
            double[] cash = {100 * CROWN};
            var open = new java.util.ArrayList<Margin.Position>();
            long seen = Long.MIN_VALUE;
            for (long t = FxMarket.HOUR; t <= days * FxMarket.DAY; t += FxMarket.HOUR) {
                m.advance(t);
                if (Math.floorMod(t, FxMarket.DAY) == 0) open.replaceAll(p -> p.withAccrued(p.accrued() + Margin.overnight(p)));
                for (var p : java.util.List.copyOf(open))
                    if (Margin.check(p, m.rate(p.currency())) != Margin.Trigger.NONE) { cash[0] += Margin.close(m, p); open.remove(p); }
                if (!FxMarket.open(t)) continue;
                final long since = seen;
                var fresh = m.news().stream().filter(n -> n.time() > since).toList();
                seen = t;
                s.act(m, cash, open, fresh, (int) (Math.floorMod(t, FxMarket.DAY) / FxMarket.HOUR), r);
            }
            for (var p : open) cash[0] += Margin.close(m, p);
            out[run] = cash[0] / (100.0 * CROWN) - 1;
        }
        return out;
    }

    private static void closeAll(FxMarket m, double[] cash, java.util.List<Margin.Position> open) {
        for (var p : open) cash[0] += Margin.close(m, p);
        open.clear();
    }

    private static void enter(FxMarket m, double[] cash, java.util.List<Margin.Position> open, Currency c, int side, int leverage) {
        long margin = (long) cash[0];
        if (margin < CROWN) return;
        cash[0] -= margin;
        open.add(Margin.open(m, open.size() + 1, c, side, margin, leverage, 0, 0, 0, true));
    }

    private static String stats(String name, double[] r) {
        var s = r.clone(); Arrays.sort(s);
        long wiped = Arrays.stream(r).filter(v -> v < -.9).count();
        return String.format("%-12s 平均 %+7.1f%%  中位 %+7.1f%%  最好 %+8.1f%%  爆光 %d%%", name,
                Arrays.stream(r).average().orElse(0) * 100, s[r.length / 2] * 100, s[r.length - 1] * 100, wiped * 100 / r.length);
    }

    /** 传闻指向的货币和方向；收成、帝国币涨跌也算。 */
    private static int[] signal(FxMarket.News n) {
        String k = n.key();
        if (k.startsWith("fx.news.festival.notice")) return new int[] {Currency.FOX.ordinal(), 1};
        if (k.startsWith("fx.news.calamity.rumor")) return new int[] {Currency.FOX.ordinal(), 1};
        if (k.startsWith("fx.news.council.rumor")) return new int[] {Currency.CAT.ordinal(), k.endsWith(".up") ? 1 : -1};
        if (k.startsWith("fx.news.war.rumor")) return new int[] {Currency.WOLF.ordinal(), k.endsWith(".up") ? 1 : -1};
        if (k.startsWith("fx.news.mint.rumor")) return new int[] {Currency.WOLF.ordinal(), k.endsWith(".up") ? 1 : -1};
        return null;
    }

    @Test void leverageWidensTheOutcomes() {
        int runs = 200, days = 30;
        double[] gambler = simulate(() -> (m, cash, open, fresh, hour, r) -> {
            if (hour != 0) return;
            closeAll(m, cash, open);
            enter(m, cash, open, Currency.TRADED.get(r.nextInt(3)), r.nextBoolean() ? 1 : -1, 25);
        }, runs, days);
        double[] carry = simulate(() -> (m, cash, open, fresh, hour, r) -> {
            if (open.isEmpty()) enter(m, cash, open, Currency.WOLF, 1, 5);
        }, runs, days);
        double[] informed = simulate(() -> {
            String[] holding = {null};
            return (m, cash, open, fresh, hour, r) -> {
                for (var n : fresh) {
                    String k = n.key();
                    if (holding[0] != null && !k.contains(".rumor") && !k.contains(".notice") && k.split("[.]")[2].equals(holding[0])) {
                        closeAll(m, cash, open); holding[0] = null;
                    }
                    var sig = signal(n);
                    if (holding[0] == null && open.isEmpty() && sig != null && n.tier() <= 2) {
                        enter(m, cash, open, Currency.values()[sig[0]], sig[1], 3);
                        holding[0] = k.split("[.]")[2];
                    }
                }
            };
        }, runs, days);
        System.out.println(stats("25倍乱炒", gambler));
        System.out.println(stats("5倍套息狼币", carry));
        System.out.println(stats("3倍跟传闻", informed));
        assertTrue(Arrays.stream(gambler).average().orElse(0) < -.8, "满杠杆乱炒一个月应基本爆光");
        assertTrue(Arrays.stream(informed).average().orElse(0) > .1, "低杠杆跟可靠传闻应能赚钱");
    }
}
