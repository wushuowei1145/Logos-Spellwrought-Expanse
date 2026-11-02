package dev.logos.dimension;

import com.google.gson.JsonObject;
import dev.logos.dimension.combat.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 神狐打刀：两个视角的动作与招式文件一致（时长、动作标记），格挡、弹刀、突刺的逐刻时间轴，以及四套刀装的网格。 */
class UchigatanaTest {
    private static Moveset moveset() throws Exception { return Moveset.parse(LongswordMotionTest.read("/data/logos_dimension/combat/movesets/uchigatana.json")); }
    private static JsonObject animations(String view) throws Exception { return LongswordMotionTest.combat("animations/uchigatana_" + view + ".animation").getAsJsonObject("animations"); }

    private static final class Actor implements MoveRunner.Actor {
        final List<Double> contacts = new ArrayList<>();
        double now;
        @Override public double attackSpeed() { return 1.8; }
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
            List<String> warnings = new ArrayList<>();
            var clips = CombatClip.parseFile(LongswordMotionTest.combat("animations/uchigatana_" + view + ".animation"), warnings);
            assertEquals(List.of(), warnings);
            var json = animations(view);
            for (var move : set.moves().values()) {
                var clip = clips.get(move.clip());
                assertNotNull(clip, view + " 缺少动作 " + move.clip());
                assertEquals(move.length(), clip.length(), 1e-6, move.name());
                for (var bone : clip.bones())
                    assertTrue(CombatBones.REQUIRED.contains(bone) || bone.equals(CombatBones.WEAPON) || bone.equals(CombatBones.SHEATH), bone);
                // 动作标记（COMBAT_SKELETON.md 第 6 节）与招式文件的接触时刻、取消点一致。
                Map<String, Double> markers = new HashMap<>();
                var timeline = json.getAsJsonObject(move.clip()).getAsJsonObject("timeline");
                if (timeline != null) for (var e : timeline.entrySet()) markers.put(e.getValue().getAsString(), Double.parseDouble(e.getKey()));
                if (move.hasContact()) assertEquals(move.contact(), markers.get("contact"), 1e-6, move.name() + " 的 contact");
                else assertFalse(markers.containsKey("contact"), move.name() + " 没有伤害却标了 contact");
                for (var t : move.on()) if (t.input() == Moveset.Input.ATTACK && !t.atEnd())
                    assertEquals(t.at(), markers.get("cancel"), 1e-6, move.name() + " 的 cancel");
            }
        }
    }
    @Test void comboRunsFourCutsAndGuardCancelsAtOnce() throws Exception {
        var s = new Sim(moveset()).until(20);
        assertEquals("idle", s.move());
        assertTrue(s.input(Moveset.Input.ATTACK)); assertEquals("slash_1", s.move());
        s.until(22); assertTrue(s.input(Moveset.Input.ATTACK));
        // 攻速 1.8 与招式基准相同，按原速播放：接触 0.15 秒 = 3 刻，取消点 0.3 秒 = 6 刻。
        s.until(23); assertEquals(List.of(23.0), s.actor.contacts);
        s.until(26); assertEquals("slash_2", s.move());
        // 出刀中按右键不排队，当刻进入格挡并清掉连招记忆。
        s.until(27); assertTrue(s.input(Moveset.Input.USE_PRESS)); assertEquals("guard", s.move());
        assertEquals(List.of(23.0), s.actor.contacts, "被格挡打断的这一刀不再结算");
        assertTrue(s.input(Moveset.Input.USE_RELEASE)); assertEquals("idle", s.move());
        assertTrue(s.input(Moveset.Input.ATTACK)); assertEquals("slash_1", s.move());
    }
    @Test void onlyAnEarlyBlockParriesAndTheThrustFollows() throws Exception {
        var s = new Sim(moveset()).until(20);
        assertTrue(s.input(Moveset.Input.USE_PRESS)); assertEquals("guard", s.move());
        // 进入格挡 0.3 秒（6 刻）内挡下才弹刀。
        s.until(27); assertFalse(s.input(Moveset.Input.BLOCK)); assertEquals("guard", s.move());
        assertTrue(s.input(Moveset.Input.USE_RELEASE));
        s.until(30); assertTrue(s.input(Moveset.Input.USE_PRESS));
        s.until(36); assertTrue(s.input(Moveset.Input.BLOCK)); assertEquals("parry", s.move());
        // 弹刀后马上按左键先缓冲，到 0.12 秒（2.4 刻）接突刺，突刺 0.09 秒命中。
        s.until(37); assertTrue(s.input(Moveset.Input.ATTACK)); assertEquals("parry", s.move());
        s.until(39); assertEquals("riposte", s.move()); assertEquals(38.4, s.runner.start(), 1e-9);
        s.until(41); assertEquals(1, s.actor.contacts.size());
        // 一直按着右键时，突刺过了取消点回到格挡。
        s.until(43); assertFalse(s.input(Moveset.Input.USE_PRESS));
        s.until(44); assertTrue(s.input(Moveset.Input.USE_PRESS)); assertEquals("guard", s.move());
    }
    @Test void parryWithoutFollowUpReturnsToGuard() throws Exception {
        var s = new Sim(moveset()).until(20);
        assertTrue(s.input(Moveset.Input.USE_PRESS)); s.until(22);
        assertTrue(s.input(Moveset.Input.BLOCK)); assertEquals("parry", s.move());
        // 弹刀 0.42 秒（8.4 刻）播完回到格挡。
        s.until(30); assertEquals("parry", s.move());
        s.until(31); assertEquals("guard", s.move());
    }
    @Test void allFourFinishesShareTheMeshAndScale() throws Exception {
        List<Integer> counts = new ArrayList<>();
        for (var finish : new String[]{"ink", "vermilion", "indigo", "wood"}) {
            var weapon = LongswordMotionTest.combat("weapons/fox_uchigatana_" + finish);
            assertEquals("logos_dimension:textures/item/fox_uchigatana_" + finish + "_rig.png", weapon.get("texture").getAsString());
            assertEquals(1.25, weapon.get("scale").getAsDouble(), 1e-9);
            assertNotNull(UchigatanaTest.class.getResource("/assets/logos_dimension/textures/item/fox_uchigatana_" + finish + "_rig.png"));
            assertNotNull(UchigatanaTest.class.getResource("/assets/logos_dimension/textures/item/fox_uchigatana_" + finish + ".png"));
            counts.add(weapon.getAsJsonArray("cubes").size());
            // 立体物品模型就是刀身网格平移 (8, 1, 8)，魔力缠绕才贴在刀上。
            var model = LongswordMotionTest.read("/assets/logos_dimension/models/item/fox_uchigatana_" + finish + ".json");
            int blade = 0;
            for (var cube : weapon.getAsJsonArray("cubes")) if (cube.getAsJsonObject().get("part").getAsString().equals("weapon")) blade++;
            var elements = model.getAsJsonArray("elements");
            assertEquals(blade, elements.size());
            var first = weapon.getAsJsonArray("cubes").asList().stream().filter(c -> c.getAsJsonObject().get("part").getAsString().equals("weapon")).findFirst().orElseThrow().getAsJsonObject();
            var from = elements.get(0).getAsJsonObject().getAsJsonArray("from");
            for (int i = 0; i < 3; i++) assertEquals(Math.min(first.getAsJsonArray("start").get(i).getAsDouble(), first.getAsJsonArray("end").get(i).getAsDouble()) + new int[]{8, 1, 8}[i], from.get(i).getAsDouble(), 1e-3);
        }
        assertEquals(1, new HashSet<>(counts).size());
    }
}
