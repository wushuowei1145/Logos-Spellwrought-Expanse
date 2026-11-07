package dev.logos.dimension.camera;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 一段运镜：镜头从玩家视角过渡到 {@code from} 看向 {@code lookAt}，停一会儿，再过渡回玩家。
 * 时长都按刻。只是一份描述，服务端和客户端都能用；发给玩家见 {@link CameraShots#play}。
 *
 * @param lockControl 镜头离开期间是否锁住玩家的按键和鼠标
 * @param hideHud 镜头离开期间是否隐藏界面
 * @param letterboxed 是否在画面上下加遮幅：跟着镜头的过渡从屏幕外滑入，回程时滑出
 */
public record CameraShot(CameraPoint from, CameraPoint lookAt, int blendIn, int hold, int blendOut, boolean lockControl, boolean hideHud, boolean letterboxed) {
    private static final int DEFAULT_BLEND = 24, DEFAULT_HOLD = 60;
    public static final StreamCodec<ByteBuf, CameraShot> CODEC = StreamCodec.of((b, s) -> {
        CameraPoint.CODEC.encode(b, s.from);
        CameraPoint.CODEC.encode(b, s.lookAt);
        b.writeInt(s.blendIn); b.writeInt(s.hold); b.writeInt(s.blendOut);
        b.writeBoolean(s.lockControl); b.writeBoolean(s.hideHud); b.writeBoolean(s.letterboxed);
    }, b -> new CameraShot(CameraPoint.CODEC.decode(b), CameraPoint.CODEC.decode(b), b.readInt(), b.readInt(), b.readInt(), b.readBoolean(), b.readBoolean(), b.readBoolean()));

    public CameraShot {
        blendIn = Math.max(0, blendIn);
        hold = Math.max(0, hold);
        blendOut = Math.max(0, blendOut);
    }

    /** 默认：过渡各 1.2 秒，停留 3 秒，锁操作，隐藏界面，没有遮幅。 */
    public static CameraShot of(CameraPoint from, CameraPoint lookAt) {
        return new CameraShot(from, lookAt, DEFAULT_BLEND, DEFAULT_HOLD, DEFAULT_BLEND, true, true, false);
    }

    public CameraShot timing(int blendIn, int hold, int blendOut) {
        return new CameraShot(from, lookAt, blendIn, hold, blendOut, lockControl, hideHud, letterboxed);
    }

    /** 不锁操作：玩家可以一边看一边动，回程的终点跟着玩家走。 */
    public CameraShot keepControl() {
        return new CameraShot(from, lookAt, blendIn, hold, blendOut, false, hideHud, letterboxed);
    }

    public CameraShot keepHud() {
        return new CameraShot(from, lookAt, blendIn, hold, blendOut, lockControl, false, letterboxed);
    }

    /** 信箱模式：上下加遮幅。 */
    public CameraShot letterbox() {
        return new CameraShot(from, lookAt, blendIn, hold, blendOut, lockControl, hideHud, true);
    }

    public int duration() {
        return blendIn + hold + blendOut;
    }

    /** 从这一刻起镜头开始往回走。 */
    public int returnsAt() {
        return blendIn + hold;
    }

    /** {@code age} 刻时镜头离玩家视角多远：0 完全是玩家视角，1 完全在机位上。头尾都平滑地贴到 0，所以进出不跳。 */
    public float weight(float age) {
        if (age <= 0 || age >= duration()) return 0;
        if (age < blendIn) return ease(age / blendIn);
        if (age < returnsAt()) return 1;
        return ease((duration() - age) / blendOut);
    }

    /** 五次缓动：起点终点的速度和加速度都是 0，比三次的更不容易看出起步。 */
    private static float ease(float t) {
        return Math.clamp(t * t * t * (t * (t * 6 - 15) + 10), 0f, 1f);
    }
}
