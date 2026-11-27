package dev.logos.dimension;

import com.google.gson.JsonObject;
import dev.logos.dimension.combat.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 炽焰长剑：两个视角的动作与招式文件一致性（时长、骨骼规范、多段接触/取消点与防翻面）、
 * 四段大开大合连招、托脊格挡弹反、格挡横扫火浪技能、潜行突刺、疾跑旋风斩、空中坠星破与十文字连斩，
 * 以及立体模型坐标对齐。
 */
class CrimsonFlameLongswordTest {
    private static Moveset moveset() throws Exception {
        return Moveset.parse(LongswordMotionTest.read("/data/logos_dimension/combat/movesets/crimson_flame_longsword.json"));
    }

    private static JsonObject animations(String view) throws Exception {
        return LongswordMotionTest.combat("animations/crimson_flame_longsword_" + view + ".animation").getAsJsonObject("animations");
    }

    private static final class Actor implements MoveRunner.Actor {
        final List<Double> contacts = new ArrayList<>();
        final List<String> moves = new ArrayList<>();
        final Set<String> allowed = new HashSet<>();
        double now;

        @Override public double attackSpeed() { return 1.5; }
        @Override public void moveStarted(MoveRunner r) { if (!r.cleared()) moves.add(r.move().name()); }
        @Override public void contact(MoveRunner r) { contacts.add(now); }
        @Override public boolean allows(String requirement) { return allowed.contains(requirement); }
    }

    private static final class Sim {
        final Actor actor = new Actor();
        final MoveRunner runner;
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
            var clips = CombatClip.parseFile(LongswordMotionTest.combat("animations/crimson_flame_longsword_" + view + ".animation"), warnings);
            assertEquals(List.of(), warnings, view + " 动作解析告警");
            var json = animations(view);
            for (var move : set.moves().values()) {
                var clip = clips.get(move.clip());
                assertNotNull(clip, view + " 缺少动作 " + move.clip());
                assertEquals(move.length(), clip.length(), 1e-6, view + " " + move.name());
                for (var bone : clip.bones()) {
                    assertTrue(CombatBones.REQUIRED.contains(bone) || bone.equals(CombatBones.WEAPON), view + " " + move.name() + " 非法骨骼：" + bone);
                    if ("tp".equals(view)) {
                        assertNotEquals(CombatBones.ROOT, bone, "第三人称动作不应写 Root");
                        assertFalse(bone.contains("Leg") || bone.contains("Foot"), "第三人称剑术动作不应覆盖腿部骨骼：" + bone);
                    }
                }
                float[] contacts = clip.markers("contact");
                assertEquals(move.contacts().size(), contacts.length, view + " " + move.name() + " contact 数量");
                for (int i = 0; i < contacts.length; i++) {
                    assertEquals(move.contacts().get(i), contacts[i], 1e-6, view + " " + move.name() + " contact[" + i + "]");
                }
                for (var t : move.on()) if (t.input() == Moveset.Input.ATTACK && !t.atEnd()) {
                    float[] cancels = clip.markers("cancel");
                    assertEquals(1, cancels.length, view + " " + move.name() + " cancel");
                    assertEquals(t.at(), cancels[0], 1e-6, view + " " + move.name() + " cancel");
                }
            }
        }
    }

    @Test void bladeNeverFlipsBetweenKeysInEitherView() throws Exception {
        for (var view : new String[]{"fp", "tp"}) UchigatanaTest.assertWeaponNeverFlips(animations(view), "");
    }

    @Test void fourHitComboChainsWithLaunchAndSlam() throws Exception {
        var set = moveset();
        var s = new Sim(set);
        s.until(21); assertEquals("draw", s.move());
        s.until(22); assertEquals("idle", s.move());

        var names = List.of("cleave_1", "rend_2", "ascend_3", "cataclysm_4");
        assertTrue(s.input(Moveset.Input.ATTACK));
        for (var name : names) {
            assertEquals(name, s.move());
            var move = set.move(name);
            double start = s.runner.start();
            for (int i = 0; i < 40 && s.move().equals(name); i++) { s.until(s.now + 1); s.input(Moveset.Input.ATTACK); }
            var cancel = move.on().stream().filter(t -> t.input() == Moveset.Input.ATTACK).findFirst().orElseThrow().at();
            assertEquals(start + cancel * 20, s.runner.start(), 1e-6, name + " 在取消点接下一式");
        }
        assertEquals("cleave_1", s.move(), "四式之后可接回一式循环");
        assertEquals(4, s.actor.contacts.size());
        assertEquals(CombatEffects.LAUNCH, set.move("ascend_3").effect());
        assertEquals(CombatEffects.SLAM, set.move("cataclysm_4").effect());

        // 连招中右键可立即打断进入托脊格挡并清空连招记忆，松开回到待机后重新由一式起手。
        assertTrue(s.input(Moveset.Input.USE_PRESS));
        assertEquals("guard", s.move());
        assertTrue(s.input(Moveset.Input.USE_RELEASE));
        assertEquals("idle", s.move());
        assertTrue(s.input(Moveset.Input.ATTACK));
        assertEquals("cleave_1", s.move());
    }

    @Test void earlyBlockParriesIntoDragonFangWhileLateBlockHitsGuard() throws Exception {
        var set = moveset();
        var s = new Sim(set).until(25);
        assertEquals("idle", s.move());
        assertTrue(s.input(Moveset.Input.USE_PRESS));
        assertEquals("guard", s.move());

        // 超过 0.35s 弹反窗口后的格挡走 guard_hit，并在 0.34s 后回落 guard。
        s.until(33);
        assertTrue(s.input(Moveset.Input.BLOCK));
        assertEquals("guard_hit", s.move());
        s.until(40);
        assertEquals("guard", s.move());

        // 松开重起盾并在 0.35s 内挡下攻击 -> 逆焰弹反。
        assertTrue(s.input(Moveset.Input.USE_RELEASE));
        assertEquals("idle", s.move());
        s.until(42);
        assertTrue(s.input(Moveset.Input.USE_PRESS));
        assertEquals("guard", s.move());
        s.until(46);
        assertTrue(s.input(Moveset.Input.BLOCK));
        assertEquals("parry", s.move());
        assertEquals(CombatEffects.PARRY, set.move("parry").effect());

        // 弹反瞬间可预输入左键，在 0.12s（2.4 刻）接破军龙牙，0.18s（3.6 刻）命中，再接二式逆鳞反劈。
        s.until(47);
        assertTrue(s.input(Moveset.Input.ATTACK));
        assertEquals("parry", s.move());
        s.until(49);
        assertEquals("dragon_fang", s.move());
        assertEquals(46 + 0.12 * 20, s.runner.start(), 1e-9);
        s.until(52);
        assertEquals(1, s.actor.contacts.size());
        assertTrue(s.input(Moveset.Input.ATTACK));
        s.until(58);
        assertEquals("rend_2", s.move());
    }

    @Test void stanceAndGuardSkillsChainCleanly() throws Exception {
        var set = moveset();
        var s = new Sim(set).until(25);
        assertEquals("idle", s.move());

        // 1. 空中 + 左键 -> 炽翼坠星破（1 次重砸接触），0.8s 后可接一式
        s.actor.allowed.add("airborne");
        assertTrue(s.input(Moveset.Input.ATTACK));
        assertEquals("skyfall_judgement", s.move());
        // 落点把周围挑飞（CrimsonFlameSkills），招式本身不能带下砸：同一刻的 slam 会把刚挑起的目标砸回地面
        assertNotEquals(CombatEffects.SLAM, set.move("skyfall_judgement").effect());
        s.actor.allowed.clear();
        double airStart = s.runner.start();
        for (int i = 0; i < 20 && s.move().equals("skyfall_judgement"); i++) {
            s.until(s.now + 1);
            s.input(Moveset.Input.ATTACK);
        }
        assertEquals("cleave_1", s.move());
        assertEquals(airStart + 0.8 * 20, s.runner.start(), 1e-6);
        assertEquals(1, s.actor.contacts.size());

        // 2. 格挡中 + 左键 -> 立即施放红莲劫焰横扫，再于 0.68s 取消接逆鳞十文字斩（2 段接触） -> 三式
        assertTrue(s.input(Moveset.Input.USE_PRESS));
        assertEquals("guard", s.move());
        assertTrue(s.input(Moveset.Input.ATTACK));
        assertEquals("flame_sweep", s.move());
        double sweepStart = s.runner.start();
        for (int i = 0; i < 20 && s.move().equals("flame_sweep"); i++) {
            s.until(s.now + 1);
            s.input(Moveset.Input.ATTACK);
        }
        assertEquals("cross_rend", s.move());
        assertEquals(sweepStart + 0.68 * 20, s.runner.start(), 1e-6);
        assertEquals(2, s.actor.contacts.size());

        double crossStart = s.runner.start();
        for (int i = 0; i < 20 && s.move().equals("cross_rend"); i++) {
            s.until(s.now + 1);
            s.input(Moveset.Input.ATTACK);
        }
        assertEquals("ascend_3", s.move());
        assertEquals(crossStart + 0.68 * 20, s.runner.start(), 1e-6);
        assertEquals(4, s.actor.contacts.size(), "十文字斩打出两段接触");

        // 3. 潜行 + 左键 -> 破晓炎枪突刺 -> 0.46s 接炽焰旋风斩（2 段接触） -> 0.56s 接十文字斩
        assertTrue(s.input(Moveset.Input.USE_PRESS));
        assertTrue(s.input(Moveset.Input.USE_RELEASE));
        assertEquals("idle", s.move());
        s.actor.allowed.add("sneak");
        assertTrue(s.input(Moveset.Input.ATTACK));
        assertEquals("crimson_thrust", s.move());
        s.actor.allowed.clear();
        double thrustStart = s.runner.start();
        for (int i = 0; i < 15 && s.move().equals("crimson_thrust"); i++) {
            s.until(s.now + 1);
            s.input(Moveset.Input.ATTACK);
        }
        assertEquals("vortex_rush", s.move());
        assertEquals(thrustStart + 0.46 * 20, s.runner.start(), 1e-6);
        assertEquals(5, s.actor.contacts.size());

        double vortexStart = s.runner.start();
        for (int i = 0; i < 20 && s.move().equals("vortex_rush"); i++) {
            s.until(s.now + 1);
            s.input(Moveset.Input.ATTACK);
        }
        assertEquals("cross_rend", s.move());
        assertEquals(vortexStart + 0.56 * 20, s.runner.start(), 1e-6);
        assertEquals(7, s.actor.contacts.size(), "旋风突进打出两段接触");

        // 4. 疾跑 + 左键 -> 直接起手炽焰旋风斩
        assertTrue(s.input(Moveset.Input.USE_PRESS));
        assertTrue(s.input(Moveset.Input.USE_RELEASE));
        assertEquals("idle", s.move());
        s.actor.allowed.add("sprint");
        assertTrue(s.input(Moveset.Input.ATTACK));
        assertEquals("vortex_rush", s.move());
    }

    @Test void itemModelAndTexturesAlignWithCombatMesh() throws Exception {
        var weapon = LongswordMotionTest.combat("weapons/crimson_flame_longsword");
        assertEquals("logos_dimension:textures/item/crimson_flame_longsword_rig.png", weapon.get("texture").getAsString());
        assertEquals(1.0, weapon.get("scale").getAsDouble(), 1e-9);
        assertNotNull(CrimsonFlameLongswordTest.class.getResource("/assets/logos_dimension/textures/item/crimson_flame_longsword_rig.png"));
        assertNotNull(CrimsonFlameLongswordTest.class.getResource("/assets/logos_dimension/textures/item/crimson_flame_longsword.png"));

        var model = LongswordMotionTest.read("/assets/logos_dimension/models/item/crimson_flame_longsword.json");
        var cubes = weapon.getAsJsonArray("cubes");
        var elements = model.getAsJsonArray("elements");
        assertEquals(cubes.size(), elements.size());
        for (int c = 0; c < cubes.size(); c++) {
            var cube = cubes.get(c).getAsJsonObject();
            assertEquals("weapon", cube.get("part").getAsString());
            var from = elements.get(c).getAsJsonObject().getAsJsonArray("from");
            for (int i = 0; i < 3; i++) {
                double lo = Math.min(cube.getAsJsonArray("start").get(i).getAsDouble(), cube.getAsJsonArray("end").get(i).getAsDouble());
                double val = lo + new int[]{8, 1, 8}[i];
                assertEquals(val, from.get(i).getAsDouble(), 1e-3, cube.get("name").getAsString());
                assertTrue(from.get(i).getAsDouble() >= -16 && from.get(i).getAsDouble() <= 32, "原版模型坐标范围");
            }
        }
    }
}
