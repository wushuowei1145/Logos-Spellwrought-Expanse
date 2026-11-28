package dev.logos.dimension.ultimate;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 玩家空手最后一击「星穹誓约 · 羁绊焚天炮」的时间表（docs/ULTIMATES.md 第六节）：服务端脚本 {@link HeroFinisher} 和客户端画面
 * 按同一张表走，单元测试直接测。都是开演以后的游戏刻，一共 {@link #LENGTH} 刻（7.8 秒），第 {@link #IMPACT} 刻砸下去、结算伤害。
 *
 * <p>八镜：
 * <ol>
 *   <li>{@link #IGNITE} 暗中燃火：全黑，掌心悄悄燃起一朵火苗；</li>
 *   <li>{@link #REVEAL} 渐亮 · 伸臂托火：曝光慢慢抬起来，侧身伸臂托着火，几道金光交汇到掌心；</li>
 *   <li>{@link #CONVERGE} 万流归宗：参战同伴的光流从四面八方灌进掌心，火球暴涨；</li>
 *   <li>{@link #APEX} 魔弹成型：坍缩成比人大几倍的魔弹，最后 {@link #HUSH} 起屏息定格；</li>
 *   <li>{@link #LUNGE} 倾力暴砸：握着魔弹冲向锁住的目标，最后几刻把它砸出去；</li>
 *   <li>{@link #IMPACT} 极远超广角 · 黑白闪：硬切到几十格外的大远景，满规格的四拍黑白闪；</li>
 *   <li>{@link #PILLAR} 巨型火柱直冲天际：仰拍通天的火柱和云洞；</li>
 *   <li>{@link #FALL} 星火归位：漫天星火落下，镜头推回来，星芒擦镜回到玩家视角。</li>
 * </ol>
 */
public final class HeroFinisherTimeline {
    /** 一共多少刻。作为主角压轴不受同伴 5.5 秒的限制。 */
    public static final int LENGTH = 156;
    /** 魔弹砸到目标、结算伤害、满规格黑白闪的那一刻（Cut 6 第一刻）。 */
    public static final int IMPACT = 99;

    /** 每一镜从第几刻开始。 */
    public static final int IGNITE = 0, REVEAL = 18, CONVERGE = 37, APEX = 60, LUNGE = 82, BLAST = IMPACT, PILLAR = 111, FALL = 137;
    /** 火苗在全黑里燃起来的那一刻。 */
    public static final int FLAME = 6;
    /** 屏息定格（0.25 秒）：镜头停住，声音抽空。 */
    public static final int HUSH = 77;
    /** 最后几刻魔弹脱手、砸到目标上。 */
    public static final int THROW = 95;
    /** 火柱从地上冲起来的那一刻（黑白闪退去）。 */
    public static final int RISE = 104;
    /** 擦镜从第几刻开始（一直擦到最后）。 */
    public static final int WIPE = 144;
    /** 起手压黑用几刻；开了「隐藏闪电的闪烁」时慢一点，不硬切。 */
    public static final int BLACK_IN = 3, BLACK_IN_GENTLE = 6;

    /** 暖金、炽白（分镜的主色）。 */
    public static final int GOLD = 0xFFD166, WHITE = 0xFFFFFF;

    /** 冲刺最多冲几格；离目标还剩多远停下（魔弹握在身前，剩下这段扔出去）。 */
    public static final double MAX_LUNGE = 11, REACH = 3.2;
    /** 极远机位离爆发点多远：分镜 45–85 格。 */
    public static final double FAR_MIN = 45, FAR_MAX = 85;
    /** 机位离山体、地面至少留多远；洞里离岩壁留多远（砸下时镜头要抖）。 */
    static final double FAR_MARGIN = 2, CAVE_MARGIN = 2.2;
    /** 封闭的洞穴里，镜头最远退多远也不到这个数时就算洞里（改洞内极限退距 + 穿顶仰拍）。 */
    public static final double CAVE_BELOW = 18;

    private HeroFinisherTimeline() {}

    // ---------------------------------------------------------------- 曲线

    /** 两头慢、中间快，{@code x} 在 {@code a} 到 {@code b} 之间从 0 走到 1。 */
    public static float smooth(float a, float b, float x) {
        float t = Mth.clamp((x - a) / (b - a), 0, 1);
        return t * t * (3 - 2 * t);
    }

    /**
     * 画面的曝光（0 全黑、1 原样）：开演几刻里压黑，Cut 1 一直黑着，Cut 2 慢慢抬起来。
     * {@code gentle}（「隐藏闪电的闪烁」）时压黑用 {@link #BLACK_IN_GENTLE} 刻，不硬切。
     */
    public static float exposure(float age, boolean gentle) {
        if (age < 0 || age >= CONVERGE) return 1;
        if (age < REVEAL) return 1 - smooth(0, gentle ? BLACK_IN_GENTLE : BLACK_IN, age);
        float t = Mth.clamp((age - REVEAL) / (CONVERGE - REVEAL), 0, 1);
        // 先慢后快、最后刹住：火光一点点照亮天空
        return smooth(0, 1, t * t * (2 - t));
    }

    /** 全黑里那一朵火苗有多亮（屏幕上画的那一层）：燃起来一下，Cut 2 随曝光让给世界里的火。 */
    public static float flame(float age) {
        if (age < FLAME || age >= CONVERGE) return 0;
        float pop = smooth(FLAME, FLAME + 4, age);
        return pop * (1 - exposure(age, false));
    }

    /** 掌心的火 / 魔弹有多大（半径，格）。 */
    public static float orbRadius(float age) {
        if (age < FLAME || age >= IMPACT + 1) return 0;
        if (age < CONVERGE) return Mth.lerp(smooth(FLAME, FLAME + 4, age), 0, .14f) + .06f * smooth(REVEAL, CONVERGE, age);
        if (age < APEX) {
            // 光流灌进来，越涨越快
            float t = (age - CONVERGE) / (APEX - CONVERGE);
            return .2f + 1.3f * t * t;
        }
        if (age < LUNGE) return Mth.lerp(smooth(APEX, APEX + 6, age), 1.5f, 3.1f);
        // 握在身前往前冲时压实一点
        return Mth.lerp(smooth(LUNGE, LUNGE + 6, age), 3.1f, 2.5f);
    }

    /** 魔弹从掌心升到头顶前方用了几成（Cut 3 末到 Cut 4 初）。 */
    public static float lift(float age) {
        return smooth(APEX - 6, APEX + 6, age);
    }

    /** 魔弹扔出去走了几成（{@link #THROW} 到 {@link #IMPACT}）。越来越快地砸下去。 */
    public static float thrown(float age) {
        float t = Mth.clamp((age - THROW) / (IMPACT - THROW), 0, 1);
        return t * t;
    }

    /** 冲刺走了几成（{@link #LUNGE} 到 {@link #THROW}）：一蹬就快，快到了收一点。 */
    public static float lunge(float age) {
        float t = Mth.clamp((age - LUNGE) / (THROW - LUNGE), 0, 1);
        return 1 - (1 - t) * (1 - t);
    }

    /** 火柱冲到了几成高（{@link #RISE} 起，八刻冲到顶）。 */
    public static float pillar(float age) {
        float t = Mth.clamp((age - RISE) / 8, 0, 1);
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    /** 火柱还剩几成（Cut 8 化作星火散掉）。 */
    public static float pillarFade(float age) {
        return 1 - smooth(FALL - 4, FALL + 12, age);
    }

    // ---------------------------------------------------------------- 混色

    /**
     * 参战同伴的主色混成一条随 {@code x} 循环的颜色（光带、外环、火柱的螺旋）：{@code x} 每走 1 绕完所有人的颜色一圈，
     * 相邻两人之间平滑过渡。没有同伴时是暖金。不写死四色：几位同伴参战就混几色。
     */
    public static int mix(int[] accents, float x) {
        if (accents.length == 0) return GOLD;
        if (accents.length == 1) return accents[0] & 0xFFFFFF;
        float f = (x - Mth.floor(x)) * accents.length;
        int i = Mth.floor(f) % accents.length;
        float k = f - Mth.floor(f);
        k = k * k * (3 - 2 * k);
        return lerpColor(accents[i], accents[(i + 1) % accents.length], k);
    }

    /** 参战同伴主色的平均（再往暖金靠一点），没有同伴时是暖金。 */
    public static int blend(int[] accents) {
        if (accents.length == 0) return GOLD;
        float r = 0, g = 0, b = 0;
        for (int c : accents) {
            r += (c >> 16) & 0xFF;
            g += (c >> 8) & 0xFF;
            b += c & 0xFF;
        }
        int avg = ((int) (r / accents.length) << 16) | ((int) (g / accents.length) << 8) | (int) (b / accents.length);
        return lerpColor(avg, GOLD, .35f);
    }

    public static int lerpColor(int a, int b, float t) {
        int r = (int) Mth.lerp(t, (a >> 16) & 0xFF, (b >> 16) & 0xFF);
        int g = (int) Mth.lerp(t, (a >> 8) & 0xFF, (b >> 8) & 0xFF);
        int bl = (int) Mth.lerp(t, a & 0xFF, b & 0xFF);
        return (r << 16) | (g << 8) | bl;
    }

    // ---------------------------------------------------------------- 极远机位

    /** 从 {@code from} 沿 {@code dir}（单位向量）走，最多 {@code max} 格，碰到方块（或水）之前空着多远。 */
    @FunctionalInterface
    public interface Clear {
        double free(Vec3 from, Vec3 dir, double max);
    }

    /**
     * Cut 6–7 的机位：{@code from} 站哪、{@code look} 看哪（火柱脚下往上一点）、{@code cave} 是不是封闭的洞里。
     * 洞里时镜头退到洞内最远处、压低，仰拍火柱穿顶。
     */
    public record Far(Vec3 from, Vec3 look, boolean cave) {}

    /** 找机位时先试的方位（度，0 是主角身后、正的往他右手边转）和仰角（度）。先试斜后方，看得到主角那一侧。 */
    private static final float[] YAWS = {-35, 35, -60, 60, 0, -90, 90, -120, 120, -150, 150, 180};
    private static final float[] PITCHES = {32, 24, 40, 16, 8};

    /**
     * 极远超广角的机位：从爆发点 {@code burst} 往斜上方退 {@link #FAR_MIN}–{@link #FAR_MAX} 格（不超过 {@code limit}，渲染距离以内），
     * 每个方向先用 {@code clear} 量一下，碰到山体、地面就不往那边退。{@code forward} 是主角冲过来的方向（水平）。
     * 哪个方向都退不到 {@link #CAVE_BELOW} 格就当是封闭的洞：沿水平找最远的一边退到洞里，压低了仰拍。
     */
    public static Far far(Vec3 burst, Vec3 forward, double limit, Clear clear) {
        Vec3 back = new Vec3(-forward.x, 0, -forward.z);
        back = back.lengthSqr() < 1e-6 ? new Vec3(0, 0, -1) : back.normalize();
        double max = Math.max(CAVE_BELOW + FAR_MARGIN, Math.min(FAR_MAX, limit));
        double want = Math.min(max, Math.max(FAR_MIN, (FAR_MIN + FAR_MAX) / 2 + 5));
        Vec3 origin = burst.add(0, 1.5, 0);
        Vec3 best = null;
        double bestFree = 0;
        for (float pitch : PITCHES) {
            for (float yaw : YAWS) {
                Vec3 dir = direction(back, yaw, pitch);
                double free = clear.free(origin, dir, max) - FAR_MARGIN;
                if (free >= want) return new Far(origin.add(dir.scale(want)), look(burst), false);
                if (free > bestFree) {
                    bestFree = free;
                    best = dir;
                }
            }
        }
        if (best != null && bestFree >= Math.min(FAR_MIN, max - FAR_MARGIN)) return new Far(origin.add(best.scale(bestFree)), look(burst), false);
        if (best != null && bestFree >= CAVE_BELOW) return new Far(origin.add(best.scale(bestFree)), look(burst), false);
        // 封闭的洞：贴着地面找最远的一边，退到那里仰拍
        Vec3 low = burst.add(0, 1.2, 0);
        Vec3 side = null;
        double sideFree = 0;
        for (float yaw : YAWS) {
            for (float pitch : new float[]{4, 0, 10}) {
                Vec3 dir = direction(back, yaw, pitch);
                double free = clear.free(low, dir, max) - CAVE_MARGIN;
                if (free > sideFree) {
                    sideFree = free;
                    side = dir;
                }
            }
        }
        Vec3 from = side == null ? low : low.add(side.scale(Math.max(0, sideFree)));
        // 仰拍火柱穿顶：看向洞顶往下一点，火柱和它捅进去的洞顶都在画面里
        double roof = clear.free(low, new Vec3(0, 1, 0), 40);
        return new Far(from, low.add(0, Math.max(1.5, roof * .75), 0), true);
    }

    private static Vec3 look(Vec3 burst) {
        return burst.add(0, 8, 0);
    }

    /** {@code back} 往右转 {@code yaw} 度、抬起 {@code pitch} 度的单位向量。 */
    static Vec3 direction(Vec3 back, float yaw, float pitch) {
        double r = Math.toRadians(yaw), p = Math.toRadians(pitch);
        Vec3 right = new Vec3(-back.z, 0, back.x);
        Vec3 flat = back.scale(Math.cos(r)).add(right.scale(Math.sin(r)));
        return flat.scale(Math.cos(p)).add(0, Math.sin(p), 0).normalize();
    }
}
