package dev.logos.dimension;

import dev.logos.dimension.combat.*;
import java.util.*;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 神狐薙刀：两个视角的动作与招式文件一致，四刀连招和立即格挡的逐刻时间轴，受击反应不算弹刀，
 * 武器不入地、不转身、平时单手立持，以及 12 把共用杆身、物品模型与网格对齐。
 */
class NaginataTest {
    private static final String[] HEADS = {"standard", "slender", "broad"};
    private static final String[] FINISHES = {"wood", "ink", "vermilion", "indigo"};
    private static Moveset moveset() throws Exception { return Moveset.parse(LongswordMotionTest.read("/data/logos_dimension/combat/movesets/naginata.json")); }
    private static Map<String, CombatClip> clips(String view) throws Exception {
        List<String> warnings = new ArrayList<>();
        var clips = CombatClip.parseFile(LongswordMotionTest.combat("animations/naginata_" + view + ".animation"), warnings);
        assertEquals(List.of(), warnings);
        return clips;
    }

    private static final class Actor implements MoveRunner.Actor {
        final List<Double> contacts = new ArrayList<>();
        double now;
        @Override public double attackSpeed() { return 1.3; }
        @Override public void moveStarted(MoveRunner r) {}
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
            var clips = clips(view);
            for (var move : set.moves().values()) {
                var clip = clips.get(move.clip());
                assertNotNull(clip, view + " 缺少动作 " + move.clip());
                assertEquals(move.length(), clip.length(), 1e-6, move.name());
                for (var bone : clip.bones())
                    assertTrue(CombatBones.REQUIRED.contains(bone) || bone.equals(CombatBones.WEAPON) || bone.equals(CombatBones.SHEATH), bone);
                float[] contacts = clip.markers("contact");
                assertEquals(move.contacts().size(), contacts.length, view + " " + move.name() + " 的 contact 个数");
                for (int i = 0; i < contacts.length; i++) assertEquals(move.contacts().get(i), contacts[i], 1e-6, move.name() + " 的第 " + i + " 次 contact");
                for (var t : move.on()) if (t.input() == Moveset.Input.ATTACK && !t.atEnd()) {
                    float[] cancel = clip.markers("cancel");
                    assertEquals(1, cancel.length, move.name());
                    assertEquals(t.at(), cancel[0], 1e-6, move.name() + " 的 cancel");
                }
            }
        }
    }

    @Test void comboRunsFourCutsAndComesBackToTheFirst() throws Exception {
        var set = moveset();
        var s = new Sim(set);
        // 拔刀 1.2 秒（24 刻）后待机。
        s.until(23); assertEquals("draw", s.move());
        s.until(24); assertEquals("idle", s.move());
        assertTrue(s.input(Moveset.Input.ATTACK));
        for (var name : List.of("kesa_1", "kaeshi_2", "sune_3", "men_4")) {
            assertEquals(name, s.move());
            var move = set.move(name);
            double start = s.runner.start();
            for (int i = 0; i < 60 && s.move().equals(name); i++) { s.until(s.now + 1); s.input(Moveset.Input.ATTACK); }
            var cancel = move.on().stream().filter(t -> t.input() == Moveset.Input.ATTACK).findFirst().orElseThrow().at();
            assertEquals(start + cancel * 20 / (1.3 / move.speedBase()), s.runner.start(), 1e-6, name + " 在取消点接下一段");
        }
        assertEquals("kesa_1", s.move(), "第四刀之后回到第一刀");
        assertEquals(4, s.actor.contacts.size());
    }

    @Test void guardBreaksTheComboAtOnceAndAnyHitJustRecoils() throws Exception {
        var set = moveset();
        var s = new Sim(set).until(40);
        assertTrue(s.input(Moveset.Input.ATTACK)); assertEquals("kesa_1", s.move());
        s.until(42);
        assertTrue(s.input(Moveset.Input.USE_PRESS)); assertEquals("guard", s.move(), "右键立即转格挡，不等取消点");
        assertEquals(0, s.actor.contacts.size(), "打断后这一刀不再结算");
        // 任何时候被击中都是受击反应，反应完回到格挡；连续被打也能重新开始。
        s.until(80);
        assertTrue(s.input(Moveset.Input.BLOCK)); assertEquals("guard_hit", s.move());
        s.until(82);
        assertTrue(s.input(Moveset.Input.BLOCK)); assertEquals("guard_hit", s.move());
        assertEquals(82, s.runner.start(), 1e-6);
        s.until(82 + 7); assertEquals("guard", s.move());
        // 受击反应中松手立即回待机。
        assertTrue(s.input(Moveset.Input.BLOCK));
        assertTrue(s.input(Moveset.Input.USE_RELEASE)); assertEquals("idle", s.move());
    }

    @Test void onlyTheKatanaParrySparesDurability() throws Exception {
        assertNotEquals(CombatEffects.PARRY, moveset().move("guard_hit").effect(), "薙刀的格挡照常消耗耐久");
        var katana = Moveset.parse(LongswordMotionTest.read("/data/logos_dimension/combat/movesets/uchigatana.json"));
        assertEquals(CombatEffects.PARRY, katana.move("parry").effect(), "打刀的弹刀不消耗耐久");
    }

    /** 第三人称每段动作里刃尖和石突都在地面以上（模型单位，脚底 0）。 */
    @Test void theWeaponStaysAboveTheGround() throws Exception {
        var skeleton = CombatSkeleton.fromJson(LongswordMotionTest.combat("skeletons/player_tp"));
        var pivot = skeleton.bone(CombatBones.WEAPON).pivot();
        for (var entry : clips("tp").entrySet()) {
            var clip = entry.getValue();
            for (float t = 0; t <= clip.length(); t += 1 / 60f) {
                var m = CombatPose.sample(clip, t).matrices(skeleton).get(CombatBones.WEAPON);
                for (var point : List.of(new Vector3f(-1.42f, 25.15f, 0), new Vector3f(0, -10.45f, 0))) {
                    var at = m.transformPosition(new Vector3f(point).add(pivot));
                    assertTrue(at.y > 1, entry.getKey() + " 在 " + t + " 秒入地：" + at.y);
                }
            }
        }
    }

    /** 第二版不转身：第三人称动作里没有 Root（整个人的转身），只有第一人称的取景根骨。 */
    @Test void noMoveTurnsTheWholeBody() throws Exception {
        for (var entry : clips("tp").entrySet()) assertFalse(entry.getValue().bones().contains(CombatBones.ROOT), entry.getKey());
    }

    /** 平时单手立持，出招时左手才搭上来：待机时左手不在杆上，第一刀出到接触时两手都在杆上。 */
    @Test void idleHoldsTheShaftWithOneHand() throws Exception {
        var skeleton = CombatSkeleton.fromJson(LongswordMotionTest.combat("skeletons/player_tp"));
        var pivot = skeleton.bone(CombatBones.WEAPON).pivot();
        var set = moveset();
        java.util.function.BiFunction<String, Float, Float> leftToShaft = (name, t) -> {
            try {
                var m = CombatPose.sample(clips("tp").get(name), t).matrices(skeleton);
                var hand = m.get("LeftHand").transformPosition(new Vector3f(skeleton.bone("LeftHand").pivot()).add(0, -1.5f, 0));
                var weapon = m.get(CombatBones.WEAPON);
                var a = weapon.transformPosition(new Vector3f(0, -10.45f, 0).add(pivot));
                var b = weapon.transformPosition(new Vector3f(0, 16f, 0).add(pivot));
                var ab = new Vector3f(b).sub(a);
                float u = Math.clamp(new Vector3f(hand).sub(a).dot(ab) / ab.lengthSquared(), 0, 1);
                return new Vector3f(a).add(ab.mul(u)).distance(hand);
            } catch (Exception e) { throw new RuntimeException(e); }
        };
        assertTrue(leftToShaft.apply("idle", 0f) > 6, "待机时左手离杆");
        assertTrue(leftToShaft.apply("kesa_1", (float) set.move("kesa_1").contact()) < 2.5, "砍下去时左手在杆上");
        assertTrue(leftToShaft.apply("men_4", (float) set.move("men_4").contact()) < 2.5, "劈下去时左手在杆上");
    }

    @Test void allTwelveShareTheShaftAndTheItemModelIsTheMeshOffset() throws Exception {
        var base = LongswordMotionTest.combat("weapons/fox_naginata_standard_wood").getAsJsonArray("cubes");
        for (var head : HEADS) for (var finish : FINISHES) {
            String name = "fox_naginata_" + head + "_" + finish;
            var weapon = LongswordMotionTest.combat("weapons/" + name);
            assertEquals("logos_dimension:textures/item/fox_naginata_" + finish + ".png", weapon.get("texture").getAsString());
            assertEquals(1.25, weapon.get("scale").getAsDouble(), 1e-9);
            assertTrue(weapon.has("fp_sheath"), "第一人称也画刃套");
            assertNotNull(NaginataTest.class.getResource("/assets/logos_dimension/textures/item/fox_naginata_" + finish + ".png"));
            assertNotNull(NaginataTest.class.getResource("/assets/logos_dimension/textures/item/" + name + ".png"), "图标");
            assertNotNull(NaginataTest.class.getResource("/assets/logos_dimension/items/" + name + ".json"));
            var cubes = weapon.getAsJsonArray("cubes");
            // 杆身和装具（名字不带刃型前缀的方块）逐块相同，所以共用握点和动作。
            Map<String, String> shared = new HashMap<>();
            for (var c : base) { var o = c.getAsJsonObject(); if (!o.get("name").getAsString().startsWith("standard_")) shared.put(o.get("name").getAsString(), o.get("start") + "|" + o.get("end")); }
            int matched = 0;
            for (var c : cubes) {
                var o = c.getAsJsonObject();
                var expected = shared.get(o.get("name").getAsString());
                if (expected != null) { assertEquals(expected, o.get("start") + "|" + o.get("end"), name + " " + o.get("name")); matched++; }
            }
            assertEquals(shared.size(), matched, name);
            // 物品模型 = 网格的武器部分 + (8, 1, 8)；刃套不进物品模型。
            var model = LongswordMotionTest.read("/assets/logos_dimension/models/item/" + name + ".json");
            var elements = model.getAsJsonArray("elements");
            int e = 0;
            for (var c : cubes) {
                var cube = c.getAsJsonObject();
                if (!cube.get("part").getAsString().equals("weapon")) continue;
                var from = elements.get(e++).getAsJsonObject().getAsJsonArray("from");
                var start = cube.getAsJsonArray("start");
                var end = cube.getAsJsonArray("end");
                double[] offset = {8, 1, 8};
                for (int i = 0; i < 3; i++)
                    assertEquals(Math.min(start.get(i).getAsDouble(), end.get(i).getAsDouble()) + offset[i], from.get(i).getAsDouble(), 1e-3, name);
            }
            assertEquals(e, elements.size(), name + " 的物品模型只含武器部分");
        }
    }
}
