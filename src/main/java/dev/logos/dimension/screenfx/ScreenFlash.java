package dev.logos.dimension.screenfx;

import dev.logos.dimension.camera.CameraPoint;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.network.codec.StreamCodec;

/**
 * 一次黑白闪（冲击帧）：按 24fps 一拍一张放几张画面，线条都指向 {@code center} 这个光源。
 * 默认四个阶段：先在彩色里发光变亮，再突然变成黑白剪影，光吞没得更大，最后明暗翻转。
 * 明暗、剪影和轮廓都取自当时的画面和深度，所以不需要美术素材，打在什么上面都能用。发给玩家见 {@link ScreenEffects#flash}。
 */
public record ScreenFlash(CameraPoint center, List<Frame> frames) {
    /** 一拍多少纳秒：动画的 24fps，和游戏帧率无关。 */
    public static final long FRAME_NANOS = 1_000_000_000L / 24;
    /** 一次最多几拍。黑白交替太久既刺眼，也有光敏风险。 */
    public static final int MAX_FRAMES = 8;
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

    /** 一拍的内容。新加的只能往后排：网络上按序号传。 */
    public enum Frame {
        /** 白底黑：只看颜色的明暗，亮处全白，暗处变成黑色剪影和排线。 */
        WHITE('w'),
        /** 黑底白：白底黑的负片，再加几根贯穿全屏的白线。 */
        BLACK('b'),
        /** 夹一拍原画。 */
        NORMAL('n'),
        /** 还是彩色：画面整体变亮，光源一团白光和放射的光芒，靠近光源的东西被吞没。连放几拍会一拍比一拍亮。 */
        GLOW('g'),
        /** 突然变成黑白：光源是白的，背对光源的一面是黑色剪影，剪影背离光源拉出影子线，所有线都指向光源。 */
        SILHOUETTE('s'),
        /** 光吞没得更大，线更长，形状更抽象。 */
        ENGULF('e'),
        /** 明暗翻转：光源再扩大，上一拍黑的被光吞没变白，光以外整个反过来，上一拍亮的暗下去。 */
        SWAP('x');

        private final char letter;

        Frame(char letter) {
            this.letter = letter;
        }

        public char letter() {
            return letter;
        }

        /** 这一拍画面是不是被整个换掉了（不再是原画）。 */
        public boolean replacesScreen() {
            return this != NORMAL;
        }
    }

    public ScreenFlash {
        frames = List.copyOf(frames.size() > MAX_FRAMES ? frames.subList(0, MAX_FRAMES) : frames);
    }

    /** 默认五拍：发光两拍，剪影、吞没、翻转各一拍，约 0.2 秒。 */
    public static ScreenFlash at(CameraPoint center) {
        return new ScreenFlash(center, List.of(Frame.GLOW, Frame.GLOW, Frame.SILHOUETTE, Frame.ENGULF, Frame.SWAP));
    }

    public ScreenFlash frames(Frame... frames) {
        return new ScreenFlash(center, List.of(frames));
    }

    /** 一个字母一拍，见 {@link Frame} 的字母；认不出的字母算原画。 */
    public static List<Frame> parse(String letters) {
        var frames = new ArrayList<Frame>();
        for (char c : letters.toLowerCase(Locale.ROOT).toCharArray()) {
            Frame match = Frame.NORMAL;
            for (Frame frame : Frame.values()) if (frame.letter == c) match = frame;
            frames.add(match);
        }
        return frames;
    }

    /** 从开始算过了 {@code nanos} 纳秒时是第几拍。 */
    public static int frameAt(long nanos) {
        return (int) (Math.max(0, nanos) / FRAME_NANOS);
    }
}
