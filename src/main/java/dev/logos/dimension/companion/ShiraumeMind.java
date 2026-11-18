package dev.logos.dimension.companion;

import dev.logos.dimension.cloudsea.HotSpringStoneBlock;
import dev.logos.dimension.execution.ExecutionShots;
import dev.logos.dimension.execution.InteractiveExecutions;
import dev.logos.dimension.foxfire.FoxfireRules;
import dev.logos.dimension.foxfire.FoxfireWraps;
import dev.logos.dimension.item.RyukaItem;
import dev.logos.dimension.npc.LogosMagicBridge;
import dev.logos.dimension.ryuka.FoxMarks;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * 白梅的心智（docs/SHIRAUME_COMPANION.md 第 3、7.3、8 节）：通用的那些在 {@link CompanionMind}，这里是她自己的——
 * 狐火弹和流火的打法、截箭、缠火、处决、摘浆果、泡温泉、怕狗、打完换回装束。
 */
public final class ShiraumeMind extends CompanionMind {
    /** 迎战：和敌人保持的距离、放狐火弹的射程。 */
    static final double KEEP_MIN = 4.5, KEEP_MAX = 10, SHOOT_RANGE = 14;
    /** 流火：敌人贴近到这里、她血量在这一成以上就凝刀；血量掉到这里以下、或敌人退到这么远就散刀。 */
    static final double MELEE_IN = 3.5, MELEE_OUT = 6;
    static final float MELEE_HEALTH = .5f, SHEATHE_HEALTH = .35f;
    /**
     * 行动策略（{@link CompanionTactic}）：优先远程时敌人贴到这么近才凝刀、拉开到这么远就收刀；优先近战时这么远以内就凝刀走过去
     * （远过 {@link #MELEE_CHASE} 才收刀），够不着的才放狐火。
     */
    static final double RANGED_DRAW = 1.8, RANGED_SHEATHE = 2.8, MELEE_REACH = SHOOT_RANGE, MELEE_CHASE = SHOOT_RANGE + 4;
    /** 突刺只用来拉近：两次之间至少隔这么多刻；冲到跟前接着再砍这么多刀（另加 0–1）不停手。一串砍完在架势里停这么多刻（另加 0–同样多）。 */
    public static final int THRUST_GAP = 100, COMBO_AFTER_THRUST = 2, STRING_REST = 8;
    /** 两次放狐火弹之间至少隔这么多刻。 */
    static final int CAST_GAP = 22;
    /**
     * 血少了、敌人贴着她（后撤时）：先凝刀守 {@link #GUARD_HOLD} 刻；还贴着就闪到主人身边拉开（隔 {@link #ESCAPE_GAP} 刻才能再闪），
     * 闪不了就站着照常砍，不再只守着发呆。
     */
    static final int GUARD_HOLD = 30;
    /** 截弹射物：截完多久才能再截。 */
    static final int INTERCEPT_GAP = 20;
    /** 摘浆果：找多远；不饿时两次之间隔多久。泡温泉：找多远的温泉格。怕狗：多近算有狗。 */
    static final int BUSH_RADIUS = 8, FORAGE_GAP = 600, SPRING_RADIUS = 4, SPRING_GRACE = 40;
    static final double DOG_RADIUS = 7;
    /**
     * 泡温泉：水面到池底比这还深的格不泡（在池边岸上陪着）；水面停在她肩头时，她脚底在水面下多深
     * （同模型 soak 动作的水线，tools/life_clips.py 的 WATER = 28.4 模型像素，渲染缩放 0.5）。
     */
    public static final double SPRING_DEEP = 2.2, SOAK_WATERLINE = 28.4 / 32;

    private final ShiraumeCompanion c;
    private @Nullable LivingEntity execTarget, dog;
    private @Nullable BlockPos bush, spring, soakAt, edge;
    private double soakY;
    private int combo;
    /** 优先远程：这一串砍完已经往后跳过一次（一串只跳一次，跳完照常在架势里停、再起下一串）。 */
    private boolean backstepped;
    private String lastMove = "";
    private long guardSince = -1;
    private long lastCast = -1000, nextThrust, restUntil = -1, countered, nextIntercept, nextForage, soakSince = -1, springSeen = -10_000;
    /** 最近一次在打（迎战、护主、缠火、处决、后撤、拿着刀）是哪一刻：打完歇一会儿才换回她自己选的装束。 */
    private long lastFight = -10_000;
    /** 测试用：截下过几发；泡着泡着起身过几次。 */
    static int intercepts, soakEnds;

    ShiraumeMind(ShiraumeCompanion c) {
        super(c);
        this.c = c;
    }

    public static int intercepts() {
        return intercepts;
    }

    public static int soakEnds() {
        return soakEnds;
    }

    @Override
    public void stop() {
        super.stop();
        endSoak();
    }

    @Override
    protected boolean engagedOwn(Kind k) {
        return k == Kind.KINDLE || k == Kind.EXECUTE;
    }

    // ---------------------------------------------------------------- 打分

    @Override
    protected void beforeScore(ServerLevel level, ServerPlayer p, long now) {
        execTarget = c.blade().executing() ? execTarget : pickExecution(level, p);
    }

    @Override
    protected boolean keepsFighting() {
        return c.blade().executing();
    }

    @Override
    protected boolean talkBlocked() {
        return c.soaking();
    }

    @Override
    protected void scoreOwn(ServerLevel level, ServerPlayer p, OwnerWatch w, long now, Situation s) {
        boolean wrapped = FoxfireWraps.flame(p) != null || LogosMagicBridge.wrapped(p);
        if (now % 40 == 0 || bush != null && !ripe(level, bush)) bush = s.calm() && !c.staying() ? findBush(level, p) : null;
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
        dog = s.calm() ? nearestDog(level) : null;

        scores.put(Kind.KINDLE, foe != null && s.strong() && !wrapped && !c.blade().drawn() && c.kindleReady(now) && w.fighting(now) && dist < 8 ? .9 : 0);
        scores.put(Kind.EXECUTE, c.blade().executing() ? 1.2
                : execTarget != null && tactic != CompanionTactic.PASSIVE && c.getHealth() >= c.getMaxHealth() * SHEATHE_HEALTH ? .93 : 0);
        scores.put(Kind.FORAGE, !s.calm() || c.staying() ? 0 : s.pouchFood() ? .6
                : bush != null && (s.hungry() || now >= nextForage) && dist < 10 ? (s.hungry() ? .55 : .33) : 0);
        scores.put(Kind.SOAK, inSpring && s.calm() ? .75 : 0);
        scores.put(Kind.HIDE, dog != null ? .5 : 0);
    }

    @Override
    protected void switched(Kind from, Kind to, long now) {
        if (from == Kind.SOAK) endSoak();
        if (from == Kind.FORAGE && to != Kind.FORAGE) nextForage = now + FORAGE_GAP;
    }

    @Override
    protected void afterThink(ServerLevel level, ServerPlayer p, long now) {
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
     * 能处决的：狐火印爆开后还在窗口里、离她不远。她自己打爆的一直能接；别人（主人的流火）打爆的，第二印（{@link CompanionBond#CHAIN_SEAL}）
     * 以后才接。主人拿着流火站在旁边时让给主人（他要自己点）；已经在被别人处决的不抢；玩家不处决。
     */
    private @Nullable LivingEntity pickExecution(ServerLevel level, ServerPlayer p) {
        LivingEntity best = null;
        boolean chain = CompanionBond.unlocked(c.seals(), CompanionBond.CHAIN_SEAL);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, c.getBoundingBox().inflate(10),
                e -> e.isAlive() && e != c && !(e instanceof net.minecraft.world.entity.player.Player) && c.canHarm(e) && FoxMarks.executable(e))) {
            if (!chain && !c.getUUID().equals(FoxMarks.burstBy(e))) continue;
            if (InteractiveExecutions.target(e) != null || ExecutionShots.frozen(e) || e.distanceTo(p) > 16) continue;
            if (p.getMainHandItem().getItem() instanceof RyukaItem && p.distanceTo(e) < 5) continue;
            if (best == null || c.distanceToSqr(e) < c.distanceToSqr(best)) best = e;
        }
        return best;
    }

    // ---------------------------------------------------------------- 反射：截箭

    @Override
    protected boolean reflexOwn(ServerLevel level, ServerPlayer p, long now) {
        return intercept(level, p, now);
    }

    /** 截下射向主人的弹射物（第 8.5 节）：闪到弹道上、凝刀一挥弹回去，射手记下来优先打。 */
    private boolean intercept(ServerLevel level, ServerPlayer p, long now) {
        if (now < nextIntercept || c.blade().staggered() || c.blade().executing() || c.eating() || dist > 16) return false;
        AABB around = p.getBoundingBox().inflate(14);
        for (Projectile proj : level.getEntitiesOfClass(Projectile.class, around, proj -> !friendlyShot(proj))) {
            Vec3 at = CompanionGuard.incoming(proj.position(), proj.getDeltaMovement(), CompanionGuard.gravity(proj), p.getBoundingBox());
            if (at == null) continue;
            Vec3 toward = proj.position().subtract(p.position()).multiply(1, 0, 1);
            Vec3 spot = p.position().add(toward.lengthSqr() < 1e-4 ? Vec3.ZERO : toward.normalize().scale(1.0));
            if (c.position().distanceTo(spot) > .8) {
                c.puff(level, c.position().add(0, .7, 0));
                c.teleportTo(spot.x, p.getY(), spot.z);
                c.getNavigation().stop();
                c.puff(level, spot.add(0, .7, 0));
            }
            // 先记下射手：弹开以后这支箭就算她的了
            Entity shotBy = proj.getOwner();
            c.lookAt(proj, 360, 360);
            c.blade().parry();
            c.blade().deflect(proj);
            level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.ANVIL_LAND, SoundSource.NEUTRAL, .35f, 1.9f);
            level.sendParticles(ParticleTypes.CRIT, proj.getX(), proj.getY(), proj.getZ(), 10, .1, .1, .1, .3);
            markShooter(shotBy, now);
            nextIntercept = now + INTERCEPT_GAP;
            intercepts++;
            return true;
        }
        return false;
    }

    @Override
    protected void stuckSaid(ServerPlayer p) {
        CompanionLines.say(p, "stuck", 60);
        p.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("message.logos_dimension.companion.stuck"));
    }

    // ---------------------------------------------------------------- 做

    @Override
    protected void actOwn(Kind kind, ServerLevel level, ServerPlayer p, long now) {
        switch (kind) {
            case KINDLE -> kindle(p, now);
            case EXECUTE -> execute(level, now);
            case SOAK -> soak(level, p, now);
            case HIDE -> hide(p, now);
            default -> idle(p, now, true);
        }
    }

    @Override
    protected boolean canMoveToCover() {
        return c.blade().ready() || !c.blade().drawn();
    }

    @Override
    protected boolean canTurnToTalk() {
        return !c.blade().drawn() && !c.casting();
    }

    /**
     * 迎战（第 8.4 节）：远处的敌人用狐火打，不主动靠近；敌人贴近、她血量充裕时凝出流火近战，血少了或敌人退远了就散刀。
     * {@code hold} 时站在原地（护主）。
     */
    @Override
    protected void fight(ServerLevel level, LivingEntity target, long now, boolean hold) {
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
     * 突刺只用来拉近，隔 {@link #THRUST_GAP} 刻才一记，冲到跟前接着连砍。{@code guardOnly}：只守不攻（血少时），弹开了才反击。
     */
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
     * 转身放狐火会被一路砍（放的时候挡不了），就凝刀站住守着：只弹不攻，弹开了才趁对方僵直反击一串。守了 {@link #GUARD_HOLD} 刻
     * 还贴着：闪到主人身边拉开距离（隔 {@link #ESCAPE_GAP} 刻一次）；还不能闪就站着照常砍（不参与攻击时接着守），不会一直发呆。
     */
    @Override
    protected void retreat(ServerPlayer p, LivingEntity threat, long now) {
        double close = FoxfireRules.kin(threat) ? MELEE_OUT : MELEE_IN;
        // 不参与攻击：只有被追到跟前、冲着她来的才凝刀挡，不然一直往主人背后躲
        boolean stand = holdsBack() ? threat instanceof Mob m && m.getTarget() == c && c.distanceTo(threat) <= RANGED_DRAW
                : c.distanceTo(threat) <= close;
        if (stand && !c.casting() && (c.blade().drawn() || c.blade().draw())) {
            c.setSprinting(false);
            if (guardSince < 0) guardSince = now;
            boolean held = now - guardSince >= GUARD_HOLD;
            if (held && now >= nextEscape && !c.blade().executing() && c.level() instanceof ServerLevel level) {
                c.blade().sheathe();
                escape(level, p, threat);
                nextEscape = now + ESCAPE_GAP;
                guardSince = -1;
                escapes++;
                return;
            }
            melee(threat, now, c.distanceTo(threat), true, !held || holdsBack());
            return;
        }
        guardSince = -1;
        c.blade().sheathe();
        Vec3 back = p.position().subtract(threat.position()).multiply(1, 0, 1);
        Vec3 spot = p.position().add(back.lengthSqr() < 1e-4 ? Vec3.ZERO : back.normalize().scale(2.5));
        if (now % THINK == 0) c.getNavigation().moveTo(spot.x, spot.y, spot.z, 1.4);
        c.setSprinting(true);
        c.getLookControl().setLookAt(threat, 30, 30);
        if (!holdsBack() && !FoxfireRules.kin(threat) && c.hasLineOfSight(threat) && c.distanceTo(threat) < SHOOT_RANGE && now - lastCast >= CAST_GAP
                && !c.casting() && c.startCast(threat, false)) lastCast = now;
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
            c.puff(level, c.position().add(0, .7, 0));
            c.teleportTo(spot.x, t.getY(), spot.z);
            c.getNavigation().stop();
            c.puff(level, spot.add(0, .7, 0));
        }
        face(t);
        blade.execute(t);
    }

    // ---------------------------------------------------------------- 摘浆果

    @Override
    protected boolean foraging() {
        return c.picking();
    }

    /** 包袱里没吃的：去摘熟了的浆果（第 8.7 节）。 */
    @Override
    protected void forageMore(ServerLevel level, ServerPlayer p, long now) {
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

    // ---------------------------------------------------------------- 温泉

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

    // ---------------------------------------------------------------- 怕狗、差事

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

    @Override
    protected void describeOwn(StringBuilder sb) {
        sb.append(" blade=").append(c.blade().drawn() ? c.blade().move() == null ? "?" : c.blade().move().name() : "-");
    }
}
