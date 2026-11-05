package dev.logos.dimension;

import dev.logos.dimension.cloudsea.OfferingBoxBlock;
import dev.logos.dimension.cloudsea.Omikuji;
import dev.logos.dimension.economy.Currency;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 赛钱箱的保佑与签运：面额越大保佑越久，狐钱比同值的别家钱币久；签运权重走得到每一支。 */
class FoxShrineGoodsTest {
    @Test void biggerCoinsBlessLonger() {
        for (Currency c : Currency.values()) {
            int last = 0;
            for (var d : c.denominations) {
                int seconds = OfferingBoxBlock.blessing(c, d).seconds();
                assertTrue(seconds > last, c + " " + d.name() + " 的保佑没有比小一档的久");
                last = seconds;
            }
        }
    }

    @Test void smallCoinsGiveLuckOnlyAndGoldGivesEverything() {
        var cent = OfferingBoxBlock.blessing(Currency.IMPERIAL, Currency.IMPERIAL.denominations.getFirst());
        assertEquals(0, cent.luck());
        assertFalse(cent.regen());
        assertFalse(cent.resist());
        for (Currency c : Currency.values()) {
            var top = OfferingBoxBlock.blessing(c, c.top());
            assertEquals(1, top.luck(), c + " 金币应当是福运 II");
            assertTrue(top.regen() && top.resist(), c + " 金币应当带生命恢复与抗性");
            assertTrue(top.seconds() <= OfferingBoxBlock.MAX_SECONDS, c + " 单枚不应超过累加上限");
        }
    }

    @Test void foxCoinsBlessLongerAtTheFoxShrine() {
        var koban = OfferingBoxBlock.blessing(Currency.FOX, Currency.FOX.top());
        var crown = OfferingBoxBlock.blessing(Currency.IMPERIAL, Currency.IMPERIAL.top());
        assertTrue(koban.seconds() > crown.seconds(), "小判与金冠等值，狐钱应当更久");
    }

    @Test void everyFortuneCanBeDrawn() {
        var seen = new java.util.HashSet<Omikuji.Fortune>();
        for (int roll = 0; roll < Omikuji.totalWeight(); roll++) seen.add(Omikuji.pick(roll));
        assertEquals(Omikuji.Fortune.values().length, seen.size());
    }
}
