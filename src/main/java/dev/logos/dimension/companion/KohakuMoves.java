package dev.logos.dimension.companion;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.CombatWeapon;
import dev.logos.dimension.combat.Moveset;
import dev.logos.dimension.combat.Movesets;
import dev.logos.item.BladeStaffItem;
import dev.logos.lang.Element;
import dev.logos.lang.Runes;
import dev.logos.lang.Spell;
import dev.logos.runtime.Casting;
import dev.logos.runtime.Effects;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 琥珀的近身解法（docs/KOHAKU_COMPANION.md 第 6、7.4 节，第三步）：
 * <ul>
 *   <li>凝辉：敌人贴到跟前（又不到要逃的地步）时，右手凝出魔力大剑「凝辉」横扫一圈——身边前方 {@code cone}° 内的敌人受奥术伤害、
 *       往外击退；收势时剑碎成光，她往后一跳拉开距离。伤害、张角、出手时刻在招式文件 {@code combat/movesets/kohaku_companion.json}；
 *       手里是你给的杖剑时直接用杖剑的剑刃砍（不另外凝剑），花费 −30%、伤害 +30%（同杖剑对缠绕的加成）。</li>
 *   <li>猫步：被包围时闪到开阔的地方（她自己的载页 {@code cat_step}，Logos 的 {@code blink}）。起跳的地方留下几个发光的猫爪印，
 *       在落点猫式落地。</li>
 *   <li>两个人的猫步（第九印）：你快撑不住、身边又围着敌人时，她拉着你一起闪走（载页 {@code two_step}）。一场战斗一次。</li>
 * </ul>
 * 动作走 combat 控制器：{@code conjure_sweep}、{@code blink_out} → {@code blink_in}（模型第 7 版）。
 */
final class KohakuMoves {
    /** 凝辉的战斗网格（{@code combat/weapons/vesperglass_greatsword.json}，tools/import_vesperglass.py 导入）；动作共用长剑的。客户端按它画剑。 */
    static final CombatWeapon VESPERGLASS = CombatWeapon.register(70, "vesperglass_greatsword", "longsword", LogosDimension.id("kohaku_companion"));
    /** 招式文件里凝辉那一招。 */
    static final String SWEEP = "conjure_sweep";
    /** 凝辉：半径、击退、冷却（刻）、花费；动作多长、几时剑碎、几时往后跳（同模型的标记）。 */
    static final double SWEEP_RADIUS = 3.5, SWEEP_PUSH = 1.2;
    static final int SWEEP_COOLDOWN = 120, SWEEP_TICKS = 32, SHATTER_AT = 17, HOP_AT = 20;
    static final double SWEEP_MANA = 80, BLADE_DISCOUNT = .3, BLADE_BONUS = .3;
    /** 前方张角（招式文件的 cone 最多 180°，这一斩是 220°，写在这里）；招式文件读不到时的伤害（同文件里写的）。 */
    static final double SWEEP_DAMAGE = 6, SWEEP_CONE = 220;
    static final int SWEEP_CONTACT = 12;
    /** 猫步：冷却、起跳到消失几刻、落地动作多长；落点离她多远、附近敌人最多几个。 */
    static final int BLINK_COOLDOWN = 200, VANISH_AT = 6, LAND_TICKS = 24;
    /** 猫步、两个人的猫步花多少（同两页载页写的注入）。 */
    static final double BLINK_MANA = 60, CARRY_MANA = 120;
    static final double BLINK_MIN = 8, BLINK_MAX = 14, CLEAR_RADIUS = 5;

    enum Kind { SWEEP, BLINK, CARRY }

    private final KohakuCompanion c;
    private @Nullable Kind doing;
    private long start = -1;
    private @Nullable LivingEntity target;
    private @Nullable Vec3 dest;
    private boolean blade, landing;
    private long sweepReady, blinkReady;
    /** 测试用：横扫过几次（打中几个）、猫步闪过几次、两个人一起闪过几次。 */
    static int sweeps, sweepHits, blinks, carries;

    KohakuMoves(KohakuCompanion c) {
        this.c = c;
    }

    boolean busy() {
        return doing != null;
    }

    boolean sweeping() {
        return doing == Kind.SWEEP;
    }

    boolean sweepReady(long now) {
        return doing == null && now >= sweepReady && c.available() >= sweepCost();
    }

    boolean blinkReady(long now) {
        return doing == null && now >= blinkReady;
    }

    double sweepCost() {
        return SWEEP_MANA * (c.staff.blade() ? 1 - BLADE_DISCOUNT : 1);
    }

    void cancel() {
        if (doing == null) return;
        doing = null;
        landing = false;
        if (c.move().startsWith("conjure_") || c.move().startsWith("blink_")) c.setMove("");
    }

    // ---------------------------------------------------------------- 凝辉

    /** 凝辉：转过去对着 {@code t}，凝出大剑。 */
    boolean startSweep(LivingEntity t, long now) {
        if (!sweepReady(now) || c.magic.casting() || c.eating()) return false;
        double cost = sweepCost();
        double fromStaff = Math.min(cost, c.staff.store());
        c.staff.setStore(c.staff.store() - fromStaff);
        c.spendMana(cost - fromStaff);
        c.mood.cut();
        doing = Kind.SWEEP;
        start = now;
        target = t;
        blade = c.staff.blade();
        KohakuMind.faceExactly(c, t);
        c.getNavigation().stop();
        c.setMove(SWEEP + "|" + now + "|1|false");
        c.fought(now);
        if (c.level() instanceof ServerLevel level) {
            Vec3 hand = hand();
            level.sendParticles(new DustParticleOptions(KohakuCompanion.AMBER, 1f), hand.x, hand.y, hand.z, 12, .2, .2, .2, 0);
            level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.NEUTRAL, .9f, 1.6f);
        }
        return true;
    }

    private Vec3 hand() {
        return c.getEyePosition().add(Vec3.directionFromRotation(0, c.yBodyRot + 40).scale(.45)).add(0, -.4, 0);
    }

    /** 招式文件里的这一招；读不到为 null（用代码里的同样的数）。 */
    private static Moveset.@Nullable Move move(ServerLevel level) {
        Moveset set = Movesets.get(level.getServer(), LogosDimension.id("kohaku_companion"));
        return set == null ? null : set.move(SWEEP);
    }

    static int contactTick(Moveset.@Nullable Move m) {
        return m == null || !m.hasContact() ? SWEEP_CONTACT : (int) Math.round(m.contact() * 20);
    }

    private void tickSweep(ServerLevel level, long now) {
        long t = now - start;
        c.getNavigation().stop();
        Moveset.Move m = move(level);
        if (t < contactTick(m) && target != null && target.isAlive()) c.getLookControl().setLookAt(target, 40, 40);
        if (t == contactTick(m)) strike(level, m);
        if (t == SHATTER_AT && !blade) {
            Vec3 h = hand();
            level.sendParticles(new DustParticleOptions(0xBFE8E4, 1.1f), h.x, h.y, h.z, 24, .5, .3, .5, 0);
            level.sendParticles(ParticleTypes.END_ROD, h.x, h.y, h.z, 10, .4, .3, .4, .05);
            level.playSound(null, h.x, h.y, h.z, SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.NEUTRAL, .9f, 1.4f);
        }
        if (t == HOP_AT) hop(level);
        if (t >= SWEEP_TICKS) cancel();
    }

    /**
     * 出手：她身边 {@link #SWEEP_RADIUS} 格、前方 {@code cone}° 内她能伤的敌人，每个受奥术伤害（乘她的法强，杖剑 +30%），往外击退。
     * 主人、主人的使魔和宠物、别的同伴她本来就伤不到（{@link CompanionEntity#canHarm}）。
     */
    private void strike(ServerLevel level, Moveset.@Nullable Move m) {
        double damage = (m == null ? SWEEP_DAMAGE : m.damage()) * (blade ? 1 + BLADE_BONUS : 1);
        double half = Math.toRadians(SWEEP_CONE / 2);
        Vec3 face = Vec3.directionFromRotation(0, c.getYRot()).multiply(1, 0, 1).normalize();
        int hit = 0;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, c.getBoundingBox().inflate(SWEEP_RADIUS),
                e -> e != c && e.isAlive() && c.canHarm(e) && (e instanceof Enemy || c.hostileTo(e)))) {
            Vec3 to = e.position().subtract(c.position()).multiply(1, 0, 1);
            double d = to.length();
            if (d > SWEEP_RADIUS + e.getBbWidth() / 2) continue;
            if (d > .3 && Math.acos(Mth.clamp(to.normalize().dot(face), -1, 1)) > half) continue;
            Effects.damage(c, e, damage, Element.ARCANE);
            Vec3 out = d < 1e-3 ? face : to.normalize();
            e.knockback(SWEEP_PUSH, -out.x, -out.z);
            hit++;
        }
        sweeps++;
        sweepHits += hit;
        // 一道琥珀色的弧光，沿着剑扫过的地方
        for (int i = 0; i <= 14; i++) {
            double a = Math.toRadians(c.getYRot() + 90) + (i / 14.0 - .5) * 2 * half;
            Vec3 p = c.position().add(-Math.sin(a - Math.PI / 2) * 2.6, 1.0, Math.cos(a - Math.PI / 2) * 2.6);
            level.sendParticles(new DustParticleOptions(KohakuCompanion.AMBER, 1.2f), p.x, p.y, p.z, 2, .1, .05, .1, 0);
        }
        level.sendParticles(ParticleTypes.SWEEP_ATTACK, c.getX() + face.x * 1.6, c.getY() + 1, c.getZ() + face.z * 1.6, 1, 0, 0, 0, 0);
        level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.NEUTRAL, 1, 1.1f);
        sweepReady = level.getGameTime() + SWEEP_COOLDOWN;
    }

    /** 往后一跳：背离她对着的那个、往主人那边偏；落点脚下没地面就往旁边跳。 */
    private void hop(ServerLevel level) {
        Vec3 away = target != null ? c.position().subtract(target.position()).multiply(1, 0, 1) : Vec3.ZERO;
        if (away.lengthSqr() < 1e-4) away = Vec3.directionFromRotation(0, c.getYRot() + 180);
        away = away.normalize();
        ServerPlayer p = c.ownerPlayer();
        if (p != null) {
            Vec3 toOwner = p.position().subtract(c.position()).multiply(1, 0, 1);
            if (toOwner.lengthSqr() > 1) away = away.add(toOwner.normalize().scale(.35)).normalize();
        }
        if (!standable(level, c.position().add(away.scale(2.6)))) {
            Vec3 side = new Vec3(-away.z, 0, away.x);
            away = standable(level, c.position().add(side.scale(2.6))) ? side : standable(level, c.position().subtract(side.scale(2.6))) ? side.scale(-1) : null;
        }
        if (away == null) return;
        c.setDeltaMovement(away.x * .7, .42, away.z * .7);
        c.hurtMarked = true;
    }

    // ---------------------------------------------------------------- 猫步

    /** 猫步：蹲身一跃，跳起来那一下闪到 {@code to}。{@code carry}：两个人的猫步，拉着主人一起。 */
    boolean startBlink(Vec3 to, boolean carry, long now) {
        if (doing != null || c.magic.casting() || c.eating()) return false;
        KohakuSpells page = carry ? KohakuSpells.TWO_STEP : KohakuSpells.CAT_STEP;
        if (!page.unlocked(c.seals()) || c.available() < (carry ? CARRY_MANA : BLINK_MANA)) return false;
        c.magic.cancel();
        c.mood.cut();
        doing = carry ? Kind.CARRY : Kind.BLINK;
        start = now;
        dest = to;
        landing = false;
        c.getNavigation().stop();
        c.setMove("blink_out|" + now + "|1|false");
        return true;
    }

    private void tickBlink(ServerLevel level, long now) {
        long t = now - start;
        c.getNavigation().stop();
        if (!landing && t == VANISH_AT) {
            Vec3 from = c.position();
            boolean carry = doing == Kind.CARRY;
            KohakuSpells page = carry ? KohakuSpells.TWO_STEP : KohakuSpells.CAT_STEP;
            ServerPlayer p = c.ownerPlayer();
            Spell spell = page.spell(level.getServer(), false);
            if (spell == null || dest == null) {
                cancel();
                return;
            }
            double cost = carry ? CARRY_MANA : BLINK_MANA, fromStaff = Math.min(cost, c.staff.store());
            c.staff.setStore(c.staff.store() - fromStaff);
            c.spendMana(cost - fromStaff);
            pawPrints(level, from);
            if (carry && p != null) pawPrints(level, p.position());
            // 按「已蓄满」放：起跳就是出手，不另外画脚下的施法圈
            Casting.castAs(c, spell, cost, c.staff.pageTier().recovery(), c, dest, true);
            c.resetFallDistance();
            if (carry && p != null) p.resetFallDistance();
            c.puff(level, c.position().add(0, .6, 0));
            c.blinkSound(level, c.position());
            if (carry) {
                carries++;
                if (p != null) CompanionLines.say(c, p, "two_step", 50);
            } else {
                blinks++;
                blinkReady = now + BLINK_COOLDOWN;
            }
            landing = true;
            start = now;
            c.setMove("blink_in|" + now + "|1|false");
            return;
        }
        if (landing && t >= LAND_TICKS) cancel();
    }

    /** 起跳的地方留下几个发光的猫爪印（琥珀色的光点按爪印的样子落在地上，慢慢淡掉）。 */
    static void pawPrints(ServerLevel level, Vec3 at) {
        double[][] toes = {{-.16, .17}, {-.06, .25}, {.06, .25}, {.16, .17}};
        for (int i = 0; i < 3; i++) {
            double ox = (i - 1) * .55, oz = (i % 2) * .4 - .15, y = at.y + .08;
            level.sendParticles(new DustParticleOptions(KohakuCompanion.AMBER, 1.6f), at.x + ox, y, at.z + oz, 6, .05, 0, .05, 0);
            for (double[] toe : toes) {
                level.sendParticles(new DustParticleOptions(0xFFD98A, 1.0f), at.x + ox + toe[0], y, at.z + oz + toe[1], 2, .01, 0, .01, 0);
            }
        }
        level.sendParticles(ParticleTypes.END_ROD, at.x, at.y + .3, at.z, 4, .2, .1, .2, .01);
    }

    void tick(ServerLevel level, long now) {
        if (doing == null) return;
        if (doing == Kind.SWEEP) tickSweep(level, now);
        else tickBlink(level, now);
    }

    // ---------------------------------------------------------------- 落点

    /**
     * 猫步的落点（第 7.4 节）：{@link #BLINK_MIN}–{@link #BLINK_MAX} 格外，背离敌群中心、往主人那边偏；站得住（下面是实心方块，没有岩浆、水），
     * {@link #CLEAR_RADIUS} 格内敌人不超过一个，看得见主人。几个方向都试过找不到就退而求其次往主人身边闪；那里也不行为 null。
     */
    static @Nullable Vec3 openGround(ServerLevel level, LivingEntity who, @Nullable ServerPlayer owner, List<? extends LivingEntity> foes) {
        Vec3 here = who.position();
        Vec3 center = here;
        if (!foes.isEmpty()) {
            Vec3 sum = Vec3.ZERO;
            for (LivingEntity f : foes) sum = sum.add(f.position());
            center = sum.scale(1.0 / foes.size());
        }
        Vec3 away = here.subtract(center).multiply(1, 0, 1);
        if (away.lengthSqr() < 1e-4) away = Vec3.directionFromRotation(0, who.getYRot() + 180);
        away = away.normalize();
        if (owner != null) {
            Vec3 toOwner = owner.position().subtract(here).multiply(1, 0, 1);
            if (toOwner.lengthSqr() > 1) away = away.add(toOwner.normalize().scale(.5)).normalize();
        }
        double base = Math.atan2(away.z, away.x);
        for (int i = 0; i < 12; i++) {
            double a = base + (i % 2 == 0 ? 1 : -1) * ((i + 1) / 2) * Math.PI / 8;
            for (double r = BLINK_MAX; r >= BLINK_MIN; r -= 2) {
                Vec3 spot = spot(level, here.add(Math.cos(a) * r, 0, Math.sin(a) * r));
                if (spot != null && clear(level, spot, foes) && (owner == null || sees(level, spot, owner))) return spot;
            }
        }
        // 远处都不行（地方窄）：近一点再找一圈
        for (int i = 0; i < 12; i++) {
            double a = base + (i % 2 == 0 ? 1 : -1) * ((i + 1) / 2) * Math.PI / 8;
            for (double r = BLINK_MIN - 1; r >= 5; r -= 1.5) {
                Vec3 spot = spot(level, here.add(Math.cos(a) * r, 0, Math.sin(a) * r));
                if (spot != null && clear(level, spot, foes)) return spot;
            }
        }
        if (owner != null) {
            Vec3 near = Companions.spotNear(level, owner, 2, who);
            return near.distanceTo(here) > 2 ? near : null;
        }
        return null;
    }

    /** 那一列里站得住的地方（Logos 的 {@code Runes.safeSpot}）；脚下是水或岩浆、离原来的高度太远都不要。 */
    private static @Nullable Vec3 spot(ServerLevel level, Vec3 at) {
        Vec3 s = Runes.safeSpot(level, at);
        if (Math.abs(s.y - at.y) > 6) return null;
        BlockPos feet = BlockPos.containing(s);
        if (!level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.below()).isEmpty()) return null;
        return standable(level, s) ? s : null;
    }

    private static boolean standable(ServerLevel level, Vec3 at) {
        BlockPos feet = BlockPos.containing(at.x, at.y + .2, at.z);
        for (int dy = 1; dy >= -3; dy--) {
            BlockPos f = feet.above(dy);
            if (level.getFluidState(f).is(net.minecraft.tags.FluidTags.LAVA)) return false;
            if (level.getBlockState(f.below()).isFaceSturdy(level, f.below(), net.minecraft.core.Direction.UP)
                    && level.getBlockState(f).getCollisionShape(level, f).isEmpty()
                    && level.getBlockState(f.above()).getCollisionShape(level, f.above()).isEmpty()) return true;
        }
        return false;
    }

    private static boolean clear(ServerLevel level, Vec3 spot, List<? extends LivingEntity> foes) {
        int near = 0;
        for (LivingEntity f : foes) if (f.position().distanceTo(spot) < CLEAR_RADIUS) near++;
        for (Mob m : level.getEntitiesOfClass(Mob.class, new net.minecraft.world.phys.AABB(spot, spot).inflate(CLEAR_RADIUS),
                m -> m.isAlive() && m instanceof Enemy && !foes.contains(m))) near++;
        return near <= 1;
    }

    private static boolean sees(ServerLevel level, Vec3 spot, ServerPlayer owner) {
        Vec3 eye = spot.add(0, 1, 0);
        return level.clip(new net.minecraft.world.level.ClipContext(eye, owner.getEyePosition(), net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, owner)).getType() == net.minecraft.world.phys.HitResult.Type.MISS;
    }

    String describe(long now) {
        return (doing == null ? "-" : doing.name().toLowerCase() + " " + (now - start)) + " sweep=" + (now >= sweepReady ? "ready" : "cd")
                + " blink=" + (now >= blinkReady ? "ready" : "cd");
    }

    /** 手里是不是杖剑（凝辉直接用剑刃砍，不另外凝剑；客户端也看主手）。 */
    static boolean bladeIn(net.minecraft.world.item.ItemStack stack) {
        return stack.getItem() instanceof BladeStaffItem;
    }
}
