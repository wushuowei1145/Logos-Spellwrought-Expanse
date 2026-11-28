package dev.logos.dimension;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.logos.dimension.ultimate.FinisherTemplate;
import dev.logos.dimension.ultimate.HeroUltimate;
import dev.logos.dimension.ultimate.RelayGift;
import dev.logos.dimension.ultimate.UltimateChain;
import dev.logos.dimension.ultimate.UltimateChain.Event;
import dev.logos.dimension.ultimate.UltimateChain.Phase;
import dev.logos.dimension.ultimate.UltimateChain.Stage;
import dev.logos.dimension.ultimate.UltimateCooldowns;
import dev.logos.dimension.ultimate.UltimateDef;
import dev.logos.dimension.ultimate.UltimatePayloads;
import dev.logos.dimension.ultimate.UltimateScript;
import io.netty.buffer.Unpooled;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 奥义的纯逻辑（docs/ULTIMATES.md）：连携状态机、倍率和折扣、馈赠交给谁、包、武器大类落类、语言文件。 */
class UltimateTest {
    /** 四位同伴（加新同伴时把她加进来，语言文件里要有她奥义的名字和三句台词）。 */
    static final List<String> COMPANIONS = List.of("shiraume", "elf_princess", "kohaku", "lacerta");

    /** 一段什么都不做、只演几刻的脚本。 */
    static UltimateScript script(int length, int impact) {
        return new UltimateScript() {
            @Override public int length() { return length; }
            @Override public int impact() { return impact; }
            @Override public void tick(dev.logos.dimension.ultimate.UltimateCast cast, int t) {}
        };
    }

    @Test void stagesGetStrongerAndCheaper() {
        assertEquals(1, UltimateChain.power(0));
        assertEquals(1.25, UltimateChain.power(1));
        assertEquals(1.55, UltimateChain.power(2));
        assertEquals(1, UltimateChain.manaFactor(0));
        assertEquals(.85, UltimateChain.manaFactor(1));
        assertEquals(.7, UltimateChain.manaFactor(2));
        assertTrue(UltimateChain.manaFactor(9) > 0 && UltimateChain.power(9) >= UltimateChain.power(2), "超出表的段数沿用最后一档");
        assertEquals(1, UltimateChain.finisherPower(2), "两位同伴 + 玩家");
        assertEquals(1.6, UltimateChain.finisherPower(3), "三位同伴 + 玩家：满格");
        assertTrue(UltimateChain.finisherPower(4) >= 1.6);
    }

    @Test void aLoneCompanionNeverChains() {
        var c = new UltimateChain();
        Stage s = c.order("shiraume", 1, 0);
        assertEquals(new Stage(0, false, 1), s);
        assertEquals(Phase.SINGLE, c.phase());
        assertFalse(c.relaying(), "单人奥义不交馈赠");
        assertFalse(c.open("elf_princess"), "单人奥义演完以前不接别人");
        c.impact(10);
        assertEquals(Event.NONE, c.tick(List.of("shiraume"), 11));
        c.ended(s);
        assertEquals(Phase.IDLE, c.phase());
        assertTrue(c.released().isEmpty());
        assertTrue(c.open("shiraume"), "单人奥义不算进这一轮（冷却另算）");
    }

    @Test void theWholePartyLeadsToTheCutinThenThePlayersFinisher() {
        var c = new UltimateChain();
        List<String> party = List.of("shiraume", "kohaku");
        Stage a = c.order("kohaku", 2, 0);
        assertEquals(new Stage(0, false, 2), a);
        assertEquals(Phase.WINDOW, c.phase());
        assertFalse(c.open("kohaku"), "这一轮放过的不能再放");
        c.impact(14);
        c.ended(a);
        assertEquals(Event.NONE, c.tick(party, 30), "只放了一位");
        Stage b = c.order("shiraume", 2, 40);
        assertEquals(new Stage(1, false, 2), b);
        c.impact(54);
        assertEquals(Event.NONE, c.tick(party, 60), "第二位还在演");
        c.ended(b);
        assertEquals(Event.CUTIN, c.tick(party, 70));
        assertEquals(Phase.CUTIN, c.phase());
        assertEquals(2, c.members());
        assertFalse(c.open("lacerta"), "切入里不接新的");
        assertEquals(Event.NONE, c.tick(party, 70 + UltimateChain.SWAP - 1));
        assertEquals(Event.CONVERGE, c.tick(party, 70 + UltimateChain.SWAP));
        assertEquals(Phase.CONVERGED, c.phase());
        long converged = 70 + UltimateChain.SWAP;
        assertEquals(UltimateChain.CONVERGE, c.convergedLeft(converged));
        assertEquals(Event.NONE, c.tick(party, converged + UltimateChain.CONVERGE - 1));
        assertEquals(Event.AUTO_FINISH, c.tick(party, converged + UltimateChain.CONVERGE), "汇流超时替玩家放");
        Stage f = c.finish();
        assertEquals(new Stage(2, true, 2), f, "最后一击排在所有同伴后面");
        assertEquals(Phase.FINISHER, c.phase());
        c.reset();
        assertEquals(Phase.IDLE, c.phase());
        assertTrue(c.open("kohaku"));
    }

    @Test void theSwapMatchesTheCutinTimeline() {
        assertTrue(UltimateChain.SWAP >= dev.logos.dimension.cutin.ChainTimeline.SWAP, "汇流在切入换场那一拍以后");
        assertTrue(UltimateChain.SWAP < dev.logos.dimension.cutin.ChainTimeline.END, "汇流在切入结束以前");
    }

    @Test void theWindowCountsFromTheStrikeNotTheKeyPress() {
        var c = new UltimateChain();
        List<String> party = List.of("shiraume", "kohaku");
        Stage a = c.order("kohaku", 2, 0);
        assertEquals(UltimateChain.WINDOW, c.windowLeft(5), "还在演时窗口算满的");
        c.impact(30);
        c.ended(a);
        assertEquals(Event.NONE, c.tick(party, 30 + UltimateChain.WINDOW), "出手后整整一个窗口都还在");
        assertEquals(10, c.windowLeft(30 + UltimateChain.WINDOW - 10));
        assertEquals(Event.TIMEOUT, c.tick(party, 31 + UltimateChain.WINDOW));
        c.reset();
        assertEquals(Phase.IDLE, c.phase());
        assertTrue(c.open("kohaku"), "超时以后这一轮作废");
    }

    @Test void aStageStillPlayingHoldsTheWindowOpen() {
        var c = new UltimateChain();
        List<String> party = List.of("shiraume", "kohaku", "lacerta");
        Stage a = c.order("kohaku", 3, 0);
        c.impact(10);
        c.ended(a);
        Stage b = c.order("lacerta", 3, 100);
        assertEquals(Event.NONE, c.tick(party, 10 + UltimateChain.WINDOW + 50), "第二位还没出手，窗口不过");
        c.impact(10 + UltimateChain.WINDOW + 60);
        c.ended(b);
        assertEquals(Event.NONE, c.tick(party, 10 + UltimateChain.WINDOW + 61));
    }

    @Test void someoneOnCooldownKeepsTheChainFromTheCutin() {
        var c = new UltimateChain();
        List<String> party = List.of("shiraume", "kohaku", "lacerta");
        for (String id : List.of("kohaku", "lacerta")) {
            Stage s = c.order(id, 3, 0);
            c.impact(10);
            c.ended(s);
        }
        assertEquals(Event.NONE, c.tick(party, 20), "白梅在冷却，没凑齐");
        assertEquals(Event.TIMEOUT, c.tick(party, 11 + UltimateChain.WINDOW));
    }

    @Test void aRecalledCompanionNoLongerCounts() {
        var c = new UltimateChain();
        for (String id : List.of("kohaku", "lacerta")) {
            Stage s = c.order(id, 3, 0);
            c.impact(10);
            c.ended(s);
        }
        assertEquals(Event.CUTIN, c.tick(List.of("kohaku", "lacerta"), 20), "白梅被收回以后在场的两位都放过了");
    }

    @Test void giftsReachTheStagesAfterTheirGiver() {
        var c = new UltimateChain();
        Stage a = c.order("elf_princess", 3, 0);
        RelayGift next = new RelayGift() {
            @Override public double power(Stage stage) { return 1.3; }
            @Override public double manaFactor(Stage stage) { return .75; }
        };
        RelayGift lasting = new RelayGift() {
            @Override public boolean keep(Stage done) { return true; }
        };
        c.give(next, a);
        c.give(lasting, a);
        assertTrue(c.gifts(a).isEmpty(), "交出馈赠的那一段自己不吃");
        c.ended(a);
        Stage b = c.order("kohaku", 3, 20);
        assertEquals(List.of(next, lasting), c.gifts(b));
        c.ended(b);
        Stage d = c.order("lacerta", 3, 40);
        assertEquals(List.of(lasting), c.gifts(d), "默认只管交出之后的那一段");
        assertEquals(1, c.reset().size(), "清掉时把还留着的都交回去");
    }

    /** 单人和连携第一位演完整版，第二位起演缩短版，玩家的最后一击总是完整的。 */
    @Test void laterStagesPlayTheShortCut() {
        assertFalse(UltimateDef.condensed(new Stage(0, false, 1)), "单人");
        assertFalse(UltimateDef.condensed(new Stage(0, false, 3)), "连携第一位");
        assertTrue(UltimateDef.condensed(new Stage(1, false, 3)), "连携第二位");
        assertTrue(UltimateDef.condensed(new Stage(2, false, 3)), "连携第三位");
        assertFalse(UltimateDef.condensed(new Stage(3, true, 3)), "最后一击");
        UltimateScript full = script(100, 80), shortCut = script(40, 30);
        var def = new UltimateDef(700, 260, () -> full, () -> shortCut);
        assertSame(full, def.script(new Stage(0, false, 2)));
        assertSame(shortCut, def.script(new Stage(1, false, 2)));
        var same = new UltimateDef(700, 260, () -> full);
        assertSame(full, same.script(new Stage(1, false, 2)), "只给一段时两版是同一段");
        assertTrue(UltimateDef.MAX_FULL <= 110 && UltimateDef.MAX_CONDENSED < UltimateDef.MAX_FULL, "完整版不超过 5.5 秒");
    }

    @Test void heroFinisherFallsBackToBareHands() {
        HeroUltimate hero = HeroUltimate.PLAYER;
        for (FinisherTemplate t : FinisherTemplate.values()) {
            FinisherTemplate routed = hero.route(t);
            assertTrue(routed == t || routed == FinisherTemplate.UNARMED, t + " 落到 " + routed);
        }
        assertEquals(FinisherTemplate.UNARMED, hero.route(FinisherTemplate.BLADE), "现在只有空手一类");
        assertNotNull(hero.script(FinisherTemplate.BOW));
        assertThrows(IllegalArgumentException.class, () -> new HeroUltimate(Map.of(FinisherTemplate.BLADE, () -> script(10, 5))), "必须有空手");
        for (FinisherTemplate t : FinisherTemplate.values()) assertEquals(t, FinisherTemplate.byId(t.id));
        assertEquals(FinisherTemplate.UNARMED, FinisherTemplate.byId("nonsense"));
    }

    @Test void cooldownsCountDown() {
        var cd = UltimateCooldowns.NONE.with("kohaku", 700);
        assertEquals(700, cd.left("kohaku", 0));
        assertEquals(0, cd.left("kohaku", 800));
        assertEquals(0, cd.left("shiraume", 0));
    }

    @Test void payloadsRoundTrip() {
        var member = new UltimatePayloads.Member("kohaku", 42, 700, 120, true, false, false, 238, true);
        var status = new UltimatePayloads.Status(Phase.WINDOW.ordinal(), 90, 0, 17, List.of(member,
                new UltimatePayloads.Member("lacerta", 43, 800, 0, false, true, false, 272, false)));
        var buf = Unpooled.buffer();
        UltimatePayloads.Status.CODEC.encode(buf, status);
        assertEquals(status, UltimatePayloads.Status.CODEC.decode(buf));
        assertEquals(Phase.WINDOW, status.chainPhase());
        assertFalse(member.ready(), "放过了");
        assertFalse(new UltimatePayloads.Member("shiraume", 44, 700, 0, false, false, true, 260, true).ready(), "排着队的不能再点");

        var cast = new UltimatePayloads.Cast(9, 3, "hero/unarmed", -1, new Vec3(1.5, 64, -2.25), 2, true, false, 2, 1234567890123L, 156, 99);
        buf = Unpooled.buffer();
        UltimatePayloads.Cast.CODEC.encode(buf, cast);
        assertEquals(cast, UltimatePayloads.Cast.CODEC.decode(buf));

        for (var order : List.of(new UltimatePayloads.Order(""), new UltimatePayloads.Order("shiraume"))) {
            buf = Unpooled.buffer();
            UltimatePayloads.Order.CODEC.encode(buf, order);
            assertEquals(order, UltimatePayloads.Order.CODEC.decode(buf));
        }
        var denied = new UltimatePayloads.Denied("lacerta", UltimatePayloads.Denied.NO_MANA);
        buf = Unpooled.buffer();
        UltimatePayloads.Denied.CODEC.encode(buf, denied);
        assertEquals(denied, UltimatePayloads.Denied.CODEC.decode(buf));
    }

    /** 每位同伴的奥义名字、发动 / 接力 / 缺魔三句台词、按键名，中英文都有。 */
    @Test void everyCompanionHasHerUltimateWords() throws Exception {
        for (String locale : List.of("zh_cn", "en_us")) {
            JsonObject lang;
            try (var in = UltimateTest.class.getResourceAsStream("/assets/logos_dimension/lang/" + locale + ".json")) {
                lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            }
            for (String id : COMPANIONS) {
                assertTrue(lang.has("ultimate.logos_dimension." + id + ".name"), locale + " " + id + " 的奥义名");
                for (String line : Set.of("ultimate_cast", "ultimate_relay", "ultimate_no_mana"))
                    assertTrue(lang.has("line.logos_dimension." + id + "." + line), locale + " " + id + " " + line);
            }
            for (String key : List.of("key.logos_dimension.ultimate", "key.logos_dimension.ultimate_1", "key.logos_dimension.ultimate_4",
                    "gui.logos_dimension.ultimate_wheel.title", "gui.logos_dimension.ultimate_wheel.cost"))
                assertTrue(lang.has(key), locale + " " + key);
        }
    }
}
