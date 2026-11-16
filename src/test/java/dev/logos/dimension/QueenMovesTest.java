package dev.logos.dimension;

import com.google.gson.JsonParser;
import dev.logos.dimension.elven.blight.QueenMoves;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QueenMovesTest {
    private static QueenMoves.Set load(String name) throws Exception {
        try (var in = QueenMovesTest.class.getResourceAsStream("/data/logos_dimension/combat/movesets/" + name + ".json")) {
            return QueenMoves.parse(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
        }
    }

    @Test void queenShapesAreInTheMovesetFile() throws Exception {
        var set = load("corrupted_queen");
        var reap = set.shape("queen_reap");
        assertEquals("cone", reap.kind()); assertEquals(5, reap.reach());
        var thorns = set.shape("queen_thorns");
        assertEquals("line", thorns.kind()); assertEquals(17, thorns.reach()); assertTrue(thorns.filth() > 0);
        assertEquals("ring", set.shape("queen_roots").kind());
        assertEquals("ray", set.shape("queen_life_ray").spell()); assertEquals(40, set.shape("queen_life_ray").mana());
        assertEquals(5, set.plan().rotation().size());
        assertEquals("queen_reap", set.plan().close());
        assertEquals("queen_roots", set.plan().fallback());
        for (String move : set.plan().rotation()) assertNotEquals("", set.shape(move).kind(), move + " 没有形状");
    }

    @Test void coneLineAndRingHitTests() {
        var cone = new QueenMoves.Shape("cone", 5, 60, 1, 0, .65, 0, 3, null, 0, 0);
        Vec3 o = Vec3.ZERO, aim = new Vec3(0, 0, 1);
        assertTrue(cone.hits(o, aim, new Vec3(0, 0, 4), 0));
        assertTrue(cone.hits(o, aim, new Vec3(3, 0, 2.5), 0));
        assertFalse(cone.hits(o, aim, new Vec3(0, 0, -3), 0));
        assertFalse(cone.hits(o, aim, new Vec3(0, 0, 6), 0));
        var line = new QueenMoves.Shape("line", 17, 0, 1.2, 0, 0, 0, 2, null, 0, 0);
        assertTrue(line.hits(o, aim, new Vec3(1, 0, 10), 0));
        assertFalse(line.hits(o, aim, new Vec3(1.5, 0, 10), 0));
        assertFalse(line.hits(o, aim, new Vec3(0, 3, 10), 0));
        var ring = new QueenMoves.Shape("ring", 22, 0, 0, 9, .65, 24, .65, null, 0, 0);
        assertEquals(10, ring.ring(1), 1e-9);
        assertTrue(ring.hits(o, aim, new Vec3(-10, 0, 0), 1));
        assertFalse(ring.hits(o, aim, new Vec3(-12, 0, 0), 1));
        // 缺口朝 aim：站在缺口里的不挨打。
        assertFalse(ring.hits(o, aim, new Vec3(0, 0, 10), 1));
        // 跳起来躲过环。
        assertFalse(ring.hits(o, aim, new Vec3(-10, 1, 0), 1));
    }

    @Test void coreMovesetHasAGappedTide() throws Exception {
        var set = load("queen_core");
        assertTrue(set.shape("core_wave").gap() > 0);
        assertEquals("line", set.shape("core_thorns").kind());
    }
}
