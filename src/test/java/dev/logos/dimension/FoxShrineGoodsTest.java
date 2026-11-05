package dev.logos.dimension;

import dev.logos.dimension.cloudsea.OfferingBoxBlock;
import dev.logos.dimension.cloudsea.Omikuji;
import dev.logos.dimension.economy.Currency;
import dev.logos.dimension.luck.LuckKind;
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

    @Test void everyFortuneCanFillItsKinds() {
        long withBad = java.util.Arrays.stream(LuckKind.values()).filter(k -> k.hasBad).count();
        for (Omikuji.Fortune f : Omikuji.Fortune.values()) {
            assertTrue(f.kinds <= (f.good ? LuckKind.values().length : withBad), f + " 要的运势比能挑的多");
        }
    }

    /** 缺贴图时图标画成紫黑块、缺名字时显示原始键名，都只有进游戏才看得出来，这里先拦住。 */
    @Test void everyLuckEffectHasAnIconAndAName() throws Exception {
        for (String locale : new String[] {"zh_cn", "en_us"}) {
            String lang;
            try (var in = getClass().getResourceAsStream("/assets/logos_dimension/lang/" + locale + ".json")) {
                lang = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            for (Omikuji.Fortune fortune : Omikuji.Fortune.values()) {
                for (int i = 0; i < Omikuji.LINES; i++) {
                    assertTrue(lang.contains("\"" + fortune.verseKey(i) + "\""), locale + " 缺 " + fortune.verseKey(i));
                }
            }
            for (LuckKind kind : LuckKind.values()) {
                assertTrue(lang.contains("\"" + kind.labelKey() + "\""), locale + " 缺 " + kind.labelKey());
                for (int level : kind.hasBad ? new int[] {2, 1, -1, -2} : new int[] {2, 1}) {
                    for (int i = 0; i < Omikuji.LINES; i++) {
                        assertTrue(lang.contains("\"" + kind.lineKey(level, i) + "\""), locale + " 缺 " + kind.lineKey(level, i));
                    }
                }
                for (String side : kind.hasBad ? new String[] {"luck", "misfortune"} : new String[] {"luck"}) {
                    String id = kind.id + "_" + side;
                    assertTrue(lang.contains("\"effect.logos_dimension." + id + "\""), locale + " 缺 " + id);
                    assertNotNull(getClass().getResource("/assets/logos_dimension/textures/mob_effect/" + id + ".png"), "缺图标 " + id);
                }
            }
        }
    }
}
