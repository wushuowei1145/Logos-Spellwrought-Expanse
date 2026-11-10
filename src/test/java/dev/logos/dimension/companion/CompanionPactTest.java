package dev.logos.dimension.companion;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.logos.dimension.combat.Moveset;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 白梅同伴第二版的纯计算和数据（docs/SHIRAUME_COMPANION.md 第 8 节）：好感与契印的规矩、截弹射物的判断、
 * 招式文件和立契过场的时刻与模型第 8 版的动画一致。
 */
class CompanionPactTest {
    private static JsonObject read(String path) throws Exception {
        try (var in = CompanionPactTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static JsonObject animations() throws Exception {
        return read("/assets/logos_dimension/geckolib/animations/entity/shiraume_companion.animation.json").getAsJsonObject("animations");
    }

    /** 一天里头一顿全额、第二顿六成、之后一成五；喜欢的多；饿着的时候多五成。 */
    @Test void mealsFadeThroughTheDay() {
        assertEquals(CompanionBond.MEAL_FAVORITE, CompanionBond.meal(true, 0, 20), 1e-6);
        assertEquals(CompanionBond.MEAL_FAVORITE * .6f, CompanionBond.meal(true, 1, 20), 1e-6);
        assertEquals(CompanionBond.MEAL_FAVORITE * .15f, CompanionBond.meal(true, 5, 20), 1e-6);
        assertEquals(CompanionBond.MEAL_LIKED, CompanionBond.meal(false, 0, 20), 1e-6);
        assertEquals(CompanionBond.MEAL_LIKED * 1.5f, CompanionBond.meal(false, 0, CompanionBond.HUNGRY - 1), 1e-6);
        assertTrue(CompanionBond.meal(true, 0, 20) > CompanionBond.meal(false, 0, 0), "喜欢的哪怕不饿也比饿着吃普通的多");
    }

    @Test void onlyTheFirstPatsOfTheDayCount() {
        assertEquals(CompanionBond.PAT, CompanionBond.pat(0));
        assertEquals(CompanionBond.PAT, CompanionBond.pat(CompanionBond.PATS_PER_DAY - 1));
        assertEquals(0, CompanionBond.pat(CompanionBond.PATS_PER_DAY));
    }

    @Test void feedingFillsUpToFull() {
        assertEquals(CompanionBond.SATIETY_MAX, CompanionBond.fed(19, 4));
        assertEquals(1, CompanionBond.fed(0, 0), "再少也算一口");
    }

    @Test void affectionStages() {
        assertEquals(CompanionBond.Stage.STRANGER, CompanionBond.Stage.of(0));
        assertEquals(CompanionBond.Stage.FAMILIAR, CompanionBond.Stage.of(40));
        assertEquals(CompanionBond.Stage.CLOSE, CompanionBond.Stage.of(399));
        assertEquals(CompanionBond.Stage.BONDED, CompanionBond.Stage.of(5000));
    }

    @Test void theFirstSealUnlocksTheKatashiroGuard() {
        assertFalse(CompanionBond.unlocked(0, CompanionBond.GUARD_SEAL));
        assertTrue(CompanionBond.unlocked(1, CompanionBond.GUARD_SEAL));
        assertEquals(.35, CompanionStats.ratio(1), 1e-9, "每枚契印继承多 5%");
        assertEquals(CompanionStats.MAX_RATIO, CompanionStats.ratio(30), 1e-9);
    }

    /** 截弹射物：飞向主人、半秒内打中的才截；擦身而过的、太远的、快停下的不截。 */
    @Test void onlyShotsAboutToHitTheOwnerAreCaught() {
        AABB body = new AABB(-.3, 0, -.3, .3, 1.8, .3);
        Vec3 at = CompanionGuard.incoming(new Vec3(0, 1.5, -6), new Vec3(0, .1, 1.5), .05, body);
        assertNotNull(at, "正对着射来的箭");
        assertTrue(at.z < -.3, "截在打中之前: " + at);
        assertNull(CompanionGuard.incoming(new Vec3(3, 1.5, -6), new Vec3(0, .1, 1.5), .05, body), "旁边飞过去的");
        assertNull(CompanionGuard.incoming(new Vec3(0, 1.5, -40), new Vec3(0, .1, 1.5), .05, body), "还要飞二十几刻的");
        assertNull(CompanionGuard.incoming(new Vec3(0, 1, -1), new Vec3(0, 0, .1), 0, body), "快停下的");
        assertNotNull(CompanionGuard.incoming(new Vec3(0, 2.5, -8), new Vec3(0, -.1, 1.2), 0, body), "火球没有重力");
    }

    /** 招式文件里每一招的动作都在模型第 8 版里，接触时刻落在动作里面。 */
    @Test void everyBladeMoveHasItsClip() throws Exception {
        Moveset set = Moveset.parse(read("/data/logos_dimension/combat/movesets/shiraume_companion.json"));
        JsonObject anims = animations();
        for (Moveset.Move m : set.moves().values()) {
            String clip = CompanionBlade.PREFIX + m.clip();
            assertTrue(anims.has(clip), m.name() + " 缺动作 " + clip);
            double length = anims.getAsJsonObject(clip).get("animation_length").getAsDouble();
            for (double t : m.contacts()) assertTrue(t <= length + 1e-6, m.name() + " 的接触 " + t + " 超出动作 " + length);
        }
        assertNotNull(set.move(Moveset.PARRIED_NPC), "被弹反要有 NPC 的僵直");
        assertNotNull(set.move("deflect"));
        assertNotNull(set.move("throw_kiriage"), "处决的上挑");
    }

    /** 服务端按刻数做的事，和动画的时间标记对得上（模型 README「第 8 版」）。 */
    @Test void serverTimingsMatchTheClipMarkers() throws Exception {
        JsonObject anims = animations();
        Map<String, Integer> pact = Map.of("line1", ShiraumePact.LINE_1, "sword", ShiraumePact.SWORD, "present", ShiraumePact.PRESENT,
                "give_sword", ShiraumePact.GIVE_SWORD, "give_katashiro", ShiraumePact.GIVE_KATASHIRO, "stand", ShiraumePact.STAND);
        JsonObject timeline = anims.getAsJsonObject("pact").getAsJsonObject("timeline");
        for (var e : pact.entrySet()) assertEquals((long) e.getValue(), Math.round(marker(timeline, e.getKey()) * 20), e.getKey());
        assertEquals(ShiraumePact.END, Math.round(anims.getAsJsonObject("pact").get("animation_length").getAsDouble() * 20));
        JsonObject eat = anims.getAsJsonObject("eat");
        assertEquals(ShiraumeCompanion.EAT_TICKS, Math.round(eat.get("animation_length").getAsDouble() * 20));
        assertEquals(ShiraumeCompanion.EAT_SWALLOW, Math.round(marker(eat.getAsJsonObject("timeline"), "swallow") * 20));
        JsonObject pick = anims.getAsJsonObject("pick");
        assertEquals(ShiraumeCompanion.PICK_PLUCK, Math.round(marker(pick.getAsJsonObject("timeline"), "pluck") * 20));
        for (String name : new String[]{"eat", "pick", "refuse"}) {
            assertEquals((long) CompanionMood.LENGTH.get(name), Math.round(anims.getAsJsonObject(name).get("animation_length").getAsDouble() * 20), name);
        }
        for (String loop : new String[]{"pact_wait", "soak"}) assertTrue(anims.getAsJsonObject(loop).get("loop").getAsBoolean(), loop + " 要循环");
    }

    /**
     * 立契过场的两个机位：主人站在 {@link ShiraumePact#TOO_CLOSE} 格到伸手够得着的距离之间（再近会先被请退开），面朝她，
     * 从机位看她的胸口、头、递形代的手，视线都不穿过主人（人物模型连胳膊宽 1 格、厚 0.6 格、高 1.9 格）。
     * 旧的机位二在一格半处会被挡住。四个正方向朝向时身体的盒子和坐标轴对齐，量得准。
     */
    @Test void pactCamerasSeeHerPastTheOwner() {
        for (float yaw : new float[]{0, 90, 180, 270}) {
            Vec3 fwd = Vec3.directionFromRotation(0, yaw), side = new Vec3(-fwd.z, 0, fwd.x);
            for (double d = ShiraumePact.TOO_CLOSE; d <= 4.5; d += .05) {
                Vec3 near = fwd.scale(d - .3).add(side.scale(-.5)), far = fwd.scale(d + .3).add(side.scale(.5)).add(0, 1.9, 0);
                AABB owner = new AABB(near, far);
                for (double[][] shot : new double[][][]{{ShiraumePact.SHOT_A, ShiraumePact.LOOK_A}, {ShiraumePact.SHOT_B, ShiraumePact.LOOK_B}}) {
                    Vec3 eye = ShiraumePact.local(shot[0], fwd);
                    assertFalse(owner.contains(eye), "机位在主人身体里：yaw " + yaw + " 距离 " + d);
                    for (Vec3 her : new Vec3[]{ShiraumePact.local(shot[1], fwd), new Vec3(0, 1.0, 0), ShiraumePact.local(new double[]{.3, 0, .45}, fwd)}) {
                        assertTrue(owner.clip(eye, her).isEmpty(), "主人挡住了她：yaw " + yaw + " 距离 " + String.format("%.2f", d));
                    }
                }
            }
        }
        // 旧的机位二（正前方 1.9 格、偏 0.5 格）：主人站在一格半处时，看她胸口的视线穿过主人
        Vec3 fwd = Vec3.directionFromRotation(0, 0), side = new Vec3(-fwd.z, 0, fwd.x);
        AABB owner = new AABB(fwd.scale(1.2).add(side.scale(-.5)), fwd.scale(1.8).add(side.scale(.5)).add(0, 1.9, 0));
        assertFalse(owner.clip(ShiraumePact.local(new double[]{1.9, -.5, .95}, fwd), new Vec3(0, .55, 0)).isEmpty());
    }

    /** 基础狐火时她一刀的伤害和玩家手里流火的一刀一样（她的是吃护甲的刀伤，见 CompanionBlade.BLADE）。 */
    @Test void aBaseCutMatchesRyuka() {
        assertEquals(dev.logos.dimension.ryuka.Ryuka.DAMAGE, CompanionStats.BASE_FOXFIRE * CompanionBlade.BLADE, 1e-6);
    }

    /** 突刺往前冲的那一段和 Boss 招式文件 tsuki 的 lunge 一致，两下接触都在冲的那一段里。 */
    @Test void thrustLungeMatchesTheBoss() throws Exception {
        JsonObject boss = read("/data/logos_dimension/combat/movesets/shiraume.json").getAsJsonObject("moves").getAsJsonObject("tsuki");
        var lunge = boss.getAsJsonArray("lunge");
        assertEquals(lunge.get(0).getAsDouble(), CompanionBlade.LUNGE_FROM, 1e-9);
        assertEquals(lunge.get(1).getAsDouble(), CompanionBlade.LUNGE_TO, 1e-9);
        Moveset set = Moveset.parse(read("/data/logos_dimension/combat/movesets/shiraume_companion.json"));
        for (double t : set.move("tsuki").contacts()) assertTrue(t > CompanionBlade.LUNGE_FROM && t < CompanionBlade.LUNGE_TO, "接触 " + t);
    }

    /**
     * 出刀够快（用户：沿用 Boss 出招太慢，两只神狐都打不过）：每一招从起手到能接下一刀，平均每砍中一下不超过半秒
     * （神狐打刀一刀约 0.5 秒）；连招按整串算。
     */
    @Test void cutsAreQuickEnough() throws Exception {
        Moveset set = Moveset.parse(read("/data/logos_dimension/combat/movesets/shiraume_companion.json"));
        for (String opener : new String[]{"kesa", "gyaku", "yoko", "tsuki", "kiriage", "sanren_1", "tsubame_1"}) {
            double ticks = 0;
            int hits = 0;
            Moveset.Move m = set.move(opener);
            while (true) {
                double rate = CompanionBlade.ATTACK_SPEED / m.speedBase();
                hits += m.contacts().size();
                if ("idle".equals(m.next())) {
                    ticks += m.lastContact() * 20 / rate + CompanionBlade.RECOVER;
                    break;
                }
                ticks += m.length() * 20 / rate;
                m = set.move(m.next());
            }
            assertTrue(ticks / hits <= 10, opener + " 每一下要 " + String.format("%.1f", ticks / hits) + " 刻");
        }
    }

    private static double marker(JsonObject timeline, String name) {
        for (var e : timeline.entrySet()) {
            var v = e.getValue();
            boolean hit = v.isJsonArray() ? v.getAsJsonArray().asList().stream().anyMatch(x -> x.getAsString().equals(name)) : v.getAsString().equals(name);
            if (hit) return Double.parseDouble(e.getKey());
        }
        throw new AssertionError("没有标记 " + name);
    }
}
