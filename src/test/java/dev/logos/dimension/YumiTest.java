package dev.logos.dimension;

import com.google.gson.JsonObject;
import dev.logos.dimension.combat.*;
import dev.logos.dimension.item.YumiItem;
import java.util.*;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 神狐和弓：两个视角的动作与招式文件一致，射法的逐刻时间轴，四套配色共用的弓网格与骨骼，以及箭何时搭在弦上。 */
class YumiTest {
    private static Moveset moveset() throws Exception { return Moveset.parse(LongswordMotionTest.read("/data/logos_dimension/combat/movesets/yumi.json")); }
    private static Map<String, CombatClip> clips(String view) throws Exception {
        List<String> warnings = new ArrayList<>();
        var clips = CombatClip.parseFile(LongswordMotionTest.combat("animations/yumi_" + view + ".animation"), warnings);
        assertEquals(List.of(), warnings);
        return clips;
    }
    private static Set<String> bowBones() throws Exception {
        Set<String> out = new HashSet<>();
        for (var b : LongswordMotionTest.combat("weapons/fox_yumi_bamboo").getAsJsonArray("bones")) out.add(b.getAsJsonObject().get("name").getAsString());
        return out;
    }

    private static final class Sim {
        final MoveRunner runner;
        double now;
        Sim(Moveset set) {
            runner = new MoveRunner(set, new MoveRunner.Actor() {
                @Override public double attackSpeed() { return 4; }
                @Override public void moveStarted(MoveRunner r) {}
                @Override public void contact(MoveRunner r) {}
            }, 0);
        }
        Sim until(double tick) { while (now < tick) { now++; runner.tick(now); } return this; }
        boolean input(Moveset.Input input) { return runner.input(input, now); }
        String move() { return runner.move().name(); }
    }

    @Test void clipsMatchTheMovesetInBothViews() throws Exception {
        var set = moveset();
        var bow = bowBones();
        for (var view : new String[]{"fp", "tp"}) {
            var clips = clips(view);
            for (var move : set.moves().values()) {
                var clip = clips.get(move.clip());
                assertNotNull(clip, view + " 缺少动作 " + move.clip());
                if (move.playback() != Moveset.Playback.REVERSE) assertEquals(move.length(), clip.length(), 1e-6, move.name());
                for (var bone : clip.bones())
                    assertTrue(CombatBones.REQUIRED.contains(bone) || bone.equals(CombatBones.WEAPON) || bow.contains(bone), view + " " + bone);
            }
        }
    }

    @Test void drawTimingMatchesTheItem() throws Exception {
        var set = moveset();
        // 物品按按住的刻数算力度：举弓那几刻不放箭，之后在引分け的时长里拉满。
        assertEquals(YumiItem.RAISE_TICKS, set.move("uchiokoshi").length() * 20, 1e-9);
        assertEquals(YumiItem.DRAW_TICKS, set.move("hikiwake").length() * 20, 1e-9);
    }

    @Test void raiseCancelsDrawHoldsAndReleaseFollowsThrough() throws Exception {
        var s = new Sim(moveset());
        s.until(10); assertEquals("idle", s.move());
        // 举弓中松手：倒放回执弓。
        assertTrue(s.input(Moveset.Input.USE_PRESS)); assertEquals("uchiokoshi", s.move());
        s.until(14); assertTrue(s.input(Moveset.Input.USE_RELEASE)); assertEquals("lower", s.move());
        s.until(19); assertEquals("idle", s.move());
        // 完整射法：0.45 秒举弓、0.6 秒引分け，然后一直持满。
        assertTrue(s.input(Moveset.Input.USE_PRESS));
        s.until(27); assertEquals("uchiokoshi", s.move());
        s.until(28); assertEquals("hikiwake", s.move());
        s.until(39); assertEquals("hikiwake", s.move());
        s.until(40); assertEquals("kai", s.move());
        s.until(120); assertEquals("kai", s.move());
        assertTrue(s.input(Moveset.Input.USE_RELEASE)); assertEquals("hanare", s.move());
        // 残心没收完不能再拉，0.75 秒后可以接下一箭；不按就播完回到执弓。
        s.until(130); assertFalse(s.input(Moveset.Input.USE_PRESS));
        s.until(136); assertTrue(s.input(Moveset.Input.USE_PRESS)); assertEquals("uchiokoshi", s.move());
        assertTrue(s.input(Moveset.Input.USE_RELEASE));
        s.until(200); assertEquals("idle", s.move());
    }

    @Test void allFourFinishesShareTheBowRig() throws Exception {
        List<Integer> counts = new ArrayList<>();
        Set<String> names = null;
        for (var finish : new String[]{"bamboo", "vermilion", "indigo", "chestnut"}) {
            var weapon = LongswordMotionTest.combat("weapons/fox_yumi_" + finish);
            assertEquals("logos_dimension:textures/item/fox_yumi_" + finish + "_rig.png", weapon.get("texture").getAsString());
            assertEquals(1.25, weapon.get("scale").getAsDouble(), 1e-9);
            assertNotNull(YumiTest.class.getResource("/assets/logos_dimension/textures/item/fox_yumi_" + finish + "_rig.png"));
            assertNotNull(YumiTest.class.getResource("/assets/logos_dimension/textures/item/fox_yumi_" + finish + ".png"));
            // 骨骼都挂在 Weapon 之下，每个方块都绑在一根存在的骨骼上。
            Map<String, String> parents = new HashMap<>();
            for (var b : weapon.getAsJsonArray("bones")) parents.put(b.getAsJsonObject().get("name").getAsString(), b.getAsJsonObject().get("parent").getAsString());
            for (var bone : parents.keySet()) {
                String p = bone; int guard = 0;
                while (!p.equals(CombatBones.WEAPON) && guard++ < 64) p = Objects.requireNonNull(parents.get(p), bone + " 的父级不在弓的骨骼里");
                assertEquals(CombatBones.WEAPON, p);
            }
            for (var cube : weapon.getAsJsonArray("cubes")) assertTrue(parents.containsKey(cube.getAsJsonObject().get("bone").getAsString()));
            var attachment = weapon.getAsJsonArray("attachments").get(0).getAsJsonObject();
            assertEquals("yumi_arrow", attachment.get("mesh").getAsString());
            assertTrue(parents.containsKey(attachment.get("bone").getAsString()));
            counts.add(weapon.getAsJsonArray("cubes").size());
            if (names == null) names = parents.keySet(); else assertEquals(names, parents.keySet());
        }
        assertEquals(1, new HashSet<>(counts).size());
        var arrow = LongswordMotionTest.combat("weapons/yumi_arrow");
        assertNotNull(YumiTest.class.getResource("/assets/logos_dimension/textures/entity/projectiles/yumi_arrow.png"));
        assertTrue(arrow.get("length").getAsDouble() > 15);
    }

    @Test void theArrowIsOnTheStringOnlyWhileDrawing() throws Exception {
        for (var view : new String[]{"fp", "tp"}) {
            var clips = clips(view);
            // 执弓时没有箭；拉满时箭在弦上；弓身弯到两段弦保持原长（弦不伸缩）；放箭后箭立刻不见。
            assertEquals(0, scale(clips.get("idle"), "BowArrow", 1.0f).z, 1e-6, view);
            assertTrue(scale(clips.get("kai"), "BowArrow", 0.5f).z > 0.5f, view);
            // 第一人称按镜头取景、拉得比下段弦还长，只查第三人称。
            if (view.equals("tp")) {
                assertEquals(1, scale(clips.get("kai"), "BowStringUpper", 0f).y, 0.03, view);
                assertEquals(1, scale(clips.get("kai"), "BowStringLower", 0f).y, 0.03, view);
            }
            assertEquals(0, scale(clips.get("hanare"), "BowArrow", 0.1f).z, 1e-6, view);
        }
        // 第三人称拉弓时整个人侧身对靶（Root 只带转身）。
        var kai = clips("tp").get("kai");
        var turn = new Vector3f();
        kai.sample(CombatBones.ROOT, CombatClip.Channel.ROTATION, 0.5f, turn);
        assertEquals(-90, turn.y, 0.5);
    }
    private static Vector3f scale(CombatClip clip, String bone, float t) {
        var out = new Vector3f(1);
        clip.sample(bone, CombatClip.Channel.SCALE, t, out);
        return out;
    }
}
