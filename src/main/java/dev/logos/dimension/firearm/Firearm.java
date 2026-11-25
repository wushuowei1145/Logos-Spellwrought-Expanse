package dev.logos.dimension.firearm;

import net.minecraft.world.item.ItemStack;

/**
 * 铳：能装背包里的弹药、扣扳机打出去的物品。铳只管怎么射（容量、精度、威力、什么时候扣扳机），
 * 射出去是什么由弹药决定（{@link Ammo}）。装弹状态在物品组件 {@link Firearms#LOAD} 里，见 docs/FIREARMS.md。
 */
public interface Firearm {
    /** 一次能装几发。 */
    int capacity();

    /** 散布倍数（乘在弹药自己的散布上）；举枪瞄准时更准。 */
    default double spread(boolean aimed) { return aimed ? 0.5 : 1; }

    /** 威力倍数（乘在弹药自己的伤害上）。 */
    default double damage() { return 1; }

    /** 能不能装这种弹药；默认所有弹药都能装（弹药不分口径）。 */
    default boolean accepts(ItemStack ammo) { return Firearms.isAmmo(ammo); }
}
