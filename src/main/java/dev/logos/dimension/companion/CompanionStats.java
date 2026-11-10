package dev.logos.dimension.companion;

import dev.logos.dimension.LogosDimension;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

/**
 * 白梅的数值：自己的基础值，加上从主人继承的一部分（docs/SHIRAUME_COMPANION.md 第 5.1 节）。
 *
 * <ul>
 * <li>只继承长期的属性：基础值和装备带来的。药水、信标这类状态效果给的加成剔掉（按状态效果自己登记的修饰 id）。
 * <li>攻击按打法折算：近战攻击和 Logos 法术强度折算成的攻击，取高的那个，用法术的主人不吃亏。
 * <li>在场时每隔几秒重算一次，换了装备不用重新召唤。
 * <li>继承比例随契印（白梅的叫尾之契）升高，最多 {@link #MAX_RATIO}。
 * </ul>
 */
public final class CompanionStats {
    public static final double BASE_HEALTH = 40, BASE_ARMOR = 4, BASE_FOXFIRE = 4;
    public static final double RATIO = .3, MAX_RATIO = .8;
    /** Logos 法术强度为 1 时折算成多少点攻击：一发普通的魔弹大约这么多。 */
    public static final double SPELL_ATTACK = 6;
    static final Identifier INHERIT = LogosDimension.id("companion_inherit");

    /** 主人的长期数值（继承前）。 */
    public record Owner(double health, double armor, double toughness, double melee, double spellPower) {
        /** 折算成攻击：近战与法术取高的那个。 */
        public double attack() {
            return Math.max(melee, SPELL_ATTACK * spellPower);
        }
    }

    /** 她这一刻的数值。 */
    public record Stats(double ratio, double health, double armor, double toughness, double foxfire) {}

    private CompanionStats() {}

    /** 盖了 {@code seals} 枚契印时的继承比例。 */
    public static double ratio(int seals) {
        return Math.min(MAX_RATIO, RATIO + .05 * Math.max(0, seals));
    }

    public static Stats of(Owner owner, int seals) {
        double r = ratio(seals);
        return new Stats(r, BASE_HEALTH + r * owner.health(), BASE_ARMOR + r * owner.armor(), r * owner.toughness(),
                BASE_FOXFIRE + r * owner.attack());
    }

    /** 读主人此刻的长期数值。 */
    public static Owner read(Player owner) {
        Set<Identifier> temporary = new HashSet<>();
        for (MobEffectInstance effect : owner.getActiveEffects()) {
            effect.getEffect().value().createModifiers(effect.getAmplifier(), (attribute, modifier) -> temporary.add(modifier.id()));
        }
        double power = 1;
        for (String id : new String[] {"spell_power", "arcane_spell_power"}) {
            Optional<Holder.Reference<Attribute>> attr = BuiltInRegistries.ATTRIBUTE.get(Identifier.fromNamespaceAndPath("logos", id));
            if (attr.isPresent()) power *= lasting(owner, attr.get(), temporary);
        }
        return new Owner(lasting(owner, Attributes.MAX_HEALTH, temporary), lasting(owner, Attributes.ARMOR, temporary),
                lasting(owner, Attributes.ARMOR_TOUGHNESS, temporary), lasting(owner, Attributes.ATTACK_DAMAGE, temporary), power);
    }

    /** 不算 {@code skip} 里那些修饰（状态效果给的）时，这项属性的值。没有这项属性时是它的默认值。 */
    static double lasting(LivingEntity e, Holder<Attribute> attribute, Set<Identifier> skip) {
        AttributeInstance i = e.getAttribute(attribute);
        if (i == null) return attribute.value().getDefaultValue();
        return attribute.value().sanitizeValue(compute(i.getBaseValue(), i.getModifiers(), skip));
    }

    /** 原版 {@code AttributeInstance.calculateValue} 的算法，跳过 {@code skip} 里的修饰。 */
    static double compute(double base, Collection<AttributeModifier> modifiers, Set<Identifier> skip) {
        double add = base;
        for (AttributeModifier m : modifiers) if (!skip.contains(m.id()) && m.operation() == AttributeModifier.Operation.ADD_VALUE) add += m.amount();
        double value = add;
        for (AttributeModifier m : modifiers) if (!skip.contains(m.id()) && m.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_BASE) value += add * m.amount();
        for (AttributeModifier m : modifiers) if (!skip.contains(m.id()) && m.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL) value *= 1 + m.amount();
        return value;
    }

    /** 把继承来的那部分挂到她身上（生命上限、护甲、护甲韧性），狐火的伤害记在她身上。生命上限变了时按比例保留当前生命。 */
    static Stats apply(ShiraumeCompanion c, Player owner, int seals) {
        Stats s = of(read(owner), seals);
        float before = c.getMaxHealth(), fraction = before > 0 ? c.getHealth() / before : 1;
        set(c, Attributes.MAX_HEALTH, s.health() - BASE_HEALTH);
        set(c, Attributes.ARMOR, s.armor() - BASE_ARMOR);
        set(c, Attributes.ARMOR_TOUGHNESS, s.toughness());
        if (Math.abs(c.getMaxHealth() - before) > 1e-3) c.setHealth(Math.max(1, fraction * c.getMaxHealth()));
        c.setFoxfireDamage((float) s.foxfire());
        return s;
    }

    private static void set(LivingEntity e, Holder<Attribute> attribute, double amount) {
        AttributeInstance i = e.getAttribute(attribute);
        if (i == null) return;
        AttributeModifier old = i.getModifier(INHERIT);
        if (old != null && Math.abs(old.amount() - amount) < 1e-4) return;
        i.addOrUpdateTransientModifier(new AttributeModifier(INHERIT, amount, AttributeModifier.Operation.ADD_VALUE));
    }
}
