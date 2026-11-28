package dev.logos.dimension;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.logos.dimension.combat.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 武器的 AI 用法（docs/WEAPON_AI.md）：招式文件里的 {@code ai} 读得通、对得上招式；驾驶员沿转移图找得到怎么按（横扫要先横防、坠星要空中、
 * 龙牙只能接在弹开后面）；角色文件只改用法时招式照搬；顺手的后继、防复读的罚分。
 */
class WeaponAiTest {
    private static JsonObject crimsonJson() throws Exception {
        return LongswordMotionTest.read("/data/logos_dimension/combat/movesets/crimson_flame_longsword.json");
    }

    private static final class Actor implements MoveRunner.Actor {
        @Override public double attackSpeed() { return 1.5; }
        @Override public void moveStarted(MoveRunner r) {}
        @Override public void contact(MoveRunner r) {}
        @Override public boolean allows(String requirement) { return true; }
    }

    private static MoveRunner at(Moveset set, String move) {
        var r = new MoveRunner(set, new Actor(), 0);
        r.force(move, 0);
        return r;
    }

    private static List<Moveset.Input> presses(List<WeaponPilot.Edge> path) {
        List<Moveset.Input> out = new ArrayList<>();
        for (var e : path) out.add(e.input());
        return out;
    }

    @Test void crimsonSwordDeclaresHowToUseIt() throws Exception {
        var json = crimsonJson();
        var set = Moveset.parse(json);
        var ai = WeaponAi.parse(json).check(set);
        assertTrue(ai.usable());
        assertEquals("longsword", ai.family());
        assertEquals("crimson", ai.clips());
        for (String m : List.of("cleave_1", "rend_2", "ascend_3", "cataclysm_4", "crimson_thrust", "vortex_rush", "flame_sweep",
                "skyfall_judgement", "cross_rend", "dragon_fang"))
            assertFalse(ai.uses(m).isEmpty(), m + " 没写用法");
        assertEquals(2, ai.uses("flame_sweep").size(), "横扫两种用法：前方成群、地上远处");
        assertEquals(Set.of("staggered"), ai.uses("dragon_fang").getFirst().target(), "龙牙只打被弹开的");
        assertTrue(ai.uses("idle").isEmpty() && ai.uses("guard").isEmpty(), "待机、横防不是挑出来的招");
        var crowd = ai.uses("flame_sweep").getFirst().crowd();
        assertEquals(new WeaponAi.Crowd(2, 11, 72), crowd);
    }

    @Test void pilotFindsHowToPressEachMove() throws Exception {
        var set = Moveset.parse(crimsonJson());
        var idle = at(set, "idle");
        assertEquals(List.of(Moveset.Input.ATTACK), presses(WeaponPilot.route(idle, "cleave_1", 0, r -> true)));
        assertEquals(List.of(Moveset.Input.USE_PRESS, Moveset.Input.ATTACK), presses(WeaponPilot.route(idle, "flame_sweep", 0, r -> true)),
                "横扫要先横防");
        var sky = WeaponPilot.route(idle, "skyfall_judgement", 0, r -> true);
        assertEquals("airborne", sky.getFirst().require(), "坠星要在空中");
        assertNull(WeaponPilot.route(idle, "skyfall_judgement", 0, r -> !r.equals("airborne")), "跳不起来就没有坠星");
        assertEquals("sneak", WeaponPilot.route(idle, "crimson_thrust", 0, r -> true).getFirst().require());
        assertEquals("sprint", WeaponPilot.route(idle, "vortex_rush", 0, r -> true).getFirst().require());
        assertNull(WeaponPilot.route(idle, "dragon_fang", 0, r -> true), "龙牙只能接在弹开后面");
        assertEquals(List.of(Moveset.Input.ATTACK), presses(WeaponPilot.route(at(set, "parry"), "dragon_fang", 0, r -> true)));
        // 一式砍到一半：缓冲左键接二式；想放突刺得等这一招播完回到待机
        var cleave = at(set, "cleave_1");
        assertEquals(List.of(Moveset.Input.ATTACK), presses(WeaponPilot.route(cleave, "rend_2", 4, r -> true)));
        var thrust = WeaponPilot.route(cleave, "crimson_thrust", 4, r -> true);
        assertNull(thrust.getFirst().input(), "先等一式播完");
        assertEquals("idle", thrust.getFirst().to());
        assertEquals(List.of(), WeaponPilot.route(idle, "idle", 0, r -> true));
    }

    @Test void successorsAndRepeats() throws Exception {
        var set = Moveset.parse(crimsonJson());
        assertTrue(WeaponPilot.successor(set, "cleave_1", "rend_2"));
        assertTrue(WeaponPilot.successor(set, "vortex_rush", "cross_rend"));
        assertTrue(WeaponPilot.successor(set, "dragon_fang", "rend_2"));
        assertFalse(WeaponPilot.successor(set, "cleave_1", "skyfall_judgement"));
        assertFalse(WeaponPilot.successor(set, "idle", "cleave_1"), "待机按左键不算接招");
        assertEquals(.45, WeaponPilot.repeat(List.of("flame_sweep", "cleave_1"), "flame_sweep"), 1e-9);
        assertEquals(.225, WeaponPilot.repeat(List.of("cleave_1", "flame_sweep"), "flame_sweep"), 1e-9);
        assertEquals(.45 + .1125, WeaponPilot.repeat(List.of("vortex_rush", "cleave_1", "vortex_rush"), "vortex_rush"), 1e-9);
        assertEquals(0, WeaponPilot.repeat(List.of(), "cleave_1"), 1e-9);
    }

    @Test void characterFilesOnlyChangeTheUsage() throws Exception {
        var parent = crimsonJson();
        var child = JsonParser.parseString("""
                {"extends": "logos_dimension:crimson_flame_longsword",
                 "ai": {"prefer": {"finisher": 0.1}},
                 "moves": {
                   "skyfall_judgement": {"ai": {"weight": 0.9}},
                   "cleave_1": {"ai": {"weight": 0.4}},
                   "cross_rend": {"ai": false}
                 }}""").getAsJsonObject();
        var merged = Movesets.inherit(parent, child);
        var set = Moveset.parse(merged);
        var ai = WeaponAi.parse(merged).check(set);
        assertEquals(1.06, set.move("skyfall_judgement").length(), 1e-9, "只写 ai 的招照搬武器的招");
        assertEquals(2, ai.uses("skyfall_judgement").size(), "几种用法都留着");
        for (var u : ai.uses("skyfall_judgement")) assertEquals(.9, u.weight(), 1e-9);
        assertEquals(Set.of("staggered"), ai.uses("dragon_fang").getFirst().target(), "没写的招照旧");
        var cleave = ai.uses("cleave_1").getFirst();
        assertEquals(.4, cleave.weight(), 1e-9);
        assertEquals(Set.of("opener", "combo"), cleave.roles(), "没写的字段照武器的");
        assertTrue(ai.uses("cross_rend").isEmpty() && ai.off().contains("cross_rend"), "这个角色不用十文字");
        assertEquals("crimson", ai.clips(), "顶层 ai 只盖写到的字段");
        assertEquals(.1, ai.prefer(Set.of("finisher")), 1e-9);
    }

    @Test void badUsageIsRejected() throws Exception {
        var set = Moveset.parse(crimsonJson());
        assertThrows(IllegalArgumentException.class, () -> WeaponAi.parse(JsonParser.parseString(
                "{\"moves\": {\"cleave_1\": {\"ai\": {\"role\": \"dance\"}}}}").getAsJsonObject()), "不认识的用途");
        assertThrows(IllegalArgumentException.class, () -> WeaponAi.parse(JsonParser.parseString(
                "{\"moves\": {\"cleave_1\": {\"ai\": {\"weight\": 1}}}}").getAsJsonObject()), "没写用途");
        assertThrows(IllegalArgumentException.class, () -> WeaponAi.parse(JsonParser.parseString(
                "{\"moves\": {\"cleave_1\": {\"ai\": {\"role\": \"combo\", \"range\": [5, 2]}}}}").getAsJsonObject()), "近远写反了");
        assertThrows(IllegalArgumentException.class, () -> WeaponAi.parse(JsonParser.parseString(
                "{\"moves\": {\"nope\": {\"ai\": {\"role\": \"combo\"}}}}").getAsJsonObject()).check(set), "写在不存在的招上");
        assertThrows(IllegalArgumentException.class, () -> WeaponAi.parse(JsonParser.parseString(
                "{\"moves\": {\"cleave_1\": {\"ai\": {\"role\": \"combo\", \"via\": [\"dance\"]}}}}").getAsJsonObject()), "via 不认识的键");
        var via = WeaponAi.parse(JsonParser.parseString(
                "{\"moves\": {\"cleave_1\": {\"ai\": {\"role\": \"buff\", \"via\": [\"use_press\", {\"hold\": 7}, \"use_release\"]}}}}").getAsJsonObject());
        assertEquals(List.of(new WeaponAi.Step("use_press", 0), new WeaponAi.Step(null, 7), new WeaponAi.Step("use_release", 0)),
                via.uses("cleave_1").getFirst().via());
        assertFalse(WeaponAi.parse(JsonParser.parseString("{\"moves\": {}}").getAsJsonObject()).usable(), "没写 ai 的武器 AI 不用");
    }
}
