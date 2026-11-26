package dev.logos.dimension.firearm;

/** 步枪弹的数值（docs/FIREARMS.md 第 5 节），试玩后再调；打法在 {@link RifleRoundItem}。 */
public final class Rifle {
    private Rifle() {}
    /** 散布：锥形的半角（度），乘铳的散布倍数（举枪瞄准时减半）。比霰弹的一蓬准得多。 */
    public static final double SPREAD = 1.2;
    /** 射程、满伤距离（格）与射程尽头剩下的伤害比例：远处几乎不衰减，这是它和霰弹的分工。 */
    public static final double RANGE = 64, FULL = 32, FAR = .7;
    /** 一发的伤害（铁弹头，物理元素）：贴脸不如霰弹全中，二十格外远胜霰弹。 */
    public static final float DAMAGE = 12f;
    /** 击退（每个被打中的目标）。 */
    public static final double KNOCKBACK = .35;

    /** 距离 {@code d} 处的伤害比例：满伤距离内为 1，之后线性降到射程尽头的 {@link #FAR}。 */
    public static double falloff(double d) {
        if (d <= FULL) return 1;
        return 1 - (1 - FAR) * Math.min(1, (d - FULL) / (RANGE - FULL));
    }
}
