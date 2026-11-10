package dev.logos.dimension.client.shiraume;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 白梅手里流火的刀光（{@link ShiraumeTrails}）：出光时段对得上动画，盖住每一刀的命中，人形的每一招攻击都出光。 */
class ShiraumeTrailsTest {
    private static JsonObject json(String path) throws Exception {
        try (var in = ShiraumeTrailsTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static JsonObject animations(String entity) throws Exception {
        return json("/assets/logos_dimension/geckolib/animations/entity/" + entity + ".animation.json").getAsJsonObject("animations");
    }

    @Test void windowsFitTheirAnimationsAndCoverTheHit() throws Exception {
        JsonObject human = animations("shiraume"), beast = animations("shiraume_beast");
        for (var entry : ShiraumeTrails.WINDOWS.entrySet()) {
            String name = entry.getKey();
            float[] w = entry.getValue();
            JsonObject clip = (name.startsWith("fox_") ? beast : human).getAsJsonObject(name);
            assertNotNull(clip, "动画里没有 " + name);
            assertTrue(w[0] >= 0 && w[0] < w[1] && w[1] <= clip.get("animation_length").getAsDouble(), name + " 的出光时段超出动作");
            if (!clip.has("timeline")) continue;
            // 命中那一刻要在挥刀的那一段里（突き有两个命中时刻，取第一个）
            for (var mark : clip.getAsJsonObject("timeline").entrySet()) {
                if (!mark.getValue().getAsString().equals("contact")) continue;
                double at = Double.parseDouble(mark.getKey());
                assertTrue(at >= w[0] && at <= w[1], name + " 的命中 " + at + " 不在出光时段里");
                break;
            }
        }
    }

    @Test void everyHumanAttackLeavesATrail() throws Exception {
        JsonObject moves = json("/data/logos_dimension/combat/movesets/shiraume.json").getAsJsonObject("moves");
        for (var entry : moves.entrySet()) {
            JsonObject move = entry.getValue().getAsJsonObject();
            if (!move.has("damage")) continue;
            String clip = move.has("clip") ? move.get("clip").getAsString() : entry.getKey();
            assertTrue(ShiraumeTrails.WINDOWS.containsKey("ryuka_" + clip), "攻击 " + entry.getKey() + " 没有刀光");
        }
    }

    @Test void onlyTheSwingEmits() {
        // 袈裟斩 0.64–0.77 秒出光
        assertFalse(ShiraumeTrails.swinging("ryuka_kesa", .3), "起手时不出光");
        assertTrue(ShiraumeTrails.swinging("ryuka_kesa", .7), "挥刀时出光");
        assertFalse(ShiraumeTrails.swinging("ryuka_kesa", 1.0), "定住时不出光");
        assertFalse(ShiraumeTrails.swinging("ryuka_idle", .7), "待机不出光");
        assertFalse(ShiraumeTrails.swinging((String) null, .7));
    }
}
