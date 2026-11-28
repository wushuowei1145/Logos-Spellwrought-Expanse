package dev.logos.dimension.companion;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.logos.dimension.ryuka.FoxMarks;
import dev.logos.dimension.ultimate.UltimateDef;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 白梅的奥义「狐嫁入 · 水镜形代断」（docs/SHIRAUME_COMPANION.md 第 11 节）的纯逻辑：两版的节拍对得上分镜稿、在时长限制以内、
 * 先后顺序对；横斩的接触落在居合那一刻；伤害和狐火蚀骨的算术；用到的动作都在她的动画文件里；名字中英文都改了。
 */
class ShiraumeUltimateTest {
    private static JsonObject read(String path) throws Exception {
        try (var in = ShiraumeUltimateTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test void fullFollowsTheStoryboard() {
        ShiraumeUltimate.Beats b = ShiraumeUltimate.FULL;
        // 分镜稿：107 刻，Cut 7 第 78 刻结算；各镜的起点
        assertEquals(107, b.length());
        assertEquals(78, b.impact());
        assertEquals(List.of(13, 28, 41, 55, 67, 78, 93), List.of(b.dive(), b.blow(), b.bind(), b.draw(), b.burn(), b.impact(), b.wipe()));
        assertTrue(b.length() <= UltimateDef.MAX_FULL);
        assertFalse(b.condensed());
    }

    @Test void condensedKeepsTheMirrorTheCutTheShatterAndTheSlash() {
        ShiraumeUltimate.Beats b = ShiraumeUltimate.CONDENSED;
        assertTrue(b.length() <= UltimateDef.MAX_CONDENSED);
        assertTrue(b.condensed());
        assertEquals(-1, b.blowAt());
        // 没有吹纸、封影：形代一进斩纸那一镜就贴着
        assertEquals(b.draw(), b.bound());
        assertTrue(b.wipe() - b.impact() >= 8, "因果斩那一镜至少 8 刻");
        assertTrue(b.length() - b.wipe() >= 8, "擦镜至少 8 刻");
    }

    @Test void beatsAreInOrder() {
        for (ShiraumeUltimate.Beats b : List.of(ShiraumeUltimate.FULL, ShiraumeUltimate.CONDENSED)) {
            assertTrue(0 < b.drop() && b.drop() < b.dive(), b.toString());
            assertTrue(b.dive() < b.rolled() && b.rolled() <= b.draw(), b.toString());
            if (!b.condensed()) assertTrue(b.rolled() <= b.blow() && b.blow() < b.blowAt() && b.blowAt() < b.bind() && b.bind() < b.bound()
                    && b.bound() < b.draw(), b.toString());
            assertTrue(b.manifest() > b.dive() && b.manifest() + CompanionBlade.MANIFEST < b.slash(), "流火在居合之前凝好：" + b);
            assertTrue(b.draw() <= b.slash() && b.slash() < b.burn() && b.burn() < b.sheathe(), b.toString());
            assertTrue(b.sheathe() < b.impact() && b.impact() - b.sheathe() >= 5, "破镜至少 5 刻：" + b);
            assertTrue(b.impact() < b.wipe() && b.wipe() < b.length(), b.toString());
        }
    }

    @Test void theHorizontalCutLandsOnTheIaidoBeat() {
        // 横斩（ryuka_yoko）的接触在动作 0.79 秒，按 2.2 倍播
        for (ShiraumeUltimate.Beats b : List.of(ShiraumeUltimate.FULL, ShiraumeUltimate.CONDENSED)) {
            double contact = b.swing() + .79 * 20 / CompanionBlade.ATTACK_SPEED;
            assertEquals(b.slash(), contact, .5, b.toString());
        }
    }

    @Test void damage() {
        // 五段居合：一刀 × 5 × 0.8
        assertEquals(20, ShiraumeUltimate.slashes(5), 1e-4);
        // 满印引爆至少是平常五枚印爆开的那一下
        assertEquals(FoxMarks.BURST, ShiraumeUltimate.burst(5), 1e-4);
        assertEquals(60, ShiraumeUltimate.burst(20), 1e-4);
        assertEquals(7.5f, ShiraumeUltimate.splash(5), 1e-4);
        // 狐火蚀骨 +25%
        assertEquals(125, ShiraumeUltimate.corroded(100), 1e-3);
        assertTrue(ShiraumeUltimate.MARK_LIFE > dev.logos.dimension.ultimate.UltimateChain.WINDOW, "残樱狐印挂得比接力窗口久");
    }

    @Test void handAndPuddleAreInFrontOfHer() {
        Vec3 feet = new Vec3(10, 64, -3), south = new Vec3(0, 0, 1);
        Vec3 hand = ShiraumeUltimate.handOf(feet, south, 1.5);
        assertTrue(hand.z > feet.z && hand.y > feet.y + .8 && hand.y < feet.y + 1.5, hand.toString());
        // 面朝南，右手边是西（x 小）
        assertTrue(hand.x < feet.x, hand.toString());
        Vec3 puddle = ShiraumeUltimate.puddleOf(feet, south);
        assertEquals(feet.y, puddle.y, 1e-9);
        assertEquals(1.35, puddle.z - feet.z, 1e-9);
    }

    @Test void movesExistInHerAnimationFile() throws Exception {
        JsonObject anims = read("/assets/logos_dimension/geckolib/animations/entity/shiraume_companion.animation.json").getAsJsonObject("animations");
        for (String name : List.of("face_kitsune", "act_proud", "cast_foxfire_r", "ryuka_idle", "ryuka_yoko"))
            assertTrue(anims.has(name), name);
    }

    @Test void nameIsRenamed() throws Exception {
        assertEquals("狐嫁入 · 水镜形代断", read("/assets/logos_dimension/lang/zh_cn.json").get("ultimate.logos_dimension.shiraume.name").getAsString());
        assertTrue(read("/assets/logos_dimension/lang/en_us.json").get("ultimate.logos_dimension.shiraume.name").getAsString().startsWith("Fox's Wedding"));
    }
}
