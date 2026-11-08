package dev.logos.dimension.execution;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 一次处决特写的设定：目标被抛起，画面变黑白，刀光一道道穿过它，最后一刀把背景打成红色，再把它拍到地上。
 * 时间表见 {@link ExecutionTimeline}；发给玩家见 {@link ExecutionShots#play}。只是一份描述，两端都能用。
 *
 * @param seed 刀光的角度、位置和身体的反应都由它随机出来，同一个种子每次、每个客户端都一样
 * @param slashes 重击之前有几刀
 * @param blackGround 黑底白身（默认白底黑身）
 * @param swaps 刀划过时什么时候黑白对调
 * @param letterbox 上下加遮幅
 */
public record ExecutionShot(int seed, int slashes, boolean blackGround, Swaps swaps, boolean letterbox) {
    public static final int MIN_SLASHES = 1, MAX_SLASHES = 24, DEFAULT_SLASHES = 12;
    public static final StreamCodec<ByteBuf, ExecutionShot> CODEC = StreamCodec.of((b, s) -> {
        b.writeInt(s.seed);
        b.writeByte(s.slashes);
        b.writeByte((s.blackGround ? 1 : 0) | s.swaps.ordinal() << 1 | (s.letterbox ? 8 : 0));
    }, b -> {
        int seed = b.readInt(), slashes = b.readByte(), flags = b.readByte();
        return new ExecutionShot(seed, slashes, (flags & 1) != 0, Swaps.values()[Math.min(flags >> 1 & 3, Swaps.values().length - 1)], (flags & 8) != 0);
    });

    /** 刀划过时什么时候黑白对调。网络上按序号传，不要挪位置。 */
    public enum Swaps {
        /**
         * 只在节拍上对调：第一刀和切机位的那两刀，前后至少隔 0.35 秒，每秒不超过三次。
         * 其余的刀靠受击的火花、身体被斩开错位、镜头一点点推近来撑住节奏。默认用这个。
         */
        BEATS,
        /** 每刀都对调一拍再换回来（第一版的样子），默认 12 刀时每秒约 9 次全屏黑白交替。 */
        EVERY,
        /** 不对调，其余和 {@link #BEATS} 一样。 */
        NONE;

        /** 第一版只有对调；后来补的火花、斩开错位、越来越紧只给另外两种。 */
        public boolean accents() {
            return this != EVERY;
        }
    }

    public ExecutionShot {
        slashes = Math.clamp(slashes, MIN_SLASHES, MAX_SLASHES);
    }

    /** 默认：12 刀，白底黑身，只在节拍上对调，带遮幅。 */
    public static ExecutionShot of(int seed) {
        return new ExecutionShot(seed, DEFAULT_SLASHES, false, Swaps.BEATS, true);
    }

    public ExecutionShot slashes(int count) {
        return new ExecutionShot(seed, count, blackGround, swaps, letterbox);
    }

    /** 黑底白身。 */
    public ExecutionShot onBlack() {
        return new ExecutionShot(seed, slashes, true, swaps, letterbox);
    }

    public ExecutionShot swaps(Swaps style) {
        return new ExecutionShot(seed, slashes, blackGround, style, letterbox);
    }

    /** 刀划过时不对调黑白。 */
    public ExecutionShot noSwaps() {
        return swaps(Swaps.NONE);
    }

    public ExecutionShot noLetterbox() {
        return new ExecutionShot(seed, slashes, blackGround, swaps, false);
    }
}
