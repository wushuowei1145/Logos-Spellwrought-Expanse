package dev.logos.dimension.companion;

import net.minecraft.world.damagesource.CombatRules;

/**
 * 拉塞塔的数值（docs/LACERTA_COMPANION.md 第 9 节，接入方案第二节）：顶级前排真龙骑士——生命、护甲、韧性、攻击的底子远高于别的同伴，
 * 防御向的继承分段上升、不封顶；魔力和法术强度刻意压低、长得慢。数都是建议初值，试玩后再调。
 * <ul>
 *   <li>防御向继承比例（{@link #tankRatio}，生命、护甲、韧性、魔咒保护、攻击都用它）：0 枚 50%，到第 4 枚 100%，到第 9 枚 200%，之后每枚再 +10%。</li>
 *   <li>魔力、法术强度的继承比例（{@link #magicRatio}）：10% 起，每枚 +5%（4 枚 30%，9 枚 55%，之后照样涨）。</li>
 *   <li>原版的上限：护甲 30、韧性 20、魔咒保护 20 点（减八成）。超出的部分属性写不进去，挨打时再折一点减伤（{@link #overflow}、
 *       {@link #afterProtection}）：越多越接近、永远到不了，魔咒总减伤封顶九成，不会刀枪不入。</li>
 * </ul>
 * 契印数不封顶：第 9 枚以后她自己的生命每枚只长一半，护甲、韧性、攻击照常长。
 */
final class LacertaStats {
    /** 她自己的（没盖契印、生疏时）。 */
    static final double HEALTH = 80, ARMOR = 14, TOUGHNESS = 6, ATTACK = 10, KNOCKBACK = .6;
    /** 每枚鳞之契：生命（第 9 枚以后 {@link #LATE_SEAL_HEALTH}）、护甲、韧性、攻击。 */
    static final double SEAL_HEALTH = 12, LATE_SEAL_HEALTH = 6, SEAL_ARMOR = 2, SEAL_TOUGHNESS = 1, SEAL_ATTACK = 1.5;
    /** 羁绊每升一段：生命、攻击。 */
    static final double BOND_HEALTH = 8, BOND_ATTACK = 1;
    /** 生命长到第几枚后减半。 */
    static final int FULL_SEALS = 9;
    /** 魔力上限、回复（每秒）、法术强度：她自己的、每枚、羁绊每段。 */
    static final double MANA = 120, SEAL_MANA = 20, BOND_MANA = 15;
    static final double REGEN = 1.2, SEAL_REGEN = .2, BOND_REGEN = .1;
    static final double POWER = .6, SEAL_POWER = .025, BOND_POWER = .02;
    /** 原版护甲、韧性的上限（属性写不进更多），魔咒保护的上限（20 点，减八成）。 */
    static final double ARMOR_CAP = 30, TOUGHNESS_CAP = 20;
    static final float EPF_CAP = 20;
    /** 超出上限的护甲（韧性按一半折算）：每多 {@link #OVERFLOW_SCALE} 点，剩下的伤害再少一截，最多再少 {@link #OVERFLOW_MAX}。 */
    static final double OVERFLOW_SCALE = 40, OVERFLOW_MAX = .5;
    /** 超出 20 点的魔咒保护：剩下的两成伤害里最多再减一半（总共九成）。 */
    static final double EPF_OVERFLOW_SCALE = 20, EPF_OVERFLOW_MAX = .5;

    private LacertaStats() {}

    /** 防御向的继承比例：0–4 枚 50%→100%，4–9 枚 100%→200%，之后每枚 +10%，不封顶。 */
    static double tankRatio(int seals) {
        int s = Math.max(0, seals);
        if (s <= 4) return .5 + .125 * s;
        if (s <= 9) return 1 + .2 * (s - 4);
        return 2 + .1 * (s - 9);
    }

    /** 魔力、法术强度的继承比例：10% 起，每枚 +5%。 */
    static double magicRatio(int seals) {
        return .1 + .05 * Math.max(0, seals);
    }

    static double ownHealth(int seals) {
        int s = Math.max(0, seals);
        return HEALTH + SEAL_HEALTH * Math.min(s, FULL_SEALS) + LATE_SEAL_HEALTH * Math.max(0, s - FULL_SEALS);
    }

    static double ownArmor(int seals) {
        return ARMOR + SEAL_ARMOR * Math.max(0, seals);
    }

    static double ownToughness(int seals) {
        return TOUGHNESS + SEAL_TOUGHNESS * Math.max(0, seals);
    }

    static double ownAttack(int seals) {
        return ATTACK + SEAL_ATTACK * Math.max(0, seals);
    }

    /** 生命、攻击、护甲、韧性（她自己的、羁绊给的、继承的）。攻击另加手里武器的（{@link LacertaBlade#SWORD}），不算在这里。 */
    static CompanionStats.Parts parts(CompanionStats.Owner owner, int seals, int stage) {
        double r = tankRatio(seals);
        int b = Math.max(0, stage);
        return new CompanionStats.Parts(r,
                new CompanionStats.Part(ownHealth(seals), BOND_HEALTH * b, r * owner.health()),
                new CompanionStats.Part(ownAttack(seals), BOND_ATTACK * b, r * owner.attack()),
                new CompanionStats.Part(ownArmor(seals), 0, r * owner.armor()),
                new CompanionStats.Part(ownToughness(seals), 0, r * owner.toughness()));
    }

    /** 魔力上限：她自己的、羁绊、继承主人长期上限的 {@link #magicRatio}。 */
    static CompanionStats.Part mana(double ownerMana, int seals, int stage) {
        int s = Math.max(0, seals);
        return new CompanionStats.Part(MANA + SEAL_MANA * s, BOND_MANA * Math.max(0, stage), magicRatio(seals) * ownerMana);
    }

    /** 魔力回复（每秒，不继承）。 */
    static double regen(int seals, int stage) {
        return REGEN + SEAL_REGEN * Math.max(0, seals) + BOND_REGEN * Math.max(0, stage);
    }

    /** 法术强度：她自己的、羁绊、继承主人的（乘 {@link #magicRatio}）。 */
    static CompanionStats.Part power(double ownerPower, int seals, int stage) {
        return new CompanionStats.Part(POWER + SEAL_POWER * Math.max(0, seals), BOND_POWER * Math.max(0, stage), magicRatio(seals) * ownerPower);
    }

    /** 超出原版上限的护甲、韧性折成的额外减伤（0 到 {@link #OVERFLOW_MAX}）：挨的一下再乘 1 减它。 */
    static double overflow(double armor, double toughness) {
        double over = Math.max(0, armor - ARMOR_CAP) + .5 * Math.max(0, toughness - TOUGHNESS_CAP);
        return OVERFLOW_MAX * (1 - Math.exp(-over / OVERFLOW_SCALE));
    }

    /** 魔咒保护 {@code epf}（已乘继承比例）之后剩多少：20 点以内照原版；超出的在剩下的两成里最多再减一半（总共九成）。 */
    static float afterProtection(float damage, float epf) {
        if (epf <= 0) return damage;
        float d = CombatRules.getDamageAfterMagicAbsorb(damage, Math.min(epf, EPF_CAP));
        if (epf <= EPF_CAP) return d;
        return (float) (d * (1 - EPF_OVERFLOW_MAX * (1 - Math.exp(-(epf - EPF_CAP) / EPF_OVERFLOW_SCALE))));
    }
}
