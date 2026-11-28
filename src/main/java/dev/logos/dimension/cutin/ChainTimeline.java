package dev.logos.dimension.cutin;

import net.minecraft.util.Mth;

/**
 * 连携切入的时间表（单位：游戏刻，可带小数），两端都按它算。八段照规格里拆帧的节奏：
 *
 * <ol>
 *   <li>先手刺穿 {@link #PIERCE}：战场压暗，几道光线从屏幕底下错落地刺上来，先把分格切好。</li>
 *   <li>黑底接管 {@link #TAKEOVER}：黑底盖住战场和界面，角色按格子奇偶一上一下冲进来，一个接一个（{@link #landing}）。</li>
 *   <li>落位爆闪 {@link #LAND}：玩家到位的那一下，中间炸开一团白光，能量底显出来，画面轻震；同伴跟着一个个落位。</li>
 *   <li>停留 {@link #HOLD}：都到位了。镜头继续推、扫、升降（{@link #progress}），角色极慢地漂，速度线和光屑一直在走。</li>
 *   <li>过曝预升 {@link #RISE}：整体变亮，光线变粗。</li>
 *   <li>峰值 {@link #PEAK}：光幕盖上来，角色顺着进场的方向甩出屏幕，镜头往前冲；黑底在光幕底下化开。</li>
 *   <li>幕布 {@link #SWAP}：分格卸掉、界面回来，只留光线残影。</li>
 *   <li>退潮 {@link #FADE} 到 {@link #END}：光幕很快退下去，压暗的战场慢慢回到原来的颜色，残影淡掉。</li>
 * </ol>
 *
 * <p>收尾不是一张全白：光幕用屏幕混合、中间亮四周暗（着色器里封顶），底下的画面是过曝烧白的，盖满的那一段也只有一刻半。
 *
 * <p>{@code gentle}：原版选项「隐藏闪电的闪烁」打开时，不爆闪，黑底和光幕都改成慢慢盖上、慢慢散开。光幕盖满的时刻不变。
 */
public final class ChainTimeline {
    public static final float PIERCE = 0, TAKEOVER = 2, LAND = 4.5f, HOLD = 9, RISE = 21, PEAK = 23, SWAP = 25.5f, FADE = 26, END = 33;
    /** 一个接一个落位，相隔多少刻（玩家先到，最后一位在 {@link #HOLD} 之前到）。 */
    public static final float STAGGER = 1.1f;
    /** 角色冲进来、甩出去时离开原位多远（画面高度）。 */
    public static final float SLIDE_OUT = 1.15f;
    /** 停留期间顺着进场方向多漂多远（画面高度）。 */
    public static final float DRIFT = .025f;
    /** 甩出去用几刻：越甩越快，在黑底化开之前大半已经出了格子。 */
    public static final float EXIT = 1.8f;
    /** 光幕从峰值起多久盖满（刻）。 */
    public static final float VEIL_RAMP = 1.5f;
    /** 冲镜：冲进来之前镜头远多少（离她距离的几成）；落位时冲过头多少；甩出时往前冲多少。 */
    public static final float RUSH = .6f, OVERSHOOT = .06f, LUNGE = .35f;
    /** 冲过头再回来用几刻。 */
    private static final float OVERSHOOT_TICKS = 3;

    private ChainTimeline() {}

    /** 第 {@code order} 个（{@link ChainLayout.Slot#order()}）在第几刻落位。 */
    public static float landing(int order) {
        return LAND + order * STAGGER;
    }

    /** 战场压暗多少（0..1）：刺穿时压下去，幕布之后跟着光幕慢慢恢复。 */
    public static float dim(float age) {
        if (age < SWAP) return smooth(age / TAKEOVER);
        return 1 - smooth((age - SWAP) / (END - SWAP));
    }

    /** 黑底盖住多少（0..1）：接管时盖上，峰值之后在光幕底下化开，幕布时已经没了。 */
    public static float plate(float age, boolean gentle) {
        if (age < TAKEOVER || age >= SWAP) return 0;
        float in = smooth((age - TAKEOVER) / (gentle ? 2 : .5f));
        float out = 1 - smooth((age - (PEAK + .8f)) / (SWAP - PEAK - .8f));
        return in * out;
    }

    /** 能量底（速度线、身后的光）显出多少（0..1）：落位时显形。 */
    public static float backdrop(float age) {
        if (age < LAND || age >= SWAP) return 0;
        return smooth((age - LAND) / 1.5f);
    }

    /** 角色这一刻画不画：黑底接管到幕布之间。 */
    public static boolean portraits(float age) {
        return age >= TAKEOVER && age < SWAP;
    }

    /** 界面这一刻藏不藏：和角色一样，幕布时回来。 */
    public static boolean hidesHud(float age) {
        return portraits(age);
    }

    /**
     * 第 {@code slot} 格（从左数）、第 {@code order} 个落位的角色离开原位多少（画面高度，正数是往上）。奇偶反向：第 1、3、5 格从上往下冲
     * （停留时继续往下漂、最后从下面甩出去），第 2、4 格反过来。冲进来一个接一个，甩出去一起。
     */
    public static float slide(float age, int slot, int order) {
        float dir = direction(slot), land = landing(order);
        if (age < land) {
            float t = Math.clamp((age - (land - (LAND - TAKEOVER))) / (LAND - TAKEOVER), 0, 1);
            return -dir * SLIDE_OUT * (1 - expoOut(t));
        }
        if (age < PEAK) return dir * DRIFT * Math.clamp((age - land) / (PEAK - land), 0, 1);
        return dir * (DRIFT + (SLIDE_OUT - DRIFT) * exit(age));
    }

    /** 第 {@code slot} 格冲进来时往哪走：-1 往下（从上面来），1 往上。 */
    public static float direction(int slot) {
        return slot % 2 == 0 ? -1 : 1;
    }

    /**
     * 冲镜（镜头离她的距离多出几成）：冲进来时从远处跟着冲过来，落位时冲过头一点（负数）再回来；停留时是 0（推镜另算）；
     * 甩出时往前冲。
     */
    public static float approach(float age, int order) {
        float land = landing(order);
        if (age < land) {
            float t = Math.clamp((age - (land - (LAND - TAKEOVER))) / (LAND - TAKEOVER), 0, 1);
            return RUSH * (1 - expoOut(t));
        }
        if (age < PEAK) {
            float s = (age - land) / OVERSHOOT_TICKS;
            return s < 1 ? -OVERSHOOT * (float) Math.sin(Math.PI * s) : 0;
        }
        return -LUNGE * exit(age);
    }

    /** 甩出去走到哪（0..1）：峰值起 {@link #EXIT} 刻，越走越快。 */
    private static float exit(float age) {
        float t = Math.clamp((age - PEAK) / EXIT, 0, 1);
        return t * t * t;
    }

    /** 停留走到哪（0..1）：落位后开始走，越走越慢但不停（到峰值时还有三成速度），落位前是 0。 */
    public static float progress(float age, int order) {
        float land = landing(order);
        float t = Math.clamp((age - land) / (PEAK - land), 0, 1);
        return .3f * t + .7f * (1 - (1 - t) * (1 - t));
    }

    /** 镜头额外歪多少（几成）：冲进来时最歪，落位后一两刻里摆正。 */
    public static float settle(float age, int order) {
        float since = age - landing(order);
        return since <= 0 ? 1 : (float) Math.exp(-since / 1.5f);
    }

    /**
     * 第 {@code line} 道光线长到多高（画面高度，从底下算）。刺穿时各自刺到 {@code pierce} 那么高，先后错开一点；黑底接管时全部贯穿。
     */
    public static float grow(float age, int line, float pierce) {
        if (age >= TAKEOVER) return 1;
        float t = Math.clamp((age - line * .25f) / (TAKEOVER - .6f), 0, 1);
        return pierce * cubicOut(t);
    }

    /** 中间那团白光云（0..1）：玩家落位时炸开，很快回落到一点余光；预升时再亮起来，峰值之后在光幕底下散掉。 */
    public static float cloud(float age, boolean gentle) {
        if (age < LAND) return 0;
        float since = age - LAND;
        float burst = gentle ? .35f * smooth(since / 2) : (float) Math.exp(-since / 1.2f);
        float rest = .22f * smooth(since / 2);
        float rise = age >= RISE ? .2f * smooth((age - RISE) / (PEAK - RISE)) : 0;
        return Math.min(1, Math.max(burst, rest) + rise) * (1 - smooth((age - PEAK) / 5));
    }

    /**
     * 光幕（0..1，着色器里按这个盖上去，最亮也不是全白）：预升时泛起一点，峰值后 {@link #VEIL_RAMP} 刻盖满，盖满到 {@link #FADE}
     * （一刻半，幕布在其中），然后很快退下去：前两刻退掉大半，后面拖一个尾巴。
     */
    public static float veil(float age, boolean gentle) {
        if (age >= END) return 0;
        if (age >= FADE) {
            float t = (age - FADE) / (END - FADE);
            return gentle ? 1 - smooth(t) : recede(t);
        }
        if (age >= PEAK + VEIL_RAMP) return 1;
        if (gentle) return smooth((age - (RISE - 4)) / (PEAK + VEIL_RAMP - (RISE - 4)));
        if (age >= PEAK) return Mth.lerp(smooth((age - PEAK) / VEIL_RAMP), .25f, 1);
        if (age >= RISE) return .25f * smooth((age - RISE) / (PEAK - RISE));
        return 0;
    }

    /** 曝光（乘在整个画面上，亮处烧白）：落位时冲高一点，停留时回落保证看得清，预升到峰值时推高，幕布之后回到 1。 */
    public static float exposure(float age, boolean gentle) {
        float land = age >= LAND && !gentle ? .25f * (float) Math.exp(-(age - LAND) / 1.5f) : 0;
        float rise = (gentle ? .08f : .15f) * smooth((age - RISE) / (PEAK + 1 - RISE)) * (1 - smooth((age - SWAP) / (FADE + 2 - SWAP)));
        return 1 + land + rise;
    }

    /** 光线粗细倍数：预升时膨胀。 */
    public static float beam(float age) {
        if (age < RISE) return 1;
        return 1 + .5f * smooth((age - RISE) / (PEAK + 1 - RISE));
    }

    /** 光幕退下去时留在 3D 画面上的光线残影（0..1）。 */
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

    /** 指数退：开头退得快，到 1 时正好是 0。 */
    private static float recede(float t) {
        double end = Math.exp(-3.5);
        return (float) ((Math.exp(-3.5 * Math.clamp(t, 0, 1)) - end) / (1 - end));
    }

    private static float cubicOut(float t) {
        float u = 1 - t;
        return 1 - u * u * u;
    }

    private static float expoOut(float t) {
        return t >= 1 ? 1 : 1 - (float) Math.pow(2, -10 * t);
    }
}
