package dev.logos.dimension.companion;

import dev.logos.dimension.ultimate.UltimateChain.Stage;
import dev.logos.dimension.ultimate.UltimateDef;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 精灵公主的奥义「万分之一 · 世界树祈愿矢」的纯逻辑（docs/ELF_PRINCESS_COMPANION.md 第 11 节）：两版的时间表对得上分镜稿和长度上限，
 * 光矢都在贯地之前落地；两端按同一个种子算出同样的落点；主箭的路线；森之加护只管紧接着的下一段、一直留到最后一击。
 */
class PrincessUltimateTest {
    private static void ordered(PrincessUltimatePlan.Timeline p) {
        assertTrue(p.nock() <= p.ring() && p.ring() < p.full() && p.full() < p.release(), "搭箭 → 光环 → 拉满 → 放箭");
        assertTrue(p.release() < p.apex() && p.apex() <= p.appear() && p.appear(PrincessUltimatePlan.RAIN - 1) < p.rain(), "光矢都凝出来以后才落下");
        for (int k = 0; k < PrincessUltimatePlan.RAIN; k++) {
            assertTrue(p.appear(k) < p.launch(k), "第 " + k + " 支先凝出来再落下");
            assertTrue(p.land(k) <= p.impact(), "第 " + k + " 支在圣箭贯地之前落地");
            if (k > 0) assertTrue(p.launch(k) >= p.launch(k - 1), "一支接一支");
        }
        assertTrue(p.plunge() < p.impact() && p.impact() < p.wipe() && p.wipe() < p.length(), "贯地之后才擦镜");
    }

    @Test void theFullCutFollowsTheStoryboard() {
        var p = PrincessUltimatePlan.FULL;
        assertEquals(104, p.length(), "分镜稿 5.20 秒");
        assertTrue(p.length() <= UltimateDef.MAX_FULL);
        assertEquals(79, p.impact(), "Cut 7 圣箭贯地是第 79 刻");
        assertEquals(13, p.grow(), "Cut 2 从第 13 刻起");
        assertEquals(28, p.ring(), "Cut 3 从第 28 刻起");
        assertEquals(41, p.release(), "Cut 4 从第 41 刻起");
        assertEquals(50, p.apex(), "Cut 5 从第 50 刻起");
        assertEquals(64, p.rain(), "Cut 6 从第 64 刻起");
        assertEquals(91, p.wipe(), "Cut 8 从第 91 刻起");
        assertTrue(p.sprouts() && p.seed() < p.grow() && p.grow() < p.nock());
        ordered(p);
    }

    @Test void theCondensedCutFitsInTwoSeconds() {
        var p = PrincessUltimatePlan.CONDENSED;
        assertTrue(p.length() <= UltimateDef.MAX_CONDENSED, "缩短版不超过 44 刻");
        assertFalse(p.sprouts(), "缩短版不演种子破土和抽枝");
        ordered(p);
    }

    @Test void herUltimatePlaysTheFullCutAloneAndTheShortOneInAChain() {
        var def = ElfPrincessCompanion.ULTIMATE;
        assertEquals(800, def.cooldown());
        assertEquals(300, def.mana());
        var alone = def.script(new Stage(0, false, 1));
        var second = def.script(new Stage(1, false, 3));
        assertInstanceOf(PrincessUltimate.class, alone);
        assertSame(PrincessUltimatePlan.FULL, ((PrincessUltimate) alone).plan());
        assertSame(PrincessUltimatePlan.CONDENSED, ((PrincessUltimate) second).plan());
        assertEquals(PrincessUltimatePlan.FULL.impact(), alone.impact());
        assertEquals(PrincessUltimatePlan.CONDENSED.length(), second.length());
    }

    @Test void bothSidesPlaceTheArrowsTheSame() {
        Vec3 apex = new Vec3(10, 80, -4), center = new Vec3(10, 66, -4);
        long seed = 0x1234_5678_9ABCL;
        Set<Vec3> slots = new HashSet<>();
        for (int k = 0; k < PrincessUltimatePlan.RAIN; k++) {
            Vec3 a = PrincessUltimatePlan.slot(apex, seed, k), b = PrincessUltimatePlan.slot(apex, seed, k);
            assertEquals(a, b, "同一个种子同一个位置");
            assertTrue(a.y < apex.y, "挂在法阵下面");
            double r = Math.hypot(a.x - apex.x, a.z - apex.z);
            assertTrue(r <= PrincessUltimatePlan.LOTUS_RADIUS, "在法阵底下");
            slots.add(a);
            Vec3 g = PrincessUltimatePlan.ground(center, seed, k);
            assertEquals(center.y, g.y, 1e-9, "落在地上");
            assertTrue(Math.hypot(g.x - center.x, g.z - center.z) <= PrincessUltimatePlan.RAIN_RADIUS, "落在圈里");
        }
        assertEquals(PrincessUltimatePlan.RAIN, slots.size(), "十二支各挂各的");
        assertNotEquals(PrincessUltimatePlan.slot(apex, seed, 0), PrincessUltimatePlan.slot(apex, seed + (1L << 20), 0), "换个种子排得不一样");
    }

    @Test void theWishArrowFliesUpTurnsAndPlungesIntoTheCentre() {
        var p = PrincessUltimatePlan.FULL;
        Vec3 nock = new Vec3(0, 65.3, 0), center = new Vec3(0, 64, 10), apex = PrincessUltimatePlan.apex(center, 14);
        assertNull(PrincessUltimatePlan.arrow(p, p.release() - 1, nock, apex, center), "放箭之前还在弓上");
        assertEquals(nock, PrincessUltimatePlan.arrow(p, p.release(), nock, apex, center));
        assertEquals(apex, PrincessUltimatePlan.arrow(p, p.apex(), nock, apex, center));
        assertEquals(apex, PrincessUltimatePlan.arrow(p, p.plunge() - 1, nock, apex, center), "在法阵中心停住");
        Vec3 down = PrincessUltimatePlan.arrow(p, p.impact(), nock, apex, center);
        assertEquals(center.x, down.x, 1e-9);
        assertTrue(down.y < center.y + PrincessUltimatePlan.ARROW_GIANT * .5, "贯进地里");
        assertTrue(PrincessUltimatePlan.heading(p, p.release(), nock, apex).y > .5, "飞上去时朝天");
        assertEquals(-1, PrincessUltimatePlan.heading(p, p.plunge(), nock, apex).y, 1e-6, "贯下去时朝下");
        assertEquals(PrincessUltimatePlan.ARROW_GIANT, PrincessUltimatePlan.length(p, p.apex()), 1e-6, "到天上长成巨箭");
    }

    @Test void theForestBlessingHelpsOnlyTheNextStageAndWaitsForTheFinisher() {
        var gift = new PrincessUltimate.ForestBlessing(0, UUID.randomUUID(), 1.5f);
        Stage next = new Stage(1, false, 3), later = new Stage(2, false, 3), finisher = new Stage(3, true, 3);
        assertEquals(PrincessUltimatePlan.BLESSING_POWER, gift.power(next), 1e-9);
        assertEquals(PrincessUltimatePlan.BLESSING_MANA, gift.manaFactor(next), 1e-9);
        assertEquals(1, gift.power(later), 1e-9, "只管紧接着的下一段");
        assertEquals(1, gift.manaFactor(later), 1e-9);
        assertEquals(1, gift.power(finisher), 1e-9, "她不是最后一位时，最后一击不吃加成");
        assertTrue(gift.keep(next) && gift.keep(later), "一直留到最后一击（治愈脉冲）");
        // 她排在最后一位：紧接着的就是玩家的最后一击
        var last = new PrincessUltimate.ForestBlessing(1, UUID.randomUUID(), 1);
        assertEquals(PrincessUltimatePlan.BLESSING_POWER, last.power(new Stage(2, true, 2)), 1e-9);
        assertFalse(last.pulsed());
    }

    @Test void seedsMakeDifferentButStablePhases() {
        double a = PrincessUltimatePlan.phase(42), b = PrincessUltimatePlan.phase(42);
        assertEquals(a, b);
        assertTrue(a >= 0 && a < Math.PI * 2);
        for (int k = 0; k < 64; k++) {
            double h = PrincessUltimatePlan.hash(7, k);
            assertTrue(h >= 0 && h < 1);
        }
    }
}
