package dev.logos.dimension.client;

import dev.logos.dimension.economy.Currency;
import dev.logos.dimension.economy.FxMarket;
import dev.logos.dimension.economy.MarketPayload;
import dev.logos.dimension.economy.Money;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * 兑换台和钱庄共用的 K 线：红涨绿跌，一小时一根（wide 时四小时并一根），日界画竖线，悬停看开高低收。
 * 价格一律写成「一枚最大面额值多少银元」。marks 是要画的水平线（开仓价、爆仓价、止损止盈），
 * 超出画面的贴在上下边并标箭头。
 */
final class CandleChart {
    static final int UP = 0xFFE05A4E, DOWN = 0xFF3FB27F, DIM = 0xFF8C8478;
    static final int BARS = 60;

    /** 一条水平参考线：rate 是中间价（一个最小单位值多少铜分）。 */
    record Mark(double rate, int color, Component label) {}

    static void draw(GuiGraphicsExtractor g, Font font, MarketPayload data, Currency c, boolean wide,
                     int cx, int cy, int w, int h, int mouseX, int mouseY, List<Mark> marks) {
        g.fill(cx, cy, cx + w, cy + h, 0xC0080608);
        g.outline(cx - 1, cy - 1, w + 2, h + 2, 0xFF4A4038);
        var bars = bars(data == null ? List.of() : data.candles(c), wide);
        if (bars.isEmpty()) {
            g.centeredText(font, Component.translatable("screen.logos_dimension.fx.no_data"), cx + w / 2, cy + h / 2 - 4, DIM);
            return;
        }
        double unit = c.top().value() / 100.0; // 铜分 → 一枚最大面额值多少银元
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (var k : bars) { lo = Math.min(lo, k.low()); hi = Math.max(hi, k.high()); }
        // 离得不远的参考线也纳入纵轴，太远的贴边
        float span0 = hi - lo;
        for (var m : marks) if (m.rate > lo - span0 && m.rate < hi + span0) { lo = Math.min(lo, (float) m.rate); hi = Math.max(hi, (float) m.rate); }
        float pad = Math.max((hi - lo) * .08f, hi * .002f);
        lo -= pad; hi += pad;
        final float min = lo, span = hi - lo;
        int bw = Math.max(1, w / BARS);
        int left = cx + w - bars.size() * bw;
        long lastDay = Long.MIN_VALUE;
        for (int i = 0; i < bars.size(); i++) {
            var k = bars.get(i);
            int x = left + i * bw;
            long day = Math.floorDiv(k.slot() * FxMarket.HOUR, FxMarket.DAY);
            if (day != lastDay && i > 0) g.verticalLine(x - 1, cy, cy + h, 0x40FFFFFF);
            lastDay = day;
            int yHigh = y(k.high(), min, span, cy, h), yLow = y(k.low(), min, span, cy, h);
            int yOpen = y(k.open(), min, span, cy, h), yClose = y(k.close(), min, span, cy, h);
            int color = k.close() >= k.open() ? UP : DOWN;
            g.verticalLine(x + Math.max(0, bw - 1) / 2, yHigh, yLow, color);
            g.fill(x, Math.min(yOpen, yClose), x + Math.max(1, bw - 1), Math.max(yOpen, yClose) + 1, color);
        }
        g.horizontalLine(cx, cx + w - 1, y(bars.getLast().close(), min, span, cy, h), 0x60E8C77A);
        g.text(font, fmt(hi * unit), cx + 2, cy + 2, DIM);
        g.text(font, fmt(min * unit), cx + 2, cy + h - 10, DIM);
        for (var m : marks) {
            boolean above = m.rate > hi, below = m.rate < min;
            int ym = above ? cy + 1 : below ? cy + h - 2 : y((float) m.rate, min, span, cy, h);
            for (int x = cx; x < cx + w; x += 4) g.horizontalLine(x, Math.min(x + 1, cx + w - 1), ym, m.color);
            var label = Component.empty().append(m.label).append(above ? " ↑" : below ? " ↓" : "");
            g.text(font, label, cx + w - 2 - font.width(label), Math.clamp(ym - 9, cy + 1, cy + h - 9), m.color);
        }
        if (mouseX >= left && mouseX < cx + w && mouseY >= cy && mouseY < cy + h) {
            var k = bars.get(Math.min(bars.size() - 1, (mouseX - left) / bw));
            g.setTooltipForNextFrame(font, List.of(
                    EconomyClient.when(k.slot() * FxMarket.HOUR, false),
                    Component.translatable("screen.logos_dimension.fx.ohlc", fmt(k.open() * unit), fmt(k.high() * unit), fmt(k.low() * unit), fmt(k.close() * unit))
            ), java.util.Optional.empty(), mouseX, mouseY);
        }
    }

    /** 图下方一行：当前报价和一日涨跌。 */
    static void quote(GuiGraphicsExtractor g, Font font, MarketPayload data, Currency c, int x, int y) {
        double now = EconomyClient.rate(c) * c.top().value();
        var all = data == null ? List.<FxMarket.Candle>of() : data.candles(c);
        double dayAgo = all.isEmpty() ? now : all.get(Math.max(0, all.size() - 13)).close() * c.top().value();
        double change = dayAgo == 0 ? 0 : (now / dayAgo - 1) * 100;
        var quote = Component.literal("1 ").append(Component.translatable(c.coinKey(c.top()))).append(" = ")
                .append(Money.top(Currency.IMPERIAL, now)).append("  ");
        g.text(font, quote, x, y, 0xFFE8E0D0);
        g.text(font, String.format("%+.2f%%", change), x + font.width(quote), y, change >= 0 ? UP : DOWN);
    }

    /** 近二十天时四小时并成一根，保证画面上总是六十根左右。 */
    static List<FxMarket.Candle> bars(List<FxMarket.Candle> candles, boolean wide) {
        if (!wide) return candles.subList(Math.max(0, candles.size() - BARS), candles.size());
        var merged = new ArrayList<FxMarket.Candle>();
        FxMarket.Candle cur = null;
        for (var k : candles) {
            if (cur != null && Math.floorDiv(cur.slot(), 4) == Math.floorDiv(k.slot(), 4)) {
                cur = new FxMarket.Candle(cur.slot(), cur.open(), Math.max(cur.high(), k.high()), Math.min(cur.low(), k.low()), k.close());
                merged.set(merged.size() - 1, cur);
            } else { cur = k; merged.add(cur); }
        }
        return merged.subList(Math.max(0, merged.size() - BARS), merged.size());
    }

    private static int y(float price, float min, float span, int cy, int h) {
        return cy + h - 1 - Math.round((price - min) / span * (h - 2));
    }

    static String fmt(double silver) { return String.format("%,.2f", silver); }

    private CandleChart() {}
}
