package dev.logos.dimension.firearm;

/** 霰弹的数值（docs/FIREARMS.md 第 5 节），试玩后再调；打法在 {@link BuckshotItem}。 */
public final class Buckshot {
    private Buckshot() {}
    /** 一发几颗弹丸。 */
    public static final int PELLETS = 8;
    /** 散布：锥形的半角（度），乘铳的散布倍数（举枪瞄准时减半）。 */
    public static final double SPREAD = 7;
    /** 射程、满伤距离（格）与射程尽头剩下的伤害比例。 */
    public static final double RANGE = 24, FULL = 8, FAR = .4;
    /** 每颗弹丸的伤害（物理元素）。 */
    public static final float PELLET_DAMAGE = 2.5f;

    /** 距离 {@code d} 处每颗弹丸的伤害比例：满伤距离内为 1，之后线性降到射程尽头的 {@link #FAR}。 */
    public static double falloff(double d) {
        if (d <= FULL) return 1;
        return 1 - (1 - FAR) * Math.min(1, (d - FULL) / (RANGE - FULL));
    }
}
