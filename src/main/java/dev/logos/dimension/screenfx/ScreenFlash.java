package dev.logos.dimension.screenfx;

import dev.logos.dimension.camera.CameraPoint;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.codec.StreamCodec;

/**
 * 一次黑白闪（冲击帧）：按 24fps 一拍一张放几张高对比的画面，白底黑和黑底白交替，线条从 {@code center} 放射出去。
 * 明暗取自当时的画面，所以不需要美术素材，打在什么上面都能用。发给玩家见 {@link ScreenEffects#flash}。
 */
public record ScreenFlash(CameraPoint center, List<Frame> frames) {
    /** 一拍多少纳秒：动画的 24fps，和游戏帧率无关。 */
    public static final long FRAME_NANOS = 1_000_000_000L / 24;
    /** 一次最多几拍。黑白交替太久既刺眼，也有光敏风险。 */
    public static final int MAX_FRAMES = 6;
    public static final StreamCodec<ByteBuf, ScreenFlash> CODEC = StreamCodec.of((b, f) -> {
        CameraPoint.CODEC.encode(b, f.center);
        b.writeByte(f.frames.size());
        for (Frame frame : f.frames) b.writeByte(frame.ordinal());
    }, b -> {
        CameraPoint center = CameraPoint.CODEC.decode(b);
        var frames = new ArrayList<Frame>();
        for (int i = b.readByte(); i > 0; i--) frames.add(Frame.values()[b.readByte()]);
        return new ScreenFlash(center, frames);
    });

    /** 一拍的内容。 */
    public enum Frame {
        /** 白底黑：亮处全白，暗处变成黑色剪影和排线。 */
        WHITE,
        /** 黑底白：反过来，再加几根贯穿全屏的白线。 */
        BLACK,
        /** 夹一拍原画。 */
        NORMAL
    }

    public ScreenFlash {
        frames = List.copyOf(frames.size() > MAX_FRAMES ? frames.subList(0, MAX_FRAMES) : frames);
    }

    /** 默认两拍：白底黑接黑底白。 */
    public static ScreenFlash at(CameraPoint center) {
        return new ScreenFlash(center, List.of(Frame.WHITE, Frame.BLACK));
    }

    public ScreenFlash frames(Frame... frames) {
        return new ScreenFlash(center, List.of(frames));
    }

    /** 从开始算过了 {@code nanos} 纳秒时是第几拍。 */
    public static int frameAt(long nanos) {
        return (int) (Math.max(0, nanos) / FRAME_NANOS);
    }
}
