package dev.logos.dimension.companion;

import dev.logos.dimension.cloudsea.HotSpringStoneBlock;
import dev.logos.dimension.execution.ExecutionShots;
import dev.logos.dimension.execution.InteractiveExecutions;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.dimension.foxfire.FoxfireRules;
import dev.logos.dimension.foxfire.FoxfireWraps;
import dev.logos.dimension.item.RyukaItem;
import dev.logos.dimension.npc.LogosMagicBridge;
import dev.logos.dimension.ryuka.FoxMarks;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * 白梅的心智：效用 AI（docs/SHIRAUME_COMPANION.md 第 3、7.3、8 节）。每半秒看一眼处境，给每种行为打分
 * （0 是没意义，1 左右是迫切），做分最高的那个；正在做的那个多 {@link #HYSTERESIS}，免得两件事之间来回抖。
 * 保命和护主的反射（掉队太远、卡住、掉进岩浆或虚空就闪到主人身边；有弹射物要打中主人就闪过去一刀弹开）每刻都看，不受打分影响。
 *
 * <p>和 Logos 魔力生命体的 {@code LifeformMind} 是同一种做法。主人的处境来自 {@link OwnerWatch}；
 * Logos 的命令（{@code sic} 的目标、{@code gather} 指的地方）从 {@link LogosCompanionBridge} 读。
 */
public final class CompanionMind extends Goal {
    static final int THINK = 10;
    static final double HYSTERESIS = .1;
    /** 跟随：近到这里就停，远过这里就走，再远就跑，远过 {@link #BLINK_AT} 就闪过去。 */
    static final double NEAR = 2.2, FAR = 4, RUN_AT = 9, BLINK_AT = 24;
    /** 迎战：和敌人保持的距离、放狐火弹的射程、看哪些东西算威胁。 */
    static final double KEEP_MIN = 4.5, KEEP_MAX = 10, SHOOT_RANGE = 14, THREAT_RADIUS = 12;
    /** 流火：敌人贴近到这里、她血量在这一成以上就凝刀；血量掉到这里以下、或敌人退到这么远就散刀。 */
    static final double MELEE_IN = 3.5, MELEE_OUT = 6;
    static final float MELEE_HEALTH = .5f, SHEATHE_HEALTH = .35f;
    /**
     * 行动策略（{@link CompanionTactic}）：优先远程时敌人贴到这么近才凝刀、拉开到这么远就收刀；优先近战时这么远以内就凝刀走过去
     * （远过 {@link #MELEE_CHASE} 才收刀），够不着的才放狐火。不参与攻击时，敌人离她或主人这么近就躲到主人背后。
     */
    static final double RANGED_DRAW = 1.8, RANGED_SHEATHE = 2.8, MELEE_REACH = SHOOT_RANGE, MELEE_CHASE = SHOOT_RANGE + 4, PASSIVE_SHY = 8;
    /** 突刺只用来拉近：两次之间至少隔这么多刻；冲到跟前接着再砍这么多刀（另加 0–1）不停手。一串砍完在架势里停这么多刻（另加 0–同样多）。 */
    public static final int THRUST_GAP = 100, COMBO_AFTER_THRUST = 2, STRING_REST = 8;
    /** 两次放狐火弹之间至少隔这么多刻；主人的仇人、主人在打的东西，这么多刻内算数。 */
    static final int CAST_GAP = 22, MEMORY = 100;
    /** 截弹射物：截完多久才能再截；射手在这么多刻里优先打。 */
    static final int INTERCEPT_GAP = 20, SHOOTER_MEMORY = 200;
    /** 摘浆果：找多远；不饿时两次之间隔多久。泡温泉：找多远的温泉格。怕狗：多近算有狗。 */
    static final int BUSH_RADIUS = 8, FORAGE_GAP = 600, SPRING_RADIUS = 4, SPRING_GRACE = 40;
    static final double DOG_RADIUS = 7, LOOT_RADIUS = 8;
    /**
     * 泡温泉：水面到池底比这还深的格不泡（在池边岸上陪着）；水面停在她肩头时，她脚底在水面下多深
     * （同模型 soak 动作的水线，tools/life_clips.py 的 WATER = 28.4 模型像素，渲染缩放 0.5）。
     */
    public static final double SPRING_DEEP = 2.2, SOAK_WATERLINE = 28.4 / 32;

    public enum Kind { FOLLOW, STAY, ENGAGE, COVER, KINDLE, RETREAT, REST, IDLE, EXECUTE, FORAGE, SCAVENGE, SOAK, HIDE, ERRAND, TALK }

    private final ShiraumeCompanion c;
    private Kind current = Kind.IDLE;
    private final Map<Kind, Double> scores = new EnumMap<>(Kind.class);
    private @Nullable ServerPlayer owner;
    private double dist;
    private @Nullable LivingEntity foe, execTarget, shooter, dog, ordered;
    private CompanionTactic tactic = CompanionTactic.ADAPTIVE;
    /** 优先近战时，要打的那个走得过去（不是飞在高处、寻路找得到）。 */
    private boolean reachable = true;
    private @Nullable BlockPos bush, spring, soakAt, edge;
    private double soakY;
    private @Nullable ItemEntity loot;
    private int threats, stuck, idleLook, combo;
    /** 优先远程：这一串砍完已经往后跳过一次（一串只跳一次，跳完照常在架势里停、再起下一串）。 */
    private boolean backstepped;
    private String lastMove = "";
    private long lastCast = -1000, nextThrust, restUntil = -1, countered, nextIntercept, shooterAt = -10_000, nextForage, soakSince = -1, springSeen = -10_000;
    /** 最近一次在打（迎战、护主、缠火、处决、后撤、拿着刀）是哪一刻：打完歇一会儿才换回她自己选的装束。 */
    private long lastFight = -10_000;
    private @Nullable Vec3 lastPos;
    /** 测试用：截下过几发；泡着泡着起身过几次。 */
    static int intercepts, soakEnds;

    CompanionMind(ShiraumeCompanion c) {
        this.c = c;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
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
        endSoak();
    }

    public Kind current() {
        return current;
    }

    /** 正在打（迎战、护主、缠火、处决）：表情认真，不插小动作。 */
    public boolean engaged() {
        return current == Kind.ENGAGE || current == Kind.COVER || current == Kind.KINDLE || current == Kind.EXECUTE;
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
    private boolean holdsBack() {
        return tactic == CompanionTactic.PASSIVE && (foe == null || foe != ordered);
    }

    public static int intercepts() {
        return intercepts;
    }

    public static int soakEnds() {
        return soakEnds;
    }

    @Override
    public void tick() {
        ServerPlayer p = c.ownerPlayer();
        if (p == null || !(c.level() instanceof ServerLevel level)) return;
        owner = p;
        long now = level.getGameTime();
        dist = c.distanceTo(p);
        if (now % THINK == 0) think(level, p, now);
        if (reflex(level, p, now)) return;
        // 入场动画播完之前先站着行礼（保命和护主的反射照常）
        if (c.mood.summoning()) {
            c.getNavigation().stop();
            return;
        }
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
        execTarget = c.blade().executing() ? execTarget : pickExecution(level, p);
        boolean strong = foe != null && (foe.getMaxHealth() >= ShiraumeCompanion.STRONG || threats >= 3);
        boolean wrapped = FoxfireWraps.flame(p) != null || LogosMagicBridge.wrapped(p);
        OwnerWatch.Activity doing = w.activity(now, p.isSleeping());
        Vec3 order = LogosCompanionBridge.orderPoint(c);
        boolean calm = foe == null && !c.blade().executing();
        var bond = p.getData(Companions.BOND);
        tactic = bond.tacticOf();
        reachable = foe == null || tactic != CompanionTactic.MELEE || reachable(foe);
        boolean holdBack = holdsBack();
        // 说着话的时候来了敌人：话先放一放
        if (foe != null && c.talking()) CompanionTalk.interrupt(c);
        boolean talking = c.talking() && foe == null;
        boolean hungry = bond.satiety() < CompanionBond.HUNGRY;
        boolean pouchFood = hungry && Companions.pouch(p).foodSlot() >= 0;
        if (now % 40 == 0 || bush != null && !ripe(level, bush)) bush = calm && !c.staying() ? findBush(level, p) : null;
        loot = calm && !c.staying() && order == null ? findLoot(level, p, w, now) : null;
        // 主人离开温泉两秒以后她才起身：主人在池子里挪一步、被传送的那一刻不算在水里，不然她会起来又坐下
        boolean springNow = p.isInWater() && HotSpringStoneBlock.heatedFrom(level, p.blockPosition());
        if (springNow) springSeen = now;
        boolean inSpring = now - springSeen < SPRING_GRACE;
        if (!inSpring) {
            spring = null;
            edge = null;
        } else if (springNow && (spring == null || now % 40 == 0)) {
            spring = findSpring(level, p);
            edge = spring == null ? findEdge(level, p) : null;
        }
        dog = calm ? nearestDog(level) : null;

        scores.put(Kind.FOLLOW, c.staying() || order != null ? 0 : .3 + (dist > 6 ? .4 : 0));
        scores.put(Kind.STAY, c.staying() ? .6 : 0);
        scores.put(Kind.ENGAGE, foe == null || holdBack ? 0 : .7 + (foe instanceof Mob m && m.getTarget() == p ? .1 : 0));
        scores.put(Kind.COVER, foe != null && !holdBack && p.getHealth() < p.getMaxHealth() * .4f && foe.distanceTo(p) < 8 ? .95 : 0);
        scores.put(Kind.KINDLE, foe != null && strong && !wrapped && !c.blade().drawn() && c.kindleReady(now) && w.fighting(now) && dist < 8 ? .9 : 0);
        // 不参与攻击：敌人离她或主人近了，就躲到主人背后
        scores.put(Kind.RETREAT, foe != null && c.getHealth() < c.getMaxHealth() * .3f ? .85
                : holdBack && foe != null && (foe.distanceTo(c) < PASSIVE_SHY || foe.distanceTo(p) < PASSIVE_SHY) ? .75 : 0);
        scores.put(Kind.REST, p.isSleeping() ? 1 : doing == OwnerWatch.Activity.AFK && calm ? .5 : 0);
        scores.put(Kind.IDLE, .1);
        scores.put(Kind.EXECUTE, c.blade().executing() ? 1.2
                : execTarget != null && tactic != CompanionTactic.PASSIVE && c.getHealth() >= c.getMaxHealth() * SHEATHE_HEALTH ? .93 : 0);
        scores.put(Kind.FORAGE, !calm || c.staying() ? 0 : pouchFood ? .6 : bush != null && (hungry || now >= nextForage) && dist < 10 ? (hungry ? .55 : .33) : 0);
        scores.put(Kind.SCAVENGE, loot != null ? .36 : 0);
        scores.put(Kind.SOAK, inSpring && calm ? .75 : 0);
        scores.put(Kind.HIDE, dog != null ? .5 : 0);
        scores.put(Kind.ERRAND, order == null ? 0 : LogosCompanionBridge.onErrand(c) ? .8 : .55);
        // 主人在和她说话（对话界面开着）：站住、看着主人；泡着温泉就泡着说
        scores.put(Kind.TALK, talking && !c.soaking() ? .92 : 0);

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
            if (current == Kind.SOAK) endSoak();
            if (current == Kind.FORAGE && best != Kind.FORAGE) nextForage = now + FORAGE_GAP;
            c.getNavigation().stop();
            c.setSprinting(false);
            current = best;
        }
        // 没在打了：刀收起来
        if (!engaged() && current != Kind.RETREAT && c.blade().drawn() && !c.blade().executing()) c.blade().sheathe();
        boolean fighting = engaged() || current == Kind.RETREAT || c.blade().drawn();
        if (fighting) lastFight = now;
        // 浴衣（第 8.10 节）：一打起来就换回巫女服；出浴后穿一阵（CompanionOutfit.LINGER）再换回。
        // 对话里让她换上的那一套（preferredOutfit）不到时候换回：打完歇五秒再换上
        CompanionOutfit chosen = c.preferredOutfit();
        if (c.outfit() == CompanionOutfit.UMEAKARI && (fighting
                || chosen != CompanionOutfit.UMEAKARI && current != Kind.SOAK && now - c.dressedAt() > CompanionOutfit.LINGER)) {
            c.dress(CompanionOutfit.MIKO, now);
        } else if (!fighting && chosen != CompanionOutfit.MIKO && c.outfit() != chosen && current != Kind.SOAK && now - lastFight > 100) {
            c.dress(chosen, now);
        }
        if (dog != null) c.mood.afraid(now);
    }

    /**
     * 要对付的那一个：主人用 {@code sic} 指的最优先；再是刚朝主人放冷箭的射手；然后是打主人的、主人在打的、打她的；
     * 最后是冲着主人或她来的、离主人最近的敌对生物。
     */
    private @Nullable LivingEntity pickFoe(ServerLevel level, ServerPlayer p, OwnerWatch w, long now) {
        threats = 0;
        LivingEntity best = null;
        double bestScore = -1;
        for (Mob m : level.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(THREAT_RADIUS),
                m -> m.isAlive() && m != c && c.canHarm(m) && (m.getTarget() == p || m.getTarget() == c
                        || m instanceof Enemy && m.distanceTo(p) < 8 && m.hasLineOfSight(p)))) {
            threats++;
            // 离主人近的、冲着主人的先打；伤得重的也先打（先收拾掉一个，挨打的就少一路）；正在打的那个多一点分，
            // 两只轮流砍她时不来回换
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

    /**
     * 能处决的：狐火印爆开后还在窗口里（她或主人打出来的都算）、离她不远。主人拿着流火站在旁边时让给主人（他要自己点）；
     * 已经在被别人处决的不抢。
     */
    private @Nullable LivingEntity pickExecution(ServerLevel level, ServerPlayer p) {
        LivingEntity best = null;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, c.getBoundingBox().inflate(10),
                e -> e.isAlive() && e != c && c.canHarm(e) && FoxMarks.executable(e))) {
            if (InteractiveExecutions.target(e) != null || ExecutionShots.frozen(e) || e.distanceTo(p) > 16) continue;
            if (p.getMainHandItem().getItem() instanceof RyukaItem && p.distanceTo(e) < 5) continue;
            if (best == null || c.distanceToSqr(e) < c.distanceToSqr(best)) best = e;
        }
        return best;
    }

    // ---------------------------------------------------------------- 反射

    /** 掉队太远、卡住、掉进岩浆或快掉出世界：闪到主人身边；有弹射物要打中主人：闪过去一刀弹开。做了就返回真，这一刻不再做别的。 */
    private boolean reflex(ServerLevel level, ServerPlayer p, long now) {
        if (intercept(level, p, now)) return true;
        boolean far = c.staying() || current == Kind.ERRAND;
        boolean lost = !far && (dist > BLINK_AT || dist > 12 && stuck >= 60);
        boolean danger = c.isInLava() || c.getY() < level.getMinY() + 4;
        if (!lost && !danger) return false;
        blink(level, p);
        return true;
    }

    /** 截下射向主人的弹射物（第 8.5 节）：闪到弹道上、凝刀一挥弹回去，射手记下来优先打。 */
    private boolean intercept(ServerLevel level, ServerPlayer p, long now) {
        if (now < nextIntercept || c.blade().staggered() || c.blade().executing() || c.eating() || dist > 16) return false;
        AABB around = p.getBoundingBox().inflate(14);
        for (Projectile proj : level.getEntitiesOfClass(Projectile.class, around, this::hostileShot)) {
            Vec3 at = CompanionGuard.incoming(proj.position(), proj.getDeltaMovement(), CompanionGuard.gravity(proj), p.getBoundingBox());
            if (at == null) continue;
            Vec3 toward = proj.position().subtract(p.position()).multiply(1, 0, 1);
            Vec3 spot = p.position().add(toward.lengthSqr() < 1e-4 ? Vec3.ZERO : toward.normalize().scale(1.0));
            if (c.position().distanceTo(spot) > .8) {
                puff(level, c.position().add(0, .7, 0));
                c.teleportTo(spot.x, p.getY(), spot.z);
                c.getNavigation().stop();
                puff(level, spot.add(0, .7, 0));
            }
            // 先记下射手：弹开以后这支箭就算她的了
            Entity shotBy = proj.getOwner();
            c.lookAt(proj, 360, 360);
            c.blade().parry();
            c.blade().deflect(proj);
            level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.ANVIL_LAND, SoundSource.NEUTRAL, .35f, 1.9f);
            level.sendParticles(ParticleTypes.CRIT, proj.getX(), proj.getY(), proj.getZ(), 10, .1, .1, .1, .3);
            if (shotBy instanceof LivingEntity s && c.canHarm(s)) {
                shooter = s;
                shooterAt = now;
            }
            nextIntercept = now + INTERCEPT_GAP;
            intercepts++;
            return true;
        }
        return false;
    }

    /** 是冲着主人来的：不是主人、她自己、主人的使魔放的，还在飞。 */
    private boolean hostileShot(Projectile proj) {
        if (proj.isRemoved() || proj.onGround() || proj.getDeltaMovement().lengthSqr() < .04) return false;
        Entity shotBy = proj.getOwner();
        if (shotBy == null || shotBy == c || owner != null && (shotBy == owner || owner.getUUID().equals(LogosMagicBridge.masterOf(shotBy)))) return false;
        return !(shotBy instanceof ShiraumeCompanion);
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
                if (foe != null) fight(level, foe, now, false);
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
            case EXECUTE -> execute(level, now);
            case FORAGE -> forage(level, p, now);
            case SCAVENGE -> scavenge(level, p, now);
            case SOAK -> soak(level, p, now);
            case HIDE -> hide(p, now);
            case ERRAND -> errand(p, now);
            case TALK -> talk(p);
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

    /**
     * 迎战（第 8.4 节）：远处的敌人用狐火打，不主动靠近；敌人贴近、她血量充裕时凝出流火近战，血少了或敌人退远了就散刀。
     * {@code hold} 时站在原地（护主）。
     */
    private void fight(ServerLevel level, LivingEntity target, long now, boolean hold) {
        double d = c.distanceTo(target);
        CompanionBlade blade = c.blade();
        // 神狐一族不怕狐火（只受一成半）：对它们不放狐火，6 格内就凝刀守着，不主动走过去；贴近了再砍，血少了只守不攻
        boolean kin = FoxfireRules.kin(target);
        boolean low = c.getHealth() < c.getMaxHealth() * SHEATHE_HEALTH;
        // 行动策略：随机应变照原来的；优先远程贴到跟前才凝刀、拉开就收；优先近战远远地就凝刀走过去（够不着的除外），
        // 血少了也不收刀，只守不攻
        boolean closeIn = tactic == CompanionTactic.MELEE && reachable;
        double drawAt = tactic == CompanionTactic.RANGED ? RANGED_DRAW : closeIn ? MELEE_REACH : MELEE_IN;
        double sheatheAt = tactic == CompanionTactic.RANGED ? RANGED_SHEATHE : closeIn ? MELEE_CHASE : MELEE_OUT;
        float drawHealth = closeIn ? SHEATHE_HEALTH : MELEE_HEALTH;
        if (blade.drawn()) {
            boolean away = kin ? d > MELEE_OUT : d > sheatheAt;
            if (!blade.executing() && (low && !kin && !closeIn || away || tactic == CompanionTactic.MELEE && !reachable && !kin)) blade.sheathe();
        } else if ((c.getHealth() >= c.getMaxHealth() * drawHealth && d <= drawAt || kin && d <= MELEE_OUT) && !c.casting() && !c.eating()
                && blade.draw()) {
            // 刚凝出刀：直接起一串，不先停
            combo = 2 + c.getRandom().nextInt(3);
            restUntil = -1;
        }
        if (blade.drawn()) {
            melee(target, now, d, hold, kin && (d > MELEE_IN || low) || closeIn && low);
            return;
        }
        if (kin) {
            // 远处的神狐：狐火没用，回到主人身边守着
            if (!hold && owner != null && dist > FAR) {
                if (now % THINK == 0 || c.getNavigation().isDone()) c.getNavigation().moveTo(owner, 1.2);
            } else {
                c.getNavigation().stop();
            }
            c.getLookControl().setLookAt(target, 40, 40);
            return;
        }
        boolean sees = c.hasLineOfSight(target);
        if (!hold && !c.casting()) {
            if (d < KEEP_MIN) {
                Vec3 away = c.position().subtract(target.position()).multiply(1, 0, 1).normalize().scale(4);
                Vec3 to = c.position().add(away);
                if (owner != null && to.distanceTo(owner.position()) > 10) to = owner.position();
                c.getNavigation().moveTo(to.x, to.y, to.z, 1.3);
                c.setSprinting(true);
            } else if (owner != null && dist > FAR + 2) {
                // 打不着也不追：回到主人身边守着
                if (now % THINK == 0 || c.getNavigation().isDone()) c.getNavigation().moveTo(owner, 1.2);
                c.setSprinting(false);
            } else {
                c.getNavigation().stop();
                c.setSprinting(false);
            }
        }
        c.getLookControl().setLookAt(target, 40, 40);
        if (sees && d < SHOOT_RANGE && now - lastCast >= CAST_GAP && !c.casting() && c.startCast(target, false)) lastCast = now;
    }

    /**
     * 拿着刀（第 8.4 节）：一串 2–4 刀，每刀砍完不等收势播完就接下一刀，每一刀往前带一步（敌人被打退了也跟得上），不连着出同一招；
     * 一串砍完回到架势里停 {@link #STRING_REST} 刻左右（这时挡得住，挡下就让对方僵直）。身边围着两个以上时一串只砍一两刀、
     * 架势里多停一会儿，多挡、多反击。弹开谁的近战，就趁它僵直（挡不了）接一串反击。够不着就走过去（远一点就跑）；
     * 突刺只用来拉近，隔 {@link #THRUST_GAP} 刻才一记，冲到跟前接着连砍。
     */
    private void melee(LivingEntity target, long now, double d, boolean hold) {
        melee(target, now, d, hold, false);
    }

    /** {@code guardOnly}：只守不攻（血少时），弹开了才反击。 */
    private void melee(LivingEntity target, long now, double d, boolean hold, boolean guardOnly) {
        CompanionBlade blade = c.blade();
        // 弹开的那个被震退了也追得上：一记突刺冲过去
        LivingEntity punish = blade.punishing(now);
        boolean punishing = punish != null
                && c.distanceTo(punish) <= CompanionBlade.REACH + punish.getBbWidth() * .5 - .2 + (hold ? 0 : CompanionBlade.LUNGE_MAX - .4);
        if (guardOnly && (!punishing || holdsBack())) {
            c.getNavigation().stop();
            c.getLookControl().setLookAt(target, 40, 40);
            if (blade.ready()) face(target);
            combo = 0;
            return;
        }
        if (punishing) {
            target = punish;
            d = c.distanceTo(punish);
            if (blade.punishUntil() != countered) {
                countered = blade.punishUntil();
                combo = 2;
                restUntil = -1;
            }
        }
        c.getLookControl().setLookAt(target, 40, 40);
        if (!blade.canFollowUp()) {
            c.getNavigation().stop();
            return;
        }
        double reach = CompanionBlade.REACH + target.getBbWidth() * .5 - .2;
        if (d > reach) {
            if (hold) return;
            if ((punishing || now >= nextThrust) && d < reach + CompanionBlade.LUNGE_MAX - .4 && blade.attack("tsuki", target)) {
                face(target);
                lastMove = "tsuki";
                // 反击的突刺不算进冷却，冲过去只再补一刀（对方的僵直快过了）
                if (!punishing) nextThrust = now + THRUST_GAP;
                combo = punishing ? 1 : COMBO_AFTER_THRUST + c.getRandom().nextInt(2);
                return;
            }
            // 收势播完再走
            if (!blade.ready()) return;
            boolean run = d > reach + 1.5;
            c.setSprinting(run);
            if (now % 5 == 0 || c.getNavigation().isDone()) c.getNavigation().moveTo(target, run ? 1.45 : 1.25);
            return;
        }
        c.getNavigation().stop();
        c.setSprinting(false);
        face(target);
        if (combo <= 0) {
            // 一串砍完：回到架势里停一下再起下一串（围着的多就多停一会儿）；刚弹开过她的刀的，一次只试探一刀，多守着等它出手
            if (!blade.ready()) return;
            // 优先远程：砍完一串往后一跳，拉开了就收刀放狐火（一串只跳一次，对方追上来就照常接着打）
            if (tactic == CompanionTactic.RANGED && !hold && !backstepped && !FoxfireRules.kin(target) && blade.attack("backstep", target, false)) {
                lastMove = "backstep";
                backstepped = true;
                return;
            }
            int crowd = crowd(4.5);
            boolean wary = blade.wary(target, now);
            if (restUntil < 0) restUntil = now + STRING_REST + c.getRandom().nextInt(STRING_REST) + (crowd >= 2 ? STRING_REST : 0) + (wary ? 2 * STRING_REST : 0);
            if (now < restUntil) return;
            restUntil = -1;
            backstepped = false;
            combo = wary ? 1 : crowd >= 2 ? 1 + c.getRandom().nextInt(2) : 2 + c.getRandom().nextInt(3);
        }
        // 反击：最快的三连或切上
        String move = punishing ? c.getRandom().nextFloat() < .7f ? "sanren_1" : "kiriage" : pickMove();
        if (!blade.attack(move, target, !hold)) return;
        lastMove = move;
        combo--;
    }

    /** 身边 {@code radius} 格里冲着她或主人来的敌人有几个。 */
    private int crowd(double radius) {
        return c.level().getEntitiesOfClass(Mob.class, c.getBoundingBox().inflate(radius), m -> m.isAlive() && c.canHarm(m) && c.hostileTo(m)).size();
    }

    /** 下一刀：围上来两个以上多半横斩；不然顺着上一刀接（袈裟接逆袈裟、突刺接切上……），不连着出同一招。 */
    private String pickMove() {
        int crowd = crowd(3);
        if (crowd >= 2 && !"yoko".equals(lastMove) && c.getRandom().nextFloat() < .5f) return "yoko";
        String[] next = FOLLOW.get(lastMove);
        if (next != null && c.getRandom().nextFloat() < .7f) return next[c.getRandom().nextInt(next.length)];
        for (int tries = 0; tries < 4; tries++) {
            float r = c.getRandom().nextFloat();
            String m = r < .3f ? "sanren_1" : r < .5f ? "kesa" : r < .7f ? "gyaku" : r < .88f ? "tsubame_1" : "kiriage";
            if (!m.equals(lastMove)) return m;
        }
        return "kesa".equals(lastMove) ? "gyaku" : "kesa";
    }

    /** 顺手的下一刀。 */
    private static final Map<String, String[]> FOLLOW = Map.of(
            "tsuki", new String[]{"kiriage", "sanren_1", "kesa"},
            "kesa", new String[]{"gyaku", "yoko"},
            "gyaku", new String[]{"kesa", "tsubame_1"},
            "kiriage", new String[]{"kesa", "sanren_1"},
            "yoko", new String[]{"gyaku", "kiriage"},
            "sanren_1", new String[]{"kesa", "tsubame_1"},
            "tsubame_1", new String[]{"gyaku", "kiriage"});

    private void face(LivingEntity target) {
        c.lookAt(target, 360, 30);
        c.yBodyRot = c.getYRot();
        c.setYHeadRot(c.getYRot());
    }

    /** 护主：站到主人和威胁之间，太远就闪过去，然后就地开火（或挥刀）。 */
    private void cover(ServerLevel level, ServerPlayer p, LivingEntity threat, long now) {
        Vec3 toward = threat.position().subtract(p.position()).multiply(1, 0, 1);
        Vec3 spot = p.position().add(toward.lengthSqr() < 1e-4 ? Vec3.ZERO : toward.normalize().scale(1.6));
        double d = c.position().distanceTo(spot);
        if (d > 6) blink(level, p);
        else if (d > .8 && c.blade().ready() || d > .8 && !c.blade().drawn()) c.getNavigation().moveTo(spot.x, spot.y, spot.z, 1.5);
        fight(level, threat, now, d <= .8);
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

    /**
     * 后撤（血少了不硬拼，但也不是只挨打）：敌人还远时收刀退到主人身后，边退边放狐火。敌人已经追到跟前（{@link #MELEE_IN} 以内）时，
     * 转身放狐火会被一路砍（放的时候挡不了），就凝刀站住守着：只弹不攻，弹开了才趁对方僵直反击一串。
     */
    private void retreat(ServerPlayer p, LivingEntity threat, long now) {
        double close = FoxfireRules.kin(threat) ? MELEE_OUT : MELEE_IN;
        // 不参与攻击：只有被追到跟前、冲着她来的才凝刀挡，不然一直往主人背后躲
        boolean stand = holdsBack() ? threat instanceof Mob m && m.getTarget() == c && c.distanceTo(threat) <= RANGED_DRAW
                : c.distanceTo(threat) <= close;
        if (stand && !c.casting() && (c.blade().drawn() || c.blade().draw())) {
            c.setSprinting(false);
            melee(threat, now, c.distanceTo(threat), true, true);
            return;
        }
        c.blade().sheathe();
        Vec3 back = p.position().subtract(threat.position()).multiply(1, 0, 1);
        Vec3 spot = p.position().add(back.lengthSqr() < 1e-4 ? Vec3.ZERO : back.normalize().scale(2.5));
        if (now % THINK == 0) c.getNavigation().moveTo(spot.x, spot.y, spot.z, 1.4);
        c.setSprinting(true);
        c.getLookControl().setLookAt(threat, 30, 30);
        if (!holdsBack() && !FoxfireRules.kin(threat) && c.hasLineOfSight(threat) && c.distanceTo(threat) < SHOOT_RANGE && now - lastCast >= CAST_GAP
                && !c.casting() && c.startCast(threat, false)) lastCast = now;
    }

    /** 优先近战时够不够得着 {@code t}：不是飞在头顶上，而且寻路找得到过去的路。 */
    private boolean reachable(LivingEntity t) {
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
        if (!c.blade().drawn() && !c.casting()) {
            float yaw = (float) (Math.toDegrees(Math.atan2(p.getZ() - c.getZ(), p.getX() - c.getX())) - 90);
            c.setYRot(Mth.approachDegrees(c.getYRot(), yaw, 12));
            c.yBodyRot = c.getYRot();
        }
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

    /** 处决：闪到目标身边，上挑接处决；处决的这一段站着不动。 */
    private void execute(ServerLevel level, long now) {
        CompanionBlade blade = c.blade();
        if (blade.executing()) {
            c.getNavigation().stop();
            return;
        }
        LivingEntity t = execTarget;
        if (t == null || !t.isAlive()) return;
        double d = c.distanceTo(t);
        if (d > 2.2) {
            Vec3 back = c.position().subtract(t.position()).multiply(1, 0, 1);
            Vec3 spot = t.position().add(back.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : back.normalize().scale(1.3));
            puff(level, c.position().add(0, .7, 0));
            c.teleportTo(spot.x, t.getY(), spot.z);
            c.getNavigation().stop();
            puff(level, spot.add(0, .7, 0));
        }
        face(t);
        blade.execute(t);
    }

    /** 饿了先吃包袱里的；不然去摘熟了的浆果（第 8.7 节）。 */
    private void forage(ServerLevel level, ServerPlayer p, long now) {
        if (c.eating() || c.picking()) {
            c.getNavigation().stop();
            return;
        }
        var pouch = Companions.pouch(p);
        if (p.getData(Companions.BOND).satiety() < CompanionBond.HUNGRY) {
            int slot = pouch.foodSlot();
            if (slot >= 0) {
                ItemStack food = pouch.removeItem(slot, 1);
                c.startEating(food, false);
                return;
            }
        }
        if (bush == null) return;
        Vec3 at = Vec3.atCenterOf(bush);
        if (c.position().distanceTo(at) > 1.9) {
            if (now % THINK == 0 || c.getNavigation().isDone()) c.getNavigation().moveTo(at.x, bush.getY(), at.z, 1.1);
            return;
        }
        c.getNavigation().stop();
        if (c.startPicking(bush)) {
            bush = null;
            nextForage = now + FORAGE_GAP;
        }
    }

    private boolean ripe(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return s.getBlock() instanceof SweetBerryBushBlock && s.getValue(SweetBerryBushBlock.AGE) >= 2;
    }

    private @Nullable BlockPos findBush(ServerLevel level, ServerPlayer p) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        BlockPos here = c.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(here.offset(-BUSH_RADIUS, -3, -BUSH_RADIUS), here.offset(BUSH_RADIUS, 3, BUSH_RADIUS))) {
            if (!ripe(level, pos) || pos.distSqr(p.blockPosition()) > 12 * 12) continue;
            double d = pos.distSqr(here);
            if (d < bestD) {
                bestD = d;
                best = pos.immutable();
            }
        }
        return best;
    }

    /**
     * 拾荒（第 8.6 节）：附近刚掉下来、没人扔过的东西（敌人掉的）叼回包袱，只捡主人身边的（不越捡越远）。
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
        ItemStack left = Companions.pouch(p).put(e.getItem().copy());
        int took = e.getItem().getCount() - left.getCount();
        if (took <= 0) return;
        c.take(e, took);
        if (left.isEmpty()) e.discard();
        else e.setItem(left);
        level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, .25f, 1.4f);
        loot = null;
    }

    /**
     * 主人泡温泉：她也下到旁边的温泉格里，只露出肩膀和头（第 8.9 节）。泡着时定在那一格，水面停在肩头：一格深的池子坐在池底，
     * 深一点的（神狐聚落露天风吕，含水半砖上一格水，约一格半）浮在水里，不沉下去、不呛水、不被水推走。
     */
    private void soak(ServerLevel level, ServerPlayer p, long now) {
        if (spring == null) {
            kneelBy(p, now);
            return;
        }
        if (c.soaking() && !spring.equals(soakAt)) {
            // 主人挪了地方：起来，走到新的那一格再泡
            c.setSoaking(false);
            c.mood.pose(null);
        }
        Vec3 at = Vec3.atBottomCenterOf(spring);
        if (!c.soaking()) {
            double flat = Math.hypot(c.getX() - at.x, c.getZ() - at.z);
            if (flat > .7) {
                c.mood.pose(null);
                if (now % THINK == 0 || c.getNavigation().isDone()) c.getNavigation().moveTo(at.x, at.y, at.z, 1);
                if (flat < 1.6 && c.getNavigation().isDone()) c.teleportTo(at.x, soakHeight(level, spring), at.z);
                return;
            }
            c.getNavigation().stop();
            soakAt = spring;
            soakY = soakHeight(level, spring);
            c.setSoaking(true);
        }
        // 几刻之内挪到那一格正中、浮到水面在肩头的高度，之后定在那里
        c.setPos(c.position().lerp(new Vec3(at.x, soakY, at.z), .3));
        c.setDeltaMovement(Vec3.ZERO);
        c.setAirSupply(c.getMaxAirSupply());
        // 泡温泉换上浴衣（泡着时一直续着，出浴后再穿一阵）
        c.dress(CompanionOutfit.UMEAKARI, now);
        c.mood.pose("soak");
        c.getLookControl().setLookAt(p, 10, 10);
        if (soakSince < 0) soakSince = now;
        var b = p.getData(Companions.BOND);
        long day = level.getOverworldClockTime() / 24000;
        if (now - soakSince >= 100 && b.soakDay() != day) {
            p.setData(Companions.BOND, b.withSoak(day).withAffection(b.affection() + CompanionBond.SOAK));
            CompanionLines.say(p, "soak", 60);
        }
    }

    private void endSoak() {
        soakSince = -1;
        soakAt = null;
        if (c.soaking()) soakEnds++;
        c.setSoaking(false);
        c.mood.pose(null);
    }

    /** 附近的温泉都太深：在池边岸上跪坐着陪他；岸上也没有地方就跟在旁边。 */
    private void kneelBy(ServerPlayer p, long now) {
        if (edge == null) {
            c.mood.pose(null);
            follow(p, now);
            return;
        }
        Vec3 spot = Vec3.atBottomCenterOf(edge);
        if (Math.hypot(c.getX() - spot.x, c.getZ() - spot.z) > .6) {
            c.mood.pose(null);
            if (now % THINK == 0 || c.getNavigation().isDone()) c.getNavigation().moveTo(spot.x, spot.y, spot.z, 1);
            return;
        }
        c.getNavigation().stop();
        c.mood.pose("pact_wait");
        c.getLookControl().setLookAt(p, 20, 20);
    }

    /**
     * 主人身边能泡的温泉格：水面那一格（上面是空气），底下通到地热石，水面到池底不比 {@link #SPRING_DEEP} 深（含水半砖的顶面也算池底）；
     * 主人站的那一列除外，取离主人最近的（一样近取离她近的，泡着的那格不会换来换去）。
     */
    private @Nullable BlockPos findSpring(ServerLevel level, ServerPlayer p) {
        BlockPos base = p.blockPosition(), best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(base.offset(-SPRING_RADIUS, -2, -SPRING_RADIUS), base.offset(SPRING_RADIUS, 2, SPRING_RADIUS))) {
            if (pos.getX() == base.getX() && pos.getZ() == base.getZ()) continue;
            if (!level.getFluidState(pos).is(FluidTags.WATER) || !level.getBlockState(pos.above()).isAir() || !HotSpringStoneBlock.heatedFrom(level, pos)) continue;
            double floor = floorUnder(level, pos);
            if (Double.isNaN(floor) || surface(level, pos) - floor > SPRING_DEEP) continue;
            double dx = pos.getX() + .5 - p.getX(), dz = pos.getZ() + .5 - p.getZ();
            double d = dx * dx + dz * dz + c.distanceToSqr(Vec3.atBottomCenterOf(pos)) * .01;
            if (d < bestD) {
                bestD = d;
                best = pos.immutable();
            }
        }
        return best;
    }

    /** 这一格水的水面高度。 */
    static double surface(ServerLevel level, BlockPos water) {
        return water.getY() + level.getFluidState(water).getHeight(level, water);
    }

    /** 这一格水往下，第一个站得住的面有多高（含水半砖的顶面也算）；水底下是空的就是 NaN。 */
    static double floorUnder(ServerLevel level, BlockPos water) {
        BlockPos.MutableBlockPos at = water.mutable();
        for (int i = 0; i < 6; i++) {
            VoxelShape shape = level.getBlockState(at).getCollisionShape(level, at);
            if (!shape.isEmpty()) return at.getY() + shape.max(Direction.Axis.Y);
            if (!level.getFluidState(at).is(FluidTags.WATER)) return Double.NaN;
            at.move(Direction.DOWN);
        }
        return Double.NaN;
    }

    /** 泡在这一格时她脚底的高度：水面停在肩头（{@link #SOAK_WATERLINE}）；水浅的话就坐在池底。 */
    static double soakHeight(ServerLevel level, BlockPos water) {
        return Math.max(floorUnder(level, water), surface(level, water) - SOAK_WATERLINE);
    }

    /** 池边岸上挨着温泉水、离主人最近的一格（脚下实心，身子占的两格是空气、没有水）。 */
    private @Nullable BlockPos findEdge(ServerLevel level, ServerPlayer p) {
        BlockPos base = p.blockPosition(), best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(base.offset(-SPRING_RADIUS, -1, -SPRING_RADIUS), base.offset(SPRING_RADIUS, 2, SPRING_RADIUS))) {
            if (!open(level, pos) || !open(level, pos.above())) continue;
            BlockPos below = pos.below();
            if (level.getBlockState(below).getCollisionShape(level, below).isEmpty() || !level.getFluidState(below).isEmpty()) continue;
            boolean shore = false;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = pos.relative(d);
                if (hotWater(level, n) || hotWater(level, n.below())) {
                    shore = true;
                    break;
                }
            }
            double d = pos.distSqr(base);
            if (shore && d < bestD) {
                bestD = d;
                best = pos.immutable();
            }
        }
        return best;
    }

    private static boolean open(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty() && level.getFluidState(pos).isEmpty();
    }

    private static boolean hotWater(ServerLevel level, BlockPos pos) {
        return level.getFluidState(pos).is(FluidTags.WATER) && HotSpringStoneBlock.heatedFrom(level, pos);
    }

    private @Nullable LivingEntity nearestDog(ServerLevel level) {
        LivingEntity best = null;
        // 主人自己养的狗她认得，不躲
        for (Mob m : level.getEntitiesOfClass(Mob.class, c.getBoundingBox().inflate(DOG_RADIUS), m -> m.isAlive() && m.getType() == EntityType.WOLF
                && !(m instanceof net.minecraft.world.entity.TamableAnimal t && t.getOwner() != null && owner != null && t.getOwner().getUUID().equals(owner.getUUID())))) {
            if (best == null || c.distanceToSqr(m) < c.distanceToSqr(best)) best = m;
        }
        return best;
    }

    /** 怕狗（野狼、别人的狗）：躲到主人身后（主人在她和狗之间），耳朵压低。 */
    private void hide(ServerPlayer p, long now) {
        if (dog == null) return;
        Vec3 away = p.position().subtract(dog.position()).multiply(1, 0, 1);
        Vec3 spot = p.position().add(away.lengthSqr() < 1e-4 ? Vec3.ZERO : away.normalize().scale(1.4));
        if (c.position().distanceTo(spot) > .7) {
            if (now % 5 == 0 || c.getNavigation().isDone()) c.getNavigation().moveTo(spot.x, spot.y, spot.z, 1.3);
        } else {
            c.getNavigation().stop();
            c.getLookControl().setLookAt(dog, 30, 30);
        }
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
        StringBuilder sb = new StringBuilder(current.name()).append(" tactic=").append(tactic.id).append(" foe=").append(foe == null ? "-" : foe.getName().getString())
                .append(" blade=").append(c.blade().drawn() ? c.blade().move() == null ? "?" : c.blade().move().name() : "-");
        for (var e : scores.entrySet()) {
            if (e.getValue() > 0) sb.append(' ').append(e.getKey().name().toLowerCase()).append('=').append(String.format("%.2f", e.getValue()));
        }
        return sb.toString();
    }
}
