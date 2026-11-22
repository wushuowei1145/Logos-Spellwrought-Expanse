package dev.logos.dimension.screenfx;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * 一次能停住的全屏白或全屏黑：{@code in} 刻慢慢盖上，盖满停 {@code hold} 刻，再用 {@code out} 刻退去。都按游戏刻算，
 * 服务端可以在盖满的那几刻里传送、换场景，玩家看不到。和黑白闪不同，它是一次缓慢的变化，不算闪烁；
 * 盖上、退去都至少 {@link #MIN_RAMP} 刻（docs/CINEMATICS.md 的光敏规矩）。
 *
 * @param rgb  颜色（0xRRGGBB），白屏用 {@link #WHITE}，黑屏用 {@link #BLACK}
 */
public record ScreenCover(int rgb, int in, int hold, int out) {
    public static final int WHITE = 0xFFFFFF, BLACK = 0x000000;
    /** 盖上、退去最快多少刻：再快就成了闪。 */
    public static final int MIN_RAMP = 6;
    public static final StreamCodec<ByteBuf, ScreenCover> CODEC = StreamCodec.composite(
            ByteBufCodecs.INT, ScreenCover::rgb, ByteBufCodecs.VAR_INT, ScreenCover::in,
            ByteBufCodecs.VAR_INT, ScreenCover::hold, ByteBufCodecs.VAR_INT, ScreenCover::out, ScreenCover::new);

    public ScreenCover {
        in = Math.max(MIN_RAMP, in);
        out = Math.max(MIN_RAMP, out);
        hold = Math.max(0, hold);
    }

    public static ScreenCover white(int in, int hold, int out) { return new ScreenCover(WHITE, in, hold, out); }

    public static ScreenCover black(int in, int hold, int out) { return new ScreenCover(BLACK, in, hold, out); }

    /** 从开始到盖满要几刻：服务端在这之后、{@link #fullUntil()} 之前换场景。 */
    public int fullAt() { return in; }

    /** 盖满到第几刻为止。 */
    public int fullUntil() { return in + hold; }

    public int total() { return in + hold + out; }

    /** 第 {@code age} 刻（可带小数）盖住了多少（0..1）。盖上、退去都是缓入缓出。 */
    public float alpha(float age) {
        if (age <= 0 || age >= total()) return 0;
        float k;
        if (age < in) k = age / in;
        else if (age <= in + hold) k = 1;
        else k = 1 - (age - in - hold) / out;
        return k * k * (3 - 2 * k);
    }
}
