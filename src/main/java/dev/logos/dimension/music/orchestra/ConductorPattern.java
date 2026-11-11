package dev.logos.dimension.music.orchestra;

/**
 * 指挥的图形（docs/ORCHESTRA.md 第 7.2 节）：棒尖在身前一个竖直平面上画标准的拍子图形。坐标以拍点平面为准：
 * x 向指挥的右手边为正，y 向上为正，单位是图形的大小（再按响度缩放）。每一拍的「拍点」（棒尖最低、最快的一瞬）
 * 正好落在拍子上：拍点之后先弹起到这一拍的高点（拍内 0–0.4），再加速落向下一拍的拍点（0.4–1）。
 *
 * <p>拍号对应的图形：二拍 下→上；三拍 下→右→上；四拍 下→左→右→上；六拍 下、左、左、右、右、上。
 * 快的复拍子合拍（6/8 打二拍、9/8 打三拍、12/8 打四拍），快的三拍、二拍打一拍；其他拍子按 2+3、3+2 的分组拼起来。
 */
public final class ConductorPattern {
    private ConductorPattern() {}

    /** 实际打的拍：一小节几拍、这是第几拍、拍内相位、一拍多长。 */
    public record Conducted(int beats, int beat, double phase, double beatMs) {}

    /** 按速度把记谱的拍子换成实际打的拍子（快的复拍子合拍、快的三拍打一拍）。 */
    public static Conducted conduct(BeatMap.Beat b) {
        int n = b.beatsPerBar();
        // 复拍子（6/8、9/8、12/8）：每分钟八分音符超过约 110 个时，三个八分音符合成一拍
        if (b.beatUnit() == 8 && n % 3 == 0 && n >= 6 && b.beatMs() < 545) {
            int groups = n / 3;
            double inBar = b.beat() + b.phase();
            int g = (int) Math.floor(inBar / 3);
            return new Conducted(groups, g, (inBar - g * 3) / 3, b.beatMs() * 3);
        }
        // 很快的三拍、二拍（圆舞曲、急板）：一小节打一下
        if ((n == 3 || n == 2) && b.beatMs() < 280) {
            double inBar = b.beat() + b.phase();
            return new Conducted(1, 0, inBar / n, b.beatMs() * n);
        }
        if (b.beatUnit() == 8 && n == 3 && b.beatMs() < 400) {
            double inBar = b.beat() + b.phase();
            return new Conducted(1, 0, inBar / 3, b.beatMs() * 3);
        }
        return new Conducted(n, b.beat(), b.phase(), b.beatMs());
    }

    /** 第 beat 拍的拍点（beats 拍一小节）。 */
    static double[] ictus(int beats, int beat) {
        double[][] table = switch (beats) {
            case 1 -> new double[][]{{0, 0}};
            case 2 -> new double[][]{{0, 0}, {0.18, 0.08}};
            case 3 -> new double[][]{{0, 0}, {0.6, 0.06}, {0.3, 0.22}};
            case 4 -> new double[][]{{0, 0}, {-0.55, 0.06}, {0.62, 0.06}, {0.3, 0.26}};
            case 6 -> new double[][]{{0, 0}, {-0.28, 0.06}, {-0.55, 0.08}, {0.4, 0.06}, {0.66, 0.08}, {0.3, 0.28}};
            default -> null;
        };
        if (table != null) return table[Math.floorMod(beat, beats)];
        // 其他拍子：前面的拍分组往左右排开，最后一拍上挑
        if (Math.floorMod(beat, beats) == 0) return new double[]{0, 0};
        if (Math.floorMod(beat, beats) == beats - 1) return new double[]{0.3, 0.26};
        double t = (Math.floorMod(beat, beats) - 1) / (double) Math.max(1, beats - 2);
        return new double[]{-0.55 + t * 1.2, 0.06};
    }

    /** 第 beat 拍弹起的高点：最后一拍弹得最高（为下一小节的下拍蓄势）。 */
    static double[] apex(int beats, int beat) {
        double[] i = ictus(beats, beat);
        if (Math.floorMod(beat, beats) == beats - 1) return new double[]{0.0, 1.0};
        double outward = beats == 1 ? 0 : Math.signum(i[0]) * 0.08;
        return new double[]{i[0] + outward, i[1] + 0.32};
    }

    /** 棒尖此刻的位置 {x, y}。 */
    public static double[] tip(Conducted c) {
        double[] from = ictus(c.beats(), c.beat());
        double[] top = apex(c.beats(), c.beat());
        double[] to = ictus(c.beats(), c.beat() + 1);
        double p = c.phase();
        if (p < 0.4) {
            // 弹起：先快后慢
            double t = p / 0.4;
            double e = 1 - (1 - t) * (1 - t);
            return new double[]{lerp(from[0], top[0], e), lerp(from[1], top[1], e)};
        }
        // 落下：先慢后快，拍点前最快；中间略往外鼓一点，走成弧线
        double t = (p - 0.4) / 0.6;
        double e = t * t;
        double bulge = Math.sin(Math.PI * t) * 0.08 * Math.signum(to[0] - top[0]);
        return new double[]{lerp(top[0], to[0], e) + bulge, lerp(top[1], to[1], e)};
    }

    /** 收束：最后一个音之后画一个小圆弧收住（t 从 0 到 1），再落回胸前。 */
    public static double[] cutoff(double t) {
        if (t >= 1) return new double[]{0, 0.35};
        double a = Math.PI * 2 * t;
        double r = 0.22 * (1 - t * 0.5);
        return new double[]{Math.sin(a) * r, 0.35 + (1 - Math.cos(a)) * r * 0.8 - t * 0.2};
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
