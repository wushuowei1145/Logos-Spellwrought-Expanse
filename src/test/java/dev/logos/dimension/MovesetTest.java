package dev.logos.dimension;

import com.google.gson.JsonParser;
import dev.logos.dimension.combat.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 招式状态机：长剑数据与迁移前的时间轴逐刻一致，以及校验和容错。 */
class MovesetTest {
    private static Moveset longsword() throws Exception { return Moveset.parse(LongswordMotionTest.read("/data/logos_dimension/combat/movesets/longsword.json")); }

    /** 记录换招与接触时刻的假角色。 */
    private static final class Actor implements MoveRunner.Actor {
        double speed = 1.6;
        final List<String> moves = new ArrayList<>();
        final List<Double> contacts = new ArrayList<>();
        double now;
        @Override public double attackSpeed() { return speed; }
        @Override public void moveStarted(MoveRunner r) { moves.add(r.cleared() ? "$clear" : r.move().name() + "@" + r.start()); }
        @Override public void contact(MoveRunner r) { contacts.add(now); }
    }
    private static final class Sim {
        final Actor actor = new Actor(); final MoveRunner runner;
        double now;
        Sim(Moveset set) { runner = new MoveRunner(set, actor, 0); }
        Sim until(double tick) { while (now < tick) { now++; actor.now = now; runner.tick(now); } return this; }
        boolean input(Moveset.Input input) { return runner.input(input, now); }
        String move() { return runner.move().name(); }
    }

    @Test void everyMoveUsesAClipOfTheSameLengthInBothViews() throws Exception {
        var set = longsword();
        for (var view : new String[]{"fp", "tp"}) {
            var clips = LongswordMotionTest.clips(view);
            for (var move : set.moves().values()) {
                assertTrue(clips.containsKey(move.clip()), view + " 缺少动作 " + move.clip());
                if (move.playback() != Moveset.Playback.REVERSE) assertEquals(clips.get(move.clip()).length(), move.length(), 1e-6, move.name());
            }
        }
    }
    @Test void drawThenThreeHitComboKeepsTheOldCadence() throws Exception {
        var s = new Sim(longsword()).until(24);
        assertEquals("idle", s.move());
        s.until(30); assertTrue(s.input(Moveset.Input.ATTACK)); assertEquals("attack_1", s.move());
        // 接触在 4.8 刻后：第 35 刻结算，与旧版 contact=4.8/rate 一致。
        s.until(34); assertEquals(List.of(), s.actor.contacts);
        s.until(35); assertEquals(List.of(35.0), s.actor.contacts);
        // 收势最后 4 刻（动作时间 0.425 秒起）才接受下一段。
        s.until(38); assertFalse(s.input(Moveset.Input.ATTACK));
        s.until(39); assertTrue(s.input(Moveset.Input.ATTACK));
        s.until(43); assertEquals("attack_2", s.move()); assertEquals(42.5, s.runner.start(), 1e-9);
        s.until(48); assertEquals(List.of(35.0, 48.0), s.actor.contacts);
        s.until(50); assertFalse(s.input(Moveset.Input.ATTACK));
        s.until(52); assertTrue(s.input(Moveset.Input.ATTACK));
        s.until(54); assertEquals("attack_2", s.move());
        s.until(55); assertEquals("attack_3", s.move()); assertEquals(55.0, s.runner.start(), 1e-9);
        s.until(60); assertEquals(List.of(35.0, 48.0, 60.0), s.actor.contacts);
        // 没有排队输入时按到点的那一刻回到待机，与旧版 begin(IDLE, now) 相同。
        s.until(67); assertEquals("attack_3", s.move());
        s.until(68); assertEquals("idle", s.move()); assertEquals(68.0, s.runner.start(), 1e-9);
    }
    @Test void comboMemoryLastsThirtyTicksFromTheLastStrike() throws Exception {
        var a = new Sim(longsword()).until(30); a.input(Moveset.Input.ATTACK);
        a.until(60); a.input(Moveset.Input.ATTACK); assertEquals("attack_2", a.move());
        var b = new Sim(longsword()).until(30); b.input(Moveset.Input.ATTACK);
        b.until(61); b.input(Moveset.Input.ATTACK); assertEquals("attack_1", b.move());
    }
    @Test void attackSpeedScalesDurationAndContact() throws Exception {
        var s = new Sim(longsword()).until(30); s.actor.speed = 3.2; s.input(Moveset.Input.ATTACK);
        assertEquals(2, s.runner.rate(), 1e-9);
        s.until(33); assertEquals(List.of(33.0), s.actor.contacts);
        s.until(36); assertEquals("attack_1", s.move());
        s.until(37); assertEquals("idle", s.move());
    }
    @Test void shortChargeCancelsBackwardsWithoutContact() throws Exception {
        var s = new Sim(longsword()).until(30); assertTrue(s.input(Moveset.Input.USE_PRESS));
        s.until(34); s.input(Moveset.Input.USE_RELEASE);
        assertEquals("charge_cancel", s.move()); assertEquals(.2, s.runner.offset(), 1e-9);
        assertEquals(.15, s.runner.move().seconds(1, s.runner.rate(), s.runner.offset()), 1e-9);
        s.until(37); assertEquals("charge_cancel", s.move());
        s.until(38); assertEquals("idle", s.move());
        assertEquals(List.of(), s.actor.contacts);
    }
    @Test void fullChargeHoldsThenReleasesWithContact() throws Exception {
        var s = new Sim(longsword()).until(30); s.input(Moveset.Input.USE_PRESS);
        s.until(41); assertEquals("charge_hold", s.move()); assertEquals(41, s.runner.start(), 1e-9);
        // 旧版蓄力保持取样为 (age-11)/20 % 1.6。
        assertEquals((60 - 41) / 20.0 % 1.6, s.runner.move().seconds(60 - 41, 1, 0), 1e-9);
        s.until(55); s.input(Moveset.Input.USE_RELEASE); assertEquals("charge_release", s.move());
        s.until(65); assertEquals(List.of(), s.actor.contacts);
        s.until(66); assertEquals(List.of(66.0), s.actor.contacts);
        s.until(75); assertEquals("charge_release", s.move());
        s.until(76); assertEquals("idle", s.move());
    }
    @Test void releaseAtTheChargeBoundaryBeforeTheTickStillReleases() throws Exception {
        var s = new Sim(longsword()).until(30); s.input(Moveset.Input.USE_PRESS);
        s.until(40); s.now = 41; s.input(Moveset.Input.USE_RELEASE);
        assertEquals("charge_release", s.move());
    }
    @Test void chargingResetsTheCombo() throws Exception {
        var s = new Sim(longsword()).until(30); s.input(Moveset.Input.ATTACK);
        s.until(44); s.input(Moveset.Input.USE_PRESS); s.until(46); s.input(Moveset.Input.USE_RELEASE);
        s.until(60); assertEquals("idle", s.move()); s.input(Moveset.Input.ATTACK);
        assertEquals("attack_1", s.move());
    }
    @Test void inputsOutsideTheirMovesAreIgnored() throws Exception {
        var s = new Sim(longsword()).until(5);
        assertFalse(s.input(Moveset.Input.ATTACK)); assertFalse(s.input(Moveset.Input.USE_PRESS));
        assertEquals("draw", s.move());
    }
    @Test void sheatheEndsTheMoveset() throws Exception {
        var s = new Sim(longsword()).until(30); s.input(Moveset.Input.ATTACK);
        s.until(32); s.runner.force("sheathe", s.now);
        s.until(40); assertEquals(List.of(), s.actor.contacts, "收剑取消待结算攻击");
        s.until(55); assertFalse(s.runner.cleared());
        s.until(56); assertTrue(s.runner.cleared());
        assertEquals("$clear", s.actor.moves.getLast());
        assertFalse(s.input(Moveset.Input.ATTACK));
    }
    @Test void playbackSecondsMatchTheOldSampling() {
        assertEquals(.625, Moveset.playbackSeconds(Moveset.Playback.ONCE, .625, 30, 1, 0), 1e-9);
        assertEquals(.3, Moveset.playbackSeconds(Moveset.Playback.ONCE, .625, 3, 2, 0), 1e-9);
        assertEquals(50 / 20.0 % 2.4, Moveset.playbackSeconds(Moveset.Playback.LOOP, 2.4, 50, 1, 0), 1e-9);
        assertEquals(0, Moveset.playbackSeconds(Moveset.Playback.REVERSE, .55, 9, 1, .4), 1e-9);
        assertEquals(0, Moveset.playbackSeconds(Moveset.Playback.ONCE, .625, -3, 1, 0), 1e-9);
    }
    @Test void invalidMovesetsAreRejectedWithReasons() {
        var missing = assertThrows(IllegalArgumentException.class, () -> Moveset.parse(JsonParser.parseString(
                "{\"start\":\"a\",\"moves\":{\"a\":{\"length\":1,\"next\":\"ghost\"}}}").getAsJsonObject()));
        assertTrue(missing.getMessage().contains("ghost"));
        assertThrows(IllegalArgumentException.class, () -> Moveset.parse(JsonParser.parseString(
                "{\"start\":\"a\",\"moves\":{\"a\":{\"length\":1}}}").getAsJsonObject()));
        assertThrows(IllegalArgumentException.class, () -> Moveset.parse(JsonParser.parseString(
                "{\"start\":\"a\",\"moves\":{\"a\":{\"playback\":\"loop\"}}}").getAsJsonObject()));
        var ok = Moveset.parse(JsonParser.parseString(
                "{\"start\":\"a\",\"moves\":{\"a\":{\"length\":1,\"next\":\"$clear\"}}}").getAsJsonObject());
        assertEquals("a", ok.move("a").clip());
    }
}
