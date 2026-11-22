package dev.logos.dimension.companion;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.phys.Vec3;

/**
 * 琥珀的心智（docs/KOHAKU_COMPANION.md 第 7 节）：通用的那些在 {@link CompanionMind}，这里是她自己的。
 * <ul>
 *   <li>WARD 琥珀护膜：一打起来先给自己罩上；碎了歇够了再凝。分比迎战高。</li>
 *   <li>迎战（ENGAGE）就是施法：她更喜欢拉开距离——站到主人身后、和敌人隔开，站定了再放；被贴近了先往外退。</li>
 *   <li>HISS 哈气：不参与攻击时她不出手，可敌人冲过来她还是会朝它哈气（{@code act_guard}）。</li>
 *   <li>幻翼：同原版的猫，幻翼靠近她、冲着主人来时她哈一口气，幻翼就不敢扑下来（{@link #scarePhantoms}）。</li>
 * </ul>
 *
 * <p>行动策略在她身上的意思（第 7.5 节）：随机应变隔开 {@link #KEEP} 格；优先远程隔得更远；优先近战站得近一点（她还是法师，不冲上去）；
 * 不参与攻击不施法，躲到主人背后，护膜照常、敌人近了哈气。
 */
public final class KohakuMind extends CompanionMind {
    /** 和敌人至少隔多远：随机应变、优先远程、优先近战。 */
    static final double KEEP = 8, KEEP_FAR = 12, KEEP_NEAR = 5;
    /** 护膜的分（比迎战、掩护都高）；哈气的分（比躲开高一点）。 */
    static final double WARD_SCORE = .97, HISS_SCORE = .8;
    /** 哈气：敌人离她或主人多近、两次之间隔多久（刻）。 */
    static final double HISS_AT = 9;
    static final int HISS_GAP = 100;
    /** 幻翼：离她多远以内的会被吓走；两次哈气之间隔多久。 */
    static final double PHANTOM_RANGE = 16;
    static final int PHANTOM_GAP = 60;
    /**
     * 站位：在主人斜后方——主人身后、再往一侧偏 {@link #SIDE_OFFSET} 格。一滴就够是直线飞的，站在主人正后方会被主人挡住。
     * 弹道离主人近过 {@link #CLEAR} 格时不放，往旁边挪一步。
     */
    static final double SIDE_OFFSET = 2.5, CLEAR = 1.0;
    /** 敌人近到这里就先退开（之后凝辉在这里接手）；冲着她来的近过「隔开」也退。 */
    static final double CLOSE = 3.5;
    /** 测试用：哈过几次气、吓走过几次幻翼。 */
    static int hisses, phantomsScared;

    private final KohakuCompanion c;
    private long nextHiss, nextPhantomHiss;
    /** 站在主人哪一侧（1 或 -1，进入战斗时按她当时在哪边定）。 */
    private int side = 1;
    private boolean sideChosen;

    KohakuMind(KohakuCompanion c) {
        super(c);
        this.c = c;
    }

    @Override
    protected boolean engagedOwn(Kind k) {
        return k == Kind.WARD;
    }

    /** 撤退、法阵还在转开时什么都不做。 */
    @Override
    protected boolean busy(ServerLevel level, ServerPlayer p, long now) {
        if (!c.withdrawing() && !c.inCircle()) return false;
        c.getNavigation().stop();
        return true;
    }

    @Override
    protected void beforeScore(ServerLevel level, ServerPlayer p, long now) {
        scarePhantoms(level, p, now);
    }

    // ---------------------------------------------------------------- 打分

    @Override
    protected void scoreOwn(ServerLevel level, ServerPlayer p, OwnerWatch w, long now, Situation s) {
        KohakuMagic m = c.magic;
        // 护膜：有敌人就先罩上（不参与攻击也罩，那不是攻击）
        boolean wardCasting = m.casting() && current == Kind.WARD;
        scores.put(Kind.WARD, wardCasting ? 1.1 : foe != null && m.wardReady(now) ? WARD_SCORE : 0);
        // 哈气：不出手时，敌人冲到她或主人跟前
        boolean hissing = current == Kind.HISS && "act_guard".equals(c.mood.playing());
        boolean near = foe != null && (foe.distanceTo(c) < HISS_AT || foe.distanceTo(p) < HISS_AT);
        scores.put(Kind.HISS, hissing ? 1 : s.holdBack() && near && now >= nextHiss ? HISS_SCORE : 0);
    }

    @Override
    protected void afterThink(ServerLevel level, ServerPlayer p, long now) {
        if (engaged() || current == Kind.RETREAT || current == Kind.HISS) c.fought(now);
    }

    @Override
    protected boolean canTurnToTalk() {
        return !c.magic.casting();
    }

    // ---------------------------------------------------------------- 做

    @Override
    protected void actOwn(Kind kind, ServerLevel level, ServerPlayer p, long now) {
        switch (kind) {
            case WARD -> {
                c.getNavigation().stop();
                c.setSprinting(false);
                if (foe != null) c.getLookControl().setLookAt(foe, 30, 30);
                if (!c.magic.casting()) c.magic.startWard(now);
            }
            case HISS -> hiss(level, now);
            default -> idle(p, now, true);
        }
    }

    /** 朝冲过来的敌人哈一口气：站住、转过去、耳朵压平、尾巴炸开（模型的 {@code act_guard}）。 */
    private void hiss(ServerLevel level, long now) {
        c.getNavigation().stop();
        c.setSprinting(false);
        if (foe == null) return;
        face(foe);
        if (now < nextHiss) return;
        nextHiss = now + HISS_GAP;
        c.mood.act("act_guard");
        level.playSound(null, c.getX(), c.getY(), c.getZ(), KohakuCompanion.cat().hissSound().value(), SoundSource.NEUTRAL, .9f, 1.15f);
        hisses++;
    }

    /**
     * 幻翼（同原版的猫）：她身边 {@link #PHANTOM_RANGE} 格内的幻翼，冲着主人、她或附近的人来的，一律不敢扑下来——
     * 丢掉目标、往上飞开。她看见了就朝它哈一口气（隔一阵一次）。
     */
    private void scarePhantoms(ServerLevel level, ServerPlayer p, long now) {
        boolean any = false;
        Phantom near = null;
        for (Phantom ph : level.getEntitiesOfClass(Phantom.class, c.getBoundingBox().inflate(PHANTOM_RANGE), Phantom::isAlive)) {
            LivingEntity t = ph.getTarget();
            if (t != null && t != p && t != c && t.distanceTo(c) > PHANTOM_RANGE) continue;
            if (t != null) {
                ph.setTarget(null);
                Vec3 away = ph.position().subtract(c.position()).multiply(1, 0, 1);
                away = away.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : away.normalize();
                ph.setDeltaMovement(away.scale(.5).add(0, .6, 0));
                ph.hurtMarked = true;
                phantomsScared++;
            }
            any = true;
            if (near == null || ph.distanceToSqr(c) < near.distanceToSqr(c)) near = ph;
        }
        if (!any || now < nextPhantomHiss || c.magic.casting() || c.eating()) return;
        nextPhantomHiss = now + PHANTOM_GAP;
        if (!c.mood.acting()) c.mood.act("act_guard");
        c.getLookControl().setLookAt(near, 40, 60);
        level.playSound(null, c.getX(), c.getY(), c.getZ(), KohakuCompanion.cat().hissSound().value(), SoundSource.NEUTRAL, 1, 1.1f);
        hisses++;
    }

    /**
     * 迎战就是施法：放着术式时站住；离敌人近过「隔开」就往外退（退向主人那边）；不然先站到主人身后，站定了再放。
     * {@code hold} 时站在原地（护主）。
     */
    @Override
    protected void fight(ServerLevel level, LivingEntity target, long now, boolean hold) {
        c.fought(now);
        KohakuMagic m = c.magic;
        if (m.casting()) {
            c.getNavigation().stop();
            return;
        }
        double d = c.distanceTo(target);
        if (!hold) {
            double keep = tactic == CompanionTactic.RANGED ? KEEP_FAR : tactic == CompanionTactic.MELEE ? KEEP_NEAR : KEEP;
            // 被贴脸、或者冲着她来的近过「隔开」：先往外退；冲主人去的不退，站定了照打
            boolean atMe = target instanceof net.minecraft.world.entity.Mob mob && mob.getTarget() == c;
            if (d < CLOSE || atMe && d < keep - 1.5) {
                backOff(target, now);
                return;
            }
            if (owner != null && d >= keep - 1.5) {
                if (!sideChosen) {
                    side = sideOf(owner, target, c.position());
                    sideChosen = true;
                }
                Vec3 spot = castSpot(owner, target, keep, side);
                if (c.position().distanceTo(spot) > PrincessMind.SUPPORT_SLACK) {
                    if (now % THINK == 0 || c.getNavigation().isDone()) c.getNavigation().moveTo(spot.x, spot.y, spot.z, 1.3);
                    c.setSprinting(c.position().distanceTo(spot) > 8);
                    c.getLookControl().setLookAt(target, 40, 40);
                    return;
                }
            }
        }
        c.getNavigation().stop();
        c.setSprinting(false);
        c.getLookControl().setLookAt(target, 40, 40);
        castAt(target, now);
    }

    /** 往外退开一点（退向主人那边），边退边看着它。 */
    private void backOff(LivingEntity target, long now) {
        Vec3 away = c.position().subtract(target.position()).multiply(1, 0, 1);
        away = away.lengthSqr() < 1e-4 ? Vec3.directionFromRotation(0, c.getYRot() + 180) : away.normalize();
        Vec3 to = c.position().add(away.scale(4));
        if (owner != null && to.distanceTo(owner.position()) > 10) to = owner.position();
        if (now % 5 == 0 || c.getNavigation().isDone()) c.getNavigation().moveTo(to.x, to.y, to.z, 1.35);
        c.setSprinting(true);
        c.getLookControl().setLookAt(target, 40, 40);
    }

    /** 看得见、够得着、弹道上没有主人就放一页单体攻击（现在是一滴就够）；弹道上有主人就往旁边挪一步。 */
    private void castAt(LivingEntity target, long now) {
        if (!c.hasLineOfSight(target) || c.distanceTo(target) > KohakuMagic.RANGE) return;
        if (owner != null && !clearShot(c, owner, target)) {
            Vec3 line = target.position().subtract(c.position()).multiply(1, 0, 1);
            Vec3 perp = line.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : new Vec3(-line.z, 0, line.x).normalize();
            Vec3 to = c.position().add(perp.scale(side * 2));
            c.getNavigation().moveTo(to.x, to.y, to.z, 1.2);
            return;
        }
        KohakuSpells strike = KohakuSpells.ONE_DROP;
        if (c.magic.ready(strike, now)) c.magic.startPage(strike, target, now);
    }

    /** 后撤（血少了，或不参与攻击时敌人近了）：退到主人身后；站好了、没在躲的话照常施法。 */
    @Override
    protected void retreat(ServerPlayer p, LivingEntity threat, long now) {
        if (c.magic.casting()) {
            c.getNavigation().stop();
            return;
        }
        Vec3 back = p.position().subtract(threat.position()).multiply(1, 0, 1);
        Vec3 spot = p.position().add(back.lengthSqr() < 1e-4 ? Vec3.ZERO : back.normalize().scale(2.5));
        if (c.position().distanceTo(spot) > 1.2) {
            if (now % THINK == 0) c.getNavigation().moveTo(spot.x, spot.y, spot.z, 1.4);
            c.setSprinting(true);
            c.getLookControl().setLookAt(threat, 30, 30);
            return;
        }
        c.getNavigation().stop();
        c.setSprinting(false);
        c.getLookControl().setLookAt(threat, 30, 30);
        if (!holdsBack()) castAt(threat, now);
    }

    /** 打仗时她站的地方：主人身后（{@link PrincessMind#supportSpot}），再往 {@code side} 那一侧偏 {@link #SIDE_OFFSET} 格。 */
    static Vec3 castSpot(LivingEntity owner, LivingEntity foe, double keep, int side) {
        Vec3 base = PrincessMind.supportSpot(owner, foe, keep);
        Vec3 line = owner.position().subtract(foe.position()).multiply(1, 0, 1);
        Vec3 dir = line.lengthSqr() < 1e-4 ? new Vec3(0, 0, 1) : line.normalize();
        return base.add(new Vec3(-dir.z, 0, dir.x).scale(side * SIDE_OFFSET));
    }

    /** {@code at} 在主人到敌人那条线的哪一侧（1 或 -1）。 */
    static int sideOf(LivingEntity owner, LivingEntity foe, Vec3 at) {
        Vec3 dir = owner.position().subtract(foe.position()), to = at.subtract(owner.position());
        return dir.x * to.z - dir.z * to.x >= 0 ? 1 : -1;
    }

    /** 从她眼睛到 {@code t} 身子中间那一段，离主人身子中间远过 {@link #CLEAR} 格。 */
    static boolean clearShot(LivingEntity c, LivingEntity owner, LivingEntity t) {
        Vec3 a = c.getEyePosition(), b = t.position().add(0, t.getBbHeight() * .55, 0), o = owner.position().add(0, owner.getBbHeight() * .5, 0);
        Vec3 ab = b.subtract(a);
        double len2 = ab.lengthSqr();
        if (len2 < 1e-6) return true;
        double k = Mth.clamp(o.subtract(a).dot(ab) / len2, 0, 1);
        return a.add(ab.scale(k)).distanceTo(o) > CLEAR;
    }

    @Override
    protected void switched(Kind from, Kind to, long now) {
        // 打完一仗再打下一仗时，重新按她在哪边定站位
        if (to != Kind.ENGAGE && to != Kind.WARD) sideChosen = false;
    }

    /** 转过去正对着 {@code t} 的身子中间（放弹射物前：她的视线就是术式的方向）。 */
    static void faceExactly(KohakuCompanion c, LivingEntity t) {
        Vec3 eye = c.getEyePosition(), at = t.position().add(0, t.getBbHeight() * .55, 0);
        Vec3 d = at.subtract(eye);
        double h = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90, pitch = (float) -(Mth.atan2(d.y, h) * Mth.RAD_TO_DEG);
        c.setYRot(yaw);
        c.setXRot(pitch);
        c.yBodyRot = yaw;
        c.setYHeadRot(yaw);
    }

    @Override
    protected void describeOwn(StringBuilder sb) {
        long now = c.level().getGameTime();
        sb.append(" cast=").append(c.magic.describe(now)).append(String.format(" power=%.2f mana=%.0f/%.0f store=%.0f staff=%s",
                c.spellPower(), c.mana(), c.maxMana(), c.staff.store(), c.staff.held().getItem()));
    }
}
