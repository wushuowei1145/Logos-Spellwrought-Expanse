package dev.logos.dimension.cutin;

import net.minecraft.util.Mth;

/**
 * 连携切入的时间表（单位：游戏刻，可带小数），两端都按它算。八段照规格里拆帧的节奏：
 *
 * <ol>
 *   <li>先手刺穿 {@link #PIERCE}：战场压暗，几道光线从屏幕底下错落地刺上来，先把分格切好。</li>
 *   <li>黑底接管 {@link #TAKEOVER}：黑底盖住战场和界面，角色按格子奇偶一上一下冲进来。</li>
 *   <li>落位爆闪 {@link #LAND}：角色到位的那一下，中间炸开一团白光，星空底显出来，画面轻震。</li>
 *   <li>停留 {@link #HOLD}：角色极慢地继续漂、慢慢放大，焰柱向上滚动，光尘飘落。</li>
 *   <li>过曝预升 {@link #RISE}：整体变亮，光线变粗。</li>
 *   <li>峰值闪白 {@link #PEAK}：全屏白，角色顺着进场的方向甩出屏幕。</li>
 *   <li>白光幕布 {@link #SWAP}：白光底下卸掉分格、界面回来，只留光线残影。</li>
 *   <li>退潮 {@link #FADE} 到 {@link #END}：白光散去，露出 3D 画面，残影淡掉。</li>
 * </ol>
 *
 * <p>{@code gentle}：原版选项「隐藏闪电的闪烁」打开时，不爆闪，黑底和闪白都改成慢慢盖上、慢慢散开。盖满白的时刻不变。
 */
public final class ChainTimeline {
    public static final float PIERCE = 0, TAKEOVER = 2, LAND = 4.5f, HOLD = 7, RISE = 21, PEAK = 23, SWAP = 25.5f, FADE = 28, END = 33;
    /** 角色冲进来、甩出去时离开原位多远（画面高度）。 */
    public static final float SLIDE_OUT = 1.15f;
    /** 停留期间顺着进场方向多漂多远（画面高度）。 */
    public static final float DRIFT = .025f;
    /** 停留期间放大到多少。 */
    public static final float ZOOM = 1.05f;

    private ChainTimeline() {}

    /** 战场压暗多少（0..1）：刺穿时压下去，白光盖满时恢复。 */
    public static float dim(float age) {
        if (age >= SWAP) return 0;
        return smooth(age / TAKEOVER);
    }

    /** 黑底盖住多少（0..1）。 */
    public static float plate(float age, boolean gentle) {
        if (age < TAKEOVER || age >= SWAP) return 0;
        return smooth((age - TAKEOVER) / (gentle ? 2 : .5f));
    }

    /** 星空底显出多少（0..1）：落位时显形。 */
    public static float stars(float age) {
        if (age < LAND || age >= SWAP) return 0;
        return smooth((age - LAND) / 1.5f);
    }

    /** 角色这一刻画不画：黑底接管到白光幕布之间。 */
    public static boolean portraits(float age) {
        return age >= TAKEOVER && age < SWAP;
    }

    /** 界面这一刻藏不藏：和角色一样，白光盖满时回来。 */
    public static boolean hidesHud(float age) {
        return portraits(age);
    }

    /**
     * 第 {@code slot} 格（从左数）的角色离开原位多少（画面高度，正数是往上）。奇偶反向：第 1、3、5 格从上往下冲（停留时继续往下漂、
     * 最后从下面甩出去），第 2、4 格反过来。
     */
    public static float slide(float age, int slot) {
        float dir = slot % 2 == 0 ? -1 : 1;
        if (age < LAND) {
            float t = Math.clamp((age - TAKEOVER) / (LAND - TAKEOVER), 0, 1);
            return -dir * SLIDE_OUT * (1 - expoOut(t));
        }
        if (age < PEAK) return dir * DRIFT * Math.clamp((age - LAND) / (PEAK - LAND), 0, 1);
        float t = Math.clamp((age - PEAK) / (SWAP - PEAK), 0, 1);
        return dir * (DRIFT + (SLIDE_OUT - DRIFT) * expoIn(t));
    }

    /** 停留期间慢慢放大（1 到 {@link #ZOOM}）。 */
    public static float zoom(float age) {
        return Mth.lerp(Math.clamp((age - LAND) / (PEAK - LAND), 0, 1), 1, ZOOM);
    }

    /**
     * 第 {@code line} 道光线长到多高（画面高度，从底下算）。刺穿时各自刺到 {@code pierce} 那么高，先后错开一点；黑底接管时全部贯穿。
     */
    public static float grow(float age, int line, float pierce) {
        if (age >= TAKEOVER) return 1;
        float t = Math.clamp((age - line * .25f) / (TAKEOVER - .6f), 0, 1);
        return pierce * cubicOut(t);
    }

    /** 中间那团白光云（0..1）：落位时炸开，很快回落到一点余光；预升时再亮起来。 */
    public static float cloud(float age, boolean gentle) {
        if (age < LAND || age >= SWAP) return 0;
        float since = age - LAND;
        float burst = gentle ? .35f * smooth(since / 2) : (float) Math.exp(-since / 1.2f);
        float rest = .22f * smooth(since / 2);
        float rise = age >= RISE ? .4f * smooth((age - RISE) / (PEAK - RISE)) : 0;
        return Math.min(1, Math.max(burst, rest) + rise);
    }

    /** 全屏白（0..1）：预升时开始泛白，峰值盖满，幕布期间一直盖满，然后退潮。 */
    public static float white(float age, boolean gentle) {
        if (age >= END) return 0;
        if (age >= FADE) {
            float t = (age - FADE) / (END - FADE);
            return gentle ? 1 - smooth(t) : (1 - t) * (1 - t);
        }
        if (age >= PEAK + 1) return 1;
        if (gentle) return smooth((age - (RISE - 4)) / (PEAK + 1 - (RISE - 4)));
        if (age >= PEAK) return Mth.lerp(age - PEAK, .35f, 1);
        if (age >= RISE) return .35f * smooth((age - RISE) / (PEAK - RISE));
        return 0;
    }

    /** 角色和底板的曝光：落位时冲高一点，停留时回落保证看得清，预升时再推高。 */
    public static float exposure(float age, boolean gentle) {
        float land = age >= LAND ? (gentle ? 0 : .25f * (float) Math.exp(-(age - LAND) / 1.5f)) : 0;
        float rise = age >= RISE ? .35f * smooth((age - RISE) / (PEAK - RISE)) : 0;
        return 1 + land + rise;
    }

    /** 光线粗细倍数：预升时膨胀。 */
    public static float beam(float age) {
        if (age < RISE) return 1;
        return 1 + 1.2f * smooth((age - RISE) / (PEAK + 1 - RISE));
    }

    /** 白光散去时留在 3D 画面上的光线残影（0..1）。 */
    public static float residual(float age) {
        if (age < SWAP || age >= END) return 0;
        return 1 - smooth((age - SWAP) / (END - SWAP));
    }

    /** 落位时的轻震（画面高度），按种子定方向。 */
    public static float shake(float age, long seed, boolean y) {
        if (age < LAND || age > LAND + 3) return 0;
        float since = age - LAND;
        float amp = .006f * (1 - since / 3);
        double phase = (seed & 0xFF) * .1 + (y ? 1.7 : 0);
        return amp * (float) Math.sin(since * 9 + phase);
    }

    private static float smooth(float t) {
        t = Math.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    private static float cubicOut(float t) {
        float u = 1 - t;
        return 1 - u * u * u;
    }

    private static float expoOut(float t) {
        return t >= 1 ? 1 : 1 - (float) Math.pow(2, -10 * t);
    }

    private static float expoIn(float t) {
        return t <= 0 ? 0 : (float) Math.pow(2, 10 * t - 10);
    }
}
