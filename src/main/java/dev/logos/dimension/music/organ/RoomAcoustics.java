package dev.logos.dimension.music.organ;

/**
 * 由射线统计估计房间的混响（纯数学部分，客户端的射线在 {@code client.music.OrganRoom}）。
 *
 * <ul>
 *   <li>enclosure：射线碰到方块的比例（0–1）；不到六成当作露天，没有混响。</li>
 *   <li>meanDistance：碰到方块的射线的平均距离（格），越远房间越大、混响越长。</li>
 *   <li>absorption：碰到的方块的平均吸音系数，石头、玻璃约 0.03，木头 0.1，羊毛 0.6。</li>
 * </ul>
 * 混响时间仿照赛宾公式（与房间尺寸成正比、与吸音成反比），截在 0.3–4 秒。
 */
public final class RoomAcoustics {
    public static final double OPEN_AIR = 0.55;
    public static final double MAX_SECONDS = 4.0;

    /** 手动档位：自动、关、小厅、教堂。 */
    public static final int AUTO = 0, OFF = 1, HALL = 2, CATHEDRAL = 3;
    public static final double HALL_SECONDS = 1.2, CATHEDRAL_SECONDS = 3.5;

    /** 一次估计的结果：混响秒数（0 表示没有）与明亮程度。 */
    public record Reverb(double seconds, double brightness) {
        public static final Reverb NONE = new Reverb(0, 1);

        public boolean on() {
            return seconds > 0.05;
        }
    }

    private RoomAcoustics() {}

    public static Reverb estimate(double enclosure, double meanDistance, double absorption) {
        if (enclosure < OPEN_AIR) return Reverb.NONE;
        double a = Math.max(0.01, absorption);
        double rt = 0.12 * Math.max(1, meanDistance) * Math.sqrt(0.08 / a);
        double t = Math.min(1, (enclosure - OPEN_AIR) / (0.9 - OPEN_AIR));
        double closed = t * t * (3 - 2 * t);
        rt = Math.min(MAX_SECONDS, Math.max(0.3, rt)) * closed;
        double bright = Math.max(0, Math.min(1, 1 - (a - 0.02) / 0.4));
        return rt < 0.15 ? Reverb.NONE : new Reverb(rt, bright);
    }

    /** 按档位取最终的混响。auto 是自动估计的结果；soundPhysics 为真时「自动」视为「关」。 */
    public static Reverb choose(int mode, Reverb auto, boolean soundPhysics) {
        return switch (mode) {
            case OFF -> Reverb.NONE;
            case HALL -> new Reverb(HALL_SECONDS, 0.6);
            case CATHEDRAL -> new Reverb(CATHEDRAL_SECONDS, 0.75);
            default -> soundPhysics ? Reverb.NONE : auto;
        };
    }

    /** 尾音缓存用的档：0.25 秒一档，亮度分两档。 */
    public static int bucket(Reverb r) {
        if (!r.on()) return 0;
        return (int) Math.round(r.seconds() * 4) * 2 + (r.brightness() > 0.5 ? 1 : 0);
    }

    public static Reverb fromBucket(int bucket) {
        if (bucket <= 0) return Reverb.NONE;
        return new Reverb((bucket / 2) / 4.0, (bucket & 1) == 1 ? 0.8 : 0.3);
    }
}
