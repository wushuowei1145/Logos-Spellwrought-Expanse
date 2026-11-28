package dev.logos.dimension.companion;

import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jspecify.annotations.Nullable;

/**
 * 精灵公主的奥义「万分之一 · 世界树祈愿矢」的时间表、几何和数（docs/ELF_PRINCESS_COMPANION.md 第 11 节）。
 * 服务端脚本（{@link PrincessUltimate}）和客户端的过场、世界里画的东西按同一张表走：同一个种子、同一个落点、同一块天空，两边算出来一样。
 *
 * <p>因果链（分镜稿「单箭变区域箭雨」）：一粒种子 → 抽枝化成一支祈愿主箭 → 射上天空绽开莲华，外围的光矢先落下洗地 → 主箭最后垂直贯地。
 */
public final class PrincessUltimatePlan {
    private PrincessUltimatePlan() {}

    // ---------------------------------------------------------------- 数

    /** 生命光环：以她为中心多远；回多少血、给多厚的护盾（都乘她的法强）；护盾留多久（刻）。 */
    public static final double HEAL_RADIUS = 16, HEAL = 10, SHIELD = 14;
    public static final int SHIELD_TICKS = 240;
    /** 光矢：几支；落点挑目标的半径；每支的生命伤害（Logos 再乘她的生命法强，同她的箭）。 */
    public static final int RAIN = 12;
    public static final double RAIN_RADIUS = 7, RAIN_DAMAGE = 4;
    /** 圣箭贯地：范围、生命伤害；生命易伤 II 几刻；把周围的震退、震起多少。 */
    public static final double IMPACT_RADIUS = 6, IMPACT_DAMAGE = 24, KNOCK = .55, LIFT = .5;
    public static final int VULNERABILITY_TICKS = 200, VULNERABILITY_LEVEL = 1;
    /** 森之加护：下一段的威力、耗魔；最后一击命中处的全队治愈脉冲（乘她的法强）和范围。 */
    public static final double BLESSING_POWER = 1.3, BLESSING_MANA = .75, PULSE_HEAL = 6, PULSE_RADIUS = 16;

    // ---------------------------------------------------------------- 时间表

    /**
     * 一版的时间表（刻，从开演算）。{@code -1} 表示这一版没有这一拍（缩短版不演种子破土和抽枝）。
     *
     * @param length  一共几刻
     * @param impact  圣箭贯地（{@code UltimateScript#impact}）：主要伤害、易伤、黑白闪、接力窗口起算
     * @param seed    种子破土
     * @param grow    新苗抽枝、螺旋往上长
     * @param nock    长成一支箭、搭上弓
     * @param ring    脚下绽开莲华光环：全队回血、清负面、上护盾
     * @param full    拉满
     * @param release 放箭
     * @param apex    主箭飞到敌群正上方停住，莲华法阵绽开
     * @param appear  第一支光矢在法阵下凝出来
     * @param appearSpan 十二支全凝出来用几刻
     * @param rain    第一支光矢落下
     * @param rainSpan 十二支一支接一支落下，从第一支到最后一支几刻
     * @param fall    一支光矢从法阵落到地面几刻
     * @param plunge  主箭开始垂直落下（到 {@code impact} 贯地）
     * @param wipe    花瓣擦镜开始（到 {@code length}）
     */
    public record Timeline(int length, int impact, int seed, int grow, int nock, int ring, int full, int release, int apex, int appear, int appearSpan,
                           int rain, int rainSpan, int fall, int plunge, int wipe) {
        /** 第 {@code k} 支光矢在第几刻凝出来。 */
        public int appear(int k) {
            return appear + Math.round(appearSpan * k / (float) (RAIN - 1));
        }

        /** 第 {@code k} 支光矢在第几刻落下。 */
        public int launch(int k) {
            return rain + Math.round(rainSpan * k / (float) (RAIN - 1));
        }

        /** 第 {@code k} 支光矢在第几刻落地。 */
        public int land(int k) {
            return launch(k) + fall;
        }

        /** 这一版有没有种子破土、抽枝那两镜。 */
        public boolean sprouts() {
            return seed >= 0;
        }
    }

    /**
     * 完整版，照分镜稿 104 刻：Cut 1 种子破土（0–13）、Cut 2 抽枝化箭（13–28）、Cut 3 满弓蓄势（28–41）、Cut 4 破空追踪（41–50）、
     * Cut 5 莲华化雨（50–64）、Cut 6 箭雨洗地（64–79）、Cut 7 圣箭贯地（79–91）、Cut 8 花瓣擦镜（91–104）。
     */
    public static final Timeline FULL = new Timeline(104, 79, 3, 13, 24, 28, 31, 41, 50, 52, 10, 64, 11, 3, 76, 91);

    /**
     * 缩短版（连携里第二位起演），44 刻：从满弓起（她已经搭好箭），放箭追一小段，莲华绽开、光矢一齐落下，圣箭贯地，擦镜。
     */
    public static final Timeline CONDENSED = new Timeline(44, 30, -1, -1, 0, 1, 2, 10, 15, 16, 4, 21, 5, 3, 27, 33);

    public static Timeline timeline(boolean condensed) {
        return condensed ? CONDENSED : FULL;
    }

    // ---------------------------------------------------------------- 几何

    /** 莲华法阵离地多高：头顶空着时 {@link #MAX_HEIGHT}，有顶（室内、山洞）时压到顶下面，最低 {@link #MIN_HEIGHT}。 */
    public static final double MAX_HEIGHT = 14, MIN_HEIGHT = 5;
    /** 法阵多大；光矢挂在法阵下面的两圈（半径、往下多少）。 */
    public static final float LOTUS_RADIUS = 7.5f, SLOT_INNER = 3.6f, SLOT_OUTER = 5.4f, DROP_INNER = 1.7f, DROP_OUTER = 2.6f;
    /** 主箭：刚搭上弓时多长，飞到天上变成多长。 */
    public static final float ARROW_SHORT = 1.3f, ARROW_GIANT = 7f;

    /** 从她指向目标（水平）；目标就在脚下时用她面朝的方向 {@code facing}。 */
    public static Vec3 toward(Vec3 feet, Vec3 center, Vec3 facing) {
        Vec3 d = new Vec3(center.x - feet.x, 0, center.z - feet.z);
        if (d.lengthSqr() < .25) d = new Vec3(facing.x, 0, facing.z);
        return d.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : d.normalize();
    }

    /** {@code dir} 的右手边（同 {@code UltimateFilm.Shot#right}）。 */
    public static Vec3 side(Vec3 dir) {
        return new Vec3(-dir.z, 0, dir.x);
    }

    /** 种子破土的地方：她左前方一步半。 */
    public static Vec3 seed(Vec3 feet, Vec3 dir) {
        return feet.add(dir.scale(1.5)).subtract(side(dir).scale(.45));
    }

    /** 搭箭的地方：她胸前、往前一点。 */
    public static Vec3 nock(Vec3 feet, Vec3 dir) {
        return feet.add(0, 1.3, 0).add(dir.scale(.45));
    }

    /** 法阵离地多高（见 {@link #MAX_HEIGHT}）。两端各算一次：方块一样，结果一样。 */
    public static double height(BlockGetter level, Vec3 center) {
        Vec3 from = center.add(0, 1, 0), to = center.add(0, MAX_HEIGHT + 3, 0);
        var hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        double room = hit.getType() == HitResult.Type.MISS ? MAX_HEIGHT + 3 : hit.getLocation().y - center.y;
        return Mth.clamp(room - 2.5, MIN_HEIGHT, MAX_HEIGHT);
    }

    /** 法阵中心。 */
    public static Vec3 apex(Vec3 center, double height) {
        return center.add(0, height, 0);
    }

    /** 种子定的起始角（弧度）：每次放的光矢排得不一样。 */
    public static double phase(long seed) {
        return (seed >>> 16 & 0xFFFF) / 65536.0 * Math.PI * 2;
    }

    /** 第 {@code k} 支光矢挂在法阵下的位置：双数在里圈、单数在外圈。 */
    public static Vec3 slot(Vec3 apex, long seed, int k) {
        double a = phase(seed) + Math.PI * 2 * k / RAIN;
        boolean outer = (k & 1) == 1;
        double r = outer ? SLOT_OUTER : SLOT_INNER, drop = outer ? DROP_OUTER : DROP_INNER;
        return apex.add(Math.cos(a) * r, -drop, Math.sin(a) * r);
    }

    /** 没有目标可追时第 {@code k} 支光矢落在哪：圈里斜着错开一点（分镜「斜掠洗地」），离中心远近按种子定。 */
    public static Vec3 ground(Vec3 center, long seed, int k) {
        double a = phase(seed) + Math.PI * 2 * k / RAIN + 1.1;
        double r = 1.2 + 4.3 * hash(seed, k);
        return center.add(Math.cos(a) * r, 0, Math.sin(a) * r);
    }

    /** 0–1 的确定的随机数。 */
    public static double hash(long seed, int k) {
        long h = seed * 0x9E3779B97F4A7C15L + (k + 1) * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (h & 0xFFFFFF) / (double) 0x1000000;
    }

    /** 主箭飞到第几成（放箭到 {@code apex}）：一出弦最快，到天上慢慢刹住。 */
    public static float flight(Timeline plan, float age) {
        float u = Mth.clamp((age - plan.release()) / Math.max(1f, plan.apex() - plan.release()), 0, 1);
        return 1 - (1 - u) * (1 - u);
    }

    /** 主箭贯下去走到第几成（{@code plunge} 到 {@code impact}）：越落越快。 */
    public static float plunge(Timeline plan, float age) {
        float u = Mth.clamp((age - plan.plunge()) / Math.max(1f, plan.impact() - plan.plunge()), 0, 1);
        return u * u;
    }

    /** 第 {@code age} 刻主箭（箭身中点）在哪；还没放出去时是 null。 */
    public static @Nullable Vec3 arrow(Timeline plan, float age, Vec3 nock, Vec3 apex, Vec3 center) {
        if (age < plan.release()) return null;
        if (age < plan.apex()) return nock.lerp(apex, flight(plan, age));
        if (age < plan.plunge()) return apex;
        // 最后插在地上：箭身一半没进土里
        return apex.lerp(center.add(0, ARROW_GIANT * .25, 0), plunge(plan, age));
    }

    /** 主箭朝哪：飞上去时朝天，到了法阵中心慢慢掉头朝下，贯下去时朝下。 */
    public static Vec3 heading(Timeline plan, float age, Vec3 nock, Vec3 apex) {
        Vec3 up = apex.subtract(nock).normalize(), down = new Vec3(0, -1, 0);
        float turn = Mth.clamp((age - plan.apex()) / 4f, 0, 1);
        if (turn <= 0) return up;
        turn = turn * turn * (3 - 2 * turn);
        Vec3 h = up.scale(1 - turn).add(down.scale(turn));
        return h.lengthSqr() < 1e-4 ? down : h.normalize();
    }

    /** 主箭多长：搭在弓上时是一支箭，飞上天时长成巨箭。 */
    public static float length(Timeline plan, float age) {
        if (age < plan.release()) return ARROW_SHORT;
        return Mth.lerp(flight(plan, age), ARROW_SHORT, ARROW_GIANT);
    }
}
