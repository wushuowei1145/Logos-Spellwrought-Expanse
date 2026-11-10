package dev.logos.dimension.companion;

import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.dimension.foxfire.FoxfireWraps;
import dev.logos.dimension.npc.LogosMagicBridge;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 白梅的心智：效用 AI（docs/SHIRAUME_COMPANION.md 第 3、7.3 节）。每半秒看一眼处境，给每种行为打分
 * （0 是没意义，1 左右是迫切），做分最高的那个；正在做的那个多 {@link #HYSTERESIS}，免得两件事之间来回抖。
 * 保命的反射（掉队太远、卡住、掉进岩浆或虚空就闪到主人身边）每刻都看，不受打分影响。
 *
 * <p>和 Logos 魔力生命体的 {@code LifeformMind} 是同一种做法。主人的处境来自 {@link OwnerWatch}。
 */
public final class CompanionMind extends Goal {
    static final int THINK = 10;
    static final double HYSTERESIS = .1;
    /** 跟随：近到这里就停，远过这里就走，再远就跑，远过 {@link #BLINK_AT} 就闪过去。 */
    static final double NEAR = 2.2, FAR = 4, RUN_AT = 9, BLINK_AT = 24;
    /** 迎战：和敌人保持的距离、放狐火弹的射程、看哪些东西算威胁。 */
    static final double KEEP_MIN = 4.5, KEEP_MAX = 10, SHOOT_RANGE = 14, THREAT_RADIUS = 12;
    /** 两次放狐火弹之间至少隔这么多刻；主人的仇人、主人在打的东西，这么多刻内算数。 */
    static final int CAST_GAP = 22, MEMORY = 100;

    public enum Kind { FOLLOW, STAY, ENGAGE, COVER, KINDLE, RETREAT, REST, IDLE }

    private final ShiraumeCompanion c;
    private Kind current = Kind.IDLE;
    private final Map<Kind, Double> scores = new EnumMap<>(Kind.class);
    private @Nullable ServerPlayer owner;
    private double dist;
    private @Nullable LivingEntity foe;
    private int threats, stuck, idleLook;
    private long lastCast = -1000;
    private @Nullable Vec3 lastPos;

    CompanionMind(ShiraumeCompanion c) {
        this.c = c;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return !c.isDeadOrDying() && !c.leaving() && c.ownerPlayer() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void stop() {
        c.getNavigation().stop();
        c.setSprinting(false);
        c.mood.curl(false);
    }

    public Kind current() {
        return current;
    }

    /** 正在打（迎战、护主、缠火）：表情认真，不插小动作。 */
    public boolean engaged() {
        return current == Kind.ENGAGE || current == Kind.COVER || current == Kind.KINDLE;
    }

    public Map<Kind, Double> scores() {
        return scores;
    }

    public @Nullable LivingEntity foe() {
        return foe;
    }

    @Override
    public void tick() {
        ServerPlayer p = c.ownerPlayer();
        if (p == null || !(c.level() instanceof ServerLevel level)) return;
        owner = p;
        long now = level.getGameTime();
        dist = c.distanceTo(p);
        if (now % THINK == 0) think(level, p, now);
        if (reflex(level, p)) return;
        act(level, p, now);
    }

    // ---------------------------------------------------------------- 打分

    private void think(ServerLevel level, ServerPlayer p, long now) {
        OwnerWatch w = OwnerWatch.of(p);
        w.sample(now, p.position(), THINK);
        Vec3 here = c.position();
        stuck = lastPos != null && c.getNavigation().isInProgress() && here.distanceTo(lastPos) < .1 ? stuck + THINK : 0;
        lastPos = here;
        foe = pickFoe(level, p, w, now);
        boolean strong = foe != null && (foe.getMaxHealth() >= ShiraumeCompanion.STRONG || threats >= 3);
        boolean wrapped = FoxfireWraps.flame(p) != null || LogosMagicBridge.wrapped(p);
        OwnerWatch.Activity doing = w.activity(now, p.isSleeping());

        scores.put(Kind.FOLLOW, c.staying() ? 0 : .3 + (dist > 6 ? .4 : 0));
        scores.put(Kind.STAY, c.staying() ? .6 : 0);
        scores.put(Kind.ENGAGE, foe == null ? 0 : .7 + (foe instanceof Mob m && m.getTarget() == p ? .1 : 0));
        scores.put(Kind.COVER, foe != null && p.getHealth() < p.getMaxHealth() * .4f && foe.distanceTo(p) < 8 ? .95 : 0);
        scores.put(Kind.KINDLE, foe != null && strong && !wrapped && c.kindleReady(now) && w.fighting(now) && dist < 8 ? .9 : 0);
        scores.put(Kind.RETREAT, foe != null && c.getHealth() < c.getMaxHealth() * .3f ? .85 : 0);
        scores.put(Kind.REST, p.isSleeping() ? 1 : doing == OwnerWatch.Activity.AFK && foe == null ? .5 : 0);
        scores.put(Kind.IDLE, .1);

        Kind best = current;
        double top = -1;
        for (var e : scores.entrySet()) {
            double s = e.getValue() + (e.getKey() == current ? HYSTERESIS : 0);
            if (s > top) {
                top = s;
                best = e.getKey();
            }
        }
        if (best != current) {
            if (current == Kind.REST) c.mood.curl(false);
            c.getNavigation().stop();
            c.setSprinting(false);
            current = best;
        }
    }

    /** 要对付的那一个：先是打主人的，再是主人在打的，再是打她的，最后是冲着主人或她来的、离主人最近的敌对生物。 */
    private @Nullable LivingEntity pickFoe(ServerLevel level, ServerPlayer p, OwnerWatch w, long now) {
        threats = 0;
        LivingEntity best = null;
        double bestScore = -1;
        for (Mob m : level.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(THREAT_RADIUS),
                m -> m.isAlive() && m != c && c.canHarm(m) && (m.getTarget() == p || m.getTarget() == c
                        || m instanceof Enemy && m.distanceTo(p) < 8 && m.hasLineOfSight(p)))) {
            threats++;
            double s = 1 - m.distanceTo(p) / (THREAT_RADIUS * 2) + (m.getTarget() == p ? .5 : 0);
            if (s > bestScore) {
                bestScore = s;
                best = m;
            }
        }
        LivingEntity hurtMe = c.getLastHurtByMob();
        if (hurtMe != null && hurtMe.isAlive() && c.tickCount - c.getLastHurtByMobTimestamp() < MEMORY && c.canHarm(hurtMe)) best = hurtMe;
        LivingEntity ownerTarget = recent(level, w.attacked, w.attackedAt, now);
        if (ownerTarget != null && ownerTarget.distanceTo(p) < 24) best = ownerTarget;
        LivingEntity ownerFoe = recent(level, w.attacker, w.attackerAt, now);
        if (ownerFoe != null && ownerFoe.distanceTo(p) < 24) best = ownerFoe;
        if (best != null && c.staying() && best.distanceTo(c) > THREAT_RADIUS) return null;
        return best;
    }

    private @Nullable LivingEntity recent(ServerLevel level, @Nullable UUID id, long at, long now) {
        if (id == null || now - at > MEMORY) return null;
        Entity e = level.getEntity(id);
        return e instanceof LivingEntity l && l.isAlive() && c.canHarm(l) ? l : null;
    }

    // ---------------------------------------------------------------- 反射

    /** 掉队太远、卡住、掉进岩浆或快掉出世界：闪到主人身边。闪了就返回真，这一刻不再做别的。 */
    private boolean reflex(ServerLevel level, ServerPlayer p) {
        boolean lost = dist > BLINK_AT || dist > 12 && stuck >= 60 && !c.staying();
        boolean danger = c.isInLava() || c.getY() < level.getMinY() + 4;
        if (!lost && !danger) return false;
        blink(level, p);
        return true;
    }

    /** 化作狐火闪到主人身边：两头各一团淡粉狐火和樱花瓣。 */
    void blink(ServerLevel level, ServerPlayer p) {
        Vec3 from = c.position().add(0, .7, 0);
        Vec3 to = Companions.spotNear(level, p, 2, c);
        puff(level, from);
        c.teleportTo(to.x, to.y, to.z);
        c.getNavigation().stop();
        c.resetFallDistance();
        puff(level, to.add(0, .7, 0));
        level.playSound(null, to.x, to.y, to.z, SoundEvents.FOX_TELEPORT, SoundSource.NEUTRAL, .8f, 1.3f);
        stuck = 0;
    }

    private static void puff(ServerLevel level, Vec3 at) {
        level.sendParticles(ParticleTypes.CHERRY_LEAVES, at.x, at.y, at.z, 16, .3, .45, .3, .03);
        level.sendParticles(new DustParticleOptions(FoxfireFlame.SHIRAUME.core, 1.3f), at.x, at.y, at.z, 14, .25, .4, .25, 0);
    }

    // ---------------------------------------------------------------- 做

    private void act(ServerLevel level, ServerPlayer p, long now) {
        switch (current) {
            case FOLLOW -> follow(p, now);
            case STAY -> idle(p, now, false);
            case ENGAGE -> {
                if (foe != null) fight(foe, now, false);
            }
            case COVER -> {
                if (foe != null) cover(level, p, foe, now);
            }
            case KINDLE -> kindle(p, now);
            case RETREAT -> {
                if (foe != null) retreat(p, foe, now);
            }
            case REST -> rest(level, p, now);
            case IDLE -> idle(p, now, true);
        }
    }

    private void follow(ServerPlayer p, long now) {
        if (dist > FAR) {
            double speed = dist > RUN_AT || p.isSprinting() ? 1.5 : 1;
            c.setSprinting(speed > 1.2);
            if (now % THINK == 0 || c.getNavigation().isDone()) c.getNavigation().moveTo(p, speed);
        } else if (dist < NEAR + .3) {
            c.getNavigation().stop();
            c.setSprinting(false);
            look(p, now);
        }
    }

    private void idle(ServerPlayer p, long now, boolean follow) {
        if (follow && dist > FAR) {
            follow(p, now);
            return;
        }
        c.getNavigation().stop();
        c.setSprinting(false);
        look(p, now);
    }

    /** 平时的视线：多半看着主人，偶尔四下看看。 */
    private void look(ServerPlayer p, long now) {
        if (--idleLook <= 0) idleLook = 40 + c.getRandom().nextInt(60);
        if (idleLook > 25) c.getLookControl().setLookAt(p, 20, 20);
        else if (idleLook == 25) {
            Vec3 at = c.getEyePosition().add((c.getRandom().nextDouble() - .5) * 8, (c.getRandom().nextDouble() - .5) * 2,
                    (c.getRandom().nextDouble() - .5) * 8);
            c.getLookControl().setLookAt(at.x, at.y, at.z);
        }
    }

    /** 迎战：保持距离放狐火弹；太近就往后退，看不见或太远就靠过去。{@code hold} 时站在原地（护主）。 */
    private void fight(LivingEntity target, long now, boolean hold) {
        double d = c.distanceTo(target);
        boolean sees = c.hasLineOfSight(target);
        if (!hold && !c.casting()) {
            if (d < KEEP_MIN) {
                Vec3 away = c.position().subtract(target.position()).multiply(1, 0, 1).normalize().scale(4);
                Vec3 to = c.position().add(away);
                if (owner != null && to.distanceTo(owner.position()) > 10) to = owner.position();
                c.getNavigation().moveTo(to.x, to.y, to.z, 1.3);
                c.setSprinting(true);
            } else if (d > KEEP_MAX || !sees) {
                if (now % THINK == 0 || c.getNavigation().isDone()) c.getNavigation().moveTo(target, 1.2);
                c.setSprinting(false);
            } else {
                c.getNavigation().stop();
                c.setSprinting(false);
            }
        }
        c.getLookControl().setLookAt(target, 40, 40);
        if (sees && d < SHOOT_RANGE && now - lastCast >= CAST_GAP && !c.casting() && c.startCast(target, false)) lastCast = now;
    }

    /** 护主：站到主人和威胁之间，太远就闪过去，然后就地开火。 */
    private void cover(ServerLevel level, ServerPlayer p, LivingEntity threat, long now) {
        Vec3 toward = threat.position().subtract(p.position()).multiply(1, 0, 1);
        Vec3 spot = p.position().add(toward.lengthSqr() < 1e-4 ? Vec3.ZERO : toward.normalize().scale(1.6));
        double d = c.position().distanceTo(spot);
        if (d > 6) blink(level, p);
        else if (d > .8) c.getNavigation().moveTo(spot.x, spot.y, spot.z, 1.5);
        fight(threat, now, d <= .8);
    }

    /** 缠火：走到主人身边，给主人的手缠上淡粉狐火。 */
    private void kindle(ServerPlayer p, long now) {
        if (dist > 4.5) {
            if (now % THINK == 0 || c.getNavigation().isDone()) c.getNavigation().moveTo(p, 1.4);
            return;
        }
        c.getNavigation().stop();
        c.getLookControl().setLookAt(p, 40, 40);
        if (!c.casting()) c.startCast(p, true);
    }

    private void retreat(ServerPlayer p, LivingEntity threat, long now) {
        Vec3 back = p.position().subtract(threat.position()).multiply(1, 0, 1);
        Vec3 spot = p.position().add(back.lengthSqr() < 1e-4 ? Vec3.ZERO : back.normalize().scale(2.5));
        if (now % THINK == 0) c.getNavigation().moveTo(spot.x, spot.y, spot.z, 1.4);
        c.setSprinting(true);
        c.getLookControl().setLookAt(threat, 30, 30);
    }

    /** 休息：主人睡下，她走到床边蜷起来睡；主人挂机，她挨着主人打盹。 */
    private void rest(ServerLevel level, ServerPlayer p, long now) {
        if (c.mood.curled()) {
            c.getNavigation().stop();
            return;
        }
        double want = p.isSleeping() ? 1.6 : 2.2;
        if (dist > want + .6) {
            if (now % THINK == 0 || c.getNavigation().isDone()) c.getNavigation().moveTo(p, 1);
            return;
        }
        c.getNavigation().stop();
        c.setSprinting(false);
        c.mood.curl(true);
    }

    /** 给命令 {@code info} 看的一行。 */
    String describe() {
        StringBuilder sb = new StringBuilder(current.name()).append(" foe=").append(foe == null ? "-" : foe.getName().getString());
        for (var e : scores.entrySet()) sb.append(' ').append(e.getKey().name().toLowerCase()).append('=').append(String.format("%.2f", e.getValue()));
        return sb.toString();
    }
}
