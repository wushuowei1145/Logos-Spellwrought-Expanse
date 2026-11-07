package dev.logos.dimension;

import dev.logos.dimension.camera.CameraPoint;
import dev.logos.dimension.camera.CameraShot;
import io.netty.buffer.Unpooled;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CameraShotTest {
    private static final CameraPoint A = CameraPoint.world(new Vec3(1, 2, 3)), B = CameraPoint.world(new Vec3(4, 5, 6));

    @Test void startsAndEndsExactlyOnThePlayerView() {
        CameraShot shot = CameraShot.of(A, B).timing(24, 60, 24);
        assertEquals(108, shot.duration());
        assertEquals(84, shot.returnsAt());
        assertEquals(0, shot.weight(0));
        assertEquals(0, shot.weight(108));
        assertEquals(0, shot.weight(500));
        assertEquals(1, shot.weight(24));
        assertEquals(1, shot.weight(83.9f));
    }

    /** 权重每一小步都不跳，进出的起步和收尾也够慢，看不出那一下切换。 */
    @Test void weightNeverJumps() {
        CameraShot shot = CameraShot.of(A, B).timing(24, 60, 24);
        float previous = 0, step = .05f;
        for (float age = step; age <= 108; age += step) {
            float weight = shot.weight(age);
            assertTrue(weight >= 0 && weight <= 1);
            assertTrue(Math.abs(weight - previous) <= 1.875f / 24 * step + 1e-3f, () -> "跳变在第 " + weight + " 处");
            previous = weight;
        }
        assertTrue(shot.weight(1) < .01f);
        assertTrue(shot.weight(107) < .01f);
    }

    @Test void zeroLengthBlendsAreHardCuts() {
        CameraShot shot = CameraShot.of(A, B).timing(0, 10, 0);
        assertEquals(1, shot.weight(.01f));
        assertEquals(1, shot.weight(9.99f));
        assertEquals(0, shot.weight(10));
    }

    @Test void negativeTimesAreTreatedAsZero() {
        assertEquals(0, CameraShot.of(A, B).timing(-5, -5, -5).duration());
    }

    @Test void optionsKeepEverythingElse() {
        assertFalse(CameraShot.of(A, B).letterboxed());
        CameraShot shot = CameraShot.of(A, B).letterbox().timing(1, 2, 3).keepControl().keepHud();
        assertFalse(shot.lockControl());
        assertFalse(shot.hideHud());
        assertTrue(shot.letterboxed());
        assertEquals(6, shot.duration());
        assertEquals(A, shot.from());
        assertEquals(B, shot.lookAt());
    }

    @Test void survivesTheWire() {
        CameraShot shot = new CameraShot(new CameraPoint(42, new Vec3(.5, 3, -6)), B, 7, 8, 9, false, true, true);
        var buffer = Unpooled.buffer();
        CameraShot.CODEC.encode(buffer, shot);
        assertEquals(shot, CameraShot.CODEC.decode(buffer));
        assertEquals(0, buffer.readableBytes());
    }

    @Test void worldPointsIgnoreTheLevel() {
        assertEquals(new Vec3(1, 2, 3), A.resolve(null, .5f, null));
    }
}
