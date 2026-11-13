package dev.logos.dimension.client.trail;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.logos.dimension.combat.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Set;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * 神狐薙刀的刀光（combat/trails/naginata.json）：刀身从刀鎺之上量到各刃型的刀尖（弯刃写 tip_x）；
 * 四刀都在命中那一刻出光，而且只亮砍下去那一段：举刀、收招的刀尖也很快，但不出光；
 * 拔刀、待机、格挡、收刀、被弹反不出光。
 */
class NaginataTrailsTest {
    private static final String[] HEADS = {"standard", "slender", "broad"};
    private static final String[] FINISHES = {"wood", "ink", "vermilion", "indigo"};
    private static JsonObject read(String path) throws Exception {
        try (var stream = NaginataTrailsTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, path);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    private static JsonObject combat(String path) throws Exception { return read("/assets/logos_dimension/combat/" + path + ".json"); }
    private static TrailConfigs.Set config() throws Exception { return TrailConfigs.parse(combat("trails/naginata")); }

    @Test void bladeRunsFromTheHabakiToEachTip() throws Exception {
        var config = config();
        assertEquals("physical", config.style().getPath());
        assertFalse(config.fixed(), "魔力缠绕要能换掉薙刀的刀光");
        for (var head : HEADS) for (var finish : FINISHES) {
            String mesh = "fox_naginata_" + head + "_" + finish;
            var blade = config.blade(mesh);
            assertNotNull(blade, mesh);
            float habaki = 0, tipY = -1e9f, tipX = 0;
            for (var c : combat("weapons/" + mesh).getAsJsonArray("cubes")) {
                var cube = c.getAsJsonObject();
                if (!cube.get("part").getAsString().equals("weapon")) continue;
                String name = cube.get("name").getAsString();
                float top = Math.max(cube.getAsJsonArray("start").get(1).getAsFloat(), cube.getAsJsonArray("end").get(1).getAsFloat());
                if (name.equals("habaki")) habaki = top;
                if (name.contains("_blade_") && top > tipY) {
                    tipY = top;
                    tipX = (cube.getAsJsonArray("start").get(0).getAsFloat() + cube.getAsJsonArray("end").get(0).getAsFloat()) / 2;
                }
            }
            assertTrue(blade.from() >= habaki - .05f, mesh + " 的刀身伸到了刀鎺下面：" + blade.from());
            assertEquals(tipY, blade.to(), .1f, mesh + " 的刀身没有到刀尖");
            // tip_x 取美术交付 attachment_points.json 的刀尖；最上面那块刃是斜的，方块中心和刀尖差一点。
            assertEquals(tipX, blade.tipX(), .2f, mesh + " 的 tip_x 没有对上弯刃的刀尖");
        }
    }

    @Test void eachCutEmitsAroundContactAndNothingElseDoes() throws Exception {
        var config = config();
        var moveset = Moveset.parse(read("/data/logos_dimension/combat/movesets/naginata.json"));
        var clips = CombatClip.parseFile(combat("animations/naginata_tp.animation"), new ArrayList<>());
        var json = combat("skeletons/player_tp");
        var skeleton = CombatSkeleton.fromJson(json);
        float scale = json.get("weapon_scale").getAsFloat() * combat("weapons/fox_naginata_standard_wood").get("scale").getAsFloat();
        Vector3f pivot = skeleton.bone(CombatBones.WEAPON).pivot();
        for (var move : moveset.moves().values()) {
            var clip = config.clip(move.clip());
            if (move.contacts().isEmpty()) {
                assertTrue(clip.none(), move.name() + " 不是攻击，不该出光");
                continue;
            }
            assertFalse(clip.windows().isEmpty(), move.name() + " 要按时段出光（举刀、收招也很快，不能按速度）");
            var animation = clips.get(move.clip());
            for (float[] w : clip.windows()) {
                assertTrue(w[0] >= move.blendIn() - 1e-6, move.name() + " 的出光时段落在进入时的过渡里");
                assertTrue(w[1] <= animation.length(), move.name() + " 的出光时段超出动作");
            }
            for (double contact : move.contacts()) {
                long hit = clip.windows().stream().filter(w -> contact >= w[0] && contact <= w[1]).count();
                assertEquals(1, hit, move.name() + " 在 " + contact + " 秒的命中不在出光时段里");
                // 命中那一刻刀尖确实在挥：比自动出光的阈值快得多。
                float speed = tip(animation, skeleton, pivot, scale, contact, config).distance(
                        tip(animation, skeleton, pivot, scale, contact - 1 / 60.0, config)) / 16f * 60;
                assertTrue(speed >= 2 * config.speed(), move.name() + " 命中时刀尖只有 " + speed + " 格/秒");
            }
        }
        for (String quiet : Set.of("draw", "idle", "guard", "guard_hit", "sheathe", "parried", "parried_npc"))
            assertTrue(config.clip(quiet).none(), quiet + " 不该出光");
    }

    private static Vector3f tip(CombatClip clip, CombatSkeleton skeleton, Vector3f pivot, float scale, double seconds, TrailConfigs.Set config) {
        var bones = CombatPose.sample(clip, (float) seconds).matrices(skeleton);
        var blade = config.blade("fox_naginata_standard_wood");
        return new Matrix4f(bones.get(CombatBones.WEAPON)).translate(pivot).scale(scale).transformPosition(new Vector3f(blade.tipX(), blade.to(), 0));
    }
}
