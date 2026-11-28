package dev.logos.dimension.companion;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.logos.dimension.ultimate.UltimateChain;
import dev.logos.dimension.ultimate.UltimateDef;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 拉塞塔的奥义「绯穹裁决 · 炽龙裂地破」（docs/LACERTA_COMPANION.md 第 22 节）里的纯逻辑：两版的时间表对得上分镜和规矩、
 * 室内跳多高、两版一样重、馈赠「龙炎碎甲」只管打中过的并管到最后一击、脚本用到的动作都在模型里且砍中的刻对得上动作的标记。
 * 游戏里真放、截帧看节奏在客户端场景 {@code lacerta_ultimate}。
 */
class LacertaUltimateTest {
    private static JsonObject animations() throws Exception {
        try (var in = LacertaUltimateTest.class.getResourceAsStream("/assets/logos_dimension/geckolib/animations/entity/lacerta_companion.animation.json")) {
            assertNotNull(in);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("animations");
        }
    }

    private static JsonObject lang(String file) throws Exception {
        try (var in = LacertaUltimateTest.class.getResourceAsStream("/assets/logos_dimension/lang/" + file + ".json")) {
            assertNotNull(in);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test void fullFollowsTheStoryboard() {
        LacertaUltimate.Beats b = LacertaUltimate.Beats.FULL;
        assertEquals(107, b.length(), "分镜 5.35 秒");
        assertTrue(b.length() <= UltimateDef.MAX_FULL);
        assertEquals(63, b.impact(), "Cut 6 裂地那一拍就是出手");
        // 八镜的分界：冲阵 0–11、横扫 11–24、挑空 24–37、登穹 37–51、坠 51–63、裂地 63
        assertEquals(11, b.dash());
        assertEquals(11, b.sweep());
        assertEquals(24, b.ascend());
        assertEquals(37, b.rise());
        assertEquals(51, b.dive());
        assertEquals(4, b.dive() - 1 - b.apex(), "滞空定格约 0.18 秒（4 刻）");
        int[] order = {0, b.sweep(), b.sweepHit(), b.ascend(), b.ascendHit(), b.rise(), b.apex(), b.dive(), b.strike(), b.impact(), b.recover(),
                b.stand(), b.length()};
        for (int i = 1; i < order.length; i++) assertTrue(order[i - 1] < order[i], "时间表按顺序：第 " + i + " 拍");
        assertTrue(b.wipe() > b.stand() && b.wipe() < b.length(), "最后一镜擦镜");
        assertFalse(b.condensed());
    }

    @Test void condensedKeepsTheApexAndTheSlam() {
        LacertaUltimate.Beats b = LacertaUltimate.Beats.SHORT;
        assertTrue(b.length() <= UltimateDef.MAX_CONDENSED, "缩短版不超过 44 刻");
        assertTrue(b.condensed());
        assertEquals(0, b.rise(), "一开演就登穹");
        assertTrue(b.rise() < b.apex() && b.apex() < b.dive() && b.dive() < b.strike() && b.strike() < b.impact());
        assertTrue(b.impact() < b.wipe() && b.wipe() < b.length());
        assertEquals(0, b.lightHits().length, "缩短版没有路上的轻伤");
        assertSame(b, LacertaUltimate.Beats.of(true));
        assertSame(LacertaUltimate.Beats.FULL, LacertaUltimate.Beats.of(false));
    }

    @Test void bothVersionsHitAsHard() {
        float full = LacertaUltimate.MAIN + LacertaUltimate.LIGHT * LacertaUltimate.Beats.FULL.lightHits().length;
        assertEquals(LacertaUltimate.MAIN, LacertaUltimate.mainScale(false));
        assertEquals(full, LacertaUltimate.mainScale(true), 1e-6, "缩短版把三下轻伤折进裂地");
        assertEquals(3, LacertaUltimate.Beats.FULL.lightHits().length);
        for (int t : LacertaUltimate.Beats.FULL.lightHits()) assertTrue(t < LacertaUltimate.Beats.FULL.rise(), "轻伤都在登穹之前");
        // 一剑的底同她的剑：攻击 + 8
        assertEquals(18, LacertaUltimate.base(10), 1e-6);
    }

    @Test void pullsOnlyBeforeTheLeap() {
        LacertaUltimate.Beats f = LacertaUltimate.Beats.FULL, s = LacertaUltimate.Beats.SHORT;
        assertTrue(f.pulling(0) && f.pulling(f.rise() - 1));
        assertFalse(f.pulling(f.rise()), "Cut 1–3 聚怪，登穹以后不拉");
        assertTrue(s.pulling(0) && !s.pulling(s.strike()));
        assertFalse(f.pulling(-1));
    }

    @Test void leapStaysUnderTheCeiling() {
        double h = 1.35;
        assertEquals(LacertaUltimate.LIFT, LacertaUltimate.lift(30, h), 1e-9, "露天跳满");
        // 不足 5 格的室内压低，留出头顶的空
        double low = LacertaUltimate.lift(4, h);
        assertTrue(low < LacertaUltimate.LIFT);
        assertTrue(low >= LacertaUltimate.MIN_HOP, "4 格高的屋子里还有 1.8 格的小跳");
        assertEquals(4 - h - LacertaUltimate.CLEARANCE, low, 1e-9);
        for (double room = 0; room < 12; room += .25) {
            double lift = LacertaUltimate.lift(room, h);
            assertTrue(lift >= 0);
            assertTrue(lift + h <= Math.max(room, h) + 1e-9, "头顶不顶进天花板：" + room);
        }
        assertEquals(0, LacertaUltimate.lift(1, h), 1e-9, "比她还矮的洞里不跳");
    }

    @Test void sunderMarksOnlyWhatSheHitAndLastsToTheFinisher() {
        Set<UUID> marked = new HashSet<>();
        UUID hit = UUID.randomUUID(), missed = UUID.randomUUID();
        var gift = new LacertaUltimate.Sunder(marked, 0);
        // 交出去的时候裂地还没打：后来打中的也算
        marked.add(hit);
        assertTrue(gift.marked(hit));
        assertFalse(gift.marked(missed));
        assertEquals(0, gift.from());
        assertTrue(gift.keep(new UltimateChain.Stage(1, false, 3)), "下一段演完还留着");
        assertTrue(gift.keep(new UltimateChain.Stage(2, false, 3)), "管到最后一击");
        assertEquals(.4, LacertaUltimate.SUNDER_PIERCE, 1e-9, "无视四成护甲");
        assertEquals(1, gift.manaFactor(new UltimateChain.Stage(1, false, 3)), 1e-9, "不改耗魔");
        assertEquals(1, gift.power(new UltimateChain.Stage(1, false, 3)), 1e-9, "不改威力");
    }

    @Test void clipsAndContactsLineUp() throws Exception {
        JsonObject anims = animations();
        for (String name : new String[]{"fly_move", "crimson_flame_sweep", "crimson_ascend_3", "crimson_skyfall_judgement", "cutin", "face_battle"})
            assertTrue(anims.has(name), "模型里有 " + name);
        JsonObject skyfall = anims.getAsJsonObject("crimson_skyfall_judgement");
        assertEquals(LacertaUltimate.SKYFALL_END, skyfall.get("animation_length").getAsDouble(), 1e-6);
        assertEquals("contact", skyfall.getAsJsonObject("timeline").get(String.valueOf(LacertaUltimate.CONTACT)).getAsString(),
                "下劈砍中的那一帧");
        // 举剑过顶：右臂抬过头（x 小于 -80°）的那一段里
        JsonObject arm = skyfall.getAsJsonObject("bones").getAsJsonObject("RightArm").getAsJsonObject("rotation");
        double before = Double.NaN;
        for (var e : arm.entrySet()) {
            double t = Double.parseDouble(e.getKey());
            if (t <= LacertaUltimate.OVERHEAD) before = e.getValue().getAsJsonArray().get(0).getAsDouble();
        }
        assertTrue(before < -80, "0.27 秒时剑举过头顶：" + before);
        // 横扫、挑空砍中的刻对得上动作的接触（按 1 倍速，差不过一刻）
        LacertaUltimate.Beats b = LacertaUltimate.Beats.FULL;
        assertEquals(.36, (b.sweepHit() - b.sweep()) / 20.0, .05);
        assertEquals(.34, (b.ascendHit() - b.ascend()) / 20.0, .05);
        // 坠下慢慢挪到下劈起势、砍下正好在出手那一刻砍中
        double atStrike = LacertaUltimate.OVERHEAD + .15 * (b.strike() - b.dive()) / 20.0;
        assertTrue(atStrike < .37, "砍下之前还举在头顶");
    }

    @Test void nameAndLines() throws Exception {
        JsonObject zh = lang("zh_cn"), en = lang("en_us");
        assertEquals("绯穹裁决 · 炽龙裂地破", zh.get("ultimate.logos_dimension.lacerta.name").getAsString());
        assertTrue(en.get("ultimate.logos_dimension.lacerta.name").getAsString().contains("Earthsplitter"));
        for (String line : new String[]{"ultimate_cast", "ultimate_relay", "ultimate_no_mana"}) {
            assertTrue(zh.has("line.logos_dimension.lacerta." + line), line);
            assertTrue(en.has("line.logos_dimension.lacerta." + line), line);
        }
    }
}
