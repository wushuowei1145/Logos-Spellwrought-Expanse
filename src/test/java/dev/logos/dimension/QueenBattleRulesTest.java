package dev.logos.dimension;

import dev.logos.dimension.elven.blight.*;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QueenBattleRulesTest {
    @Test void veinMaskOnlyCountsMainVeins() {
        for (int slot = 0; slot < HeartVeins.MAIN; slot++) assertTrue(QueenBattleRules.severed(0xFF, slot));
        assertFalse(QueenBattleRules.severed(0xFF, -1)); assertFalse(QueenBattleRules.severed(0xFF, HeartVeins.MAIN));
        // 北侧那条越过入口拱门，够不着；其余七条落在高台上方。
        assertEquals(7, QueenBattleRules.reachableVeins());
    }
    @Test void reachableJointsSitAboveTheGallery() {
        for (int i = 0; i < HeartVeins.MAIN; i++) {
            if (!HeartVeins.reachable(i)) continue;
            double y = HeartVeins.joint(i).y;
            assertTrue(y >= 2 && y <= 6, "接头 " + i + " 的高度 " + y);
            assertTrue(HeartVeins.knot(i, 2).horizontalDistance() < 24, "可砍的接头要在大厅墙里面");
        }
    }
    @Test void waveSizeShrinksWithCutVeinsButNeverEmpties() {
        assertEquals(3, QueenBattleRules.waveSize(1, 0));
        assertEquals(4, QueenBattleRules.waveSize(2, 0));
        assertEquals(2, QueenBattleRules.waveSize(1, 2));
        assertEquals(1, QueenBattleRules.waveSize(1, 7));
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
    @Test void heartSinksOnlyWhileTheWindowIsOpen() {
        assertEquals(0, QueenBattleRules.coreDrop(2, 100, 0, 0));
        assertEquals(-5, QueenBattleRules.coreDrop(2, 100, 80, 80), 1e-9);
        // 开合各 1 秒，不瞬移。
        assertTrue(QueenBattleRules.coreDrop(2, 100, 5, 155) > -2);
        assertTrue(QueenBattleRules.coreDrop(2, 100, 155, 5) > -2);
        // 狂乱时它不降，只在原处发抖（客户端画）。
        assertEquals(0, QueenBattleRules.coreDrop(3, 200, 0, 0), 1e-9);
        assertEquals(-6, QueenBattleRules.coreDrop(4, 0, 0, 0));
    }
    @Test void frenzySpreadsFasterAndFasterUpToTheAltar() {
        double limit = 14, previous = 0, step = 0;
        for (int t = 0; t <= QueenBattleRules.FRENZY_SPREAD; t += 20) {
            double r = QueenBattleRules.frenzyReach(t, limit);
            assertTrue(r >= previous && r <= limit + 1e-9);
            if (t > 40) assertTrue(r - previous >= step - 1e-9, "越漫越快");
            step = r - previous; previous = r;
        }
        assertEquals(2, QueenBattleRules.frenzyReach(0, limit), 1e-9);
        assertEquals(limit, QueenBattleRules.frenzyReach(QueenBattleRules.FRENZY_SPREAD, limit), 1e-9);
        assertEquals(limit, QueenBattleRules.frenzyReach(QueenBattleRules.FRENZY_TICKS, limit), 1e-9);
        // 公主先喊，女王在快淹到祭坛时站起来，再拥心。
        assertTrue(QueenBattleRules.FRENZY_LINE < QueenBattleRules.FRENZY_RISE && QueenBattleRules.FRENZY_SPREAD <= QueenBattleRules.FRENZY_RISE
                && QueenBattleRules.FRENZY_RISE + 40 <= QueenBattleRules.FRENZY_TICKS);
    }
    @Test void endingCuePreservesWorldClockAndCoordinates() {
        var cue = new QueenEndingPayload(new BlockPos(-4097, 87, 140), new Vec3(-4096.5, 80, 140.5), new Vec3(0, 0, -1), 18000);
        var buffer = Unpooled.buffer();
        try { QueenEndingPayload.CODEC.encode(buffer, cue); assertEquals(cue, QueenEndingPayload.CODEC.decode(buffer)); }
        finally { buffer.release(); }
    }
    @Test void arenaMarkersRotateWithTheHeart() {
        var heart = new BlockPos(100, 64, 200);
        assertEquals(new Vec3(100.5, 60, 219), QueenArena.at(heart, Direction.SOUTH, new Vec3(0, -4, 18.5)));
        Vec3 west = QueenArena.at(heart, Direction.WEST, new Vec3(0, -4, 18.5));
        assertEquals(100.5 - 18.5, west.x, 1e-9);
        assertEquals(200.5, west.z, 1e-9);
    }
}
