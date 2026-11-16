package dev.logos.dimension.gametest;

import dev.logos.dimension.corruption.Corruption;
import dev.logos.dimension.corruption.CorruptionDamage;
import dev.logos.dimension.corruption.CorruptionNetworks;
import dev.logos.dimension.corruption.mob.CorruptionMobs;
import dev.logos.dimension.elven.blight.CorruptedElfQueen;
import dev.logos.dimension.elven.blight.ElvenBlight;
import dev.logos.dimension.foxfire.Foxfire;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.lang.Element;
import dev.logos.runtime.Effects;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/** 腐败伤害（生命的子类型）、女王的抗性表和核心庇护，用真实的 Logos 伤害入口打。 */
public final class CorruptionDamageTests {
    private CorruptionDamageTests() {}

    private static <T extends Mob> T spawn(GameTestHelper h, EntityType<T> type, int x, int z) {
        T mob = type.create(h.getLevel(), EntitySpawnReason.TRIGGERED);
        mob.setPos(h.absoluteVec(new net.minecraft.world.phys.Vec3(x + .5, 2, z + .5)));
        mob.setNoAi(true);
        h.getLevel().addFreshEntity(mob);
        return mob;
    }

    /** 腐败一方打出的生命伤害换成腐败来源；打中衍生物反而回血。 */
    static void heals(GameTestHelper h) {
        var queen = spawn(h, ElvenBlight.QUEEN.get(), 1, 1);
        var zombie = spawn(h, CorruptionMobs.FILTH_ZOMBIE.get(), 4, 1);
        var cow = spawn(h, EntityType.COW, 1, 4);
        zombie.setHealth(zombie.getMaxHealth() / 2);
        float zombieBefore = zombie.getHealth(), cowBefore = cow.getHealth();
        long swapped = dev.logos.dimension.elven.blight.QueenSpellEffects.replaced();
        CorruptionDamage.hit(queen, zombie, 4);
        CorruptionDamage.hit(queen, cow, 4);
        h.assertTrue(dev.logos.dimension.elven.blight.QueenSpellEffects.replaced() > swapped, "腐败伤害的命中特效没有换成腐败的");
        h.assertTrue(zombie.getHealth() > zombieBefore, "腐败伤害没有治疗衍生物：" + zombieBefore + " → " + zombie.getHealth());
        h.assertTrue(cow.getHealth() < cowBefore, "腐败伤害没有伤到普通生物");
        h.assertTrue(cow.getLastDamageSource() != null && cow.getLastDamageSource().is(Corruption.DAMAGE), "腐败一方的生命伤害没有换成腐败来源");
        // 不是腐败一方打出的生命伤害仍是普通生命（原版魔法来源）。
        var other = spawn(h, EntityType.ZOMBIE, 4, 4);
        var cow2 = spawn(h, EntityType.COW, 6, 4);
        long before = dev.logos.dimension.elven.blight.QueenSpellEffects.replaced();
        Effects.damage(other, cow2, 2, Element.LIFE);
        h.assertTrue(dev.logos.dimension.elven.blight.QueenSpellEffects.replaced() == before, "普通生命伤害的特效也被换了");
        h.assertTrue(cow2.getLastDamageSource() != null && !cow2.getLastDamageSource().is(Corruption.DAMAGE), "普通生命伤害被当成了腐败");
        h.succeed();
    }

    private static float strainFrom(CorruptedElfQueen q, Runnable hit) {
        q.invulnerableTime = 0;
        float before = q.strain();
        hit.run();
        return q.strain() - before;
    }

    private static void near(GameTestHelper h, float actual, double expected, String what) {
        h.assertTrue(Math.abs(actual - expected) < 1e-3, what + "：期望 " + expected + "，实际 " + actual);
    }

    /** 抗性表：火、狐火 50%，其他奥秘与冰霜等 85%，生命多受 30%，原版魔法同女巫，普通打击不吃法术抗性。 */
    static void resistances(GameTestHelper h) {
        ServerLevel world = h.getLevel();
        var q = spawn(h, ElvenBlight.QUEEN.get(), 1, 1);
        var attacker = spawn(h, EntityType.ZOMBIE, 5, 1);
        q.refreshWard(world);
        int ward = q.ward();
        double left = 1 - Math.min(.6, .03 * ward);
        h.assertTrue(Math.abs(dev.logos.runtime.SpellModifiers.taken(q, Element.LIFE) - 1.3) < 1e-9, "生命易伤不是 30%");
        near(h, strainFrom(q, () -> Effects.damage(attacker, q, 8, Element.FIRE)), 4 * left, "Logos 火");
        near(h, strainFrom(q, () -> Effects.damage(attacker, q, 8, Element.ARCANE)), 1.2 * left, "普通奥秘");
        near(h, strainFrom(q, () -> Foxfire.deal(attacker, q, 8, FoxfireFlame.KITSUNE)), 4 * left, "狐火");
        near(h, strainFrom(q, () -> Effects.damage(attacker, q, 8, Element.FROST)), 1.2 * left, "冰霜");
        near(h, strainFrom(q, () -> Effects.damage(attacker, q, 8, Element.LIFE)), 10.4 * left, "生命");
        var lookup = world.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE);
        near(h, strainFrom(q, () -> q.hurtServer(world, new DamageSource(lookup.getOrThrow(DamageTypes.MAGIC), attacker), 8)), 1.2 * left, "原版魔法");
        near(h, strainFrom(q, () -> q.hurtServer(world, new DamageSource(lookup.getOrThrow(DamageTypes.IN_FIRE), attacker), 8)), 4 * left, "原版的火");
        near(h, strainFrom(q, () -> q.hurtServer(world, world.damageSources().mobAttack(attacker), 8)), 8 * left, "普通近战");
        h.succeed();
    }

    /** 核心庇护：周围每个腐败核心减 3%；虚空这类标签里的伤害不减。 */
    static void ward(GameTestHelper h) {
        ServerLevel world = h.getLevel();
        var q = spawn(h, ElvenBlight.QUEEN.get(), 1, 1);
        var attacker = spawn(h, EntityType.ZOMBIE, 5, 1);
        q.refreshWard(world);
        int baseline = q.ward();
        for (int i = 0; i < 5; i++) world.setBlockAndUpdate(h.absolutePos(new BlockPos(2 + i * 2, 1, 6)), Corruption.CORE.get().defaultBlockState());
        q.refreshWard(world);
        h.assertTrue(q.ward() == baseline + 5, "核心庇护没有数到新放的 5 个核心：" + baseline + " → " + q.ward());
        h.assertTrue(CorruptionNetworks.get(world).coresWithin(q.blockPosition(), 64) == q.ward(), "庇护计数和网络里的核心不一致");
        double left = 1 - Math.min(.6, .03 * q.ward());
        near(h, strainFrom(q, () -> q.hurtServer(world, world.damageSources().mobAttack(attacker), 10)), 10 * left, "核心庇护减伤");
        float health = q.getHealth();
        q.invulnerableTime = 0;
        q.hurtServer(world, world.damageSources().genericKill(), 10);
        h.assertTrue(Math.abs(health - q.getHealth() - 10) < 1e-3, "绕过庇护的伤害被减了：" + (health - q.getHealth()));
        h.succeed();
    }

    public static void register(java.util.Map<String, Consumer<GameTestHelper>> tests) {
        tests.put("elven_blight_corruption_heals_its_own", CorruptionDamageTests::heals);
        tests.put("elven_blight_queen_resistances", CorruptionDamageTests::resistances);
        tests.put("elven_blight_queen_blight_ward", CorruptionDamageTests::ward);
    }
}
