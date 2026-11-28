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
 * 同伴的数值：自己的基础值，加上从主人继承的一部分（docs/COMPANIONS.md 第 3.5 节；白梅的数见 docs/SHIRAUME_COMPANION.md 第 5.1 节）。
 * 规矩每位同伴一样，数是每位自己的（{@link CompanionDef.Growth}）；下面的常量是白梅的。
 *
 * <ul>
 * <li>只继承长期的属性：基础值和装备带来的。药水、信标这类状态效果给的加成剔掉（按状态效果自己登记的修饰 id）。
 * <li>攻击按打法折算：近战攻击和 Logos 法术强度折算成的攻击，取高的那个，用法术的主人不吃亏。
 * <li>在场时每隔几秒重算一次，换了装备不用重新召唤。
 * <li>继承比例随契印（白梅的叫尾之契）升高：三成起，每枚加八成里的一成（{@link #RATIO_STEP}），第九枚到 {@link #MAX_RATIO}（全部）。
 * <li>她自己的生命、护甲、韧性也随契印长（{@link #SEAL_HEALTH} 等），羁绊每升一段再加一点生命和攻击（{@link #BOND_HEALTH} 等）。
 * <li>主人盔甲上的保护类魔咒按同样的比例继承（{@link #protection}）：她挨打时按主人的魔咒对这一下算保护，再乘比例。
 * </ul>
 */
public final class CompanionStats {
    /** 她自己的（没盖契印、生疏时）。 */
    public static final double BASE_HEALTH = 40, BASE_ARMOR = 4, BASE_TOUGHNESS = 0, BASE_FOXFIRE = 4;
    /** 每枚契印她自己多长的生命、护甲、韧性（到第 {@link #FULL_SEALS} 枚为止）。 */
    public static final double SEAL_HEALTH = 5, SEAL_ARMOR = 1, SEAL_TOUGHNESS = .5;
    /** 羁绊每升一段（生疏 → 熟悉 → 亲近 → 信赖 → 羁绊）多的生命、攻击。 */
    public static final double BOND_HEALTH = 4, BOND_ATTACK = .5;
    /** 继承比例：三成起，每枚契印加 {@link #RATIO_STEP}，最多全部继承。 */
    public static final double RATIO = .3, RATIO_STEP = .08, MAX_RATIO = 1;
    /** 九尾就是九阶：契印到这么多枚，她自己的成长和流火的倍数就到顶了。 */
    public static final int FULL_SEALS = 9;
    /** 流火一刀是攻击的几倍：没盖契印时 {@link #BLADE_MIN}，到第 {@link #FULL_SEALS} 枚时 {@link #BLADE_MAX}（基础攻击时和玩家手里的流火一刀一样）。 */
    public static final double BLADE_MIN = 1.25, BLADE_MAX = 2.25;
    /** Logos 法术强度为 1 时折算成多少点攻击：一发普通的魔弹大约这么多。 */
    public static final double SPELL_ATTACK = 6;
    static final Identifier INHERIT = LogosDimension.id("companion_inherit");
    /** 白梅的数（上面那些常量；法术强度她不用，狐火弹按攻击算）。 */
    public static final CompanionDef.Growth SHIRAUME = new CompanionDef.Growth(BASE_HEALTH, BASE_ARMOR, BASE_TOUGHNESS, BASE_FOXFIRE, 0,
            SEAL_HEALTH, SEAL_ARMOR, SEAL_TOUGHNESS, 0, BOND_HEALTH, BOND_ATTACK, 0, RATIO, RATIO_STEP, MAX_RATIO, FULL_SEALS);

    /** 主人的长期数值（继承前）。 */
    public record Owner(double health, double armor, double toughness, double melee, double spellPower) {
        /** 折算成攻击：近战与法术取高的那个。 */
        public double attack() {
            return Math.max(melee, SPELL_ATTACK * spellPower);
        }
    }

    /** 一项数值的三部分：她自己的（基础 + 契印）、羁绊给的、从主人那里继承的。 */
    public record Part(double own, double bond, double inherited) {
        public double total() {
            return own + bond + inherited;
        }
    }

    /** 她这一刻的数值（各项都是合计；分开的见 {@link #parts}）。{@code foxfire} 是她的攻击（一发狐火弹的基础伤害）。 */
    public record Stats(double ratio, double health, double armor, double toughness, double foxfire) {}

    /** 分开的四项：生命、攻击、护甲、韧性。 */
    public record Parts(double ratio, Part health, Part attack, Part armor, Part toughness) {
        public Stats stats() {
            return new Stats(ratio, health.total(), armor.total(), toughness.total(), attack.total());
        }
    }

    private CompanionStats() {}

    /** 盖了 {@code seals} 枚契印时白梅的继承比例。 */
    public static double ratio(int seals) {
        return SHIRAUME.ratio(seals);
    }

    /** 契印算到第几枚为止（她自己的成长、流火的倍数）。 */
    static int grown(int seals) {
        return SHIRAUME.grown(seals);
    }

    /** 流火一刀是攻击的几倍。 */
    public static double blade(int seals) {
        return BLADE_MIN + (BLADE_MAX - BLADE_MIN) * grown(seals) / FULL_SEALS;
    }

    /** 白梅盖了 {@code seals} 枚契印、羁绊在第 {@code stage} 段（0 是生疏）时的各项。 */
    public static Parts parts(Owner owner, int seals, int stage) {
        return parts(SHIRAUME, owner, seals, stage);
    }

    /** 按 {@code g} 这位同伴的数，盖了 {@code seals} 枚契印、羁绊在第 {@code stage} 段时的各项。 */
    public static Parts parts(CompanionDef.Growth g, Owner owner, int seals, int stage) {
        double r = g.ratio(seals), hr = g.healthRatio(seals), ar = g.armorRatio(seals);
        int n = g.grown(seals);
        int b = Math.max(0, stage);
        return new Parts(r,
                new Part(g.health() + g.sealHealth() * n, g.bondHealth() * b, hr * owner.health()),
                new Part(g.attack(), g.bondAttack() * b, r * owner.attack()),
                new Part(g.armor() + g.sealArmor() * n, 0, ar * owner.armor()),
                new Part(g.toughness() + g.sealToughness() * n, 0, ar * owner.toughness()));
    }

    /**
     * 她的法术强度（倍数）：她自己的（基础 + 每枚契印长的）、羁绊给的、继承主人的（主人的长期法强乘继承比例）。
     * {@code ownerPower} 是主人不算药水的法术强度（{@link #spellPower}）。
     */
    public static Part power(CompanionDef.Growth g, double ownerPower, int seals, int stage) {
        return new Part(g.spellPower() + g.sealSpellPower() * g.grown(seals), g.bondSpellPower() * Math.max(0, stage), g.ratio(seals) * ownerPower);
    }

    public static Stats of(Owner owner, int seals, int stage) {
        return parts(owner, seals, stage).stats();
    }

    /** 生疏时的数值。 */
    public static Stats of(Owner owner, int seals) {
        return of(owner, seals, 0);
    }

    /** 主人和白梅的羁绊在第几段（0 是生疏）。 */
    static int stage(Player owner) {
        return stage(owner, Companions.SHIRAUME_DEF);
    }

    static int stage(Player owner, CompanionDef d) {
        return CompanionBond.Stage.of(Companions.bond(owner, d).affection()).ordinal();
    }

    /**
     * 继承来的魔咒保护：主人身上的保护、火焰保护、爆炸保护、弹射物保护……对 {@code source} 这一下合起来算多少（同原版），
     * 乘继承比例。原版的规矩照旧：封顶 20 点（减八成），绕过魔咒的伤害不算。
     */
    public static float protection(net.minecraft.server.level.ServerLevel level, Player owner, net.minecraft.world.damagesource.DamageSource source, int seals) {
        return protection(level, owner, source, ratio(seals));
    }

    /** 同上，继承比例直接给（各位同伴的比例不一样）。 */
    public static float protection(net.minecraft.server.level.ServerLevel level, Player owner, net.minecraft.world.damagesource.DamageSource source, double ratio) {
        if (source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_ENCHANTMENTS) || source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_EFFECTS)) return 0;
        return (float) (net.minecraft.world.item.enchantment.EnchantmentHelper.getDamageProtection(level, owner, source) * ratio);
    }

    /** 读主人此刻的长期数值。 */
    public static Owner read(Player owner) {
        Set<Identifier> temporary = temporary(owner);
        return new Owner(lasting(owner, Attributes.MAX_HEALTH, temporary), lasting(owner, Attributes.ARMOR, temporary),
                lasting(owner, Attributes.ARMOR_TOUGHNESS, temporary), lasting(owner, Attributes.ATTACK_DAMAGE, temporary),
                spellPower(owner, "spell_power", "arcane_spell_power"));
    }

    /**
     * 主人不算药水、信标的法术强度：Logos 属性 {@code ids}（{@code spell_power}、{@code life_spell_power}……）相乘。
     * 白梅的狐火算奥秘，精灵公主的生命之力算生命。
     */
    public static double spellPower(Player owner, String... ids) {
        Set<Identifier> temporary = temporary(owner);
        double power = 1;
        for (String id : ids) {
            Optional<Holder.Reference<Attribute>> attr = BuiltInRegistries.ATTRIBUTE.get(Identifier.fromNamespaceAndPath("logos", id));
            if (attr.isPresent()) power *= lasting(owner, attr.get(), temporary);
        }
        return power;
    }

    /** 主人不算药水、信标时这项属性的值（琥珀继承主人的魔力上限）。 */
    public static double lasting(Player owner, Holder<Attribute> attribute) {
        return lasting(owner, attribute, temporary(owner));
    }

    /** 状态效果给的修饰（继承时剔掉）。 */
    private static Set<Identifier> temporary(Player owner) {
        Set<Identifier> temporary = new HashSet<>();
        for (MobEffectInstance effect : owner.getActiveEffects()) {
            effect.getEffect().value().createModifiers(effect.getAmplifier(), (attribute, modifier) -> temporary.add(modifier.id()));
        }
        return temporary;
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

    /**
     * 把基础之外的那部分（契印、羁绊、继承）挂到她身上（生命上限、护甲、护甲韧性），攻击和契印数记在她身上。
     * 生命上限变了时按比例保留当前生命。
     */
    static Parts apply(CompanionEntity c, Player owner, int seals) {
        CompanionDef d = c.def();
        CompanionDef.Growth g = d.growth();
        int stage = stage(owner, d);
        Parts parts = c.statParts(read(owner), seals, stage);
        Stats s = parts.stats();
        float before = c.getMaxHealth(), fraction = before > 0 ? c.getHealth() / before : 1;
        set(c, Attributes.MAX_HEALTH, s.health() - g.health());
        set(c, Attributes.ARMOR, s.armor() - g.armor());
        set(c, Attributes.ARMOR_TOUGHNESS, s.toughness() - g.toughness());
        if (Math.abs(c.getMaxHealth() - before) > 1e-3) c.setHealth(Math.max(1, fraction * c.getMaxHealth()));
        c.setAttack((float) s.foxfire());
        c.setSeals(seals);
        c.statsApplied(owner, seals, stage);
        return parts;
    }

    /** 把 {@code attribute} 上「继承」那一份修饰设成 {@code amount}（加在基础值上）。 */
    static void set(LivingEntity e, Holder<Attribute> attribute, double amount) {
        AttributeInstance i = e.getAttribute(attribute);
        if (i == null) return;
        AttributeModifier old = i.getModifier(INHERIT);
        if (old != null && Math.abs(old.amount() - amount) < 1e-4) return;
        i.addOrUpdateTransientModifier(new AttributeModifier(INHERIT, amount, AttributeModifier.Operation.ADD_VALUE));
    }
}
