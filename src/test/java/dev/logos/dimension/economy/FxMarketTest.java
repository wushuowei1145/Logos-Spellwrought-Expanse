package dev.logos.dimension.economy;

import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FxMarketTest {
    private static final long CROWN = 10_000;

    @Test void denominationsSplitFromTheTop() {
        assertArrayEquals(new long[] {3, 8, 7}, Currency.CAT.split(3 * 240 + 8 * 12 + 7));
        assertArrayEquals(new long[] {1, 3, 0, 2}, Currency.WOLF.split(400 + 3 * 16 + 2));
        assertArrayEquals(new long[] {2, 1, 5}, Currency.FOX.split(2 * 4000 + 1000 + 5));
    }

    @Test void noRoundTripOrTriangleMakesMoney() {
        var m = new FxMarket(42);
        for (int day = 0; day < 40; day++) {
            m.advance(day * FxMarket.DAY + 3_000);
            for (var a : Currency.values()) for (var b : Currency.values()) for (var c : Currency.values()) {
                if (a == b || b == c) continue;
                long start = 5 * CROWN;
                long amount = (long) (start / m.rate(a));
                long back = m.quote(c, a, m.quote(b, c, m.quote(a, b, amount).received()).received()).received();
                assertTrue(back <= amount, a + "→" + b + "→" + c + " 套利 " + amount + "→" + back);
            }
        }
    }

    @Test void dumpingMovesThePriceAndItRecovers() {
        var m = new FxMarket(7);
        m.advance(FxMarket.DAY + 1_000);
        double before = m.rate(Currency.WOLF);
        long aurei = 1000 * 400;
        long cents = m.trade(Currency.WOLF, Currency.IMPERIAL, aurei);
        double after = m.rate(Currency.WOLF);
        assertTrue(after < before * .95, "砸盘一千奥里斯应该压低狼币");
        // 立刻原路买回只亏点差，不会凭空赚钱
        long back = m.quote(Currency.IMPERIAL, Currency.WOLF, cents).received();
        assertTrue(back < aurei && back > aurei * .95, "立刻买回应只亏点差：" + back);
        var line = m.lines.get(Currency.WOLF);
        double pressure = line.pressure;
        m.advance(FxMarket.DAY * 4 + 1_000);
        assertTrue(Math.abs(line.pressure) < Math.abs(pressure) * .02, "冲击应在几天内散去");
    }

    @Test void bigSpotRoundTripsReturnThePriceAndLoseMoney() {
        for (var c : Currency.TRADED) {
            var m = new FxMarket(77);
            m.advance(FxMarket.DAY + 1_000);
            double rate = m.rate(c);
            long cents = CROWN;
            while (!FxMarket.tooLarge(Currency.IMPERIAL, c, cents * 2, 1, m.rate(c))) cents *= 2;
            long got = m.trade(Currency.IMPERIAL, c, cents);
            assertTrue(m.rate(c) > rate * 1.05, "大单应明显推高价格");
            long back = m.trade(c, Currency.IMPERIAL, got);
            assertTrue(back < cents, c + " 原路换回不能赚钱：" + back + " / " + cents);
            assertEquals(rate, m.rate(c), rate * 1e-9, "原路换回后价格回到原处");
        }
    }

    @Test void settingTheClockBackShiftsEverything() {
        var m = new FxMarket(31);
        m.advance(0);
        m.advance(30 * FxMarket.DAY + 4_000);
        m.force("war", m.last(), 5 * FxMarket.HOUR);
        var lastCandle = m.candles(Currency.WOLF).getLast().slot();
        // /time set 1000：拨回第 0 天早上
        long shift = m.advance(1_000);
        assertEquals(1_000 - (30 * FxMarket.DAY + 4_000), shift);
        assertEquals(1_000, m.last());
        for (var n : m.news()) assertTrue(n.time() <= 1_000, "消息不能留在未来：" + n);
        assertTrue(m.candles(Currency.WOLF).getLast().slot() * FxMarket.HOUR <= 1_000, "K 线跟着往回挪");
        assertEquals(lastCandle + Math.floorDiv(shift, FxMarket.HOUR), m.candles(Currency.WOLF).getLast().slot());
        // 原本五小时后揭晓的战事，拨回后照样五小时后揭晓
        m.advance(1_000 + 6 * FxMarket.HOUR);
        assertTrue(m.news().stream().anyMatch(n -> n.key().startsWith("fx.news.war.") && !n.key().contains("rumor")), "拨回后事件照常揭晓");
        assertEquals(0, m.advance(1_000 + 7 * FxMarket.HOUR), "往前走不挪");
    }

    @Test void saveAndLoadKeepEverything() {
        var m = new FxMarket(99);
        m.advance(12 * FxMarket.DAY + 5_000);
        m.force("war", m.last(), FxMarket.DAY);
        m.milestone("fox_sanctuary", Currency.FOX, .02, m.last());
        var copy = FxMarket.load(m.save());
        for (var c : Currency.values()) assertEquals(m.rate(c), copy.rate(c), 1e-9);
        assertEquals(m.news(), copy.news());
        assertEquals(m.candles(Currency.CAT), copy.candles(Currency.CAT));
        assertFalse(copy.milestone("fox_sanctuary", Currency.FOX, .02, m.last()), "里程碑只能生效一次");
        // 读档后的随机数接着走，和没读档时完全一样
        m.advance(20 * FxMarket.DAY); copy.advance(20 * FxMarket.DAY);
        for (var c : Currency.values()) assertEquals(m.rate(c), copy.rate(c), 1e-9);
    }

    @Test void ratesStayInsideTheBand() {
        var m = new FxMarket(3);
        for (int day = 1; day <= 400; day++) {
            m.advance(day * FxMarket.DAY);
            for (var c : Currency.TRADED) {
                double log = Math.log(m.rate(c) / c.anchor);
                assertTrue(Math.abs(log) <= FxMarket.HARD + .3, c + " 越界 " + log);
            }
        }
    }

    // ---------------------------------------------------------------- 策略模拟

    /** 玩家的钱：各货币最小单位。 */
    private static final class Book {
        final Map<Currency, Long> held = new EnumMap<>(Currency.class);
        Book(long cents) { held.put(Currency.IMPERIAL, cents); }
        void move(FxMarket m, Currency from, Currency to, double share) {
            long amount = (long) (held.getOrDefault(from, 0L) * share);
            if (amount <= 0 || from == to) return;
            held.merge(from, -amount, Long::sum);
            held.merge(to, m.trade(from, to, amount), Long::sum);
        }
        double value(FxMarket m) {
            double v = 0;
            for (var e : held.entrySet()) v += e.getValue() * m.rate(e.getKey());
            return v;
        }
    }

    private interface Strategy { void act(FxMarket m, Book b, int hour, java.util.List<FxMarket.News> fresh, java.util.Random r); }

    private static double[] simulate(java.util.function.Supplier<Strategy> make, int runs, int days) {
        double[] result = new double[runs];
        for (int run = 0; run < runs; run++) {
            var s = make.get();
            var m = new FxMarket(1000 + run);
            var r = new java.util.Random(run);
            m.advance(0);
            var book = new Book(100 * CROWN);
            long seen = Long.MIN_VALUE;
            for (long t = FxMarket.HOUR; t <= days * FxMarket.DAY; t += FxMarket.HOUR) {
                m.advance(t);
                if (!FxMarket.open(t)) continue;
                // 夜里的消息要等开门才看得到、才能交易
                final long since = seen;
                var fresh = m.news().stream().filter(n -> n.time() > since).toList();
                seen = t;
                s.act(m, book, (int) (Math.floorMod(t, FxMarket.DAY) / FxMarket.HOUR), fresh, r);
            }
            for (var c : Currency.TRADED) book.move(m, c, Currency.IMPERIAL, 1);
            result[run] = book.value(m) / (100.0 * CROWN) - 1;
        }
        return result;
    }

    private static String stats(String name, double[] r) {
        var sorted = r.clone(); java.util.Arrays.sort(sorted);
        double mean = java.util.Arrays.stream(r).average().orElse(0);
        return String.format("%-10s 平均 %+6.1f%%  中位 %+6.1f%%  最差 %+6.1f%%  最好 %+6.1f%%", name, mean * 100,
                sorted[r.length / 2] * 100, sorted[0] * 100, sorted[r.length - 1] * 100);
    }

    /** 读消息：按传闻方向全仓押注对应货币，结果揭晓后平仓回帝国币。 */
    private static Currency target(FxMarket.News n) {
        String k = n.key();
        if (k.startsWith("fx.news.festival.notice")) return Currency.FOX;
        if (k.startsWith("fx.news.council.rumor.up")) return Currency.CAT;
        if (k.startsWith("fx.news.war.rumor.up")) return Currency.WOLF;
        if (k.startsWith("fx.news.calamity.rumor")) return Currency.FOX;
        if (k.startsWith("fx.news.mint.rumor.up")) return Currency.WOLF;
        if (k.startsWith("fx.news.harvest.rumor.up")) return Currency.byId(n.args().getFirst().substring("currency.logos_dimension.".length()));
        return null;
    }
    /** 这条消息是不是某类事件的揭晓（而不是传闻或告示）。 */
    private static String settledKind(FxMarket.News n) {
        String k = n.key();
        if (k.contains(".rumor") || k.contains(".notice") || !k.startsWith("fx.news.")) return null;
        return k.split("[.]")[2];
    }

    @Test void informedTradersWinAndGamblersLose() {
        int runs = 200, days = 30;
        double[] gambler = simulate(() -> (m, b, hour, fresh, r) -> {
            // 每天开盘随手把全部钱换成随机一种货币
            if (hour != 0) return;
            var to = Currency.values()[r.nextInt(4)];
            for (var c : Currency.values()) b.move(m, c, to, 1);
        }, runs, days);
        double[] holder = simulate(() -> (m, b, hour, fresh, r) -> {
            if (b.held.getOrDefault(Currency.IMPERIAL, 0L) > 0) for (var c : Currency.TRADED) b.move(m, Currency.IMPERIAL, c, c == Currency.WOLF ? 1 : .34);
        }, runs, days);
        // 只信可靠和坊间消息、不碰收成这种小行情；押注后盯住同一件事，揭晓了才平仓
        double[] informed = simulate(() -> {
            String[] holding = {null};
            return (m, b, hour, fresh, r) -> {
                for (var n : fresh) {
                    if (holding[0] != null && holding[0].equals(settledKind(n))) {
                        for (var x : Currency.TRADED) b.move(m, x, Currency.IMPERIAL, 1);
                        holding[0] = null;
                    }
                    var c = target(n);
                    if (holding[0] == null && c != null && n.tier() <= 2 && !n.key().contains("harvest")) {
                        b.move(m, Currency.IMPERIAL, c, 1);
                        holding[0] = n.key().split("[.]")[2];
                    }
                }
            };
        }, runs, days);
        System.out.println(stats("乱炒", gambler));
        System.out.println(stats("囤着不动", holder));
        System.out.println(stats("看消息", informed));
        double g = java.util.Arrays.stream(gambler).average().orElse(0), i = java.util.Arrays.stream(informed).average().orElse(0);
        assertTrue(g < -.05, "乱炒一个月应该明显亏钱");
        assertTrue(i > .03, "看懂消息的玩家应该赚钱");
    }
}
