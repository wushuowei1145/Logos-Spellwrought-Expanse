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
    /** 明确选择菱形判定的武器，其他武器继续使用原有锥形。 */
    private static final java.util.Set<Integer> DIAMONDS = ConcurrentHashMap.newKeySet();

    /** 战斗武器物品。 */
    public interface Holder {
        CombatWeapon combatWeapon();
        /** 左键将要交给招式状态机前调用（{@code move} 为当前招式）；返回 false 时这次左键作废。施法剑在格挡里借此开始蓄力。 */
        default boolean allowsAttack(net.minecraft.server.level.ServerPlayer player, String move) { return true; }
        /** 招式状态机每刻结算之后调用。 */
        default void combatTick(net.minecraft.server.level.ServerPlayer player, String move) {}
        /** 一刀接触、结算目标之前调用；返回 false 时这一刀落空，不打任何目标（流火魔力不够时）。 */
        default boolean swing(net.minecraft.server.level.ServerPlayer player, Moveset.Move move) { return true; }
        /**
         * 自己结算这一刀对 {@code target} 的伤害时返回 true，这一下就不走原版 {@code player.attack}（流火只打魔法伤害）。
         * 默认 false：伤害走原版近战流程。
         */
        default boolean strike(net.minecraft.server.level.ServerPlayer player, net.minecraft.world.entity.Entity target, Moveset.Move move) { return false; }
        /**
         * 正在招式 {@code move} 里格挡时，格挡是不是一面魔力护盾（{@code longsword.ManaGuard}）：挡下的扣魔力不扣耐久，
         * 正面的魔法伤害与法术体也挡，虚空穿透。默认 false：原版格挡。黑钢长剑在竖剑格挡时是，流火一直是。
         */
        default boolean manaGuard(net.minecraft.server.level.ServerPlayer player, String move) { return false; }
        /** 魔力护盾挡下一击、付完这一下的魔力之后调用（流火在这里结算破盾）。 */
        default void manaGuarded(net.minecraft.server.level.ServerPlayer player, net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent event) {}
        /**
         * 这一刀打中了 {@code target}（原版攻击已结算、没有被弹开）之后调用，每个目标一次；{@code first} 为这一刀的第一个目标
         * （准星正对着的那个排第一）。惊鸿在这里让身后的剑追击。
         */
        default void hit(net.minecraft.server.level.ServerPlayer player, net.minecraft.world.entity.Entity target, Moveset.Move move, boolean first) {}
        /**
         * 原版格挡挡下一击、交给招式文件的 {@code block} 输入之后调用；{@code parried} 为这一下转入了弹刀（{@code effect: parry}）。
         * 惊鸿在这里让身后的剑反击。
         */
        default void blocked(net.minecraft.server.level.ServerPlayer player, net.minecraft.world.damagesource.DamageSource source, boolean parried) {}
    }

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
    public CombatWeapon withDiamondHitbox() { DIAMONDS.add(id); return this; }
    public boolean diamondHitbox() { return DIAMONDS.contains(id); }
    /** 腰侧挂不挂剑鞘。 */
    public boolean sheathed() { return !SHEATHLESS.contains(id); }
    public static CombatWeapon of(ItemStack stack) { return stack.getItem() instanceof Holder holder ? holder.combatWeapon() : null; }
    /** 物品的战斗武器编号；不是战斗武器时为 -1。 */
    public static int id(ItemStack stack) {
        var weapon = of(stack);
        return weapon == null ? -1 : weapon.id;
    }
}
