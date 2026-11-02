package dev.logos.dimension.economy;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** 把最小单位的数额写成玩家看得懂的样子，例如「3 金镑 8 先令 7 便士」。 */
public final class Money {
    /** 按面额拆开写，零的面额省略；整数为零写成「0 便士」。 */
    public static MutableComponent format(Currency c, long amount) {
        long[] parts = c.split(amount);
        MutableComponent out = Component.empty();
        boolean any = false;
        for (int k = 0; k < parts.length; k++) {
            if (parts[k] == 0) continue;
            var d = c.denominations.get(c.denominations.size() - 1 - k);
            if (any) out.append(" ");
            out.append(String.format("%,d ", parts[k])).append(Component.translatable(c.coinKey(d)));
            any = true;
        }
        if (!any) out.append("0 ").append(Component.translatable(c.coinKey(c.denominations.getFirst())));
        return out;
    }

    /** 折合成最大面额的小数，例如「3.54 金镑」，用于价格和总额。 */
    public static MutableComponent top(Currency c, double amount) {
        var d = c.top();
        return Component.literal(String.format("%,.2f ", amount / d.value())).append(Component.translatable(c.coinKey(d)));
    }

    private Money() {}
}
