package dev.logos.dimension.fox;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleBinaryOperator;

/**
 * 上山小路的规划结果：每格一个采样点（世界坐标 ×8 存整数），路面高度单调不升；
 * 溪流沿路走，换边处从路下穿过即为石板桥。
 *
 * <p>规划在设计坐标里完成：从入口石阶底出发，沿核心外的人工山坡向北绕行，离核心越来越远，
 * 高度随山坡下降；途中左右蜿蜒。入口在西侧，镜像与旋转由结构按地形选定。
 */
final class SanctuaryTrail {
    /** 路面半宽（3 格宽的路）。 */
    static final double HALF_WIDTH = 1.6;
    static final double STREAM_OFFSET = 3.7;

    final int[] x8, z8, y;
    /** 0 = 山脚土路，1 = 山腰乱石阶，2 = 近山顶石板路。 */
    final byte[] zone;
    final int[] sx8, sz8, sy;
    /** 鸟居所在的采样序号；石灯笼：序号 ×2 + (在路外侧 ? 1 : 0)。 */
    final int[] torii, lanterns;
    /** 竹林段：[起, 止, 侧(1 外侧 / -1 内侧)] 依次排列。 */
    final int[] bamboo;
    /** 规划代价：自然地形高出路面需要开挖的量，越小越贴合。 */
    final double cost;

    private SanctuaryTrail(int[] x8, int[] z8, int[] y, byte[] zone, int[] sx8, int[] sz8, int[] sy,
                           int[] torii, int[] lanterns, int[] bamboo, double cost) {
        this.x8 = x8;
        this.z8 = z8;
        this.y = y;
        this.zone = zone;
        this.sx8 = sx8;
        this.sz8 = sz8;
        this.sy = sy;
        this.torii = torii;
        this.lanterns = lanterns;
        this.bamboo = bamboo;
        this.cost = cost;
    }

    int size() {
        return y.length;
    }

    double x(int i) {
        return x8[i] / 8.0;
    }

    double z(int i) {
        return z8[i] / 8.0;
    }

    /** 采样点处的路面切向（单位向量，指向下山方向）。 */
    double[] tangent(int i) {
        int a = Math.max(0, i - 2), b = Math.min(size() - 1, i + 2);
        double dx = x(b) - x(a), dz = z(b) - z(a);
        double len = Math.max(1e-6, Math.hypot(dx, dz));
        return new double[]{dx / len, dz / len};
    }

    /** 山路样式的数量：0 山野小径，1 石段参道，2 竹林道。 */
    static final int STYLES = 3;

    /**
     * @param natural 自然地表高度的估计（生成前用生成器采样）
     * @param style   0 山野小径：土路木阶 → 乱石阶 → 石板路，鸟居只在近山顶一段；
     *                1 石段参道：全程石阶与乱石，一路鸟居，石灯密；
     *                2 竹林道：土路与乱石为主，两侧长段竹林，鸟居只在山顶几座，石灯稀
     */
    static SanctuaryTrail plan(SanctuaryFrame frame, DoubleBinaryOperator natural, int style) {
        SanctuaryLayout layout = frame.layout;
        int s = layout.size;
        int y0 = frame.p + layout.entranceY;
        int drop = Math.max(12, y0 - frame.foot);
        double planLength = Mth.clamp(drop * 7.5, 160, 440);
        double ring = SanctuaryFrame.RING;
        double rc = 18;
        double cornerLen = Math.PI / 2 * rc;
        double startV = layout.entranceV + 0.5;
        // 方形核心外的回环：西边（自入口向北）→ 西北角 → 北边 → 东北角 → 东边 → …
        double[] segLen = {startV, cornerLen, s, cornerLen, s, cornerLen, s, cornerLen, s - startV};
        double phase1 = (frame.seed & 255) / 40.0, phase2 = ((frame.seed >> 8) & 255) / 40.0;

        List<double[]> raw = new ArrayList<>();
        double total = 0;
        for (double l : segLen) total += l;
        for (double t = 0; t <= total; t += 0.25) {
            double progress = Math.min(1, t / planLength);
            double ease = progress * progress * (3 - 2 * progress);
            double fadeIn = Mth.clamp(t / 14.0, 0, 1);
            double meander = (4.6 * Math.sin(t * Math.PI * 2 / 67 + phase1) + 2.2 * Math.sin(t * Math.PI * 2 / 29 + phase2)) * fadeIn;
            double d = Mth.clamp(1.2 + (ring - 6) * (0.15 * progress + 0.85 * ease) + meander, 1.2, ring - 3);
            double[] cn = boundary(t, segLen, s, startV);
            raw.add(new double[]{cn[0] + cn[2] * d, cn[1] + cn[3] * d, cn[2], cn[3], progress});
            if (progress >= 1 && t > planLength + 60) break;
        }

        // 按实际路长重采样为每格一点
        List<double[]> pts = new ArrayList<>();
        double acc = 0;
        double[] prev = raw.get(0);
        pts.add(prev);
        for (int i = 1; i < raw.size(); i++) {
            double[] cur = raw.get(i);
            acc += Math.hypot(cur[0] - prev[0], cur[1] - prev[1]);
            if (acc >= 1) {
                pts.add(cur);
                acc = 0;
            }
            prev = cur;
        }

        int n = pts.size();
        double[] wx = new double[n], wz = new double[n], ground = new double[n], nat = new double[n];
        double[] nx = new double[n], nz = new double[n], prog = new double[n];
        for (int i = 0; i < n; i++) {
            double[] p = pts.get(i);
            double[] w = frame.worldCont(p[0], p[1]);
            wx[i] = w[0];
            wz[i] = w[1];
            // 外法线也换到世界方向
            double[] o = frame.worldCont(p[0] + p[2], p[1] + p[3]);
            nx[i] = o[0] - w[0];
            nz[i] = o[1] - w[1];
            prog[i] = p[4];
            nat[i] = natural.applyAsDouble(w[0], w[1]);
            ground[i] = frame.planned(Mth.floor(w[0]), Mth.floor(w[1]), nat[i]);
        }

        // 路面高度：跟随平滑后的地表下降，坡度至多约 1:2，永不回升
        double[] smooth = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = 0;
            int c = 0;
            for (int k = Math.max(0, i - 4); k <= Math.min(n - 1, i + 4); k++) {
                sum += ground[k];
                c++;
            }
            smooth[i] = sum / c;
        }
        int[] ys = new int[n];
        double yc = y0;
        int end = n;
        for (int i = 0; i < n; i++) {
            double want = Math.min(yc, Math.max(smooth[i], yc - 0.55));
            if (i < 3) want = y0;
            yc = want;
            ys[i] = (int) Math.floor(yc + 0.5);
            // 走完规划长度且已贴近地面就收尾
            if (prog[i] >= 1 && ys[i] <= ground[i] + 1.5) {
                end = i + 1;
                break;
            }
        }
        n = end;

        int[] x8 = new int[n], z8 = new int[n], y = new int[n];
        byte[] zone = new byte[n];
        for (int i = 0; i < n; i++) {
            x8[i] = (int) Math.round(wx[i] * 8);
            z8[i] = (int) Math.round(wz[i] * 8);
            y[i] = ys[i];
            double f = (double) i / n;
            zone[i] = (byte) switch (style) {
                case 1 -> f < 0.55 ? 2 : 1;
                case 2 -> f < 0.12 ? 2 : f < 0.45 ? 1 : 0;
                default -> f < 0.32 ? 2 : f < 0.62 ? 1 : 0;
            };
        }

        // 溪流：自靠近山顶处的泉眼起沿路外侧下行，在两处从路下穿过换到内侧再换回
        int start = Math.min(n - 1, 7);
        int cross1 = (int) (n * 0.40), cross2 = (int) (n * 0.74);
        int sn = n - start;
        int[] sx8 = new int[sn], sz8 = new int[sn], sy = new int[sn];
        for (int i = start; i < n; i++) {
            double side = sideAt(i, cross1, cross2);
            double len = Math.max(1e-6, Math.hypot(nx[i], nz[i]));
            double ox = nx[i] / len, oz = nz[i] / len;
            sx8[i - start] = (int) Math.round((wx[i] + ox * STREAM_OFFSET * side) * 8);
            sz8[i - start] = (int) Math.round((wz[i] + oz * STREAM_OFFSET * side) * 8);
            sy[i - start] = ys[i] - 2;
        }

        // 鸟居：山野小径只在快到山顶的一段，越靠近入口越密；石段参道一路都有；竹林道只在山顶几座
        List<Integer> toriiList = new ArrayList<>();
        int lastTorii = -100;
        double toriiEnd = style == 1 ? n - 10 : style == 2 ? n * 0.12 : n * 0.36;
        for (int i = 6; i < toriiEnd; i++) {
            int gap = style == 1 ? 11 : i < n * 0.15 ? 9 : 14;
            if (i - lastTorii < gap) continue;
            double tx = x(x8, i + 2, n) - x(x8, i - 2, n), tz = x(z8, i + 2, n) - x(z8, i - 2, n);
            double len = Math.hypot(tx, tz);
            if (len < 1e-6 || Math.max(Math.abs(tx), Math.abs(tz)) / len < 0.9) continue;
            if (Math.abs(ys[Math.min(n - 1, i + 3)] - ys[Math.max(0, i - 3)]) > 2) continue;
            toriiList.add(i);
            lastTorii = i;
        }

        // 石灯笼：沿路疏落地放在溪流的另一侧；山脚一对
        List<Integer> lanternList = new ArrayList<>();
        int lanternGap = style == 1 ? 13 : style == 2 ? 41 : 31;
        for (int i = 20; i < n - 8; i += lanternGap) {
            boolean streamInner = i >= start && sideAt(i, cross1, cross2) < 0;
            lanternList.add(i * 2 + (streamInner ? 1 : 0));
        }
        lanternList.add((n - 3) * 2);
        lanternList.add((n - 3) * 2 + 1);

        int[] bamboo = switch (style) {
            case 1 -> new int[]{(int) (n * 0.70), (int) (n * 0.80), 1};
            case 2 -> new int[]{(int) (n * 0.10), (int) (n * 0.90), 1, (int) (n * 0.14), (int) (n * 0.86), -1};
            default -> new int[]{
                (int) (n * 0.44), (int) (n * 0.62), 1,
                (int) (n * 0.20), (int) (n * 0.30), -1,
                (int) (n * 0.80), (int) (n * 0.92), 1,
            };
        };

        double cost = 0;
        for (int i = 0; i < n; i++) {
            cost += Math.max(0, nat[i] - ys[i] - 1);
            cost += Math.max(0, ys[i] - nat[i] - 8) * 0.15;
        }
        cost += Math.abs(nat[n - 1] - ys[n - 1]) * 2;
        return new SanctuaryTrail(x8, z8, y, zone, sx8, sz8, sy,
            toriiList.stream().mapToInt(Integer::intValue).toArray(),
            lanternList.stream().mapToInt(Integer::intValue).toArray(), bamboo, cost);
    }

    /** 溪流在路的哪一侧：1 = 外侧（下坡），-1 = 内侧；换边处平滑穿过路面。 */
    private static double sideAt(int i, int cross1, int cross2) {
        double a = Mth.clamp((i - cross1) / 9.0, -1, 1), b = Mth.clamp((i - cross2) / 9.0, -1, 1);
        double first = -Math.sin(a * Math.PI / 2), second = Math.sin(b * Math.PI / 2);
        // cross1 之前为 1，之后为 -1；cross2 之后回到 1
        return i < (cross1 + cross2) / 2 ? first : second;
    }

    private static double x(int[] arr, int i, int n) {
        return arr[Mth.clamp(i, 0, n - 1)] / 8.0;
    }

    /** 方形核心边界上的点与外法线；t 为沿回环的参数长度。 */
    private static double[] boundary(double t, double[] seg, int s, double startV) {
        double[][] corners = {{0, 0}, {s, 0}, {s, s}, {0, s}};
        // 西边向北
        if (t < seg[0]) return new double[]{0, startV - t, -1, 0};
        t -= seg[0];
        int side = 0;
        for (int k = 1; k < seg.length; k++) {
            if (t < seg[k] || k == seg.length - 1) {
                if (k % 2 == 1) {
                    // 角：位置固定在角点，法线转过 90°
                    double[] c = corners[side];
                    double a = Math.PI + Math.PI / 2 * Math.min(1, t / seg[k]) + side * Math.PI / 2;
                    return new double[]{c[0], c[1], Math.cos(a), Math.sin(a)};
                }
                double f = Math.min(t, seg[k]);
                return switch (side) {
                    case 1 -> new double[]{f, 0, 0, -1};
                    case 2 -> new double[]{s, f, 1, 0};
                    case 3 -> new double[]{s - f, s, 0, 1};
                    default -> new double[]{0, s - f, -1, 0};
                };
            }
            t -= seg[k];
            if (k % 2 == 1) side++;
        }
        return new double[]{0, 0, -1, 0};
    }

    void save(CompoundTag tag) {
        tag.putIntArray("TX", x8);
        tag.putIntArray("TZ", z8);
        tag.putIntArray("TY", y);
        tag.putByteArray("Zone", zone);
        tag.putIntArray("SX", sx8);
        tag.putIntArray("SZ", sz8);
        tag.putIntArray("SY", sy);
        tag.putIntArray("Torii", torii);
        tag.putIntArray("Lanterns", lanterns);
        tag.putIntArray("Bamboo", bamboo);
    }

    static SanctuaryTrail load(CompoundTag tag) {
        int[] none = new int[0];
        return new SanctuaryTrail(tag.getIntArray("TX").orElse(none), tag.getIntArray("TZ").orElse(none), tag.getIntArray("TY").orElse(none),
            tag.getByteArray("Zone").orElse(new byte[0]), tag.getIntArray("SX").orElse(none), tag.getIntArray("SZ").orElse(none),
            tag.getIntArray("SY").orElse(none), tag.getIntArray("Torii").orElse(none), tag.getIntArray("Lanterns").orElse(none),
            tag.getIntArray("Bamboo").orElse(none), 0);
    }
}
