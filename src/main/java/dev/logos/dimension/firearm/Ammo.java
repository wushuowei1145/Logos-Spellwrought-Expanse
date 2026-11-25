package dev.logos.dimension.firearm;

import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * 一种弹药打出去是什么：命中判定、伤害与元素、附加效果、声音都由它决定（服务端）；弹道和命中的画面由客户端
 * 按同一种弹药注册的画法画（{@code client.firearm.AmmoVisuals}）。铳只负责扣扳机：扣掉一发，把这一发交给弹药。
 * 新加一种弹药只写这两样，不需要改铳。见 docs/FIREARMS.md。
 */
public interface Ammo {
    /** 打出一发；返回的弹道终点交给客户端画（可以为空，例如以后只施放术式、不出弹丸的术式弹）。 */
    ShotResult fire(Shot shot);

    /** 弹药物品：物品本身就带它的打法。 */
    interface Item {
        Ammo ammo();
    }

    /**
     * 一发子弹的上下文。{@code origin} 是射手眼睛（准星对着哪里就打哪里），{@code direction} 是视线（单位向量）；
     * {@code spread} 乘在弹药自己的散布上（铳的精度、举枪瞄准时更小），{@code damage} 乘在弹药自己的伤害上（铳的威力）。
     * {@code round} 是这一发的物品样子（带组件，术式弹从这里读写进去的术式）。
     */
    record Shot(ServerLevel level, LivingEntity shooter, ItemStack gun, ItemStack round, Vec3 origin, Vec3 direction,
                double spread, double damage, boolean aimed) {}

    /** 一发打出去的结果：每条弹道（弹丸）的终点，给客户端画弹道和命中。 */
    record ShotResult(List<Trace> traces) {
        public static final ShotResult NONE = new ShotResult(List.of());
    }

    /**
     * 一条弹道的终点：{@code kind} 为 {@link #MISS}（打空，到射程尽头）、{@link #BLOCK}（打在方块上，{@code block} 是方块状态编号，
     * 画碎屑用）或 {@link #ENTITY}（打中生物）。
     */
    record Trace(Vec3 end, byte kind, int block) {
        public static final byte MISS = 0, BLOCK = 1, ENTITY = 2;
    }
}
