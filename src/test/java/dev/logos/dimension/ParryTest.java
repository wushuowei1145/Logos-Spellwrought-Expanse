package dev.logos.dimension;

import com.google.gson.JsonObject;
import dev.logos.dimension.combat.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 被弹反的僵直：近战武器的招式文件都有不接受任何输入的 {@code parried}（玩家，约 0.5 秒）和 {@code parried_npc}（NPC，约 0.8 秒），
 * 动作在两个视角和神狐身上都有；结算接触时被切走（弹开）后，后面的接触和缓冲的输入都作废，播完回到待机。
 */
class ParryTest {
    private static final double TICKS = 10;

    private static Moveset moveset(String name) throws Exception {
        JsonObject json = LongswordMotionTest.read("/data/logos_dimension/combat/movesets/" + name + ".json");
        // 继承的招式文件按运行时的规则展开。
        return Moveset.parse(json.has("extends") ? Movesets.inherit(LongswordMotionTest.read("/data/logos_dimension/combat/movesets/longsword.json"), json) : json);
    }

    private static final class Actor implements MoveRunner.Actor {
        final List<Double> contacts = new ArrayList<>();
        Runnable onContact = () -> {};
        double now;
        @Override public double attackSpeed() { return 1.8; }
        @Override public void moveStarted(MoveRunner r) {}
        @Override public void contact(MoveRunner r) { contacts.add(now); onContact.run(); }
    }

    @Test void everyMeleeWeaponHasAShortStaggerThatAcceptsNoInput() throws Exception {
        for (var name : List.of("uchigatana", "oath_spear", "longsword", "dawnvow", "blacksteel_longsword")) {
            for (var stagger : Map.of(Moveset.PARRIED, 0.5, Moveset.PARRIED_NPC, 0.8).entrySet()) {
                var move = moveset(name).move(stagger.getKey());
                assertNotNull(move, name + " 没有 " + stagger.getKey());
                assertEquals(stagger.getValue(), move.length(), 1e-6, name);
                assertEquals(List.of(), move.on(), name + " 的僵直里不该接受输入");
                assertFalse(move.hasContact(), name);
                assertEquals("idle", move.next(), name);
                assertTrue(move.resetCombo(), name + " 被弹开后连招要从头开始");
                assertTrue(Parry.isStagger(move), name);
            }
        }
        assertNull(moveset("yumi").move(Moveset.PARRIED), "和弓不是近战武器");
        assertNull(moveset("yumi").move(Moveset.PARRIED_NPC), "和弓不是近战武器");
    }

    @Test void npcsPreferTheirOwnStaggerAndFallBackToThePlayersOne() throws Exception {
        var json = LongswordMotionTest.read("/data/logos_dimension/combat/movesets/uchigatana.json");
        var runner = new MoveRunner(Moveset.parse(json), new Actor(), 0);
        assertTrue(Parry.staggerNpc(runner, 0));
        assertEquals(Moveset.PARRIED_NPC, runner.move().name());

        json.getAsJsonObject("moves").remove(Moveset.PARRIED_NPC);
        runner = new MoveRunner(Moveset.parse(json), new Actor(), 0);
        assertTrue(Parry.staggerNpc(runner, 0), "没有 NPC 版时退回 parried");
        assertEquals(Moveset.PARRIED, runner.move().name());

        runner = new MoveRunner(moveset("yumi"), new Actor(), 0);
        String before = runner.move().name();
        assertFalse(Parry.staggerNpc(runner, 0), "招式文件里都没有时没有反应");
        assertEquals(before, runner.move().name());
    }

    @Test void theNpcStaggerLastsSixteenTicksAndAcceptsNoInput() throws Exception {
        var runner = new MoveRunner(moveset("uchigatana"), new Actor(), 0);
        assertTrue(Parry.staggerNpc(runner, 0));
        for (int now = 1; now < 16; now++) {
            runner.tick(now);
            assertEquals(Moveset.PARRIED_NPC, runner.move().name(), "第 " + now + " 刻");
            assertFalse(runner.input(Moveset.Input.ATTACK, now), "僵直里不能出招");
        }
        runner.tick(16);
        assertEquals("idle", runner.move().name());
    }

    @Test void theClipExistsInBothViewsWithTheMovesetLength() throws Exception {
        for (var animations : List.of("uchigatana", "oath_spear", "longsword")) for (var view : List.of("tp", "fp")) {
            List<String> warnings = new ArrayList<>();
            var clips = CombatClip.parseFile(LongswordMotionTest.combat("animations/" + animations + "_" + view + ".animation"), warnings);
            assertEquals(0.5, clips.get(Moveset.PARRIED).length(), 1e-6, animations + " " + view);
            assertEquals(0.8, clips.get(Moveset.PARRIED_NPC).length(), 1e-6, animations + " " + view);
            assertEquals(List.of(), warnings);
        }
    }

    @Test void onlyTheKatanaParryMoveIsMarkedAsAParry() throws Exception {
        assertEquals(CombatEffects.PARRY, moveset("uchigatana").move("parry").effect());
        assertTrue(moveset("uchigatana").move("guard").accepts(Moveset.Input.BLOCK));
        assertNull(moveset("uchigatana").move("guard").effect());
    }

    @Test void beingParriedDuringAContactDropsLaterContactsAndTheBufferedInput() throws Exception {
        var set = moveset("oath_spear");
        var actor = new Actor();
        var runner = new MoveRunner(set, actor, 0);
        double now = 0;
        while (!"idle".equals(runner.move().name())) { now++; actor.now = now; runner.tick(now); }
        runner.force("flurry_a", now);
        // 连刺一段八下：第一下就被弹开。
        actor.onContact = () -> runner.force(Moveset.PARRIED, actor.now);
        double hit = -1;
        for (int i = 0; i < 40; i++) {
            now++; actor.now = now; runner.tick(now);
            if (hit < 0 && !actor.contacts.isEmpty()) hit = now;
            if (hit >= 0 && now < hit + TICKS) {
                assertEquals(Moveset.PARRIED, runner.move().name(), "第 " + now + " 刻");
                assertFalse(runner.input(Moveset.Input.ATTACK, now), "僵直里不能出招");
                assertFalse(runner.input(Moveset.Input.USE_PRESS, now), "僵直里不能格挡");
            }
        }
        assertEquals(1, actor.contacts.size(), "被弹开后后面的接触不该结算");
        assertEquals("idle", runner.move().name(), "僵直播完要回到待机");
    }

    @Test void aBufferedAttackDoesNotSurviveTheStagger() throws Exception {
        var set = moveset("uchigatana");
        var actor = new Actor();
        var runner = new MoveRunner(set, actor, 0);
        double now = 0;
        while (!"idle".equals(runner.move().name())) { now++; runner.tick(now); }
        assertTrue(runner.input(Moveset.Input.ATTACK, now));
        // 出刀 0.1 秒后缓冲下一段，再在接触时刻被弹开。
        for (int i = 0; i < 2; i++) { now++; runner.tick(now); }
        assertTrue(runner.input(Moveset.Input.ATTACK, now));
        actor.onContact = () -> runner.force(Moveset.PARRIED, now(actor));
        for (int i = 0; i < 2; i++) { now++; actor.now = now; runner.tick(now); }
        assertEquals(Moveset.PARRIED, runner.move().name());
        // 僵直 10 刻，从接触那一刻算起，已经过了 1 刻。
        for (int i = 0; i < 8; i++) { now++; actor.now = now; runner.tick(now); assertEquals(Moveset.PARRIED, runner.move().name()); }
        now++; runner.tick(now);
        assertEquals("idle", runner.move().name(), "缓冲的下一刀不该在僵直后接上");
    }
    private static double now(Actor actor) { return actor.now; }

    @Test void theFoxHasTheClipToo() throws Exception {
        var json = LongswordMotionTest.read("/assets/logos_dimension/geckolib/animations/entity/divine_fox.animation.json");
        var clip = json.getAsJsonObject("animations").getAsJsonObject("katana_parried_npc");
        assertNotNull(clip, "神狐缺少被弹反动作");
        assertEquals(0.8, clip.get("animation_length").getAsDouble(), 1e-6);
    }
}
