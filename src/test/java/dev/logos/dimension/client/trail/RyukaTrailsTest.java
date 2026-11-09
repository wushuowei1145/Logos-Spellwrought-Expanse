package dev.logos.dimension.client.trail;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 流火的刀光（combat/trails/ryuka.json）：自带的晶刃狐火、缠绕不换；每一招攻击都出光，出光时段盖住命中那一刻。 */
class RyukaTrailsTest {
    private static JsonObject json(String path) throws Exception {
        try (var in = RyukaTrailsTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test void ownStyleThatWrapsDoNotChange() throws Exception {
        TrailConfigs.Set set = TrailConfigs.parse(json("/assets/logos_dimension/combat/trails/ryuka.json"));
        assertEquals("ryuka", set.style().getPath());
        assertTrue(set.fixed(), "流火的狐火是自带的，魔力缠绕不换刀光");
        // 刀身从樱花刀镡之上量到刀尖（网格里刀镡到 y 1.35，刀尖 y 18）
        assertTrue(set.blade().from() > 1.35f && set.blade().to() <= 18f);
    }

    @Test void everyAttackEmitsAroundItsHit() throws Exception {
        TrailConfigs.Set set = TrailConfigs.parse(json("/assets/logos_dimension/combat/trails/ryuka.json"));
        JsonObject moves = json("/data/logos_dimension/combat/movesets/ryuka.json").getAsJsonObject("moves");
        for (var entry : moves.entrySet()) {
            JsonObject move = entry.getValue().getAsJsonObject();
            if (!move.has("contact")) continue;
            TrailConfigs.Clip clip = set.clip(entry.getKey());
            assertFalse(clip.none(), "攻击 " + entry.getKey() + " 没有刀光");
            double contact = move.get("contact").getAsDouble();
            assertTrue(clip.windows().isEmpty() || clip.windows().stream().anyMatch(w -> contact >= w[0] && contact <= w[1]),
                    entry.getKey() + " 的命中 " + contact + " 不在出光时段里");
        }
        for (String quiet : new String[]{"idle", "manifest", "dissipate", "guard"})
            assertTrue(set.clip(quiet).none(), quiet + " 不该出刀光");
    }
}
