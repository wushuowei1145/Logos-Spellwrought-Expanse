package dev.logos.dimension.companion;

import java.util.List;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 白梅同伴：数值继承和玩家模型的纯计算（docs/SHIRAUME_COMPANION.md 第 5.1、3.2 节）。 */
class CompanionTest {
    private static final Identifier SWORD = Identifier.withDefaultNamespace("base_attack_damage");
    private static final Identifier STRENGTH = Identifier.withDefaultNamespace("effect.strength");

    /** 和原版算法一致：先加，再按基础值乘，最后按总值乘。 */
    @Test void computesLikeVanilla() {
        var mods = List.of(new AttributeModifier(SWORD, 6, Operation.ADD_VALUE),
                new AttributeModifier(Identifier.withDefaultNamespace("a"), .5, Operation.ADD_MULTIPLIED_BASE),
                new AttributeModifier(Identifier.withDefaultNamespace("b"), .1, Operation.ADD_MULTIPLIED_TOTAL));
        // (1 + 6) → 7 + 7 × 0.5 = 10.5 → × 1.1
        assertEquals(11.55, CompanionStats.compute(1, mods, Set.of()), 1e-9);
    }

    /** 状态效果给的加成不继承：喝完力量药水再召唤，她不会一直带着。 */
    @Test void skipsWhatEffectsGive() {
        var mods = List.of(new AttributeModifier(SWORD, 6, Operation.ADD_VALUE), new AttributeModifier(STRENGTH, 3, Operation.ADD_VALUE));
        assertEquals(10, CompanionStats.compute(1, mods, Set.of()), 1e-9);
        assertEquals(7, CompanionStats.compute(1, mods, Set.of(STRENGTH)), 1e-9);
    }

    /** 攻击按打法折算：近战和法术取高的那个，用法术的主人不吃亏。 */
    @Test void attackTakesTheBetterOfMeleeAndSpells() {
        var swordsman = new CompanionStats.Owner(20, 20, 8, 8, 1);
        var mage = new CompanionStats.Owner(20, 6, 0, 1, 1.5);
        assertEquals(8, swordsman.attack(), 1e-9);
        assertEquals(CompanionStats.SPELL_ATTACK * 1.5, mage.attack(), 1e-9);
    }

    /** 第 0 阶继承三成；阶数越高继承越多，但不超过上限。 */
    @Test void inheritsAShareThatGrowsWithTailsButIsCapped() {
        var owner = new CompanionStats.Owner(20, 20, 8, 8, 1);
        var s = CompanionStats.of(owner, 0);
        assertEquals(CompanionStats.BASE_HEALTH + 6, s.health(), 1e-9);
        assertEquals(CompanionStats.BASE_ARMOR + 6, s.armor(), 1e-9);
        assertEquals(CompanionStats.BASE_FOXFIRE + 2.4, s.foxfire(), 1e-9);
        assertTrue(CompanionStats.ratio(3) > CompanionStats.ratio(0));
        assertEquals(CompanionStats.MAX_RATIO, CompanionStats.ratio(100), 1e-9);
    }

    /** 主人在做什么：睡觉 > 战斗 > 挖 > 建 > 赶路 > 发呆 / 挂机。 */
    @Test void readsWhatTheOwnerIsDoing() {
        long now = 10_000;
        assertEquals(OwnerWatch.Activity.SLEEPING, OwnerWatch.activity(now, true, now, 5, now, 5, true, 1, 0));
        assertEquals(OwnerWatch.Activity.FIGHTING, OwnerWatch.activity(now, false, now, 5, now, 5, true, 0, 0));
        assertEquals(OwnerWatch.Activity.MINING, OwnerWatch.activity(now, false, now - 20, 4, now - 20, 4, false, 0, 0));
        assertEquals(OwnerWatch.Activity.BUILDING, OwnerWatch.activity(now, false, now - 500, 9, now - 20, 4, false, 0, 0));
        assertEquals(OwnerWatch.Activity.TRAVELING, OwnerWatch.activity(now, false, 0, 0, 0, 0, false, .2, 0));
        assertEquals(OwnerWatch.Activity.IDLE, OwnerWatch.activity(now, false, 0, 0, 0, 0, false, 0, 300));
        assertEquals(OwnerWatch.Activity.AFK, OwnerWatch.activity(now, false, 0, 0, 0, 0, false, 0, OwnerWatch.AFK_TICKS));
    }

    /** 一两下不算在挖矿：零星敲掉一块方块的时候她不该换成守矿洞的样子。 */
    @Test void oneBlockIsNotMining() {
        long now = 10_000;
        assertEquals(OwnerWatch.Activity.IDLE, OwnerWatch.activity(now, false, now - 5, 1, 0, 0, false, 0, 50));
    }
}
