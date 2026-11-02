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

    /** 战斗武器物品。 */
    public interface Holder { CombatWeapon combatWeapon(); }

    public static CombatWeapon register(int id, String mesh, String animations, Identifier moveset) {
        var weapon = new CombatWeapon(id, mesh, animations, moveset);
        if (BY_ID.putIfAbsent(id, weapon) != null) throw new IllegalStateException("战斗武器编号重复：" + id);
        return weapon;
    }
    public static CombatWeapon byId(int id) { return BY_ID.get(id); }
    public static CombatWeapon of(ItemStack stack) { return stack.getItem() instanceof Holder holder ? holder.combatWeapon() : null; }
    /** 物品的战斗武器编号；不是战斗武器时为 -1。 */
    public static int id(ItemStack stack) {
        var weapon = of(stack);
        return weapon == null ? -1 : weapon.id;
    }
}
