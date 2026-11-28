package dev.logos.dimension;

import static dev.logos.dimension.ultimate.HeroFinisherTimeline.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.logos.dimension.ultimate.FinisherTemplate;
import dev.logos.dimension.ultimate.HeroFinisher;
import dev.logos.dimension.ultimate.HeroFinisherTimeline;
import dev.logos.dimension.ultimate.HeroUltimate;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** 玩家空手最后一击「星穹誓约 · 羁绊焚天炮」的时间表、混色、极远机位（docs/ULTIMATES.md 第六节）。 */
class HeroFinisherTimelineTest {
    @Test
    void storyboardTicks() {
        assertEquals(156, LENGTH, "分镜 7.8 秒");
        assertEquals(99, IMPACT, "Cut 6 第一刻砸下、结算伤害");
        int[] cuts = {IGNITE, REVEAL, CONVERGE, APEX, LUNGE, BLAST, PILLAR, FALL, LENGTH};
        int[] storyboard = {0, 18, 37, 60, 82, 99, 111, 137, 156};
        assertArrayEquals(storyboard, cuts, "八镜的刻数照分镜稿");
        assertEquals(5, LUNGE - HUSH, "屏息定格 0.25 秒");
        assertTrue(APEX < HUSH && THROW < IMPACT && IMPACT < RISE && RISE < PILLAR && FALL < WIPE && WIPE < LENGTH);
        HeroFinisher script = new HeroFinisher();
        assertEquals(LENGTH, script.length());
        assertEquals(IMPACT, script.impact());
    }

    @Test
    void unarmedRoutesToTheFinisher() {
        assertInstanceOf(HeroFinisher.class, HeroUltimate.PLAYER.script(FinisherTemplate.UNARMED));
        assertInstanceOf(HeroFinisher.class, HeroUltimate.PLAYER.script(FinisherTemplate.BLADE), "没做的武器大类落到空手");
    }

    @Test
    void blackOpeningThenSlowReveal() {
        assertEquals(1, exposure(-1, false));
        assertEquals(0, exposure(BLACK_IN, false), 1e-6, "压黑");
        assertEquals(0, exposure(REVEAL - .5f, false), 1e-6, "Cut 1 一直黑着");
        assertTrue(exposure(2, true) > exposure(2, false), "隐藏闪电的闪烁：压黑慢一点");
        assertEquals(0, exposure(BLACK_IN_GENTLE, true), 1e-6);
        float prev = 0;
        for (float a = REVEAL; a <= CONVERGE; a += .25f) {
            float e = exposure(a, false);
            assertTrue(e >= prev - 1e-6, "Cut 2 曝光只升不降");
            prev = e;
        }
        assertEquals(1, exposure(CONVERGE, false), 1e-6);
        assertEquals(1, exposure(IMPACT, false), 1e-6);
    }

    @Test
    void exposureNeverFlickers() {
        // 光敏：整段里明暗只换一次方向（压黑→抬起），中间隔了不止一秒
        int turns = 0;
        float last = 1, dir = 0, turnAt = -100;
        for (float a = -1; a <= LENGTH; a += .25f) {
            float e = exposure(a, false), d = Math.signum(e - last);
            if (d != 0 && dir != 0 && d != dir) {
                turns++;
                assertTrue(a - turnAt >= 20, "两次明暗变化隔一秒以上");
                turnAt = a;
            }
            if (d != 0) dir = d;
            last = e;
        }
        assertTrue(turns <= 1, "只有压黑再抬起这一次");
    }

    @Test
    void flameInTheDark() {
        assertEquals(0, flame(FLAME - .1f));
        assertTrue(flame(FLAME + 6) > .9f, "全黑里火苗亮着");
        assertEquals(0, flame(CONVERGE), "曝光抬满时让给世界里的火");
    }

    @Test
    void orbGrowsIntoAGiantShot() {
        assertEquals(0, orbRadius(FLAME - 1));
        assertTrue(orbRadius(REVEAL) < .3f, "Cut 2 还是掌心的一朵火");
        float prev = 0;
        for (float a = CONVERGE; a < APEX + 6; a += .5f) {
            float r = orbRadius(a);
            assertTrue(r >= prev - 1e-6, "光流灌进来时只涨不缩");
            prev = r;
        }
        assertTrue(orbRadius(APEX + 8) * 2 > 1.8f * 3, "成型的魔弹比主角全身大几倍");
        assertTrue(orbRadius(IMPACT - 1) > 2, "砸下之前一直握着");
        assertEquals(0, orbRadius(IMPACT + 1), "砸下以后没了");
    }

    @Test
    void lungeAndPillarCurves() {
        assertEquals(0, lunge(LUNGE), 1e-6);
        assertEquals(1, lunge(THROW), 1e-6);
        for (int t = LUNGE; t < THROW; t++) assertTrue(lunge(t + 1) > lunge(t), "冲刺一刻不停");
        assertEquals(0, thrown(THROW), 1e-6);
        assertEquals(1, thrown(IMPACT), 1e-6);
        assertEquals(0, pillar(RISE), 1e-6);
        assertEquals(1, pillar(RISE + 8), 1e-6);
        assertEquals(1, pillarFade(PILLAR), 1e-6);
        assertEquals(0, pillarFade(FALL + 12), 1e-6, "Cut 8 化作星火");
    }

    @Test
    void colorsFollowTheParty() {
        assertEquals(GOLD, mix(new int[0], .3f), "没有同伴：暖金");
        assertEquals(GOLD, blend(new int[0]));
        int pink = 0xF7B6D6, mint = 0x8CD9B0, amber = 0xE8A043, coral = 0xE0566B;
        assertEquals(pink, mix(new int[]{pink}, .7f));
        int[] two = {pink, mint};
        assertEquals(pink, mix(two, 0));
        assertEquals(mint, mix(two, .5f));
        int[] four = {pink, mint, amber, coral};
        assertEquals(coral, mix(four, .75f), "四位就混四色");
        // 循环：绕一圈接得上
        int a = mix(four, .9999f), b = mix(four, 0);
        assertTrue(Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) <= 2, "首尾接得上");
        assertNotEquals(blend(two), blend(four), "不写死四色：参战的人不同，混出来的不同");
    }

    @Test
    void farShotBacksOffIntoTheSky() {
        Vec3 burst = new Vec3(10, 64, -5), fwd = new Vec3(0, 0, 1);
        Far open = far(burst, fwd, 200, (from, dir, max) -> max);
        double d = open.from().distanceTo(burst);
        assertFalse(open.cave());
        assertTrue(d >= FAR_MIN && d <= FAR_MAX, "斜上方 45–85 格：" + d);
        assertTrue(open.from().y > burst.y + 15, "在斜上方");
        assertTrue(open.from().z < burst.z, "先往主角身后那边退");
    }

    @Test
    void farShotAvoidsTheMountain() {
        Vec3 burst = new Vec3(0, 70, 0), fwd = new Vec3(1, 0, 0);
        // 主角身后一整片山（往 -x 退的方向 60° 以内都只有 10 格空）
        HeroFinisherTimeline.Clear mountain = (from, dir, max) -> dir.x < -.4 ? 10 : max;
        Far f = far(burst, fwd, 200, mountain);
        assertFalse(f.cave());
        Vec3 off = f.from().subtract(burst.add(0, 1.5, 0)).normalize();
        assertTrue(off.x >= -.4, "不往山里退");
        assertTrue(f.from().distanceTo(burst) >= FAR_MIN);
    }

    @Test
    void farShotStaysInsideRenderDistance() {
        Far f = far(Vec3.ZERO, new Vec3(0, 0, 1), 40, (from, dir, max) -> max);
        assertTrue(f.from().length() <= 40 + 2, "渲染距离以内");
        assertFalse(f.cave());
    }

    @Test
    void enclosedCaveLooksUpThroughTheRoof() {
        Vec3 burst = new Vec3(0, 20, 0);
        // 四面八方 8 格就是岩壁，只有贴地往 +x 能退 14 格
        HeroFinisherTimeline.Clear cave = (from, dir, max) -> Math.abs(dir.y) < .2 && dir.x > .9 ? 14 : 8;
        Far f = far(burst, new Vec3(-1, 0, 0), 200, cave);
        assertTrue(f.cave(), "封闭的洞");
        assertTrue(f.from().x > 10, "退到洞里最远的那边");
        assertTrue(f.look().y > f.from().y + 3, "仰拍火柱穿顶");
        assertTrue(f.look().y < burst.y + 1.2 + 8, "看向洞顶往下一点，不看进岩层里");
    }

    @Test
    void nameInBothLanguages() throws Exception {
        for (String lang : new String[]{"zh_cn", "en_us"}) {
            try (var in = HeroFinisherTimelineTest.class.getResourceAsStream("/assets/logos_dimension/lang/" + lang + ".json")) {
                assertNotNull(in);
                JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                assertTrue(json.has("ultimate.logos_dimension.hero.unarmed.name"), lang);
            }
        }
    }
}
