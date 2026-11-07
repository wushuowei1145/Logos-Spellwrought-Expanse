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

    /** 默认四个阶段：发光两拍，再剪影、吞没、翻转，五拍约 0.2 秒。 */
    @Test void defaultsToTheFourStages() {
        assertEquals(List.of(Frame.GLOW, Frame.GLOW, Frame.SILHOUETTE, Frame.ENGULF, Frame.SWAP), ScreenFlash.at(AT).frames());
        assertEquals(0.208, ScreenFlash.FRAME_NANOS * 5 / 1e9, 0.001);
    }

    @Test void lettersSpellOutFrames() {
        assertEquals(List.of(Frame.GLOW, Frame.SILHOUETTE, Frame.ENGULF, Frame.SWAP, Frame.WHITE, Frame.BLACK, Frame.NORMAL), ScreenFlash.parse("gsexwbn"));
        assertEquals(List.of(Frame.GLOW, Frame.NORMAL), ScreenFlash.parse("G?"), "大小写都认，认不出的算原画");
        for (Frame frame : Frame.values()) assertEquals(List.of(frame), ScreenFlash.parse(String.valueOf(frame.letter())));
    }

    /** 网络上按序号传，旧的三种不能挪位置。 */
    @Test void oldFramesKeepTheirPlace() {
        assertEquals(0, Frame.WHITE.ordinal());
        assertEquals(1, Frame.BLACK.ordinal());
        assertEquals(2, Frame.NORMAL.ordinal());
        assertFalse(Frame.NORMAL.replacesScreen());
        assertTrue(Frame.GLOW.replacesScreen());
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
        var flash = ScreenFlash.at(new CameraPoint(7, new Vec3(0, 1, 0))).frames(Frame.BLACK, Frame.NORMAL, Frame.WHITE, Frame.GLOW, Frame.SILHOUETTE, Frame.ENGULF, Frame.SWAP);
        var buffer = Unpooled.buffer();
        ScreenFlash.CODEC.encode(buffer, flash);
        assertEquals(flash, ScreenFlash.CODEC.decode(buffer));
        assertEquals(0, buffer.readableBytes());
    }
}
