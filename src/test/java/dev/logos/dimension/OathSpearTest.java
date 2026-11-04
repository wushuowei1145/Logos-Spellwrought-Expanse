package dev.logos.dimension;

import dev.logos.dimension.combat.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 群誓·狼牙长枪：两个视角的动作与招式文件一致（时长、接触与残影标记、取消点），连击与连刺的逐刻时间轴，
 * 击飞时长按体型的规则，以及物品模型与网格对齐。
 */
class OathSpearTest {
    private static Moveset moveset() throws Exception { return Moveset.parse(LongswordMotionTest.read("/data/logos_dimension/combat/movesets/oath_spear.json")); }

    private static final class Actor implements MoveRunner.Actor {
        final List<Double> contacts = new ArrayList<>();
        final List<String> moves = new ArrayList<>();
        double now;
        @Override public double attackSpeed() { return 1.4; }
        @Override public void moveStarted(MoveRunner r) { if (!r.cleared()) moves.add(r.move().name()); }
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

    @Test void clipsMatchTheMovesetInBothViews() throws Exception {
        var set = moveset();
        for (var view : new String[]{"fp", "tp"}) {
            List<String> warnings = new ArrayList<>();
            var clips = CombatClip.parseFile(LongswordMotionTest.combat("animations/oath_spear_" + view + ".animation"), warnings);
            assertEquals(List.of(), warnings);
            for (var move : set.moves().values()) {
                var clip = clips.get(move.clip());
                assertNotNull(clip, view + " 缺少动作 " + move.clip());
                assertEquals(move.length(), clip.length(), 1e-6, move.name());
                for (var bone : clip.bones())
                    assertTrue(CombatBones.REQUIRED.contains(bone) || bone.equals(CombatBones.WEAPON) || bone.equals(CombatBones.SHEATH), bone);
                // 每一次接触都在动作里标了 contact，时刻一致；连刺的每一下还标了 tip_fx（客户端放枪尖残影）。
                float[] contacts = clip.markers("contact");
                assertEquals(move.contacts().size(), contacts.length, view + " " + move.name() + " 的 contact 个数");
                for (int i = 0; i < contacts.length; i++) assertEquals(move.contacts().get(i), contacts[i], 1e-6, move.name() + " 的第 " + i + " 次 contact");
                float[] fx = clip.markers("tip_fx");
                if (CombatEffects.FLURRY.equals(move.effect())) assertArrayEquals(contacts, fx, 1e-6f, move.name() + " 的 tip_fx");
                else assertEquals(0, fx.length, move.name() + " 不是连刺却标了 tip_fx");
                for (var t : move.on()) if (t.input() == Moveset.Input.ATTACK && !t.atEnd()) {
                    float[] cancel = clip.markers("cancel");
                    assertEquals(1, cancel.length, move.name());
                    assertEquals(t.at(), cancel[0], 1e-6, move.name() + " 的 cancel");
                }
            }
        }
    }

    @Test void comboRunsFourHitsAndTheLaunchComesLast() throws Exception {
        var s = new Sim(moveset());
        // 拔枪 1.3 秒（26 刻）后待机。
        s.until(25); assertEquals("draw", s.move());
        s.until(26); assertEquals("idle", s.move());
        var names = List.of("thrust_1", "sweep_2", "overhand_3", "launch_4");
        assertTrue(s.input(Moveset.Input.ATTACK));
        for (var name : names) {
            assertEquals(name, s.move());
            var move = moveset().move(name);
            double start = s.runner.start();
            // 按住左键：每刻都补一次输入，在取消点接下一段。
            for (int i = 0; i < 40 && s.move().equals(name); i++) { s.until(s.now + 1); s.input(Moveset.Input.ATTACK); }
            var cancel = move.on().stream().filter(t -> t.input() == Moveset.Input.ATTACK).findFirst().orElseThrow().at();
            assertEquals(start + cancel * 20, s.runner.start(), 1e-6, name + " 在取消点接下一段");
        }
        assertEquals("thrust_1", s.move(), "第四击之后回到第一击");
        assertEquals(4, s.actor.contacts.size());
        assertEquals(CombatEffects.LAUNCH, moveset().move("launch_4").effect());
    }

    @Test void holdingUseThrustsEightTimesASecondThenSlams() throws Exception {
        var s = new Sim(moveset()).until(30);
        assertTrue(s.input(Moveset.Input.USE_PRESS)); assertEquals("flurry_a", s.move());
        // 三段各 1 秒、各 8 刺，接着下砸；下砸 0.72 秒命中。
        s.until(30 + 60); assertEquals("slam", s.move());
        assertEquals(24, s.actor.contacts.size());
        for (int i = 1; i < 24; i++) assertEquals(2.5, s.actor.contacts.get(i) - s.actor.contacts.get(i - 1), 1.01, "一秒八刺");
        s.until(30 + 60 + 15); assertEquals(25, s.actor.contacts.size());
        // 下砸播完回到待机；还按着右键时服务端会再发 use_press，重新开始连刺。
        s.until(30 + 60 + 24); assertEquals("idle", s.move());
        assertTrue(s.input(Moveset.Input.USE_PRESS)); assertEquals("flurry_a", s.move());
        assertEquals(List.of("flurry_a", "flurry_b", "flurry_c", "slam"), s.actor.moves.subList(2, 6));
    }

    @Test void releasingUseStopsTheFlurryAtOnce() throws Exception {
        var s = new Sim(moveset()).until(30);
        assertTrue(s.input(Moveset.Input.USE_PRESS));
        s.until(30 + 25); assertEquals("flurry_b", s.move());
        int hits = s.actor.contacts.size();
        assertTrue(s.input(Moveset.Input.USE_RELEASE)); assertEquals("idle", s.move());
        s.until(30 + 80); assertEquals(hits, s.actor.contacts.size(), "松开后不再出刺");
    }

    @Test void holdingUseAfterAComboHitGoesStraightIntoTheFlurry() throws Exception {
        var s = new Sim(moveset()).until(30);
        assertTrue(s.input(Moveset.Input.ATTACK)); assertEquals("thrust_1", s.move());
        // 命中前按右键不打断，命中之后才接连刺（挑飞后马上连刺）。
        assertFalse(s.input(Moveset.Input.USE_PRESS));
        s.until(30 + 4); assertTrue(s.input(Moveset.Input.USE_PRESS)); assertEquals("flurry_a", s.move());
    }

    @Test void biggerTargetsFloatShorterAndLower() {
        // 玩家体型：1.6 秒、升 1.9 格。
        assertEquals(32, Airborne.durationTicks(0.6, 1.8));
        assertEquals(1.9, Airborne.liftHeight(0.6, 1.8), 1e-9);
        int zombie = Airborne.durationTicks(0.6, 1.95), golem = Airborne.durationTicks(1.4, 2.7), ravager = Airborne.durationTicks(1.95, 2.2);
        int chicken = Airborne.durationTicks(0.4, 0.7);
        assertTrue(chicken > zombie && zombie > golem && golem > ravager, chicken + " " + zombie + " " + golem + " " + ravager);
        assertTrue(Airborne.liftHeight(1.4, 2.7) < Airborne.liftHeight(0.6, 1.8));
        // 极端体型夹在范围里：体型系数最小 0.35（32 × 0.35 ≈ 11 刻）、最大 1.5（48 刻）。
        assertEquals(11, Airborne.durationTicks(16, 16));
        assertEquals(48, Airborne.durationTicks(0.1, 0.1));
        // 连刺能把浮空维持到一整套打完：每下延长的刻数大于两刺之间的 2.5 刻。
        assertTrue(Airborne.EXTEND_TICKS > 2.5);
        assertTrue(Airborne.PLAYER_MAX_TICKS < 80, "玩家悬空 80 刻会被服务端当作非法飞行踢出");
    }

    @Test void itemModelIsTheMeshOffsetLikeTheOtherWeapons() throws Exception {
        var weapon = LongswordMotionTest.combat("weapons/pack_oath_spear");
        assertEquals("logos_dimension:textures/item/pack_oath_spear.png", weapon.get("texture").getAsString());
        assertEquals(1.25, weapon.get("scale").getAsDouble(), 1e-9);
        assertNotNull(OathSpearTest.class.getResource("/assets/logos_dimension/textures/item/pack_oath_spear.png"));
        assertNotNull(OathSpearTest.class.getResource("/assets/logos_dimension/textures/vfx/spear_tip_ghost.png"));
        var model = LongswordMotionTest.read("/assets/logos_dimension/models/item/pack_oath_spear.json");
        var cubes = weapon.getAsJsonArray("cubes");
        var elements = model.getAsJsonArray("elements");
        assertEquals(cubes.size(), elements.size());
        for (int c = 0; c < cubes.size(); c++) {
            var cube = cubes.get(c).getAsJsonObject();
            var from = elements.get(c).getAsJsonObject().getAsJsonArray("from");
            for (int i = 0; i < 3; i++) {
                double lo = Math.min(cube.getAsJsonArray("start").get(i).getAsDouble(), cube.getAsJsonArray("end").get(i).getAsDouble());
                assertEquals(lo + new int[]{8, 1, 8}[i], from.get(i).getAsDouble(), 1e-3, cube.get("name").getAsString());
                assertTrue(from.get(i).getAsDouble() >= -16 && from.get(i).getAsDouble() <= 32, "原版模型坐标范围");
            }
        }
    }
}
