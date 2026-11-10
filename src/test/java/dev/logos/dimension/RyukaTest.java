package dev.logos.dimension;

import com.google.gson.JsonObject;
import dev.logos.dimension.combat.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 流火（docs/RYUKA.md）：两个视角的动作与招式文件一致，五段连斩与终式的时间轴，0.5 秒的弹刀窗口，以及网格、刀身与物品模型。 */
class RyukaTest {
    private static Moveset moveset() throws Exception { return Moveset.parse(LongswordMotionTest.read("/data/logos_dimension/combat/movesets/ryuka.json")); }
    private static JsonObject animations(String view) throws Exception { return LongswordMotionTest.combat("animations/ryuka_" + view + ".animation").getAsJsonObject("animations"); }

    private static final class Actor implements MoveRunner.Actor {
        final List<Double> contacts = new ArrayList<>();
        final List<String> moves = new ArrayList<>();
        double now;
        @Override public double attackSpeed() { return 4; }
        @Override public void moveStarted(MoveRunner r) {}
        @Override public void contact(MoveRunner r) { contacts.add(now); moves.add(r.move().name()); }
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
            var clips = CombatClip.parseFile(LongswordMotionTest.combat("animations/ryuka_" + view + ".animation"), warnings);
            assertEquals(List.of(), warnings);
            var json = animations(view);
            for (var move : set.moves().values()) {
                var clip = clips.get(move.clip());
                assertNotNull(clip, view + " 缺少动作 " + move.clip());
                assertEquals(move.length(), clip.length(), 1e-6, move.name());
                for (var bone : clip.bones())
                    assertTrue(CombatBones.REQUIRED.contains(bone) || bone.equals(CombatBones.WEAPON) || bone.equals(CombatBones.SHEATH), bone);
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

    @Test void fiveFastCutsWithHoldsAndTheFinisherReleasesTheWave() throws Exception {
        var set = moveset();
        // 不随攻速缩放：轻刀的节奏是固定的（快出刀、在终点定住）
        for (var name : List.of("slash_1", "slash_2", "slash_3", "slash_4", "slash_5")) assertFalse(set.move(name).scalesWithAttackSpeed(), name);
        assertEquals("sakura_wave", set.move("slash_5").effect());
        assertEquals(180, set.move("slash_4").cone(), 1e-9, "逆胴横扫打身前半圈");
        var s = new Sim(set).until(20);
        assertEquals("idle", s.move());
        assertTrue(s.input(Moveset.Input.ATTACK));
        // 一直按着左键：每刻都送一次，取消点到了就接下一刀
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            s.input(Moveset.Input.ATTACK);
            if (seen.isEmpty() || !seen.getLast().equals(s.move())) seen.add(s.move());
            s.until(s.now + 1);
        }
        assertEquals(List.of("slash_1", "slash_2", "slash_3", "slash_4", "slash_5", "slash_1"), seen.subList(0, 6));
        assertEquals(List.of("slash_1", "slash_2", "slash_3", "slash_4", "slash_5"), s.actor.moves.subList(0, 5));
        // 第一刀 0.1 秒（2 刻）就砍到：挥刀极快
        assertEquals(22.0, s.actor.contacts.getFirst(), 1e-9);
    }

    @Test void theParryWindowIsHalfASecond() throws Exception {
        var s = new Sim(moveset()).until(20);
        assertTrue(s.input(Moveset.Input.USE_PRESS)); assertEquals("guard", s.move());
        // 0.45 秒（9 刻）还在窗口里：打刀此时已经只能格挡
        s.until(29); assertTrue(s.input(Moveset.Input.BLOCK)); assertEquals("parry", s.move());
        s.until(31); assertTrue(s.input(Moveset.Input.ATTACK));
        s.until(33); assertEquals("riposte", s.move());
        s.until(36); assertEquals(1, s.actor.contacts.size(), "突刺命中一次");
        s.until(60); assertEquals("idle", s.move());
        // 0.55 秒（11 刻）之后只格挡
        assertTrue(s.input(Moveset.Input.USE_PRESS));
        s.until(71); assertFalse(s.input(Moveset.Input.BLOCK)); assertEquals("guard", s.move());
    }

    /** 贴图格子数：每个面按 UV 跨过几格切（{@code scale} 把 UV 换成 128 贴图的像素），和 Logos ItemSurface、WeaponDissolve 一样。 */
    private static int cells(com.google.gson.JsonArray cubes, String key, double scale) {
        int n = 0;
        for (var c : cubes) for (var face : c.getAsJsonObject().getAsJsonObject(key).entrySet()) {
            var v = face.getValue();
            var uv = (v.isJsonObject() ? v.getAsJsonObject().getAsJsonArray("uv") : v.getAsJsonArray());
            n += Math.max(1, (int) Math.round(Math.abs(uv.get(2).getAsDouble() - uv.get(0).getAsDouble()) * scale))
                    * Math.max(1, (int) Math.round(Math.abs(uv.get(3).getAsDouble() - uv.get(1).getAsDouble()) * scale));
        }
        return n;
    }

    @Test void meshBladeAndItemModel() throws Exception {
        var weapon = LongswordMotionTest.combat("weapons/ryuka");
        assertEquals(1.25, weapon.get("scale").getAsDouble(), 1e-9);
        assertTrue(weapon.get("alpha").getAsDouble() < 1, "晶刃略透明");
        assertEquals("logos_dimension:textures/item/ryuka_glow.png", weapon.get("glow").getAsString());
        var cubes = weapon.getAsJsonArray("cubes");
        // 2026-11-08 精简：原 496 块拿出来就严重掉帧；和神狐打刀（98 块）相当，最多多三成
        assertTrue(cubes.size() <= 127, "网格 " + cubes.size() + " 块");
        int blade = 0;
        double tip = -1e9;
        for (var c : cubes) {
            var cube = c.getAsJsonObject();
            assertEquals("weapon", cube.get("part").getAsString());
            if (cube.has("fade") && cube.get("fade").getAsBoolean()) {
                blade++;
                assertTrue(cube.get("name").getAsString().startsWith("blade_"), cube.get("name").getAsString());
                tip = Math.max(tip, cube.getAsJsonArray("end").get(1).getAsDouble());
            } else assertFalse(cube.get("name").getAsString().matches("blade_\\d+_.*"), "刀身方块都要标 fade");
        }
        assertTrue(blade >= 30 && blade <= 66, "刀身 " + blade + " 块（三层 × 十几段）");
        assertEquals(18, tip, .1, "刀身长 18（打刀 16）");
        // 手持、掉落与魔力缠绕用的立体物品模型 = 网格 + (8, 1, 8)
        var model = LongswordMotionTest.read("/assets/logos_dimension/models/item/ryuka.json");
        var elements = model.getAsJsonArray("elements");
        assertEquals(cubes.size(), elements.size());
        // Logos 表面火焰按物品模型的贴图格子每格画一片，附属的凝聚、消散按战斗网格的贴图格子切片：都要和打刀一个量级
        assertTrue(cells(elements, "faces", 8) <= 2500, "表面火焰 " + cells(elements, "faces", 8) + " 片（打刀约 1400）");
        assertTrue(cells(cubes, "uv", 1) <= 12000, "凝聚、消散 " + cells(cubes, "uv", 1) + " 片");
        for (int i = 0; i < cubes.size(); i++) {
            var cube = cubes.get(i).getAsJsonObject(); var element = elements.get(i).getAsJsonObject();
            for (int a = 0; a < 3; a++) {
                assertEquals(cube.getAsJsonArray("start").get(a).getAsDouble() + (a == 1 ? 1 : 8), element.getAsJsonArray("from").get(a).getAsDouble(), 1e-3);
                assertEquals(cube.getAsJsonArray("end").get(a).getAsDouble() + (a == 1 ? 1 : 8), element.getAsJsonArray("to").get(a).getAsDouble(), 1e-3);
            }
        }
    }

    /**
     * 平一文字の構え（docs/RYUKA.md 第 3.3 节）：格挡中左键进构え；蓄不到 0.3 秒松开回到格挡，蓄够了松开突进，突进完上挑（砍一下）再回待机；
     * 构え里挨打用构え的格挡，之后留在构え，这时松开立即突进（格挡动作里松开的先记着，0.12 秒再走）。
     */
    @Test void stanceChargesThenDashesIntoTheRisingCut() throws Exception {
        var set = moveset();
        assertEquals("ryuka_rise", set.move("rise").effect());
        assertFalse(set.move("rise").knockback(), "上挑不击退：挑飞或处决接手");
        var s = new Sim(set).until(20);
        assertTrue(s.input(Moveset.Input.USE_PRESS));
        assertEquals("guard", s.move());
        assertTrue(s.input(Moveset.Input.ATTACK));
        assertEquals("stance", s.move());
        s.until(s.now + 3);
        assertTrue(s.input(Moveset.Input.ATTACK_RELEASE));
        assertEquals("guard", s.move(), "蓄不到 0.3 秒松开");
        assertTrue(s.input(Moveset.Input.ATTACK));
        s.until(s.now + 6);
        assertTrue(s.input(Moveset.Input.ATTACK_RELEASE));
        assertEquals("dash", s.move());
        int contacts = s.actor.contacts.size();
        s.until(s.now + 7);
        assertEquals("rise", s.move(), "突进 0.3 秒后接上挑");
        s.until(s.now + 3);
        assertEquals(contacts + 1, s.actor.contacts.size(), "上挑砍一下");
        s.until(s.now + 12);
        assertEquals("idle", s.move());

        // 构え里挨打
        s.input(Moveset.Input.USE_PRESS);
        s.input(Moveset.Input.ATTACK);
        assertTrue(s.input(Moveset.Input.BLOCK));
        assertEquals("stance_block", s.move());
        assertTrue(s.input(Moveset.Input.ATTACK_RELEASE), "格挡动作里松开要记着");
        assertEquals("stance_block", s.move());
        s.until(s.now + 3);
        assertEquals("dash", s.move(), "0.12 秒时接上突进");

        s.until(s.now + 40);
        s.input(Moveset.Input.USE_PRESS);
        s.input(Moveset.Input.ATTACK);
        s.input(Moveset.Input.BLOCK);
        s.until(s.now + 8);
        assertEquals("stance_hold", s.move(), "构え的格挡之后回到构え");
        assertTrue(s.input(Moveset.Input.ATTACK_RELEASE));
        assertEquals("dash", s.move(), "已经蓄过了，松开就突进");
        // 处决的两个招式在：一个循环，一个收尾回待机
        assertEquals(Moveset.Playback.LOOP, set.move("execution").playback());
        assertEquals("idle", set.move("execution_end").next());
    }
}
