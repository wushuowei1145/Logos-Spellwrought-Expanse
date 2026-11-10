package dev.logos.dimension;

import com.google.gson.JsonObject;
import dev.logos.dimension.combat.Moveset;
import dev.logos.dimension.combat.Movesets;
import dev.logos.dimension.jinghong.SwordFlight;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 惊鸿：共用凌霄的动作，所以骨骼、握点、剑长要和凌霄一致；精度压低；连招里走不到御剑。 */
class JinghongTest {
    private static JsonObject mesh(String name) throws Exception { return LongswordMotionTest.read("/assets/logos_dimension/combat/weapons/" + name + ".json"); }
    private static JsonObject moveset() throws Exception {
        var child = LongswordMotionTest.read("/data/logos_dimension/combat/movesets/jinghong.json");
        assertEquals("logos_dimension:lingxiao", child.get("extends").getAsString());
        return Movesets.inherit(LongswordMotionTest.read("/data/logos_dimension/combat/movesets/lingxiao.json"), child);
    }

    @Test void sharesLingxiaosBonesGripAndReach() throws Exception {
        var mine = mesh("jinghong_sword"); var lingxiao = mesh("lingxiao_sword");
        Set<String> a = new TreeSet<>(), b = new TreeSet<>();
        for (var e : mine.getAsJsonArray("bones")) a.add(e.getAsJsonObject().get("name").getAsString() + "<" + e.getAsJsonObject().get("parent").getAsString());
        for (var e : lingxiao.getAsJsonArray("bones")) b.add(e.getAsJsonObject().get("name").getAsString() + "<" + e.getAsJsonObject().get("parent").getAsString());
        assertEquals(b, a, "骨骼同名同层级，凌霄的动作才能直接用");
        assertEquals(mine.get("grip"), lingxiao.get("grip"));
        double tip = mine.getAsJsonArray("tip").get(1).getAsDouble();
        assertEquals(lingxiao.getAsJsonArray("tip").get(1).getAsDouble(), tip, 0.3, "剑长与凌霄相差不到 0.3，刀光和取景照用");
        var trail = LongswordMotionTest.read("/assets/logos_dimension/combat/trails/lingxiao.json").getAsJsonObject("blade");
        assertTrue(trail.get("to").getAsDouble() <= tip + 0.05, "刀光不超出剑尖");
    }

    @Test void lowerDetailThanLingxiao() throws Exception {
        var mine = mesh("jinghong_sword");
        assertTrue(mine.getAsJsonArray("cubes").size() <= 20, "五把同时用，方块要少");
        assertEquals(64, mine.getAsJsonArray("texture_size").get(0).getAsInt());
        assertTrue(mesh("lingxiao_sword").getAsJsonArray("cubes").size() >= 2 * mine.getAsJsonArray("cubes").size());
    }

    @Test void itemModelIsTheMeshOffset() throws Exception {
        var mesh = mesh("jinghong_sword");
        var model = LongswordMotionTest.read("/assets/logos_dimension/models/item/jinghong_sword.json");
        var byName = new HashMap<String, JsonObject>();
        for (var e : model.getAsJsonArray("elements")) byName.put(e.getAsJsonObject().get("name").getAsString(), e.getAsJsonObject());
        int weapon = 0;
        double[] offset = {8, 1, 8};
        for (var e : mesh.getAsJsonArray("cubes")) {
            var c = e.getAsJsonObject();
            if (!c.get("part").getAsString().equals("weapon")) continue;
            weapon++;
            var element = byName.get(c.get("name").getAsString());
            assertNotNull(element, c.get("name").getAsString());
            for (int i = 0; i < 3; i++) {
                assertEquals(c.getAsJsonArray("start").get(i).getAsDouble() + offset[i], element.getAsJsonArray("from").get(i).getAsDouble(), 1e-4);
                assertEquals(c.getAsJsonArray("end").get(i).getAsDouble() + offset[i], element.getAsJsonArray("to").get(i).getAsDouble(), 1e-4);
            }
        }
        assertEquals(weapon, byName.size(), "物品模型不含剑鞘");
    }

    @Test void comboWithoutFlight() throws Exception {
        var json = moveset();
        var moves = json.getAsJsonObject("moves");
        Deque<String> todo = new ArrayDeque<>(List.of(json.get("start").getAsString(), json.get("sheathe").getAsString()));
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            var name = todo.pop();
            if (name.startsWith("$") || !seen.add(name)) continue; // $clear 这类是特殊去向，不是招式
            var move = moves.getAsJsonObject(name);
            assertNotNull(move, name);
            for (var key : List.of("next", "combo_next")) if (move.has(key)) todo.push(move.get(key).getAsString());
            if (move.has("on")) for (var on : move.getAsJsonArray("on")) todo.push(on.getAsJsonObject().get("to").getAsString());
        }
        assertTrue(seen.containsAll(List.of("attack_1", "attack_2", "attack_3", "attack_4", "guard", "parry", "throw")), seen.toString());
        assertTrue(seen.stream().noneMatch(m -> m.startsWith("flight")), "惊鸿不能御剑：" + seen);
        Moveset.parse(json);
    }

    private static Map<String, dev.logos.dimension.combat.CombatClip> clips(String view) throws Exception {
        List<String> warnings = new ArrayList<>();
        var clips = dev.logos.dimension.combat.CombatClip.parseFile(
                LongswordMotionTest.read("/assets/logos_dimension/combat/animations/jinghong_sword_" + view + ".animation.json"), warnings);
        assertTrue(warnings.isEmpty(), warnings.toString());
        return clips;
    }

    /** 格挡开头 0.3 秒弹剑；弹剑、掷剑能接；掷剑的出手就是招式的接触时刻（服务端在这一刻结算掷剑）。 */
    @Test void guardParryAndThrowMatchTheMoveset() throws Exception {
        var set = Moveset.parse(moveset());
        var guard = set.move("guard");
        var parry = guard.on().stream().filter(t -> t.input() == Moveset.Input.BLOCK).findFirst().orElseThrow();
        assertEquals("parry", parry.to());
        assertEquals(0, parry.from(), 1e-9); assertEquals(0.3, parry.until(), 1e-9);
        assertEquals("parry", set.move("parry").effect());
        assertTrue(guard.on().stream().anyMatch(t -> t.input() == Moveset.Input.ATTACK && t.to().equals("throw")), "格挡中按左键要掷剑");
        for (int i = 1; i <= 4; i++)
            assertTrue(set.move("attack_" + i).on().stream().anyMatch(t -> t.input() == Moveset.Input.USE_PRESS && t.to().equals("guard")), "出招中要能转格挡");
        for (var view : List.of("tp", "fp")) {
            var clips = clips(view);
            for (var name : List.of("guard", "parry", "throw")) {
                assertNotNull(clips.get(name), view + " 缺动作 " + name);
                assertEquals(set.move(name).length(), clips.get(name).length(), 1e-3, view + " " + name + " 时长与招式不一致");
            }
            var t = clips.get("throw");
            assertEquals(set.move("throw").contact(), t.markers("release")[0], 1e-3, view + " 掷剑出手要等于招式的接触时刻");
            assertEquals(t.markers("contact")[0], t.markers("release")[0], 1e-3);
            // 飞剑回到手里的时刻对上动作的 catch
            assertEquals(t.markers("catch")[0], t.markers("release")[0] + dev.logos.dimension.jinghong.SwordFlight.THROW_HOME / 20, 1e-3,
                    view + " 飞剑回手与 catch 不同步");
        }
    }

    /** 剑阵时间轴：追击各段依次穿过目标，闪回在最后一段之后；万剑归宗先结环、虚影剑落完再贯下。 */
    @Test void swordFlightTimeline() {
        for (int passes = 1; passes <= 3; passes++) {
            for (int k = 1; k < passes; k++) assertTrue(SwordFlight.hit(k) > SwordFlight.hit(k - 1) + 2, "两段之间要留出绕到新方向的时间");
            assertTrue(SwordFlight.hit(0) > SwordFlight.RISE + 1, "升起之后要有赶到出击点的时间");
            assertTrue(SwordFlight.blink(passes) > SwordFlight.hit(passes - 1));
            assertEquals(SwordFlight.blink(passes) + SwordFlight.HOME, SwordFlight.duration(passes));
        }
        assertTrue(SwordFlight.THROW_HIT < SwordFlight.THROW_TURN && SwordFlight.THROW_TURN < SwordFlight.THROW_HOME);
        assertTrue(SwordFlight.FINALE_GATHER <= SwordFlight.phantomBorn(0));
        double lastPhantom = SwordFlight.phantomLands(SwordFlight.PHANTOMS - 1);
        assertTrue(lastPhantom <= SwordFlight.PLUNGE, "虚影剑落完之前就贯下了：" + lastPhantom);
        assertTrue(SwordFlight.phantomHit(0) >= SwordFlight.phantomLands(0) && SwordFlight.phantomHit(SwordFlight.PHANTOM_HITS - 1) <= lastPhantom,
                "虚影剑的伤害要在它们落下的时段里");
        assertTrue(SwordFlight.PLUNGE < SwordFlight.PLUNGE_HIT && SwordFlight.PLUNGE_HIT < SwordFlight.FINALE_BLINK
                && SwordFlight.FINALE_BLINK < SwordFlight.FINALE_HOME);
    }

    /** 手里剑身上的激活刻度：亮几段就是几把，刻度贴图逐段多亮一段，第五段时剑尖也亮。 */
    @Test void gaugeLightsOneMoreSegmentPerSword() throws Exception {
        int previous = 0;
        for (int n = 1; n <= SwordFlight.FAN; n++) {
            try (var in = JinghongTest.class.getResourceAsStream("/assets/logos_dimension/textures/item/jinghong_sword_gauge_" + n + ".png")) {
                assertNotNull(in, "缺刻度贴图 " + n);
                var image = javax.imageio.ImageIO.read(in);
                int lit = 0;
                for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) if ((image.getRGB(x, y) >>> 24) > 200) lit++;
                assertTrue(lit > previous, "第 " + n + " 段没有比上一张多亮");
                previous = lit;
            }
        }
        assertNotNull(JinghongTest.class.getResource("/assets/logos_dimension/textures/item/jinghong_sword_glow.png"));
    }
}
