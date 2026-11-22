package dev.logos.dimension.execution;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 一次处决特写的设定：目标被抛起，画面变黑白，刀光一道道穿过它，最后一刀把背景打成红色，再把它拍到地上。
 * 另一种样式是飞身刺（{@link Choreo#THRUST}、{@link #thrust}）：出手的人从几格外飞身刺进目标的胸口，两人都是剪影，刺中后定格。
 * 时间表见 {@link ExecutionTimeline}；发给玩家见 {@link ExecutionShots#play}。只是一份描述，两端都能用。
 *
 * @param seed 刀光的角度、位置和身体的反应都由它随机出来，同一个种子每次、每个客户端都一样
 * @param slashes 重击之前有几刀（{@code window} 大于 0 时不用）
 * @param blackGround 黑底白身（默认白底黑身）
 * @param swaps 刀划过时什么时候黑白对调
 * @param letterbox 上下加遮幅
 * @param window 大于 0 时是可交互的处决（{@link InteractiveExecutions}）：重击之前留这么多刻让处决的人自己点左键砍，
 *               砍几刀、什么时候砍都由点击决定，刀是一刀刀发过来的；为 0 时刀数和时刻都由种子定好。飞身刺总是 0
 * @param tint 可交互处决时，旁人在世界里看到的刀光颜色（RGB）
 * @param choreo 演哪一种
 */
public record ExecutionShot(int seed, int slashes, boolean blackGround, Swaps swaps, boolean letterbox, int window, int tint, Choreo choreo) {
    public static final int MIN_SLASHES = 1, MAX_SLASHES = 24, DEFAULT_SLASHES = 12;
    /** 可交互处决留给点击的时长上限（刻）。 */
    public static final int MAX_WINDOW = 200;
    /** 标志字节：第 0 位黑底，1、2 位对调的方式，3 位遮幅，4、5 位演哪一种（{@link Choreo}）。 */
    public static final StreamCodec<ByteBuf, ExecutionShot> CODEC = StreamCodec.of((b, s) -> {
        b.writeInt(s.seed);
        b.writeByte(s.slashes);
        b.writeByte((s.blackGround ? 1 : 0) | s.swaps.ordinal() << 1 | (s.letterbox ? 8 : 0) | s.choreo.ordinal() << 4);
        b.writeShort(s.window);
        b.writeInt(s.tint);
    }, b -> {
        int seed = b.readInt(), slashes = b.readByte(), flags = b.readByte(), window = b.readShort(), tint = b.readInt();
        return new ExecutionShot(seed, slashes, (flags & 1) != 0, Swaps.values()[Math.min(flags >> 1 & 3, Swaps.values().length - 1)], (flags & 8) != 0,
                window, tint, Choreo.values()[Math.min(flags >> 4 & 3, Choreo.values().length - 1)]);
    });

    /** 演哪一种。网络上按序号传，不要挪位置。 */
    public enum Choreo {
        /** 目标被抛起，刀光一道道穿过，红色重击，拍到地上（默认）。 */
        LAUNCH,
        /**
         * 飞身刺：出手的人（{@link ExecutionShots#play} 的 {@code actor}）在目标前方几格外蹲下蓄势，飞身刺进它的胸口，刺中后定格。
         * 只演到黑白结束，后面由接着演的过场接手；目标不被定住，由调用方自己安排它。
         */
        THRUST;

        /** 服务端要不要把不是玩家的目标定住。 */
        public boolean freezes() {
            return this == LAUNCH;
        }
    }

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
        // 飞身刺没有点击窗口
        window = choreo == Choreo.THRUST ? 0 : Math.clamp(window, 0, MAX_WINDOW);
        tint &= 0xFFFFFF;
    }

    public ExecutionShot(int seed, int slashes, boolean blackGround, Swaps swaps, boolean letterbox, int window, int tint) {
        this(seed, slashes, blackGround, swaps, letterbox, window, tint, Choreo.LAUNCH);
    }

    public ExecutionShot(int seed, int slashes, boolean blackGround, Swaps swaps, boolean letterbox) {
        this(seed, slashes, blackGround, swaps, letterbox, 0, 0xFFFFFF);
    }

    /** 默认：12 刀，白底黑身，只在节拍上对调，带遮幅。 */
    public static ExecutionShot of(int seed) {
        return new ExecutionShot(seed, DEFAULT_SLASHES, false, Swaps.BEATS, true);
    }

    /**
     * 飞身刺：白底黑剪影，带遮幅；不对调黑白，全程只有进黑白、出黑白这两下明暗变化。
     * 出手的人在 {@link ExecutionShots#play} 里给。
     */
    public static ExecutionShot thrust(int seed) {
        return new ExecutionShot(seed, MIN_SLASHES, false, Swaps.NONE, true, 0, 0xFFFFFF, Choreo.THRUST);
    }

    public ExecutionShot slashes(int count) {
        return new ExecutionShot(seed, count, blackGround, swaps, letterbox, window, tint, choreo);
    }

    /** 黑底白身。 */
    public ExecutionShot onBlack() {
        return new ExecutionShot(seed, slashes, true, swaps, letterbox, window, tint, choreo);
    }

    public ExecutionShot swaps(Swaps style) {
        return new ExecutionShot(seed, slashes, blackGround, style, letterbox, window, tint, choreo);
    }

    /** 刀划过时不对调黑白。 */
    public ExecutionShot noSwaps() {
        return swaps(Swaps.NONE);
    }

    public ExecutionShot noLetterbox() {
        return new ExecutionShot(seed, slashes, blackGround, swaps, false, window, tint, choreo);
    }

    /** 可交互：重击之前留 {@code ticks} 刻让处决的人点左键砍；旁人在世界里看到的刀光是 {@code tint} 色。飞身刺不能交互，不起作用。 */
    public ExecutionShot live(int ticks, int tint) {
        return new ExecutionShot(seed, slashes, blackGround, swaps, letterbox, Math.max(1, ticks), tint, choreo);
    }

    public boolean interactive() {
        return window > 0;
    }
}
