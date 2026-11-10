package dev.logos.dimension;

import dev.logos.dimension.execution.ExecutionShot;
import dev.logos.dimension.execution.ExecutionShot.Swaps;
import dev.logos.dimension.execution.ExecutionTimeline;
import dev.logos.dimension.execution.ExecutionTimeline.Phase;
import dev.logos.dimension.execution.ExecutionTimeline.Slash;
import io.netty.buffer.Unpooled;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionTimelineTest {
    private static final ExecutionTimeline DEFAULT = new ExecutionTimeline(ExecutionShot.of(42));

    /** 默认十二刀，从抛起到落地约两秒，镜头再半秒多回到玩家。 */
    @Test void aboutTwoSeconds() {
        assertEquals(12, DEFAULT.slashes().size());
        float seconds = DEFAULT.impact() / 20;
        assertTrue(seconds > 1.8 && seconds < 2.2, "落地在 " + seconds + " 秒");
        assertEquals(DEFAULT.duration(), ExecutionTimeline.BLEND_IN + DEFAULT.hold() + ExecutionTimeline.BLEND_OUT);
        assertTrue(DEFAULT.duration() >= DEFAULT.returnAt() + ExecutionTimeline.BLEND_OUT - 1);
    }

    /** 越砍越快：间隔一刀比一刀短，最后两刀不到 0.09 秒。 */
    @Test void slashesSpeedUp() {
        List<Slash> s = DEFAULT.slashes();
        for (int i = 2; i < s.size(); i++) {
            assertTrue(s.get(i).start() - s.get(i - 1).start() < s.get(i - 1).start() - s.get(i - 2).start() + 1e-4);
        }
        assertTrue(s.getLast().start() - s.get(s.size() - 2).start() < 1.8f);
    }

    /** 再快，两刀对调的那两拍之间也隔着一拍原样的：每刀都看得出「对调、换回来」。刀最多时也一样。 */
    @Test void swapsNeverTouch() {
        for (int count : new int[] {ExecutionShot.DEFAULT_SLASHES, ExecutionShot.MAX_SLASHES}) {
            List<Slash> s = new ExecutionTimeline(ExecutionShot.of(3).slashes(count)).slashes();
            for (int i = 1; i < s.size(); i++) {
                assertTrue(ExecutionTimeline.frame(s.get(i).contact()) - ExecutionTimeline.frame(s.get(i - 1).contact()) >= 2, count + " 刀时第 " + i + " 刀紧挨着上一刀");
            }
        }
    }

    /** 顺序：抛起 → 黑白 → 一刀刀 → 静下来 → 重击变红 → 拍下 → 落地白一拍 → 恢复。 */
    @Test void phasesInOrder() {
        assertEquals(Phase.LAUNCH, DEFAULT.phase(0));
        assertEquals(Phase.INK, DEFAULT.phase(ExecutionTimeline.INK));
        for (Slash s : DEFAULT.slashes()) assertEquals(Phase.INK, DEFAULT.phase(s.contact()));
        float quiet = DEFAULT.finalHit() - (DEFAULT.slashes().getLast().start() + ExecutionTimeline.STROKE);
        assertTrue(quiet / 20 >= .15, "重击前静下来至少 0.15 秒");
        assertEquals(Phase.RED, DEFAULT.phase(DEFAULT.finalHit()));
        assertEquals(Phase.RED, DEFAULT.phase(DEFAULT.slamStart()));
        assertEquals(Phase.IMPACT, DEFAULT.phase(DEFAULT.impact()));
        assertEquals(Phase.AFTER, DEFAULT.phase(DEFAULT.impact() + ExecutionTimeline.FRAME));
        assertEquals(Phase.DONE, DEFAULT.phase(DEFAULT.duration()));
        assertTrue(ExecutionTimeline.INK < ExecutionTimeline.APEX && ExecutionTimeline.APEX < DEFAULT.slashes().getFirst().start());
    }

    /** 第一版（每刀对调）原样保留：对调的拍数就是刀数，每拍之后换回来。 */
    @Test void everySlashStyleIsKept() {
        var every = new ExecutionTimeline(ExecutionShot.of(42).swaps(Swaps.EVERY));
        assertEquals(12, every.swapFrames());
        for (Slash s : every.slashes()) {
            assertTrue(every.swapped(s.contact()));
            assertFalse(every.swapped(s.contact() + ExecutionTimeline.FRAME * 1.01f) && !anyContactIn(every, s.contact() + ExecutionTimeline.FRAME * 1.01f));
        }
        assertFalse(every.swapped(every.finalHit()), "重击是变红，不是对调");
        assertFalse(Swaps.EVERY.accents(), "第一版不加火花、错位、推镜");
    }

    private static boolean anyContactIn(ExecutionTimeline t, float age) {
        for (Slash s : t.slashes()) if (ExecutionTimeline.frame(s.contact()) == ExecutionTimeline.frame(age)) return true;
        return false;
    }

    /** 默认只在节拍上对调：第一刀和切机位的那两刀，和切镜落在同一刀上；12 刀时一共 3 次。 */
    @Test void defaultSwapsOnBeats() {
        assertEquals(Swaps.BEATS, ExecutionShot.of(1).swaps());
        assertEquals(List.of(0, 4, 8), DEFAULT.beats());
        assertEquals(3, DEFAULT.swapFrames());
        assertTrue(DEFAULT.swapped(DEFAULT.slashes().get(4).contact()));
        assertFalse(DEFAULT.swapped(DEFAULT.slashes().get(2).contact()), "不在节拍上的刀不对调");
        for (int beat : List.of(4, 8)) {
            assertTrue(DEFAULT.cuts().stream().anyMatch(c -> Math.abs(c.start() - (DEFAULT.slashes().get(beat).start() - .05f)) < 1e-4), "第 " + beat + " 刀同时切机位");
        }
        var steady = new ExecutionTimeline(ExecutionShot.of(42).noSwaps());
        assertEquals(0, steady.swapFrames());
        for (Slash s : steady.slashes()) assertFalse(steady.swapped(s.contact()));
    }

    /** 不管几刀，节拍上的对调在任何一秒里都不超过三次（光敏的常见安全线）。 */
    @Test void beatsStayUnderThreePerSecond() {
        for (int count = ExecutionShot.MIN_SLASHES; count <= ExecutionShot.MAX_SLASHES; count++) {
            var t = new ExecutionTimeline(ExecutionShot.of(9).slashes(count));
            var times = new java.util.ArrayList<Float>();
            for (int i = 0; i < t.slashes().size(); i++) if (t.swaps(i)) times.add(t.slashes().get(i).contact());
            for (int i = 0; i + 3 < times.size(); i++) assertTrue(times.get(i + 3) - times.get(i) > 20, count + " 刀时一秒里对调了四次");
            for (int i = 1; i < times.size(); i++) assertTrue(times.get(i) - times.get(i - 1) >= ExecutionTimeline.MIN_BEAT_GAP - 1e-4);
            assertTrue(t.swaps(0), "第一刀总是节拍");
        }
    }

    @Test void buildUpGrowsWithEachSlash() {
        assertEquals(0, DEFAULT.buildUp(0));
        float last = 0;
        for (Slash s : DEFAULT.slashes()) {
            float now = DEFAULT.buildUp(s.contact());
            assertTrue(now > last);
            last = now;
        }
        assertEquals(1, DEFAULT.buildUp(DEFAULT.finalHit()));
    }

    /** 相邻两刀方向至少差 60 度，看得出换了方向；每刀都穿过身体。 */
    @Test void slashesChangeDirection() {
        List<Slash> s = DEFAULT.slashes();
        for (int i = 1; i < s.size(); i++) {
            double d = Math.abs(Math.IEEEremainder(s.get(i).angle() - s.get(i - 1).angle(), Math.TAU));
            assertTrue(Math.toDegrees(d) >= 59.9, "第 " + i + " 刀只转了 " + Math.toDegrees(d) + " 度");
            assertTrue(Math.abs(s.get(i).offset()) <= .45f);
        }
        assertTrue(Math.sin(DEFAULT.finisher().angle()) < -.4, "重击从上往下劈");
    }

    /** 同一个种子在哪个客户端都算出同一张表；换个种子就不一样。 */
    @Test void sameSeedSameTable() {
        var again = new ExecutionTimeline(ExecutionShot.of(42));
        assertEquals(DEFAULT.slashes(), again.slashes());
        assertEquals(DEFAULT.cuts(), again.cuts());
        assertNotEquals(DEFAULT.slashes(), new ExecutionTimeline(ExecutionShot.of(43)).slashes());
    }

    /** 两次硬切加一个重击前的远景，各在一刀开始之前；刀很少时不切。 */
    @Test void cutsLandOnSlashes() {
        assertEquals(4, DEFAULT.cuts().size());
        assertEquals(DEFAULT.cuts().getFirst(), DEFAULT.cut(0));
        assertEquals(DEFAULT.cuts().getLast(), DEFAULT.cut(DEFAULT.finalHit()));
        assertEquals(2, new ExecutionTimeline(ExecutionShot.of(1).slashes(2)).cuts().size());
    }

    @Test void slashCountIsClamped() {
        assertEquals(ExecutionShot.MAX_SLASHES, ExecutionShot.of(1).slashes(99).slashes());
        assertEquals(ExecutionShot.MIN_SLASHES, ExecutionShot.of(1).slashes(0).slashes());
        var one = new ExecutionTimeline(ExecutionShot.of(5).slashes(1));
        assertEquals(1, one.slashes().size());
        assertTrue(one.finalHit() > one.slashes().getFirst().contact());
    }

    @Test void punchRisesThenSettles() {
        assertEquals(0, ExecutionTimeline.punch(0, 1));
        assertEquals(1, ExecutionTimeline.punch(1, 1), 1e-6);
        assertTrue(ExecutionTimeline.punch(.5f, 1) < 1 && ExecutionTimeline.punch(4, 1) < .2);
    }

    @Test void survivesTheWire() {
        for (Swaps style : Swaps.values()) {
            var shot = ExecutionShot.of(-123456).slashes(17).onBlack().swaps(style).noLetterbox();
            var buffer = Unpooled.buffer();
            ExecutionShot.CODEC.encode(buffer, shot);
            assertEquals(shot, ExecutionShot.CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        }
        var plain = ExecutionShot.of(5);
        var buffer = Unpooled.buffer();
        ExecutionShot.CODEC.encode(buffer, plain);
        assertEquals(plain, ExecutionShot.CODEC.decode(buffer));
    }

    /**
     * 可交互的处决：刀由点击一刀刀加进来；重击、落地和整段时长只看点击窗口有多长，和砍了几刀无关（服务端和客户端各算一遍对得上）。
     * 同一个种子、同样多刀，两边加出来的刀一模一样。节拍是第一刀和每次切机位后的第一刀，任何一秒不超过三次对调。
     */
    @Test void interactiveSlashesArriveOneByOne() {
        var shot = ExecutionShot.of(7).live(40, 0xF7B6D6);
        assertTrue(shot.interactive());
        var a = new ExecutionTimeline(shot);
        var none = new ExecutionTimeline(shot);
        assertTrue(a.slashes().isEmpty());
        assertEquals(ExecutionTimeline.FIRST_SLASH + 40, a.windowEnd(), 1e-6);
        assertEquals(a.windowEnd() + ExecutionTimeline.QUIET, a.finalHit(), 1e-6);
        assertFalse(a.open(ExecutionTimeline.FIRST_SLASH - .1f));
        assertTrue(a.open(ExecutionTimeline.FIRST_SLASH));
        assertFalse(a.open(a.windowEnd()));
        // 一刻一刀砍满整个窗口
        var b = new ExecutionTimeline(shot);
        for (int t = (int) ExecutionTimeline.FIRST_SLASH; t < a.windowEnd(); t++) {
            var x = a.add(t);
            var y = b.add(t);
            assertEquals(x, y, "两边同样第几刀不一样");
        }
        assertEquals(40, a.slashes().size());
        assertEquals(none.finalHit(), a.finalHit(), 1e-6);
        assertEquals(none.impact(), a.impact(), 1e-6);
        assertEquals(none.duration(), a.duration());
        assertEquals(1, a.buildUp(a.finalHit()), 1e-6, "砍满了镜头推到最近");
        // 相邻两刀至少转 60 度
        for (int i = 1; i < a.slashes().size(); i++) {
            double d = Math.abs(a.slashes().get(i).angle() - a.slashes().get(i - 1).angle()) % Math.TAU;
            d = Math.min(d, Math.TAU - d);
            assertTrue(d >= Math.toRadians(59), "第 " + i + " 刀只转了 " + Math.toDegrees(d));
        }
        // 节拍：第一刀和切机位后的第一刀，任何一秒不超过三次
        var beats = a.beats();
        assertEquals(0, beats.getFirst());
        assertTrue(beats.size() >= 2 && beats.size() <= 3, "节拍 " + beats);
        for (int i = 1; i < beats.size(); i++)
            assertTrue(a.slashes().get(beats.get(i)).contact() - a.slashes().get(beats.get(i - 1)).contact() >= ExecutionTimeline.MIN_BEAT_GAP);
        // 一刀没砍也照样有重击、落地
        assertTrue(none.phase(none.finalHit()) == Phase.RED && none.phase(none.impact()) == Phase.IMPACT);
        // 网络上来回一趟不变
        var buf = Unpooled.buffer();
        ExecutionShot.CODEC.encode(buf, shot);
        assertEquals(shot, ExecutionShot.CODEC.decode(buf));
    }

    /** 不可交互的处决不能加刀。 */
    @Test void scriptedShotsRefuseExtraSlashes() {
        assertFalse(DEFAULT.interactive());
        assertThrows(IllegalStateException.class, () -> new ExecutionTimeline(ExecutionShot.of(1)).add(10));
    }
}
