package dev.logos.dimension;

import dev.logos.dimension.elven.blight.*;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QueenBattleRulesTest {
    @Test void threeSeversAdvanceEachStageWithoutFloatingPointDeadlock() {
        float health = QueenBattleRules.HEALTH;
        for (int i = 0; i < 3; i++) health -= QueenBattleRules.rootDamage(1);
        assertEquals(0, health, .0001);
        health = QueenBattleRules.HEALTH;
        for (int i = 0; i < 3; i++) health -= QueenBattleRules.rootDamage(2);
        assertEquals(0, health, .0001);
        assertEquals(400, QueenBattleRules.HEALTH);
    }
    @Test void exposedCoreRequiresAllThreeFinalSupplyLines() {
        for (int phase = 1; phase <= 3; phase++) for (int mask = 0; mask < 8; mask++)
            assertEquals(phase == 3 && mask == 7, QueenBattleRules.coreExposed(phase, mask));
        assertFalse(QueenBattleRules.severed(7, -1)); assertFalse(QueenBattleRules.severed(7, 3));
    }
    @Test void waveIsMonotoneAndCompletesBeforeCameraReturns() {
        float previous = 0;
        for (float t = 0; t < QueenBattleRules.END; t += .125f) {
            float r = QueenBattleRules.radius(t);
            assertTrue(r >= previous && r <= 256); previous = r;
        }
        assertEquals(0, QueenBattleRules.radius(QueenBattleRules.EXPLODE));
        assertEquals(256, QueenBattleRules.radius(QueenBattleRules.WAVE_END));
        assertTrue(QueenBattleRules.WAVE_END < QueenBattleRules.END - 24);
    }
    @Test void endingDoesNotSnapHeartBackIntoTheCeiling() {
        assertEquals(0, QueenBattleRules.coreDrop(2, 100));
        assertEquals(-6, QueenBattleRules.coreDrop(3, 64));
        assertEquals(-6, QueenBattleRules.coreDrop(4, 0));
    }
    @Test void endingCuePreservesWorldClockAndCoordinates() {
        var cue = new QueenEndingPayload(new BlockPos(-4097, 87, 140), new Vec3(-4096.5, 80, 140.5), new Vec3(0, 0, -1), 18000);
        var buffer = Unpooled.buffer();
        try { QueenEndingPayload.CODEC.encode(buffer, cue); assertEquals(cue, QueenEndingPayload.CODEC.decode(buffer)); }
        finally { buffer.release(); }
    }
}
