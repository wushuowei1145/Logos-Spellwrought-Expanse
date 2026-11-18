package dev.logos.dimension.companion;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 精灵公主的心智（docs/ELF_PRINCESS_COMPANION.md 第 5 节）：通用的那些在 {@link CompanionMind}，这里是她自己的——
 * 用世界树之弓的打法（远了锁定、被贴脸时散射加后跳）、治疗和护盾（MEND）、给主人缠上生命之力（BLESS）、荆棘缠绕（ENSNARE）。
 *
 * <p>她是辅助：照顾同伴排在射箭前面（治疗、护盾的分比迎战、掩护都高），打起来时站在主人身后、和敌人隔开距离，
 * 从那里射箭；散射只是被贴到跟前时脱身用的。
 *
 * <p>行动策略（docs/COMPANIONS.md 第 3.4 节）在她身上的意思：
 * <ul>
 *   <li>随机应变：和敌人隔开 {@link #KEEP_MIN} 格以上，站到主人身后锁定射；贴到 {@link #FAN_AT} 格内才散射、往后跳。</li>
 *   <li>优先远程：隔得更远（{@link #KEEP_FAR} 格），只在被贴脸（{@link #RANGED_FAN_AT} 格）时才散射。</li>
 *   <li>优先近战：她没有近身武器，「近战」是站得近一点（{@link #KEEP_NEAR} 格），{@link #MELEE_FAN_AT} 格内就散射；不会冲上去。</li>
 *   <li>不参与攻击：不射箭，躲到主人背后；治疗、护盾照常（那不是攻击）。</li>
 * </ul>
 */
public final class PrincessMind extends CompanionMind {
    /** 和敌人至少隔多远：随机应变、优先远程、优先近战。 */
    static final double KEEP_MIN = 7, KEEP_FAR = 11, KEEP_NEAR = 5;
    /** 敌人近到这里才散射：随机应变、优先远程、优先近战。 */
    static final double FAN_AT = 3.5, RANGED_FAN_AT = 3, MELEE_FAN_AT = 4.5;
    /** 打起来时她站在主人身后（离敌人的那一边）多远；离那个位置超过 {@link #SUPPORT_SLACK} 格才走过去。 */
    static final double BEHIND_OWNER = 3, SUPPORT_SLACK = 3;
    /** 走过去治疗：要离那位多近、看得见。 */
    static final double MEND_REACH = 12;
    /** 治疗、护盾的分：有快撑不住又被盯上的给护盾；打着仗时治疗；没在打时治疗。都比迎战（0.7–0.8）、掩护主人（0.95）高。 */
    static final double SHIELD_SCORE = .97, MEND_SCORE = .96, MEND_CALM_SCORE = .5;
    /** 锁定的射程。 */
    static final double SHOOT_RANGE = PrincessBow.RANGE;

    private final ElfPrincessCompanion c;
    private @Nullable LivingEntity mendTarget;
    private boolean shieldIt;

    PrincessMind(ElfPrincessCompanion c) {
        super(c);
        this.c = c;
    }

    @Override
    protected boolean engagedOwn(Kind k) {
        return k == Kind.ENSNARE || k == Kind.BLESS;
    }

    /** 撤退时什么都不做（世界树正把她收回去）。 */
    @Override
    protected boolean busy(ServerLevel level, ServerPlayer p, long now) {
        if (!c.withdrawing() && !c.bow.backstepping(now)) return false;
        c.getNavigation().stop();
        return true;
    }

    // ---------------------------------------------------------------- 打分

    @Override
    protected void scoreOwn(ServerLevel level, ServerPlayer p, OwnerWatch w, long now, Situation s) {
        PrincessMagic m = c.magic;
        // 治疗、护盾：先看有没有快撑不住又被盯上的（第二印起给护盾），再看受伤的
        LivingEntity danger = m.unlocked(PrincessMagic.SHIELD_SEAL) && m.ready(PrincessMagic.Kind.SHIELD, now) ? m.endangered(level, p) : null;
        LivingEntity hurt = danger == null && m.ready(PrincessMagic.Kind.MEND, now) ? m.wounded(level, p, PrincessMagic.MEND_BELOW) : null;
        mendTarget = danger != null ? danger : hurt;
        shieldIt = danger != null;
        scores.put(Kind.MEND, m.casting() && current == Kind.MEND ? 1.1
                : mendTarget == null ? 0 : danger != null ? SHIELD_SCORE : foe == null ? MEND_CALM_SCORE : MEND_SCORE);
        boolean strong = foe != null && PrincessMagic.strong(foe, threats);
        // 生命缠绕：主人一打起来就给（不用等强敌）
        scores.put(Kind.BLESS, foe != null && m.unlocked(PrincessMagic.BLESS_SEAL) && m.ready(PrincessMagic.Kind.BLESS, now)
                && !PrincessMagic.wrapped(p) && w.fighting(now) && dist < 10 ? .9 : 0);
        scores.put(Kind.ENSNARE, m.casting() && current == Kind.ENSNARE ? 1.05
                : foe != null && !s.holdBack() && strong && m.unlocked(PrincessMagic.SNARE_SEAL) && m.ready(PrincessMagic.Kind.SNARE, now)
                && foe.distanceTo(c) < PrincessMagic.SNARE_RANGE && c.hasLineOfSight(foe) ? .91 : 0);
    }

    @Override
    protected void afterThink(ServerLevel level, ServerPlayer p, long now) {
        if (engaged() || current == Kind.RETREAT || current == Kind.COVER) c.fought(now);
    }

    @Override
    protected boolean canTurnToTalk() {
        return !c.bow.drawing() && !c.magic.casting();
    }

    // ---------------------------------------------------------------- 做

    @Override
    protected void actOwn(Kind kind, ServerLevel level, ServerPlayer p, long now) {
        switch (kind) {
            case MEND -> mend(now);
            case BLESS -> bless(p, now);
            case ENSNARE -> ensnare(now);
            default -> idle(p, now, true);
        }
    }

    /** 走到够得着的地方（{@link #MEND_REACH} 格内、看得见），朝那位伸出手。 */
    private void mend(long now) {
        if (c.magic.casting()) {
            c.getNavigation().stop();
            return;
        }
        LivingEntity t = mendTarget;
        if (t == null || !t.isAlive()) return;
        if (t != c && (c.distanceTo(t) > MEND_REACH || !c.hasLineOfSight(t))) {
            if (now % THINK == 0 || c.getNavigation().isDone()) c.getNavigation().moveTo(t, 1.3);
            return;
        }
        c.getNavigation().stop();
        if (t != c) face(t);
        c.magic.start(shieldIt ? PrincessMagic.Kind.SHIELD : PrincessMagic.Kind.MEND, t);
    }

    /** 走到主人身边，把一捧生命之力递到主人手里。 */
    private void bless(ServerPlayer p, long now) {
        if (c.magic.casting()) return;
        if (dist > 4) {
            if (now % THINK == 0 || c.getNavigation().isDone()) c.getNavigation().moveTo(p, 1.4);
            return;
        }
        c.getNavigation().stop();
        face(p);
        c.magic.start(PrincessMagic.Kind.BLESS, p);
    }

    /** 对着强敌蹲下按地，荆棘从它脚边绕一圈长出来。 */
    private void ensnare(long now) {
        if (c.magic.casting() || foe == null) return;
        c.getNavigation().stop();
        face(foe);
        c.magic.start(PrincessMagic.Kind.SNARE, foe);
    }

    /**
     * 迎战：拉着弓时站住对准；被贴到跟前（{@link #FAN_AT}）才横着拉弓散射、放完往后跳。别的时候先站好位置——和敌人隔开
     * {@link #KEEP_MIN} 格以上、在主人身后——再锁定射；走位时不射。{@code hold} 时站在原地（护主）。
     */
    @Override
    protected void fight(ServerLevel level, LivingEntity target, long now, boolean hold) {
        c.fought(now);
        // 天然呆：敌人冒出来那一下她还没反应过来，吓一跳以后才出手
        if (owner != null && ((PrincessMood) c.mood).hesitating(owner, now)) {
            c.getNavigation().stop();
            c.getLookControl().setLookAt(target, 20, 20);
            return;
        }
        PrincessBow bow = c.bow;
        if (bow.drawing() || c.magic.casting()) {
            c.getNavigation().stop();
            return;
        }
        double d = c.distanceTo(target);
        boolean sees = c.hasLineOfSight(target);
        double fanAt = tactic == CompanionTactic.RANGED ? RANGED_FAN_AT : tactic == CompanionTactic.MELEE ? MELEE_FAN_AT : FAN_AT;
        if (d <= fanAt && sees && bow.fanReady(now)) {
            face(target);
            bow.draw(target, true);
            return;
        }
        if (!hold) {
            double keep = tactic == CompanionTactic.RANGED ? KEEP_FAR : tactic == CompanionTactic.MELEE ? KEEP_NEAR : KEEP_MIN;
            if (d < keep) {
                // 太近了：往外退开，退向主人那边
                Vec3 away = c.position().subtract(target.position()).multiply(1, 0, 1);
                away = away.lengthSqr() < 1e-4 ? Vec3.directionFromRotation(0, c.getYRot() + 180) : away.normalize();
                Vec3 to = c.position().add(away.scale(4));
                if (owner != null && to.distanceTo(owner.position()) > 10) to = owner.position();
                c.getNavigation().moveTo(to.x, to.y, to.z, 1.35);
                c.setSprinting(true);
                c.getLookControl().setLookAt(target, 40, 40);
                return;
            }
            if (owner != null) {
                // 站到主人身后（敌人的另一边），离敌人不近于 keep
                Vec3 spot = supportSpot(owner, target, keep);
                if (c.position().distanceTo(spot) > SUPPORT_SLACK) {
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
        if (sees && d < SHOOT_RANGE && bow.volleyReady(now)) {
            face(target);
            bow.draw(target, false);
        }
    }

    /**
     * 她打仗时站的地方：从敌人看过去在主人后面，离主人 {@link #BEHIND_OWNER} 格；主人自己离敌人太近时再往后，让她离敌人不近于 {@code keep}。
     */
    static Vec3 supportSpot(LivingEntity owner, LivingEntity foe, double keep) {
        Vec3 line = owner.position().subtract(foe.position()).multiply(1, 0, 1);
        double ownerFromFoe = line.length();
        Vec3 dir = ownerFromFoe < 1e-3 ? Vec3.directionFromRotation(0, owner.getYRot() + 180).multiply(1, 0, 1).normalize() : line.scale(1 / ownerFromFoe);
        return foe.position().add(dir.scale(Math.max(ownerFromFoe + BEHIND_OWNER, keep))).with(net.minecraft.core.Direction.Axis.Y, owner.getY());
    }

    /**
     * 后撤（血少了，或不参与攻击时敌人近了）：退到主人身后；被追到跟前就散射一下往后跳（不参与攻击时只跳不射）；
     * 站到主人身后以后，没在躲的话照常锁定射。
     */
    @Override
    protected void retreat(ServerPlayer p, LivingEntity threat, long now) {
        PrincessBow bow = c.bow;
        if (bow.drawing() || c.magic.casting()) {
            c.getNavigation().stop();
            return;
        }
        double d = c.distanceTo(threat);
        if (d <= RANGED_FAN_AT + .5 && c.level() instanceof ServerLevel level) {
            if (!holdsBack() && bow.fanReady(now) && c.hasLineOfSight(threat)) {
                face(threat);
                bow.draw(threat, true);
            } else if (!bow.backstepping(now) && now >= nextEscape) {
                bow.backstep(level, now, threat);
                nextEscape = now + 40;
            }
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
        if (!holdsBack() && c.hasLineOfSight(threat) && d < SHOOT_RANGE && bow.volleyReady(now)) {
            face(threat);
            bow.draw(threat, false);
        }
    }

    @Override
    protected void describeOwn(StringBuilder sb) {
        long now = c.level().getGameTime();
        sb.append(" bow=").append(c.bow.describe(now)).append(" cast=").append(c.magic.describe(now)).append(" power=")
                .append(String.format("%.2f", c.spellPower()));
    }
}
