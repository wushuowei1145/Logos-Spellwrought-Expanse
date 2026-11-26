package dev.logos.dimension.firearm;

/**
 * 普通弹的弹头金属：决定物理伤害（乘在弹种自己的伤害上），下界合金的步枪弹还多穿一个目标。
 * 弹种（霰弹、步枪弹）决定怎么打，金属只决定多重；以后刻术式也只认弹种、不认金属（docs/FIREARMS.md 第 5 节）。
 */
public enum RoundMetal {
    COPPER("copper", 0.8, 0),
    IRON("iron", 1.0, 0),
    NETHERITE("netherite", 1.4, 1);

    public final String id;
    /** 物理伤害倍数。 */
    public final double damage;
    /** 步枪弹多穿透几个目标（霰弹的弹丸不穿透）。 */
    public final int pierce;

    RoundMetal(String id, double damage, int pierce) {
        this.id = id;
        this.damage = damage;
        this.pierce = pierce;
    }
}
