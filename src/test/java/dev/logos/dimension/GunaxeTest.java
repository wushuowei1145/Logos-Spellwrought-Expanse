package dev.logos.dimension;

import com.google.gson.JsonParser;
import dev.logos.dimension.combat.*;
import dev.logos.dimension.firearm.Buckshot;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 铳斧（docs/GUNAXE.md）与铳的招式规则（docs/FIREARMS.md）：两个视角的动作与招式文件一致（时长、接触、开火、装弹、取消点），
 * 第五下有弹开枪、空膛空响，瞄准开枪，换弹装到位，没东西可装时不换弹；物品模型与网格对齐（换弹用的备用转轮不进物品模型）；霰弹的衰减。
 */
class GunaxeTest {
    private static Moveset moveset() throws Exception { return Moveset.parse(LongswordMotionTest.read("/data/logos_dimension/combat/movesets/gunaxe.json")); }

    private static final class Actor implements MoveRunner.Actor {
        final List<Double> contacts = new ArrayList<>(), fires = new ArrayList<>(), feeds = new ArrayList<>();
        final Set<String> allowed = new HashSet<>(Set.of("loaded", "reloadable"));
        double now;
        @Override public double attackSpeed() { return 1.0; }
        @Override public void moveStarted(MoveRunner r) {}
        @Override public void contact(MoveRunner r) { contacts.add(now); }
        @Override public void fire(MoveRunner r) { fires.add(now); }
        @Override public void feed(MoveRunner r) { feeds.add(now); }
        @Override public boolean allows(String requirement) { return allowed.contains(requirement); }
        void empty() { allowed.remove("loaded"); allowed.add("empty"); }
    }
    private static final class Sim {
        final Actor actor = new Actor(); final MoveRunner runner;
        double now;
        Sim(Moveset set) { runner = new MoveRunner(set, actor, 0); }
        Sim until(double tick) { while (now < tick) { now++; actor.now = now; runner.tick(now); } return this; }
        boolean input(Moveset.Input input) { return runner.input(input, now); }
        String move() { return runner.move().name(); }
        /** 按住左键直到离开当前招式（每刻补一次输入，在取消点接下一段）。 */
        void holdAttack() { var name = move(); for (int i = 0; i < 40 && move().equals(name); i++) { until(now + 1); input(Moveset.Input.ATTACK); } }
    }

    @Test void clipsMatchTheMovesetInBothViews() throws Exception {
        var set = moveset();
        var mesh = LongswordMotionTest.combat("weapons/gunaxe");
        Set<String> own = new HashSet<>();
        for (var b : mesh.getAsJsonArray("bones")) own.add(b.getAsJsonObject().get("name").getAsString());
        for (var view : new String[]{"fp", "tp"}) {
            List<String> warnings = new ArrayList<>();
            var clips = CombatClip.parseFile(LongswordMotionTest.combat("animations/gunaxe_" + view + ".animation"), warnings);
            assertEquals(List.of(), warnings);
            for (var move : set.moves().values()) {
                var clip = clips.get(move.clip());
                assertNotNull(clip, view + " 缺少动作 " + move.clip());
                assertEquals(move.length(), clip.length(), 1e-6, move.name());
                for (var bone : clip.bones())
                    assertTrue(CombatBones.REQUIRED.contains(bone) || bone.equals(CombatBones.WEAPON) || own.contains(bone), view + " " + bone);
                assertMarkers(move.contacts(), clip.markers("contact"), view + " " + move.name() + " contact");
                assertMarkers(move.fires(), clip.markers("fire"), view + " " + move.name() + " fire");
                assertMarkers(move.feeds() ? List.of(move.feed()) : List.of(), clip.markers("feed"), view + " " + move.name() + " feed");
                // 取消点：左键（没有左键时换弹）缓冲的转移在动作里标的 cancel 那一刻接上。
                var cancelled = move.on().stream().filter(t -> !t.atEnd() && t.input() == Moveset.Input.ATTACK).findFirst()
                        .or(() -> move.on().stream().filter(t -> !t.atEnd()).findFirst());
                if (cancelled.isPresent()) {
                    float[] cancel = clip.markers("cancel");
                    assertEquals(1, cancel.length, view + " " + move.name());
                    assertEquals(cancelled.get().at(), cancel[0], 1e-6, view + " " + move.name() + " 的 cancel");
                }
            }
        }
    }

    private static void assertMarkers(List<Double> expected, float[] actual, String what) {
        assertEquals(expected.size(), actual.length, what + " 个数");
        for (int i = 0; i < actual.length; i++) assertEquals(expected.get(i), actual[i], 1e-6, what + " 第 " + i + " 个");
    }

    @Test void theFifthHitFiresWhenLoaded() throws Exception {
        var s = new Sim(moveset()).until(5);
        assertTrue(s.input(Moveset.Input.ATTACK));
        for (var name : List.of("attack_1", "attack_2", "attack_3", "attack_4")) { assertEquals(name, s.move()); s.holdAttack(); }
        assertEquals("shot_5", s.move());
        double start = s.runner.start();
        s.until(start + 2.5);
        // 0.06 秒（1.2 刻）之后的第一刻扣扳机；第五下从取消点接上，开始时刻不一定是整刻。
        assertEquals(1, s.actor.fires.size(), "第五下扣一次扳机");
        double at = s.actor.fires.getFirst() - start;
        assertTrue(at >= 1.2 - 1e-6 && at < 2.2, "扣扳机在 0.06 秒：" + at);
        assertEquals(4, s.actor.contacts.size());
    }

    @Test void theFifthHitOnlyClicksWhenEmpty() throws Exception {
        var s = new Sim(moveset()).until(5);
        s.actor.empty();
        assertTrue(s.input(Moveset.Input.ATTACK));
        for (int i = 0; i < 4; i++) s.holdAttack();
        assertEquals("dry_5", s.move(), "空膛时第五下只扣扳机空响");
        s.until(s.runner.start() + 2);
        assertEquals(1, s.actor.fires.size(), "空膛也扣一次扳机（服务端只放咔哒声并自动换弹）");
    }

    @Test void aimingFiresAndReleasingLowersTheGun() throws Exception {
        var s = new Sim(moveset()).until(5);
        assertTrue(s.input(Moveset.Input.USE_PRESS)); assertEquals("aim", s.move());
        assertTrue(s.input(Moveset.Input.ATTACK)); assertEquals("aim_shot", s.move());
        s.until(s.now + 1); assertEquals(1, s.actor.fires.size(), "0.03 秒扣扳机");
        // 开枪后 0.6 秒回到瞄准；空膛时左键只空响。
        s.until(s.now + 12); assertEquals("aim", s.move());
        s.actor.empty();
        assertTrue(s.input(Moveset.Input.ATTACK)); assertEquals("aim_dry", s.move());
        s.until(s.now + 8); assertTrue(s.input(Moveset.Input.USE_RELEASE)); assertEquals("idle", s.move());
    }

    @Test void reloadFeedsAtOnePointThreeSeconds() throws Exception {
        var s = new Sim(moveset()).until(5);
        assertTrue(s.input(Moveset.Input.RELOAD)); assertEquals("reload", s.move());
        double start = s.runner.start();
        s.until(start + 25); assertEquals(List.of(), s.actor.feeds);
        s.until(start + 26); assertEquals(List.of(start + 26), s.actor.feeds, "1.3 秒新转轮到位");
        s.until(start + 48); assertEquals("idle", s.move());
    }

    @Test void nothingToLoadMeansNoReload() throws Exception {
        var s = new Sim(moveset()).until(5);
        s.actor.allowed.remove("reloadable");
        assertFalse(s.input(Moveset.Input.RELOAD));
        assertEquals("idle", s.move());
    }

    @Test void reloadAfterTheLastShotWaitsForTheCancelPoint() throws Exception {
        var s = new Sim(moveset()).until(5);
        assertTrue(s.input(Moveset.Input.USE_PRESS));
        assertTrue(s.input(Moveset.Input.ATTACK));
        double start = s.runner.start();
        // 打空最后一发时服务端自动给出换弹：缓冲到取消点（0.45 秒，第 9 刻）再换。
        s.until(start + 1); assertTrue(s.input(Moveset.Input.RELOAD)); assertEquals("aim_shot", s.move());
        s.until(start + 9); assertEquals("reload", s.move());
        assertEquals(start + 9, s.runner.start(), 1e-6);
    }

    @Test void requirementsAndGunTimesAreValidated() {
        var tooLate = JsonParser.parseString("""
                {"start": "a", "moves": {"a": {"length": 0.5, "fire": 0.6, "next": "a"}}}""").getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> Moveset.parse(tooLate));
        var cancelBeforeFire = JsonParser.parseString("""
                {"start": "a", "moves": {"a": {"length": 0.5, "fire": 0.3, "next": "a",
                  "on": [{"input": "attack", "to": "a", "queue": true, "at": 0.2}]}}}""").getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> Moveset.parse(cancelBeforeFire));
        var ok = Moveset.parse(JsonParser.parseString("""
                {"start": "a", "moves": {"a": {"length": 0.5, "fire": [0.1, 0.2], "feed": 0.3, "next": "a",
                  "on": [{"input": "reload", "to": "a", "require": "reloadable"}]}}}""").getAsJsonObject());
        assertEquals(List.of(0.1, 0.2), ok.move("a").fires());
        assertEquals(0.3, ok.move("a").feed(), 1e-9);
        assertEquals("reloadable", ok.move("a").on().getFirst().require());
    }

    @Test void itemModelIsTheMeshOffsetWithoutTheSpareCylinder() throws Exception {
        var weapon = LongswordMotionTest.combat("weapons/gunaxe");
        assertEquals("logos_dimension:textures/item/gunaxe_rig.png", weapon.get("texture").getAsString());
        assertNotNull(GunaxeTest.class.getResource("/assets/logos_dimension/textures/item/gunaxe_rig.png"));
        assertNotNull(GunaxeTest.class.getResource("/assets/logos_dimension/textures/item/buckshot_shell.png"));
        var model = LongswordMotionTest.read("/assets/logos_dimension/models/item/gunaxe.json");
        List<com.google.gson.JsonObject> cubes = new ArrayList<>();
        int spare = 0;
        for (var c : weapon.getAsJsonArray("cubes")) {
            var cube = c.getAsJsonObject();
            if (cube.get("bone").getAsString().startsWith("Spare")) spare++; else cubes.add(cube);
        }
        assertTrue(spare > 0, "换弹用的备用转轮在网格里");
        var elements = model.getAsJsonArray("elements");
        assertEquals(cubes.size(), elements.size(), "物品模型不含备用转轮");
        int[] offset = {8, 1, 8};
        for (int c = 0; c < cubes.size(); c++) {
            var start = cubes.get(c).getAsJsonArray("start");
            var from = elements.get(c).getAsJsonObject().getAsJsonArray("from");
            for (int i = 0; i < 3; i++) assertEquals(start.get(i).getAsDouble() + offset[i], from.get(i).getAsDouble(), 1e-3);
        }
        // 六发子弹各一根骨骼，挂在转轮下；换弹的备用转轮同样六根。
        Set<String> bones = new HashSet<>();
        for (var b : weapon.getAsJsonArray("bones")) bones.add(b.getAsJsonObject().get("name").getAsString());
        for (int k = 0; k < 6; k++) { assertTrue(bones.contains("Round" + k)); assertTrue(bones.contains("SpareRound" + k)); }
        assertTrue(bones.containsAll(List.of("Cylinder", "SpareCylinder", "Hammer", "Trigger", "MuzzleLocator")));
    }

    @Test void buckshotFallsOffWithDistance() {
        assertEquals(1, Buckshot.falloff(0), 1e-9);
        assertEquals(1, Buckshot.falloff(Buckshot.FULL), 1e-9);
        assertEquals(Buckshot.FAR, Buckshot.falloff(Buckshot.RANGE), 1e-9);
        assertEquals(Buckshot.FAR, Buckshot.falloff(Buckshot.RANGE * 2), 1e-9);
        assertTrue(Buckshot.falloff(12) < 1 && Buckshot.falloff(12) > Buckshot.falloff(20));
        // 贴脸八颗全中的伤害远高于铁剑（6）。
        assertTrue(Buckshot.PELLETS * Buckshot.PELLET_DAMAGE >= 18);
    }
}
