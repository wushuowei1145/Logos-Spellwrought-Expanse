package dev.logos.dimension.companion;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.logos.dimension.client.companion.KohakuUltimateLook;
import dev.logos.dimension.ultimate.UltimateChain;
import dev.logos.dimension.ultimate.UltimateDef;
import io.netty.buffer.Unpooled;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 琥珀奥义的纯逻辑（docs/KOHAKU_COMPANION.md 第 20 节）：节拍、两版的长度、耗魔和回流、全屏后期的时间表、光敏、凝时的包、用到的动画。 */
class KohakuUltimateTest {
    @Test void beatsFollowTheStoryboard() {
        KohakuUltimate.Beats f = KohakuUltimate.FULL;
        // 分镜稿：106 刻，Cut 7（第 75 刻）星辰花炸开时结算
        assertEquals(106, f.length());
        assertEquals(75, f.impact());
        assertTrue(f.length() <= UltimateDef.MAX_FULL);
        assertEquals(List.of(14, 27, 43, 55, 65, 75, 91), List.of(f.stop(), f.collapse(), f.palm(), f.flick(), f.shatter(), f.impact(), f.wipe()));
        for (KohakuUltimate.Beats b : List.of(KohakuUltimate.FULL, KohakuUltimate.CONDENSED)) {
            assertTrue(b.stop() < b.collapse() && b.collapse() < b.palm() && b.palm() < b.flick() && b.flick() <= b.touch()
                    && b.touch() < b.shatter() && b.shatter() < b.impact() && b.impact() < b.wipe() && b.wipe() < b.length(), b.toString());
            assertTrue(b.capture() >= b.stop() && b.capture() < b.collapse(), "抓帧在凝时那一镜里");
        }
        KohakuUltimate.Beats c = KohakuUltimate.CONDENSED;
        assertTrue(c.length() <= UltimateDef.MAX_CONDENSED, "缩短版约 2 秒");
        assertFalse(c.chant());
    }

    @Test void archiveUsesBothVersions() {
        UltimateDef d = KohakuCompanion.ULTIMATE;
        assertEquals(700, d.cooldown());
        assertEquals(280, d.mana());
        var first = d.script(new UltimateChain.Stage(0, false, 2));
        var second = d.script(new UltimateChain.Stage(1, false, 2));
        assertInstanceOf(KohakuUltimate.class, first);
        assertEquals(KohakuUltimate.FULL, ((KohakuUltimate) first).beats());
        assertEquals(KohakuUltimate.CONDENSED, ((KohakuUltimate) second).beats());
        assertEquals(106, first.length());
        assertEquals(42, second.length());
        assertEquals(26, second.impact());
    }

    @Test void refluxReturnsFortyPercentOfWhatWasPaid() {
        // 第一段：280 全价，回 112
        assertEquals(280, KohakuUltimate.cost(280, 0, 1));
        assertEquals(112, KohakuUltimate.refund(KohakuUltimate.cost(280, 0, 1)));
        // 第二段 85%：238，回 95
        assertEquals(238, KohakuUltimate.cost(280, 1, 1));
        assertEquals(95, KohakuUltimate.refund(238));
        // 前面有森之加护（再 × 0.75）：179，回 72
        assertEquals(179, KohakuUltimate.cost(280, 1, .75));
        assertEquals(72, KohakuUltimate.refund(179));
        assertEquals(0, KohakuUltimate.refund(0));
    }

    @Test void screenFollowsTheBeats() {
        KohakuUltimate.Beats f = KohakuUltimate.FULL;
        assertFalse(KohakuUltimateLook.at(f, 5, false).active(), "吟唱时没有自己的后期（只有过场的调色）");
        // 凝时第一拍反色；开了隐藏闪烁不反；缩短版不反
        assertEquals(1, KohakuUltimateLook.at(f, f.stop() + .2f, false).invert());
        assertEquals(0, KohakuUltimateLook.at(f, f.stop() + .2f, true).invert());
        assertEquals(0, KohakuUltimateLook.at(f, f.stop() + 1, false).invert());
        assertEquals(0, KohakuUltimateLook.at(KohakuUltimate.CONDENSED, .2f, false).invert());
        assertTrue(KohakuUltimateLook.at(f, f.stop() + .2f, true).ripple() >= 0, "不反色时波纹和色散照旧");
        // 抓帧以后用那一帧
        assertFalse(KohakuUltimateLook.at(f, f.stop() + .5f, false).frozen());
        assertTrue(KohakuUltimateLook.at(f, f.capture() + .1f, false).frozen());
        // 坍缩、晶石、裂纹、碎镜、飞散
        KohakuUltimateLook collapse = KohakuUltimateLook.at(f, f.collapse() + 8, false);
        assertTrue(collapse.collapse() > 0 && collapse.collapse() < 1 && collapse.centered());
        KohakuUltimateLook palm = KohakuUltimateLook.at(f, f.palm() + 2, false);
        assertEquals(1, palm.gem());
        assertFalse(palm.centered(), "掌中琥珀在她手边");
        assertFalse(palm.frozen(), "晶石后面是活的画面");
        assertEquals(0, palm.crack());
        assertTrue(KohakuUltimateLook.at(f, f.touch() + 3, false).crack() > 0);
        assertTrue(KohakuUltimateLook.at(f, f.touch() + .1f, false).glint() > .9f);
        assertEquals(1, KohakuUltimateLook.at(f, f.shatter() + 1, false).shatter());
        float burst = KohakuUltimateLook.at(f, f.impact() + 2, false).burst();
        assertTrue(burst > 0 && burst < 1);
        assertFalse(KohakuUltimateLook.at(f, f.impact() + KohakuUltimateLook.burstTicks(f), false).active(), "碎片飞散完就收");
        assertFalse(KohakuUltimateLook.at(f, f.wipe(), false).active());
    }

    @Test void atMostThreeFullScreenFlipsInAnySecond() {
        for (KohakuUltimate.Beats b : List.of(KohakuUltimate.FULL, KohakuUltimate.CONDENSED)) {
            for (boolean gentle : new boolean[]{false, true}) {
                float[] flips = KohakuUltimateLook.luminanceFlips(b, gentle);
                for (float from = 0; from <= b.length(); from += .5f) {
                    int n = 0;
                    for (float t : flips) if (t >= from && t < from + 20) n++;
                    assertTrue(n <= 3, b + " 第 " + from + " 刻起的一秒里 " + n + " 次");
                }
            }
        }
    }

    @Test void sealPayloadRoundTrips() {
        for (int ticks : new int[]{KohakuSeal.HELD, 0, 1, 70, 400}) {
            var buf = Unpooled.buffer();
            KohakuSeal.Payload.CODEC.encode(buf, new KohakuSeal.Payload(1234, ticks));
            assertEquals(new KohakuSeal.Payload(1234, ticks), KohakuSeal.Payload.CODEC.decode(buf));
        }
    }

    @Test void animationsSheUsesExist() throws Exception {
        try (var in = KohakuUltimateTest.class.getResourceAsStream("/assets/logos_dimension/geckolib/animations/entity/kohaku_companion.animation.json")) {
            assertNotNull(in);
            JsonObject anims = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("animations");
            for (String name : List.of("cast_ward", "cutin", "face_focus", "face_smug")) assertTrue(anims.has(name), name);
            assertTrue(anims.getAsJsonObject("cutin").get("loop").getAsBoolean(), "托着琥珀的姿势要一直摆着");
        }
    }
}
