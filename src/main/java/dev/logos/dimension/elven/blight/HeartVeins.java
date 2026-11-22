package dev.logos.dimension.elven.blight;

import net.minecraft.world.phys.Vec3;

/**
 * 腐败之心八条主血管扎进树壁的位置（模板坐标：相对腐败之心方块的底面中心、朝向 south）。客户端画血管（SuspendedHeart）、
 * 服务端在高台上放可以砍的血管接头（{@link QueenTether}）都用这一份，两边对得上。
 */
public final class HeartVeins {
    /** 主血管条数；第 i 条的接头是 {@link QueenTether} 的第 i 号。 */
    public static final int MAIN = 8;
    /** 接头离核心的水平距离：大厅墙面（半径 24）再往里一格，保证扎在实心树壁里。 */
    public static final double WALL = 25;
    /** 高台上可砍的接头离核心的水平距离（贴着墙、站在高台上够得着）。 */
    public static final double KNOT = 23.2;

    private HeartVeins() {}

    public static double angle(int i) {
        return i * Math.PI / 4 + .12;
    }

    /** 水平角 {@code angle}、半径 {@code radius}、高 {@code y} 的接头；北侧入口拱门那一段越过门洞扎进上方树壁（那一段高台也是断开的）。 */
    public static Vec3 anchor(double angle, double radius, double y) {
        Vec3 p = new Vec3(Math.cos(angle) * radius, y, Math.sin(angle) * radius);
        if (p.z < 0 && Math.abs(p.x) < 10.5) p = new Vec3(p.x, Math.max(27.5, y), p.z);
        return p;
    }

    /** 第 {@code i} 条主血管的接头。 */
    public static Vec3 joint(int i) {
        return anchor(angle(i), WALL, 2.6 + i % 3 * .8);
    }

    /** 第 {@code i} 条主血管的接头落在高台上方够得着的高度（不是越过拱门的那条）。 */
    public static boolean reachable(int i) {
        return joint(i).y < 10;
    }

    /** 第 {@code i} 个可砍接头站在高台上的位置（高台顶面高 {@code stand}）。 */
    public static Vec3 knot(int i, double stand) {
        double a = angle(i);
        return new Vec3(Math.cos(a) * KNOT, stand, Math.sin(a) * KNOT);
    }
}
