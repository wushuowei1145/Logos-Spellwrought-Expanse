package dev.logos.dimension;

import dev.logos.dimension.camera.CameraPoint;
import dev.logos.dimension.screenfx.ScreenFlash;
import dev.logos.dimension.screenfx.ScreenFlash.Frame;
import io.netty.buffer.Unpooled;
import java.util.Collections;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScreenFlashTest {
    private static final CameraPoint AT = CameraPoint.world(new Vec3(1, 2, 3));

    /** 冲击帧的要点：白底黑接黑底白，两拍，共 1/12 秒。 */
    @Test void defaultsToWhiteThenBlackOnTwoFrames() {
        assertEquals(List.of(Frame.WHITE, Frame.BLACK), ScreenFlash.at(AT).frames());
        assertTrue(Math.abs(1_000_000_000L / 12 - ScreenFlash.FRAME_NANOS * 2) <= 1);
    }

    @Test void framesFollowTheAnimationClockNotTheGameFrameRate() {
        assertEquals(0, ScreenFlash.frameAt(0));
        assertEquals(0, ScreenFlash.frameAt(ScreenFlash.FRAME_NANOS - 1));
        assertEquals(1, ScreenFlash.frameAt(ScreenFlash.FRAME_NANOS));
        assertEquals(3, ScreenFlash.frameAt(ScreenFlash.FRAME_NANOS * 3 + 5));
        assertEquals(0, ScreenFlash.frameAt(-100));
    }

    @Test void longSequencesAreCutShort() {
        var flash = new ScreenFlash(AT, Collections.nCopies(40, Frame.WHITE));
        assertEquals(ScreenFlash.MAX_FRAMES, flash.frames().size());
    }

    @Test void survivesTheWire() {
        var flash = ScreenFlash.at(new CameraPoint(7, new Vec3(0, 1, 0))).frames(Frame.BLACK, Frame.NORMAL, Frame.WHITE);
        var buffer = Unpooled.buffer();
        ScreenFlash.CODEC.encode(buffer, flash);
        assertEquals(flash, ScreenFlash.CODEC.decode(buffer));
        assertEquals(0, buffer.readableBytes());
    }
}
