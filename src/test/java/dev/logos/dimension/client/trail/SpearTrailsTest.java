package dev.logos.dimension.client.trail;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.logos.dimension.combat.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * 群誓·狼牙长枪的刀光（combat/trails/oath_spear.json）：刀身只取枪头；每一招攻击都在命中那一刻出光，
 * 拔枪、待机、收枪、被弹反不出光；按刀尖速度自动出光的招式，命中时刀尖确实够快。
 * 进入招式时从上一姿势过渡（blend_in）的那一小段不出光：从上挑接连刺时枪从头顶落下，那一下不是攻击。
 */
class SpearTrailsTest {
    private static JsonObject read(String path) throws Exception {
        try (var stream = SpearTrailsTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, path);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    private static JsonObject combat(String path) throws Exception { return read("/assets/logos_dimension/combat/" + path + ".json"); }
    private static TrailConfigs.Set config() throws Exception { return TrailConfigs.parse(combat("trails/oath_spear")); }

    @Test void bladeIsTheSpearheadOnly() throws Exception {
        var blade = config().blade();
        assertNotNull(blade);
        // 枪头从上段握把与套筒之上算起（握把到 19.2），到枪尖为止。
        float gripTop = 0, tip = 0;
        for (var c : combat("weapons/pack_oath_spear").getAsJsonArray("cubes")) {
            var cube = c.getAsJsonObject();
            float top = cube.getAsJsonArray("end").get(1).getAsFloat();
            if (cube.get("name").getAsString().startsWith("leather_grip")) gripTop = Math.max(gripTop, top);
            tip = Math.max(tip, top);
        }
        assertTrue(blade.from() > gripTop, "刀身伸到了握把上：" + blade.from());
        assertEquals(tip, blade.to(), .1f, "刀身没有到枪尖");
        assertEquals("oath_spear", config().style().getPath());
        assertFalse(config().fixed(), "魔力缠绕要能换掉长枪的刀光");
    }

    @Test void everyAttackEmitsAtContactAndNothingElseDoes() throws Exception {
        var config = config();
        var moveset = Moveset.parse(read("/data/logos_dimension/combat/movesets/oath_spear.json"));
        var clips = CombatClip.parseFile(combat("animations/oath_spear_tp.animation"), new ArrayList<>());
        var json = combat("skeletons/player_tp");
        var skeleton = CombatSkeleton.fromJson(json);
        float scale = json.get("weapon_scale").getAsFloat() * combat("weapons/pack_oath_spear").get("scale").getAsFloat();
        Vector3f pivot = skeleton.bone(CombatBones.WEAPON).pivot();
        for (var move : moveset.moves().values()) {
            var clip = config.clip(move.clip());
            if (move.contacts().isEmpty()) {
                assertTrue(clip.none(), move.name() + " 不是攻击，不该出光");
                continue;
            }
            assertFalse(clip.none(), move.name() + " 是攻击，要出光");
            for (float[] w : clip.windows())
                assertTrue(w[0] >= move.blendIn() - 1e-6, move.name() + " 的出光时段落在进入时的过渡里");
            for (double contact : move.contacts()) {
                if (contact <= move.blendIn()) continue;
                if (!clip.windows().isEmpty()) {
                    long hit = clip.windows().stream().filter(w -> contact >= w[0] && contact <= w[1]).count();
                    assertEquals(1, hit, move.name() + " 在 " + contact + " 秒的命中不在（恰好一个）出光时段里");
                } else {
                    // 自动出光：与 RigTrails 一样，在第三人称骨架里量刀尖 1/60 秒内的速度（不含根骨的转身）
                    var animation = clips.get(move.clip());
                    float speed = tip(animation, skeleton, pivot, scale, contact, config).distance(
                            tip(animation, skeleton, pivot, scale, contact - 1 / 60.0, config)) / 16f * 60;
                    assertTrue(speed >= config.speed(), move.name() + " 命中时刀尖只有 " + speed + " 格/秒，自动出光不会亮");
                }
            }
        }
        for (String quiet : Set.of("draw", "idle", "sheathe", "parried", "parried_npc"))
            assertTrue(config.clip(quiet).none(), quiet + " 不该出光");
    }

    private static Vector3f tip(CombatClip clip, CombatSkeleton skeleton, Vector3f pivot, float scale, double seconds, TrailConfigs.Set config) {
        var pose = CombatPose.sample(clip, (float) seconds);
        if (pose.joint(CombatBones.ROOT) != null) pose = pose.without(CombatBones.ROOT);
        Map<String, Matrix4f> bones = pose.matrices(skeleton);
        return new Matrix4f(bones.get(CombatBones.WEAPON)).translate(pivot).scale(scale).transformPosition(new Vector3f(0, config.blade().to(), 0));
    }
}
