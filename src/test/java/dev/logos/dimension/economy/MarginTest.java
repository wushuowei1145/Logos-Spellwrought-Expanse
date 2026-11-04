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
        assertTrue(Margin.payout(p, rate * .5) < -10 * CROWN * 10, "跳空穿仓的亏损超出保证金，记成欠款");
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

    // ---------------------------------------------------------------- 冲击不能凭空造钱

    @Test void theReportedOrderIsRefused() {
        var m = market(21);
        // 玩家报告的单子：25 倍做空 5 万金冠狐钱，名义 125 万金冠，冲击把狐钱砸到接近零
        assertTrue(Margin.tooDeep(Currency.FOX, 50_000.0 * CROWN * 25, m.rate(Currency.FOX)), "超出市场深度的单子必须拒绝");
        assertFalse(Margin.tooDeep(Currency.FOX, 100.0 * CROWN * 25, m.rate(Currency.FOX)), "一百金冠 25 倍照常可以开");
    }

    @Test void selfImpactCannotPrintMoney() {
        for (var c : Currency.TRADED) for (int side : new int[] {1, -1}) {
            var m = market(22);
            double rate = m.rate(c);
            // 找到刚好不超出深度的最大保证金（25 倍）
            long margin = CROWN;
            while (!Margin.tooDeep(c, (margin * 2.0) * 25, rate)) margin *= 2;
            var p = Margin.open(m, 1, c, side, margin, 25, 0, 0, 0, true);
            assertTrue(Math.abs(Math.log(m.rate(c) / rate)) > .05, "大单应明显推动价格");
            long back = Margin.close(m, p);
            assertTrue(back < margin, c + (side > 0 ? " 多" : " 空") + " 立刻开平不能赚钱：" + back + " / " + margin);
            assertEquals(rate, m.rate(c), rate * 1e-9, "开平之后冲击正好抵消");
        }
    }

    @Test void shortsCannotProfitFromCrushingThePrice() {
        // 分几笔连续做空把价格一路往下砸，再全部平掉：总账不能为正
        var m = market(23);
        double rate = m.rate(Currency.FOX);
        var opened = new java.util.ArrayList<Margin.Position>();
        long posted = 0;
        for (int i = 0; i < 8; i++) {
            long margin = 250 * CROWN;
            assertFalse(Margin.tooDeep(Currency.FOX, margin * 25.0, m.rate(Currency.FOX)));
            opened.add(Margin.open(m, i, Currency.FOX, -1, margin, 25, 0, 0, 0, true));
            posted += margin;
        }
        assertTrue(m.rate(Currency.FOX) < rate * .7, "连砸八笔应把价格压低三成以上");
        long back = 0;
        for (var p : opened) back += Margin.close(m, p);
        assertTrue(back < posted, "砸盘做空全平后不能赚钱：" + back + " / " + posted);
    }

    @Test void pressureIsCapped() {
        var m = market(24);
        m.push(Currency.FOX, -1e6);
        double low = m.rate(Currency.FOX);
        assertTrue(low > 0 && low >= Currency.FOX.anchor * Math.exp(-FxMarket.HARD - FxMarket.MAX_PRESSURE) * .999, "冲击封顶，汇率不会归零");
        m.push(Currency.FOX, 2e6);
        assertTrue(Double.isFinite(m.rate(Currency.FOX)), "也不会涨到无穷");
    }

    // ---------------------------------------------------------------- 持仓管理

    @Test void addingMarginPushesTheLiquidationAway() {
        var m = market(11);
        double rate = m.rate(Currency.WOLF);
        var p = Margin.open(m, 1, Currency.WOLF, 1, 10 * CROWN, 25, 0, 0, 0, false);
        var more = p.withMargin(p.margin() + 10 * CROWN);
        assertTrue(Margin.liquidationRate(more) < Margin.liquidationRate(p), "追加保证金后爆仓价应更远");
        assertTrue(Margin.room(more, rate) > Margin.room(p, rate) * 1.8, "可承受波动约翻倍");
        assertEquals(12.5, more.effectiveLeverage(), .5, "实际倍数减半");
    }

    @Test void increasingAveragesTheEntry() {
        var m = market(12);
        var p = Margin.open(m, 1, Currency.CAT, 1, 10 * CROWN, 5, 0, 0, 0, true);
        m.shock(Currency.CAT, Math.log(1.05));
        var merged = Margin.increase(m, p, 10 * CROWN, 5, false);
        assertTrue(merged.entry() > p.entry() && merged.entry() < m.rate(Currency.CAT) * 1.01, "均价落在两次开仓价之间");
        assertEquals(20 * CROWN, merged.margin());
        assertEquals(p.notional() * 2, merged.notional(), p.notional() * .06, "名义金额约为两倍");
    }

    @Test void partialClosesAddUpToAFullClose() {
        var m = market(13);
        var p = Margin.open(m, 1, Currency.FOX, -1, 20 * CROWN, 10, 0, 0, 0, false);
        double rate = m.rate(Currency.FOX) * .98;
        var half = p.scaled(.5);
        var rest = p.scaled(.5).withMargin(p.margin() - half.margin());
        long split = Margin.payout(half, rate) + Margin.payout(rest, rate);
        assertEquals(Margin.payout(p, rate), split, 20 * CROWN * .01, "分两半平和一次平几乎一样（只差冲击）");
    }

    @Test void trailingStopsOnlyRatchet() {
        var m = market(14);
        double rate = m.rate(Currency.FOX);
        var p = Margin.open(m, 1, Currency.FOX, 1, 10 * CROWN, 5, 0, 0, .03, 0, false);
        assertEquals(rate * .97, p.stop(), rate * 1e-6, "开仓即按现价设好移动止损");
        var up = Margin.trailed(p, rate * 1.1);
        assertEquals(rate * 1.1 * .97, up.stop(), rate * 1e-6, "价格上涨，止损跟上");
        var down = Margin.trailed(up, rate * 1.02);
        assertEquals(up.stop(), down.stop(), 0, "价格回落，止损不退");
        assertEquals(Margin.Trigger.STOP, Margin.check(down, rate * 1.06));
        var shortP = Margin.trailed(Margin.open(m, 2, Currency.FOX, -1, 10 * CROWN, 5, 0, 0, .05, 0, false), rate * .9);
        assertEquals(rate * .9 * 1.05, shortP.stop(), rate * 1e-6, "做空的移动止损在上方往下压");
        assertFalse(Margin.validStops(1, rate, rate * 1.01, 0, 0), "做多的止损不能在现价上方");
        assertFalse(Margin.validStops(1, rate, 0, 0, Margin.MAX_TRAIL + .01));
    }

    @Test void ordersAndAlertsTriggerOnTheRightSide() {
        var buy = new Orders.Pending(1, Orders.BUY, Currency.FOX, 1, CROWN, 1, 2.4, 0, 0, 0, 0);
        var sell = new Orders.Pending(2, Orders.SELL, Currency.FOX, -1, 4_000, 1, 2.6, 0, 0, 0, 0);
        var shortOpen = new Orders.Pending(3, Orders.OPEN, Currency.WOLF, -1, CROWN, 10, 26, 0, 0, 0, 0);
        assertTrue(buy.due(2.39) && !buy.due(2.41), "买单等跌到挂价");
        assertTrue(sell.due(2.61) && !sell.due(2.59), "卖单等涨到挂价");
        assertTrue(shortOpen.due(26.1) && !shortOpen.due(25.9), "做空挂单等涨到挂价");
        assertEquals(Currency.FOX, sell.refundCurrency());
        assertEquals(Currency.IMPERIAL, shortOpen.refundCurrency());
        var up = new Orders.Alert(4, Currency.CAT, true, 41);
        assertTrue(up.due(41.2) && !up.due(40.9));
    }

    @Test void accountsKeepOrdersAlertsAndStops() {
        var m = market(15);
        var a = new BankAccount();
        a.add(Currency.IMPERIAL, -20 * CROWN);
        a.positions.add(Margin.open(m, 7, Currency.WOLF, 1, 10 * CROWN, 8, 0, 0, .04, 0, false).withWarned(true));
        a.orders.add(new Orders.Pending(8, Orders.OPEN, Currency.CAT, -1, 5 * CROWN, 12, 41, 43, 38, .02, 100));
        a.alerts.add(new Orders.Alert(9, Currency.FOX, false, 2.3));
        a.seen = 1234;
        var copy = BankAccount.load(a.save());
        assertEquals(a.positions(), copy.positions());
        assertEquals(a.orders(), copy.orders());
        assertEquals(a.alerts(), copy.alerts());
        assertEquals(1234, copy.seen);
        var s = BankAccount.summary(new long[] {-20 * CROWN, 0, 0, 0}, copy.positions(), copy.orders(), m::rate);
        assertEquals(10 * CROWN, s.margin());
        assertEquals(5 * CROWN, s.frozen(), 1);
        assertTrue(s.equity() < 0 && s.equity() > -20 * CROWN + 10 * CROWN, "总权益 = 借款 + 持仓净值 + 冻结");
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
