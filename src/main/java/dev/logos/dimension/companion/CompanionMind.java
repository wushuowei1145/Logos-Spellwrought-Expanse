package dev.logos.dimension.companion;

import dev.logos.dimension.npc.LogosMagicBridge;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 同伴的心智：效用 AI（docs/COMPANIONS.md 第 2.1 节）。每半秒看一眼处境，给每种行为打分（0 是没意义，1 左右是迫切），
 * 做分最高的那个；正在做的那个多 {@link #HYSTERESIS}，免得两件事之间来回抖。
 * 保命的反射（掉队太远、卡住、掉进岩浆或虚空就闪到主人身边）每刻都看，不受打分影响。
 *
 * <p>这里是每位同伴都一样的那些行为：跟随、留下、说话、休息、闲着、差事、拾荒、吃包袱里的、护主的站位，以及挑要对付的那一个。
 * 怎么打（{@link #fight}、{@link #retreat}）和她自己的行为（{@link #scoreOwn}、{@link #actOwn}）由每位同伴的心智写：
 * 白梅的是 {@link ShiraumeMind}，精灵公主的是 {@link PrincessMind}。
 *
 * <p>和 Logos 魔力生命体的 {@code LifeformMind} 是同一种做法。主人的处境来自 {@link OwnerWatch}；
 * Logos 的命令（{@code sic} 的目标、{@code gather} 指的地方）从 {@link LogosCompanionBridge} 读。
 */
public abstract class CompanionMind extends Goal {
    static final int THINK = 10;
    static final double HYSTERESIS = .1;
    /** 跟随：近到这里就停，远过这里就走，再远就跑，远过 {@link #BLINK_AT} 就闪过去。 */
    static final double NEAR = 2.2, FAR = 4, RUN_AT = 9, BLINK_AT = 24;
    /** 看哪些东西算威胁；不参与攻击时，敌人离她或主人这么近就躲到主人背后。 */
    static final double THREAT_RADIUS = 12, PASSIVE_SHY = 8;
    /** 主人的仇人、主人在打的东西，这么多刻内算数；朝主人放冷箭的射手这么多刻内优先打。 */
    static final int MEMORY = 100, SHOOTER_MEMORY = 200;
    /**
     * 掉队了闪回主人身边：两次之间隔 {@link #BLINK_GAP} 刻；闪完还离主人超过 {@link #STUCK_DIST} 格（坐在矿车、船、别的模组的座位上，
     * 位置被拽回去）算一次没闪成，连着 {@link #STUCK_FAILS} 次就回信物里歇着，免得一直困在那里。
     */
    static final int BLINK_GAP = 20, STUCK_FAILS = 5;
    static final double STUCK_DIST = 6;
    /** 残血被贴着时闪开：隔多久才能再闪。 */
    static final int ESCAPE_GAP = 160;
    static final double LOOT_RADIUS = 8;

    /**
     * 行为。前面这些每位都有；白梅的：KINDLE 缠火、EXECUTE 处决、SOAK 泡温泉、HIDE 躲狗（FORAGE 她还会去摘浆果）；
     * 精灵公主的：MEND 治疗、护盾，BLESS 给主人缠上生命之力，ENSNARE 用荆棘困住强敌。
     */
    public enum Kind { FOLLOW, STAY, ENGAGE, COVER, KINDLE, RETREAT, REST, IDLE, EXECUTE, FORAGE, SCAVENGE, SOAK, HIDE, ERRAND, TALK,
        MEND, BLESS, ENSNARE, WARD, HISS, SUPPORT, UTILITY }

    protected final CompanionEntity c;
    protected Kind current = Kind.IDLE;
    protected final Map<Kind, Double> scores = new EnumMap<>(Kind.class);
    protected @Nullable ServerPlayer owner;
    protected double dist;
    protected @Nullable LivingEntity foe, shooter, ordered;
    protected CompanionTactic tactic = CompanionTactic.ADAPTIVE;
    /** 优先近战时，要打的那个走得过去（不是飞在高处、寻路找得到）。 */
    protected boolean reachable = true;
    private @Nullable ItemEntity loot;
    protected int threats, stuck;
    private int idleLook;
    private long blinkCheck = -1, nextBlink;
    private int blinkFails;
    protected long shooterAt = -10_000, nextEscape;
    private @Nullable Vec3 lastPos;
    /** 测试用：残血被贴着时闪开过几次；困住了回信物几次。 */
    static int escapes, stuckRecalls;

    /** 这一次打分时的处境（给每位同伴自己的打分用）。 */
    protected record Situation(boolean calm, boolean hungry, boolean pouchFood, @Nullable Vec3 order, OwnerWatch.Activity doing, boolean holdBack,
                               boolean strong) {}

    protected CompanionMind(CompanionEntity c) {
        this.c = c;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    public static int escapes() {
        return escapes;
    }

    public static int stuckRecalls() {
        return stuckRecalls;
    }

    @Override
    public boolean canUse() {
        return c.isCompanion() && !c.isDeadOrDying() && !c.leaving() && c.ownerPlayer() != null;
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

    /** 正在打（迎战、护主，以及她自己的打法）：表情认真，不插小动作。 */
    public boolean engaged() {
        return current == Kind.ENGAGE || current == Kind.COVER || engagedOwn(current);
    }

    /** 她自己的行为里哪些算在打。 */
    protected boolean engagedOwn(Kind k) {
        return false;
    }

    public Map<Kind, Double> scores() {
        return scores;
    }

    public @Nullable LivingEntity foe() {
        return foe;
    }

    public CompanionTactic tactic() {
        return tactic;
    }

    /** 这一仗她不出手：不参与攻击，而且不是主人用 {@code sic} 点名的目标。 */
    protected boolean holdsBack() {
        return tactic == CompanionTactic.PASSIVE && (foe == null || foe != ordered);
    }

    @Override
    public void tick() {
        ServerPlayer p = c.ownerPlayer();
        if (p == null || !(c.level() instanceof ServerLevel level)) return;
        owner = p;
        long now = level.getGameTime();
        dist = c.distanceTo(p);
        if (now % THINK == 0) think(level, p, now);
        if (stuckInSeat(level, p, now)) return;
        if (reflex(level, p, now)) return;
        // 入场动画播完之前先站着（保命的反射照常）
        if (c.mood.summoning()) {
            c.getNavigation().stop();
            return;
        }
        if (busy(level, p, now)) return;
        act(level, p, now);
    }

    /** 她正被一个动作占着（精灵公主撤退时），这一刻什么都不做；返回真时不再往下。 */
    protected boolean busy(ServerLevel level, ServerPlayer p, long now) {
        return false;
    }

    // ---------------------------------------------------------------- 打分

    private void think(ServerLevel level, ServerPlayer p, long now) {
        OwnerWatch w = OwnerWatch.of(p);
        w.sample(now, p.position(), THINK);
        Vec3 here = c.position();
        stuck = lastPos != null && c.getNavigation().isInProgress() && here.distanceTo(lastPos) < .1 ? stuck + THINK : 0;
        lastPos = here;
        foe = pickFoe(level, p, w, now);
        beforeScore(level, p, now);
        boolean strong = foe != null && (foe.getMaxHealth() >= CompanionEntity.STRONG || threats >= 3);
        OwnerWatch.Activity doing = w.activity(now, p.isSleeping());
        Vec3 order = LogosCompanionBridge.orderPoint(c);
        boolean calm = foe == null && !keepsFighting();
        var bond = c.bond(p);
        tactic = bond.tacticOf();
        reachable = foe == null || tactic != CompanionTactic.MELEE || reachable(foe);
        boolean holdBack = holdsBack();
        // 说着话的时候来了敌人：话先放一放
        if (foe != null && c.talking()) CompanionTalk.interrupt(c);
        boolean talking = c.talking() && foe == null;
        boolean hungry = bond.satiety() < CompanionBond.HUNGRY;
        boolean pouchFood = hungry && c.pouch(p).foodSlot(c.def().id()) >= 0;
        loot = calm && !c.staying() && order == null ? findLoot(level, p, w, now) : null;

        scores.put(Kind.FOLLOW, c.staying() || order != null ? 0 : .3 + (dist > 6 ? .4 : 0));
        scores.put(Kind.STAY, c.staying() ? .6 : 0);
        scores.put(Kind.ENGAGE, foe == null || holdBack ? 0 : .7 + (foe instanceof Mob m && m.getTarget() == p ? .1 : 0));
        scores.put(Kind.COVER, foe != null && !holdBack && p.getHealth() < p.getMaxHealth() * .4f && foe.distanceTo(p) < 8 ? .95 : 0);
        // 不参与攻击：敌人离她或主人近了，就躲到主人背后
        scores.put(Kind.RETREAT, foe != null && c.getHealth() < c.getMaxHealth() * .3f ? .85
                : holdBack && foe != null && (foe.distanceTo(c) < PASSIVE_SHY || foe.distanceTo(p) < PASSIVE_SHY) ? .75 : 0);
        scores.put(Kind.REST, p.isSleeping() ? 1 : doing == OwnerWatch.Activity.AFK && calm ? .5 : 0);
        scores.put(Kind.IDLE, .1);
        scores.put(Kind.FORAGE, !calm || c.staying() ? 0 : pouchFood ? .6 : 0);
        scores.put(Kind.SCAVENGE, loot != null ? .36 : 0);
        scores.put(Kind.ERRAND, order == null ? 0 : LogosCompanionBridge.onErrand(c) ? .8 : .55);
        // 主人在和她说话（对话界面开着）：站住、看着主人
        scores.put(Kind.TALK, talking && !talkBlocked() ? .92 : 0);
        scoreOwn(level, p, w, now, new Situation(calm, hungry, pouchFood, order, doing, holdBack, strong));

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
            switched(current, best, now);
            c.getNavigation().stop();
            c.setSprinting(false);
            current = best;
        }
        afterThink(level, p, now);
    }

    /** 打分前她自己要先看的（白梅：找处决目标、浆果、温泉、狗）。 */
    protected void beforeScore(ServerLevel level, ServerPlayer p, long now) {}

    /** 没敌人了也还在打（白梅：正在处决）。 */
    protected boolean keepsFighting() {
        return false;
    }

    /** 主人在说话，但她这时不站起来说（白梅：泡着温泉就泡着说）。 */
    protected boolean talkBlocked() {
        return false;
    }

    /** 她自己的行为打分；也可以改掉上面通用的分（白梅的觅食还算浆果）。 */
    protected abstract void scoreOwn(ServerLevel level, ServerPlayer p, OwnerWatch w, long now, Situation s);

    /** 从 {@code from} 换成 {@code to} 时要收拾的。 */
    protected void switched(Kind from, Kind to, long now) {}

    /** 打完分、换完行为以后（白梅：收刀、换装束）。 */
    protected void afterThink(ServerLevel level, ServerPlayer p, long now) {}

    /**
     * 要对付的那一个：主人用 {@code sic} 指的最优先；再是刚朝主人放冷箭的射手；然后是打主人的、主人在打的、打她的；
     * 最后是冲着主人或她来的、离主人最近的敌对生物。
     */
    protected @Nullable LivingEntity pickFoe(ServerLevel level, ServerPlayer p, OwnerWatch w, long now) {
        threats = 0;
        LivingEntity best = null;
        double bestScore = -1;
        for (Mob m : level.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(THREAT_RADIUS),
                m -> m.isAlive() && m != c && c.canHarm(m) && (m.getTarget() == p || m.getTarget() == c
                        || m instanceof Enemy && m.distanceTo(p) < 8 && m.hasLineOfSight(p)))) {
            threats++;
            // 离主人近的、冲着主人的先打；伤得重的也先打（先收拾掉一个，挨打的就少一路）；正在打的那个多一点分，
            // 两只轮流打她时不来回换
            double s = 1 - m.distanceTo(p) / (THREAT_RADIUS * 2) + (m.getTarget() == p ? .5 : 0) + (1 - m.getHealth() / m.getMaxHealth()) * .6
                    + (m == foe ? .3 : 0);
            if (s > bestScore) {
                bestScore = s;
                best = m;
            }
        }
        // 打她的（不在上面那些里时，例如没把她当目标的）
        LivingEntity hurtMe = c.getLastHurtByMob();
        if (best == null && hurtMe != null && hurtMe.isAlive() && c.tickCount - c.getLastHurtByMobTimestamp() < MEMORY && c.canHarm(hurtMe)) best = hurtMe;
        LivingEntity ownerTarget = recent(level, w.attacked, w.attackedAt, now);
        if (ownerTarget != null && ownerTarget.distanceTo(p) < 24) best = ownerTarget;
        LivingEntity ownerFoe = recent(level, w.attacker, w.attackerAt, now);
        if (ownerFoe != null && ownerFoe.distanceTo(p) < 24) best = ownerFoe;
        if (shooter != null && shooter.isAlive() && now - shooterAt < SHOOTER_MEMORY && c.canHarm(shooter) && shooter.distanceTo(p) < 32) best = shooter;
        LivingEntity ordered = LogosCompanionBridge.ordered(c);
        this.ordered = ordered != null && ordered.distanceTo(p) < 32 ? ordered : null;
        if (this.ordered != null) best = ordered;
        if (best != null && c.staying() && best.distanceTo(c) > THREAT_RADIUS) return null;
        return best;
    }

    private @Nullable LivingEntity recent(ServerLevel level, @Nullable UUID id, long at, long now) {
        if (id == null || now - at > MEMORY) return null;
        Entity e = level.getEntity(id);
        return e instanceof LivingEntity l && l.isAlive() && c.canHarm(l) ? l : null;
    }

    /** 记下朝主人放冷箭的射手：接下来一阵优先打它。 */
    protected void markShooter(@Nullable Entity shotBy, long now) {
        if (shotBy instanceof LivingEntity s && c.canHarm(s)) {
            shooter = s;
            shooterAt = now;
        }
    }

    /** 不是冲着主人来的弹射物：主人、她自己、主人的使魔、同伴放的，或者已经落地、停下。 */
    protected boolean friendlyShot(net.minecraft.world.entity.projectile.Projectile proj) {
        if (proj.isRemoved() || proj.onGround() || proj.getDeltaMovement().lengthSqr() < .04) return true;
        Entity shotBy = proj.getOwner();
        if (shotBy == null || shotBy == c || owner != null && (shotBy == owner || owner.getUUID().equals(LogosMagicBridge.masterOf(shotBy)))) return true;
        return shotBy instanceof CompanionEntity;
    }

    // ---------------------------------------------------------------- 反射

    /** 掉队太远、卡住、掉进岩浆或快掉出世界：闪到主人身边。她自己的反射（白梅：截箭）先看。做了就返回真，这一刻不再做别的。 */
    private boolean reflex(ServerLevel level, ServerPlayer p, long now) {
        if (reflexOwn(level, p, now)) return true;
        boolean far = c.staying() || current == Kind.ERRAND;
        boolean lost = !far && (dist > BLINK_AT || dist > 12 && stuck >= 60) && now >= nextBlink;
        boolean danger = c.isInLava() || c.getY() < level.getMinY() + 4;
        if (!lost && !danger) return false;
        blink(level, p);
        nextBlink = now + BLINK_GAP;
        return true;
    }

    /** 她自己每刻都看的反射（白梅：截下射向主人的弹射物）。 */
    protected boolean reflexOwn(ServerLevel level, ServerPlayer p, long now) {
        return false;
    }

    /**
     * 闪回主人身边以后看一眼：还离主人很远（坐在矿车、船、别的模组的座位上，闪过去又被拽回座位）就记一次没闪成；连着
     * {@link #STUCK_FAILS} 次，她说一声、回信物里歇着（对付一切把她困住的东西，不用一个个认）。返回真时她已经在回去了。
     */
    private boolean stuckInSeat(ServerLevel level, ServerPlayer p, long now) {
        if (blinkCheck < 0 || now < blinkCheck) return false;
        blinkCheck = -1;
        blinkFails = c.isPassenger() || c.distanceTo(p) > STUCK_DIST ? blinkFails + 1 : 0;
        if (blinkFails < STUCK_FAILS) return false;
        blinkFails = 0;
        stuckRecalls++;
        stuckSaid(p);
        Companions.dismiss(p, c.def());
        return true;
    }

    /** 困住了、要回去时她说的。 */
    protected void stuckSaid(ServerPlayer p) {
        CompanionLines.say(c, p, "stuck", 60);
    }

    /** 闪到主人身边：两头各一团她的颜色（{@link CompanionEntity#puff}）。 */
    void blink(ServerLevel level, ServerPlayer p) {
        Vec3 from = c.position().add(0, .7, 0);
        Vec3 to = Companions.spotNear(level, p, 2, c);
        c.puff(level, from);
        c.teleportTo(to.x, to.y, to.z);
        c.getNavigation().stop();
        c.resetFallDistance();
        c.puff(level, to.add(0, .7, 0));
        c.blinkSound(level, to);
        stuck = 0;
        // 两刻后看闪成了没有（连着闪的那几刻只看第一次的）
        if (blinkCheck < 0) blinkCheck = level.getGameTime() + 2;
    }

    /** 残血被贴着时闪开：闪到主人身后（主人挡在她和敌人中间）；那里站不住就闪到主人身边。 */
    protected void escape(ServerLevel level, ServerPlayer p, LivingEntity threat) {
        Vec3 away = p.position().subtract(threat.position()).multiply(1, 0, 1);
        if (away.lengthSqr() < 1e-4) away = p.position().subtract(c.position()).multiply(1, 0, 1);
        Vec3 spot = away.lengthSqr() < 1e-4 ? null : p.position().add(away.normalize().scale(2.5));
        // 主人的脚底可能差一点点陷进地面（浮点误差）：抬一点再取那一格，不然取到的是地面
        BlockPos feet = spot == null ? null : BlockPos.containing(spot.x, spot.y + .2, spot.z);
        if (feet == null || !level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), net.minecraft.core.Direction.UP)
                || !level.noCollision(c, c.getDimensions(c.getPose()).makeBoundingBox(Vec3.atBottomCenterOf(feet)))) {
            blink(level, p);
            return;
        }
        c.puff(level, c.position().add(0, .7, 0));
        c.teleportTo(spot.x, feet.getY(), spot.z);
        c.getNavigation().stop();
        c.resetFallDistance();
        c.puff(level, spot.add(0, .7, 0));
        c.blinkSound(level, spot);
        if (blinkCheck < 0) blinkCheck = level.getGameTime() + 2;
    }

    // ---------------------------------------------------------------- 做

    private void act(ServerLevel level, ServerPlayer p, long now) {
        switch (current) {
            case FOLLOW -> follow(p, now);
            case STAY -> idle(p, now, false);
            case ENGAGE -> {
                if (foe != null) fight(level, foe, now, false);
            }
            case COVER -> {
                if (foe != null) cover(level, p, foe, now);
            }
            case RETREAT -> {
                if (foe != null) retreat(p, foe, now);
            }
            case REST -> rest(level, p, now);
            case IDLE -> idle(p, now, true);
            case FORAGE -> forage(level, p, now);
            case SCAVENGE -> scavenge(level, p, now);
            case ERRAND -> errand(p, now);
            case TALK -> talk(p);
            default -> actOwn(current, level, p, now);
        }
    }

    /** 她自己的行为。 */
    protected abstract void actOwn(Kind kind, ServerLevel level, ServerPlayer p, long now);

    /** 迎战 {@code target}；{@code hold} 时站在原地（护主）。 */
    protected abstract void fight(ServerLevel level, LivingEntity target, long now, boolean hold);

    /** 后撤：血少了，或不参与攻击时敌人近了。 */
    protected abstract void retreat(ServerPlayer p, LivingEntity threat, long now);

    protected void follow(ServerPlayer p, long now) {
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

    protected void idle(ServerPlayer p, long now, boolean follow) {
        if (follow && dist > FAR) {
            follow(p, now);
            return;
        }
        c.getNavigation().stop();
        c.setSprinting(false);
        look(p, now);
    }

    /** 平时的视线：多半看着主人，偶尔四下看看。 */
    protected void look(ServerPlayer p, long now) {
        if (--idleLook <= 0) idleLook = 40 + c.getRandom().nextInt(60);
        if (idleLook > 25) c.getLookControl().setLookAt(p, 20, 20);
        else if (idleLook == 25) {
            Vec3 at = c.getEyePosition().add((c.getRandom().nextDouble() - .5) * 8, (c.getRandom().nextDouble() - .5) * 2,
                    (c.getRandom().nextDouble() - .5) * 8);
            c.getLookControl().setLookAt(at.x, at.y, at.z);
        }
    }

    /** 转过去正对着 {@code target}（出手前）。 */
    protected void face(LivingEntity target) {
        c.lookAt(target, 360, 30);
        c.yBodyRot = c.getYRot();
        c.setYHeadRot(c.getYRot());
    }

    /** 护主：站到主人和威胁之间，太远就闪过去，然后就地迎战。 */
    private void cover(ServerLevel level, ServerPlayer p, LivingEntity threat, long now) {
        Vec3 toward = threat.position().subtract(p.position()).multiply(1, 0, 1);
        Vec3 spot = p.position().add(toward.lengthSqr() < 1e-4 ? Vec3.ZERO : toward.normalize().scale(1.6));
        double d = c.position().distanceTo(spot);
        if (d > 6) blink(level, p);
        else if (d > .8 && canMoveToCover()) c.getNavigation().moveTo(spot.x, spot.y, spot.z, 1.5);
        fight(level, threat, now, d <= .8);
    }

    /** 护主时这一刻能不能走到位（白梅：刀招收势还没播完时不走）。 */
    protected boolean canMoveToCover() {
        return true;
    }

    /** 优先近战时够不够得着 {@code t}：不是飞在头顶上，而且寻路找得到过去的路。 */
    protected boolean reachable(LivingEntity t) {
        if (!t.onGround() && !t.isInWater() && t.getY() - c.getY() > 2.5) return false;
        if (c.distanceTo(t) < 3) return true;
        Path path = c.getNavigation().createPath(t, 1);
        return path != null && path.canReach();
    }

    /** 说话：站住，转过来看着主人（对话由 {@link CompanionTalk} 管）。 */
    private void talk(ServerPlayer p) {
        c.getNavigation().stop();
        c.setSprinting(false);
        if (c.mood.curled()) c.mood.curl(false);
        c.getLookControl().setLookAt(p, 30, 30);
        if (canTurnToTalk()) {
            float yaw = (float) (Math.toDegrees(Math.atan2(p.getZ() - c.getZ(), p.getX() - c.getX())) - 90);
            c.setYRot(Mth.approachDegrees(c.getYRot(), yaw, 12));
            c.yBodyRot = c.getYRot();
        }
    }

    /** 说话时整个身子转过来（白梅拿着刀、正在施法时只转头）。 */
    protected boolean canTurnToTalk() {
        return true;
    }

    /** 休息：主人睡下，她走到床边睡；主人挂机，她挨着主人打盹。 */
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

    /** 饿了先吃包袱里的；她自己还会别的（白梅：去摘浆果，{@link #forageMore}）。 */
    private void forage(ServerLevel level, ServerPlayer p, long now) {
        if (c.eating() || foraging()) {
            c.getNavigation().stop();
            return;
        }
        var pouch = c.pouch(p);
        if (c.bond(p).satiety() < CompanionBond.HUNGRY) {
            int slot = pouch.foodSlot(c.def().id());
            if (slot >= 0) {
                ItemStack food = pouch.removeItem(slot, 1);
                c.startEating(food, false);
                return;
            }
        }
        forageMore(level, p, now);
    }

    /** 正忙着找吃的（白梅：在摘浆果）。 */
    protected boolean foraging() {
        return false;
    }

    /** 包袱里没吃的时她自己找（白梅：摘浆果）。 */
    protected void forageMore(ServerLevel level, ServerPlayer p, long now) {}

    /**
     * 拾荒（白梅第 8.6 节）：附近刚掉下来、没人扔过的东西（敌人掉的）放进包袱，只捡主人身边的（不越捡越远）。
     * 主人刚死在附近时不碰（那是主人的遗物），主人正站在旁边的也不抢。
     */
    private @Nullable ItemEntity findLoot(ServerLevel level, ServerPlayer p, OwnerWatch w, long now) {
        if (now - w.lastDeath < 6000 && w.deathAt != null && w.deathAt.distanceTo(c.position()) < 24) return null;
        ItemEntity best = null;
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, c.getBoundingBox().inflate(LOOT_RADIUS),
                e -> e.isAlive() && e.getOwner() == null && e.getAge() < 1200 && e.distanceTo(p) > 1.5 && e.distanceTo(p) < LOOT_RADIUS + 2
                        && !e.getItem().isEmpty())) {
            if (best == null || c.distanceToSqr(e) < c.distanceToSqr(best)) best = e;
        }
        return best;
    }

    private void scavenge(ServerLevel level, ServerPlayer p, long now) {
        ItemEntity e = loot;
        if (e == null || !e.isAlive()) return;
        if (c.distanceTo(e) > 1.3) {
            if (now % 5 == 0 || c.getNavigation().isDone()) c.getNavigation().moveTo(e, 1.2);
            return;
        }
        c.getNavigation().stop();
        ItemStack left = c.pouch(p).put(e.getItem().copy());
        int took = e.getItem().getCount() - left.getCount();
        if (took <= 0) return;
        c.take(e, took);
        if (left.isEmpty()) e.discard();
        else e.setItem(left);
        level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, .25f, 1.4f);
        loot = null;
    }

    /** Logos 的 {@code gather}：去那里；到了（不在主人身边）就守在那里。 */
    private void errand(ServerPlayer p, long now) {
        Vec3 at = LogosCompanionBridge.orderPoint(c);
        if (at == null) return;
        if (c.position().distanceTo(at) > (LogosCompanionBridge.onErrand(c) ? 1.5 : 3)) {
            if (now % THINK == 0 || c.getNavigation().isDone()) c.getNavigation().moveTo(at.x, at.y, at.z, 1.2);
            return;
        }
        c.getNavigation().stop();
        look(p, now);
    }

    /** 给命令 {@code info} 看的一行。 */
    public String describe() {
        StringBuilder sb = new StringBuilder(current.name()).append(" tactic=").append(tactic.id).append(" foe=").append(foe == null ? "-" : foe.getName().getString());
        describeOwn(sb);
        for (var e : scores.entrySet()) {
            if (e.getValue() > 0) sb.append(' ').append(e.getKey().name().toLowerCase()).append('=').append(String.format("%.2f", e.getValue()));
        }
        return sb.toString();
    }

    /** {@link #describe} 里她自己的那一段（白梅：手上的刀招）。 */
    protected void describeOwn(StringBuilder sb) {}
}
