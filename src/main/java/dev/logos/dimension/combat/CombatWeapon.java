package dev.logos.dimension.combat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * 本附属的一种战斗武器：武器网格 {@code combat/weapons/<mesh>.json}、动作文件 {@code combat/animations/<animations>_<视角>.animation.json}
 * 与招式文件。id 写进同步包，由代码固定编号，不依赖注册顺序。只有实现 {@link Holder} 的本附属物品才是战斗武器（兼容性规则 1）。
 */
public record CombatWeapon(int id, String mesh, String animations, Identifier moveset) {
    private static final Map<Integer, CombatWeapon> BY_ID = new ConcurrentHashMap<>();
    /** 不挂剑鞘的武器：网格里的剑鞘部件不画（曦誓拔剑、收剑是光点聚散）。 */
    private static final java.util.Set<Integer> SHEATHLESS = ConcurrentHashMap.newKeySet();
    /** 命中锥形的默认张角（度）：准星左右各 30°。注册时不给 {@link #withCone} 的武器都用它。 */
    public static final double DEFAULT_CONE = 60;
    /** 给了自己张角的武器。 */
    private static final Map<Integer, Double> CONES = new ConcurrentHashMap<>();

    /** 战斗武器物品。 */
    public interface Holder { CombatWeapon combatWeapon(); }

    public static CombatWeapon register(int id, String mesh, String animations, Identifier moveset) {
        var weapon = new CombatWeapon(id, mesh, animations, moveset);
        if (BY_ID.putIfAbsent(id, weapon) != null) throw new IllegalStateException("战斗武器编号重复：" + id);
        return weapon;
    }
    public static CombatWeapon byId(int id) { return BY_ID.get(id); }
    /** 标记为不挂剑鞘。 */
    public CombatWeapon withoutSheath() { SHEATHLESS.add(id); return this; }
    /**
     * 命中锥形的张角（度，大于 0、不超过 180），在注册时链式调用：{@code CombatWeapon.register(...).withCone(75)}。
     * 不调用就是 {@link #DEFAULT_CONE}。锥形的长度不在这里给，始终是玩家的原版攻击距离（{@code entity_interaction_range}），
     * 要更长就给物品加这个属性的修饰。
     */
    public CombatWeapon withCone(double degrees) {
        if (!(degrees > 0 && degrees <= 180)) throw new IllegalArgumentException("命中锥形张角要在 (0, 180] 内：" + degrees);
        CONES.put(id, degrees);
        return this;
    }
    /** 命中锥形的张角（度）。 */
    public double cone() { return CONES.getOrDefault(id, DEFAULT_CONE); }
    /** 腰侧挂不挂剑鞘。 */
    public boolean sheathed() { return !SHEATHLESS.contains(id); }
    public static CombatWeapon of(ItemStack stack) { return stack.getItem() instanceof Holder holder ? holder.combatWeapon() : null; }
    /** 物品的战斗武器编号；不是战斗武器时为 -1。 */
    public static int id(ItemStack stack) {
        var weapon = of(stack);
        return weapon == null ? -1 : weapon.id;
    }
}
