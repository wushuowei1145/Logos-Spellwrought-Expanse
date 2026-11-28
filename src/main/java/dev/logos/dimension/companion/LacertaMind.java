package dev.logos.dimension.companion;

import dev.logos.dimension.combat.WeaponPilot;
import dev.logos.dimension.foxfire.FoxfireCue;
import dev.logos.dimension.foxfire.FoxfireFlame;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;
import org.jspecify.annotations.Nullable;

/**
 * 拉塞塔的心智（docs/LACERTA_COMPANION.md 第 6 节，接入方案第三、四节）：寸步不退的绯翼前排。通用的行为在 {@link CompanionMind}；这里是她自己的：
 * <ul>
 *   <li><b>炽焰长剑全招式</b>：每到能接下一招时交给驾驶员（{@link WeaponPilot}，docs/WEAPON_AI.md）照招式文件里每一招的 {@code ai} 打分——
 *       处境（多远、几个、强不强、够不够得着）+ 顺手的后继（一式接二式、旋风接十文字、弹反接龙牙……）− 刚放过的（近四招，越近罚得越重）
 *       + 一点抖动——挑最高的，再像玩家一样按出来（突刺要潜行、旋风要疾跑、坠星要先跳起来、横扫要先横防）。战技有短的软冷却，
 *       不会复读一招。贴身四连、中近距突刺瞬贴、中距旋风追击、前方成群或被风筝时红莲横扫扫出游脉、强敌或高低差时坠星砸脸。</li>
 *   <li><b>赤龙威压</b>（{@link #taunt}）：在打、自己血量过三成时，开打那一下、以后每 8 秒一次，把 12 格内的敌对生物都拉到自己身上 6 秒
 *       （这 6 秒里它们转回去打你也会被拉回来）；Boss 不强拉。</li>
 *   <li><b>够不着的</b>：飞着的、站在高处的、隔着水的用掌心赤焰（{@link LacertaCompanion#throwFlame}），地上远处的用红莲横扫的游脉。</li>
 *   <li><b>血少了</b>（后撤）：不跑，托脊横防站住挡，挡下就弹开、接破军龙牙；守久了还被贴着就闪到你身后。</li>
 *   <li><b>四种策略</b>：随机应变（上面这些）；优先远程（拉开 6–9 格，多用横扫和掌心赤焰，贴脸才砍）；优先近战（24 格内都追，突进、坠星更勤）；
 *       不参与攻击（张翼护在你身边，被贴脸才横防，{@code sic} 点名的照打）。</li>
 *   <li><b>陪你</b>：你睡下她按羁绊去床边睡，你站着发呆她在身边坐下（LOUNGE，{@link LacertaRest}）；不用通用的「休息」。</li>
 * </ul>
 */
final class LacertaMind extends CompanionMind {
    /** 够不着时拿剑的游脉扫地上远处的：距离（格，她脚下到目标脚下）。 */
    static final double SWEEP_MIN = 5.0, SWEEP_MAX = 11.0;
    /** 主动找敌人的半径（你周围）：平时、优先近战。 */
    static final double HUNT = 18, HUNT_MELEE = 24;
    /** 一串砍完回到架势里停多久（刻，挡得住）：攻击欲望强，停得短。 */
    static final int STRING_REST = 5;
    /** 赤龙威压：半径、拉住多久、冷却（刻），血量要在几成以上。 */
    static final double TAUNT_RADIUS = 12;
    static final int TAUNT_HOLD = 120, TAUNT_GAP = 160;
    static final float TAUNT_HEALTH = .3f;
    /** 后撤：横防守多久还被贴着就闪开。 */
    static final int GUARD_HOLD = 60;
    /** 敌人没了多久收剑。 */
    static final int SHEATHE_AFTER = 40;

    private final LacertaCompanion l;
    private final Map<Mob, Long> taunted = new HashMap<>();
    private long nextTaunt, restUntil = -1, calmSince = -1, guardSince = -1, countered;
    private int combo;
    private boolean opening;
    /** 巨龙：龙炎喷到哪一刻、下次什么时候能喷；她自己变的巨龙打完以后从哪一刻开始太平；下次什么时候能再自己变身。 */
    private long breathUntil = -1, nextBreath, dragonCalmSince = -1, nextAuto;
    /** 测试用：放过几次赤龙威压。 */
    static int taunts;
    /** 测试用：强行让下一招是这一招（null 照常挑）。 */
    static @Nullable String forceMove;

    LacertaMind(LacertaCompanion c) {
        super(c);
        this.l = c;
    }

    // ---------------------------------------------------------------- 打分

    @Override
    protected void scoreOwn(ServerLevel level, ServerPlayer p, OwnerWatch w, long now, Situation s) {
        // 睡觉、陪你坐都走她自己的（LacertaRest），不用通用的休息；巨龙形态先不坐不睡
        scores.put(Kind.REST, 0.0);
        // 藏宝癖：地上有亮晶晶的，先去抢（压过跟着你）
        ItemEntity loot = loot();
        if (loot != null && LacertaLife.shiny(loot.getItem())) scores.put(Kind.SCAVENGE, SHINY_LOOT);
        // 喷着龙炎、挥着爪、振翼、喷龙息时不陪坐
        double lounge = l.dragon.breathing() || l.dragon.clawing() || l.seal.acting() ? 0 : l.rest.score(level, p, s.doing(), s.calm(), now);
        // 附近有掉落要捡时先捡，再去陪你坐（同琥珀）；你睡着时不管
        if (!p.isSleeping() && lounge > 0 && scores.getOrDefault(Kind.SCAVENGE, 0.0) > 0) lounge = Math.min(lounge, LOUNGE_AFTER_LOOT);
        scores.put(Kind.LOUNGE, lounge);
    }

    /** 通用的挑法之外，再往外找一圈：你周围 18 格（优先近战 24 格）里冲着你、她或别的同伴来的，和会主动伤人的敌对生物。 */
    @Override
    protected @Nullable LivingEntity pickFoe(ServerLevel level, ServerPlayer p, OwnerWatch w, long now) {
        // 逆鳞：先收拾伤你的那个
        LivingEntity wrath = l.seal.wrathFoe();
        if (wrath != null && wrath.distanceTo(p) < 24) return wrath;
        LivingEntity foe = super.pickFoe(level, p, w, now);
        if (foe != null || tactic == CompanionTactic.PASSIVE || c.staying()) return foe;
        double r = tactic == CompanionTactic.MELEE ? HUNT_MELEE : HUNT;
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (Mob m : level.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(r), m -> m.isAlive() && c.canHarm(m))) {
            boolean coming = m.getTarget() == p || m.getTarget() == c || m.getTarget() instanceof CompanionEntity;
            boolean hostile = m instanceof Enemy && !(m instanceof NeutralMob) && !(m instanceof Creeper);
            if (!coming && !hostile || !c.hasLineOfSight(m)) continue;
            double d = m.distanceToSqr(c);
            if (d < bestD) {
                bestD = d;
                best = m;
            }
        }
        return best;
    }

    @Override
    protected void switched(Kind from, Kind to, long now) {
        if (from == Kind.LOUNGE && c.level() instanceof ServerLevel level) {
            l.rest.rise(level, to == Kind.ENGAGE || to == Kind.COVER || to == Kind.RETREAT);
        }
        if (to == Kind.ENGAGE || to == Kind.COVER) opening = true;
        if (to != Kind.RETREAT) guardSince = -1;
    }

    @Override
    protected void afterThink(ServerLevel level, ServerPlayer p, long now) {
        // 她自己打起来变的巨龙：打完太平 {@link #DRAGON_REVERT_AFTER} 刻变回人形（你骑着时等你下来）
        if (l.isDragonForm() && l.dragon.auto() && foe == null && !l.isVehicle() && !l.dragon.breathing()) {
            if (dragonCalmSince < 0) dragonCalmSince = now;
            if (now - dragonCalmSince >= DRAGON_REVERT_AFTER) {
                CompanionLines.say(c, p, "dragon_revert", 40);
                l.dragon.revert(level, p);
                dragonCalmSince = -1;
                nextAuto = now + AUTO_GAP;
            }
        } else {
            dragonCalmSince = -1;
        }
        // 敌人没了：过一会收剑（剑花、驻剑致礼）
        if (foe == null && l.blade.drawn() && !keepsFighting()) {
            if (calmSince < 0) calmSince = now;
            if (now - calmSince >= SHEATHE_AFTER && l.blade.canFollowUp()) {
                l.blade.sheathe();
                calmSince = -1;
            }
        } else {
            calmSince = -1;
        }
        taunted.keySet().removeIf(m -> !m.isAlive() || now > taunted.get(m));
        // 她自己喷着龙炎、目标没了（打倒了、跑了、被收走）：收住（只有打的时候才叫停，不收会一直喷）；骑着时由你松开右键收
        if (breathUntil >= 0 && (foe == null || !l.dragon.breathing())) {
            if (foe == null && l.dragon.breathing() && !l.isVehicle()) l.dragon.stopBreath(level);
            breathUntil = -1;
        }
        // 人形的龙息：目标没了就收
        if (foe == null && l.seal.breathing()) l.seal.stopBreath(level);
        l.life.think(level, p, now, foe == null);
    }

    @Override
    protected boolean keepsFighting() {
        return l.blade.attacking();
    }

    @Override
    protected void actOwn(Kind kind, ServerLevel level, ServerPlayer p, long now) {
        if (kind == Kind.LOUNGE) l.rest.act(level, p, now);
        else idle(p, now, true);
    }

    // ---------------------------------------------------------------- 飞（LacertaFlight）

    /** 接住你的那几秒里什么都不做。 */
    @Override
    protected boolean busy(ServerLevel level, ServerPlayer p, long now) {
        return l.flight.catching() || l.isVehicle() || l.dragon.appearing();
    }

    /** 正在接你：别的反射都不做（掉队闪回会把她闪回地上）。跟着你飞时离得太远：闪到伴飞点。 */
    @Override
    protected boolean reflexOwn(ServerLevel level, ServerPlayer p, long now) {
        return l.flight.catching() || l.flight.reflex(level, p, dist);
    }

    /** 你在飞就跟着飞，不然照地上的走；冷了、馋了贴着你走。 */
    @Override
    protected void follow(ServerPlayer p, long now) {
        if (!c.staying() && l.flight.escort(p, now)) return;
        if (l.isDragonForm()) {
            dragonFollow(p, now);
            return;
        }
        if (snug(p, now)) return;
        super.follow(p, now);
    }

    /** 冷了（裹着翅膀贴着你）、馋了（凑过来）：离你 {@link #SNUG_FAR} 格以外就走过来，走到 {@link #SNUG_NEAR} 格停。 */
    private boolean snug(ServerPlayer p, long now) {
        if (c.staying() || !l.life.cold() && !l.lacertaMood().craving()) return false;
        if (dist > SNUG_FAR) {
            if (now % THINK == 0 || c.getNavigation().isDone()) c.getNavigation().moveTo(p, 1);
        } else if (dist < SNUG_NEAR) {
            c.getNavigation().stop();
            look(p, now);
        }
        return true;
    }

    static final double SNUG_NEAR = 1.4, SNUG_FAR = 2.2;

    /** 巨龙跟着走：离你 {@link #DRAGON_FAR} 格以外才走，走到 {@link #DRAGON_NEAR} 格停（她太大，别挤着你）。 */
    private void dragonFollow(ServerPlayer p, long now) {
        if (dist > DRAGON_FAR) {
            boolean run = dist > DRAGON_FAR * 2 || p.isSprinting();
            if (now % THINK == 0 || c.getNavigation().isDone()) c.getNavigation().moveTo(p, run ? 1.3 : .9);
        } else if (dist < DRAGON_NEAR) {
            c.getNavigation().stop();
            c.getLookControl().setLookAt(p, 20, 20);
        }
    }

    static final double DRAGON_NEAR = 5, DRAGON_FAR = 8;
    /**
     * 附近有掉落要捡时，陪你坐 / 守宝藏的分压到多少（比捡东西低）；亮晶晶的掉落捡的分：比「离你 6 格以外快回来」（0.7）还高——掉落只捡你身边
     * 10 格内的，掉在离你 6 格多的地方时不然她走过去就被叫回来、回来又去捡，来回打转。
     */
    static final double LOUNGE_AFTER_LOOT = .3, SHINY_LOOT = .75;

    @Override
    protected int lootRank(ItemStack s) {
        return LacertaLife.shiny(s) ? 1 : 0;
    }

    @Override
    protected void idle(ServerPlayer p, long now, boolean follow) {
        if (follow && !c.staying() && l.flight.escort(p, now)) return;
        if (follow && !l.isDragonForm() && snug(p, now)) return;
        super.idle(p, now, follow);
    }

    @Override
    protected boolean canMoveToCover() {
        return l.blade.ready() || !l.blade.drawn();
    }

    @Override
    protected boolean canTurnToTalk() {
        return !l.blade.drawn();
    }

    // ---------------------------------------------------------------- 打

    @Override
    protected void fight(ServerLevel level, LivingEntity target, long now, boolean hold) {
        holdTaunt(now);
        if (l.eating()) return;
        taunt(level, now, opening);
        opening = false;
        double d = l.distanceTo(target);
        boolean low = l.getHealth() < l.getMaxHealth() * TAUNT_HEALTH;
        if (l.isDragonForm()) {
            dragonFight(level, target, now, d, hold);
            return;
        }
        // 遇上强敌、被围住：她自己化身巨龙（要有空地、你的魔力够）
        if (autoTransform(level, target, now)) return;
        // 坠星砸下去的那几刻让它砸完
        var move = l.blade.move();
        if (move != null && "skyfall_judgement".equals(move.name()) && !l.blade.canFollowUp()) return;
        // 振翼、喷龙息的那几刻：站住对着它
        if (l.seal.acting()) {
            c.getNavigation().stop();
            c.getLookControl().setLookAt(target, 40, 40);
            return;
        }
        if (seals(level, target, now, d)) return;
        // 够不着（飞着、站在高处、隔着水）：坠星好了就飞上去砸，不然掌心赤焰；地上远处的接着用剑的游脉
        if (l.flight.mode() == LacertaFlight.Mode.PURSUE || !melee(target)) {
            if (!pursue(target, now, hold, low)) ranged(level, target, now, d, hold);
            return;
        }
        if (tactic == CompanionTactic.RANGED && d > 6 && (l.flameReady(now) || !l.blade.drawn())) {
            ranged(level, target, now, d, hold);
            return;
        }
        if (!l.blade.drawn()) {
            if (!l.throwing()) l.blade.draw();
            c.getLookControl().setLookAt(target, 40, 40);
            return;
        }
        // 血少了：会武艺（第二印）就守着等弹反；不会就照砍（寸步不退）
        if (low && l.seal.has(LacertaSeals.ARTS)) {
            guardAndCounter(level, target, now, d);
            return;
        }
        strike(level, target, now, d, hold);
    }

    /**
     * 契印的两招：第三印烈风振翼（身边 {@link LacertaSeals#GUST_RADIUS} 格里围着 {@link LacertaSeals#GUST_CROWD} 个以上）、
     * 第六印赤穹龙息（前方锥里扎着 {@link LacertaSeals#BREATH_CROWD} 个以上）。放了返回真。
     */
    private boolean seals(ServerLevel level, LivingEntity target, long now, double d) {
        if (l.blade.attacking() || l.throwing()) return false;
        if (l.seal.gustReady(now) && l.seal.around() >= LacertaSeals.GUST_CROWD) {
            face(target);
            l.seal.gust(level, now);
            return true;
        }
        if (l.seal.breathReady(now) && d <= LacertaSeals.BREATH_START_RANGE && c.hasLineOfSight(target) && l.seal.inCone(target) >= LacertaSeals.BREATH_CROWD) {
            face(target);
            return l.seal.breathe(level, target, now);
        }
        return false;
    }

    /**
     * 空中追击（接入方案 6.1）：够不着的敌人，炽翼坠星破好了、她起得来、不是优先远程时飞到它头顶，拔着剑飞到够近接一记坠星
     * （收了重力，坠星自己往上蹿、往下砸）。追不了返回假。
     */
    private boolean pursue(LivingEntity target, long now, boolean hold, boolean low) {
        // 炽翼坠星破是第四印
        boolean ready = l.seal.has(LacertaSeals.SKYFALL) && !l.blade.pilot.cooling("skyfall_judgement");
        if (hold || low || tactic == CompanionTactic.RANGED || !ready || !l.flight.canPursue(target)) {
            if (l.flight.mode() == LacertaFlight.Mode.PURSUE) l.flight.land();
            return false;
        }
        l.flight.pursue(target, now);
        if (!l.blade.drawn()) {
            if (!l.throwing()) l.blade.draw();
            return true;
        }
        if (l.flight.inStrikeRange(target) && l.blade.canFollowUp()) {
            l.flight.land();
            attack("skyfall_judgement", target, now);
        }
        return true;
    }

    // ---------------------------------------------------------------- 巨龙（LacertaDragon）

    /** 她自己打完以后多久变回人形；变回以后多久才会再自己变身。 */
    static final int DRAGON_REVERT_AFTER = 100, AUTO_GAP = 600;
    /** 强敌：生命上限这么多以上、首领，或者身边这么多格里围着这么多敌人。 */
    static final double STRONG_HEALTH = 40, CROWD_RADIUS = 8;
    static final int CROWD = 3;
    /** 巨龙的龙炎：多远喷、喷多久、喷完多久再喷。 */
    static final double BREATH_MIN = 6, BREATH_MAX = 16;
    static final int BREATH_TICKS = 60, BREATH_GAP = 160;

    /** 值得化身巨龙的仗：强敌（{@link #STRONG_HEALTH} 生命以上、首领）或者被围住。 */
    static boolean worthDragon(double maxHealth, boolean boss, int crowd) {
        return boss || maxHealth >= STRONG_HEALTH || crowd >= CROWD;
    }

    /** 刚变回人形（你让的、打完了、魔力不够）：一阵子里不再自己变身。 */
    void deferAuto(long now) {
        nextAuto = now + AUTO_GAP;
    }

    private boolean autoTransform(ServerLevel level, LivingEntity target, long now) {
        if (owner == null || !target.isAlive() || now < nextAuto || tactic == CompanionTactic.PASSIVE || l.flight.flying() || l.blade.attacking()) return false;
        if (!worthDragon(target.getMaxHealth(), target.is(Tags.EntityTypes.BOSSES), crowd(c.position(), CROWD_RADIUS))) return false;
        if (!l.dragon.transform(level, owner, true)) {
            nextAuto = now + 100;
            return false;
        }
        CompanionLines.say(c, owner, "dragon_auto", 40);
        return true;
    }

    /**
     * 巨龙怎么打：龙炎喷着时对着它喷完；贴身挥爪；6–16 格、看得见、龙炎好了就喷；够不着（天上、高台）飞到它后上方悬停着喷；
     * 不然走过去。
     */
    private void dragonFight(ServerLevel level, LivingEntity target, long now, double d, boolean hold) {
        LacertaDragon dragon = l.dragon;
        c.getLookControl().setLookAt(target, 30, 30);
        if (dragon.breathing()) {
            if (now >= breathUntil || !target.isAlive() || d > BREATH_MAX + 4) dragon.stopBreath(level);
            return;
        }
        double reach = LacertaDragon.CLAW_REACH - .3 + target.getBbWidth() * .5;
        if (d <= reach && dragon.clawReady(now)) {
            face(target);
            dragon.claw(now);
            return;
        }
        boolean reachable = melee(target);
        if (d >= BREATH_MIN && d <= BREATH_MAX && now >= nextBreath && c.hasLineOfSight(target) && dragon.breathReady()) {
            if (dragon.startBreath(() -> target.getEyePosition().subtract(dragon.mouth()).normalize())) {
                breathUntil = now + BREATH_TICKS;
                nextBreath = now + BREATH_TICKS + BREATH_GAP;
                c.getNavigation().stop();
                return;
            }
        }
        if (!reachable && !hold && l.flight.canTakeOff() || l.flight.flying() && !reachable) {
            l.flight.dragonHover(target, now);
            return;
        }
        if (l.flight.flying()) l.flight.land();
        if (hold || d <= reach - .5) {
            c.getNavigation().stop();
            face(target);
        } else if (now % 5 == 0 || c.getNavigation().isDone()) {
            c.getNavigation().moveTo(target, d > 10 ? 1.3 : 1.0);
        }
    }

    /** 近战够得着：在地上（或者不高），寻路过得去。 */
    private boolean melee(LivingEntity t) {
        if (!t.onGround() && !t.isInWater() && t.getY() - c.getY() > 2.5) return false;
        if (t.isInWater() && !c.isInWater() && c.distanceTo(t) > 3) return false;
        return reachable(t);
    }

    /**
     * 拿着剑：能接下一招时按处境挑一招（{@link #pick}）；挑不出来就走过去（远了就跑）。一串 2–4 招，一串之间回到架势里停一下（挡得住）。
     * 弹开了谁就趁它僵直接破军龙牙。
     */
    private void strike(ServerLevel level, LivingEntity target, long now, double d, boolean hold) {
        LacertaBlade blade = l.blade;
        LivingEntity punish = blade.punishing(now);
        if (punish != null && punish.distanceTo(c) < LacertaBlade.REACH + 2.5 && blade.punishUntil() != countered && blade.canFollowUp()) {
            countered = blade.punishUntil();
            if (attack("dragon_fang", punish, now)) {
                combo = 2;
                return;
            }
        }
        c.getLookControl().setLookAt(target, 40, 40);
        if (!blade.canFollowUp()) {
            c.getNavigation().stop();
            return;
        }
        if (combo <= 0) {
            if (restUntil < 0) restUntil = now + STRING_REST + c.getRandom().nextInt(STRING_REST * 2);
            if (now < restUntil) {
                if (blade.ready()) face(target);
                return;
            }
            restUntil = -1;
            combo = 2 + c.getRandom().nextInt(3);
        }
        String move = forceMove != null ? forceMove : pick(target, now, d);
        if (move != null) {
            if (attack(move, target, now)) {
                forceMove = null;
                combo--;
            }
            return;
        }
        // 没有能打的：走过去（收势播完再走）
        if (hold || !blade.ready()) return;
        boolean run = d > LacertaBlade.REACH + 1.5;
        c.setSprinting(run);
        if (now % 5 == 0 || c.getNavigation().isDone()) c.getNavigation().moveTo(target, run ? 1.5 : 1.25);
    }

    /** 去放这一招：驾驶员照招式文件的转移图按过去（软冷却、刚放过哪几招也记在驾驶员那里）。 */
    private boolean attack(String move, LivingEntity target, long now) {
        if (!l.blade.attack(move, target)) return false;
        face(target);
        c.getNavigation().stop();
        c.setSprinting(false);
        return true;
    }

    /**
     * 挑下一招：交给驾驶员（{@link WeaponPilot#pick}），照炽焰长剑招式文件里每一招的 {@code ai} 打分（docs/WEAPON_AI.md 第 2.3 节）。
     * 她自己的条件：炽翼坠星破要第四印。低于 0.2 就不打（走过去）。
     */
    @Nullable String pick(LivingEntity target, long now, double d) {
        double lean = tactic == CompanionTactic.MELEE ? 1 : tactic == CompanionTactic.RANGED ? -1 : 0;
        return l.blade.pilot.pick(target, d, situation, c.getRandom(), lean);
    }

    /** 驾驶员看的处境：她身边、前方的敌人；坠星要第四印。 */
    private final WeaponPilot.Situation situation = new WeaponPilot.Situation() {
        @Override
        public int crowd(double radius, double arc) {
            return arc >= 360 ? LacertaMind.this.crowd(c.position(), radius) : crowdAhead(radius, arc);
        }

        @Override
        public boolean allows(String move) {
            return !"skyfall_judgement".equals(move) || l.seal.has(LacertaSeals.SKYFALL);
        }
    };

    /** 她身边 {@code r} 格里冲着她或你来的敌人有几个。 */
    private int crowd(Vec3 at, double r) {
        return c.level().getEntitiesOfClass(Mob.class, c.getBoundingBox().inflate(r), m -> m.isAlive() && c.canHarm(m) && c.hostileTo(m)).size();
    }

    /** 她前方 {@code arc} 度扇区、{@code r} 格里的敌人有几个（红莲横扫的游脉扫得到 72° 的）。 */
    private int crowdAhead(double r, double arc) {
        Vec3 f = Vec3.directionFromRotation(0, c.getYRot());
        int n = 0;
        for (Mob m : c.level().getEntitiesOfClass(Mob.class, c.getBoundingBox().inflate(r), m -> m.isAlive() && c.canHarm(m) && c.hostileTo(m))) {
            Vec3 to = m.position().subtract(c.position()).multiply(1, 0, 1);
            double len = to.length();
            if (len < 1e-3 || len > r) continue;
            if (to.dot(f) / len >= Math.cos(Math.toRadians(arc / 2))) n++;
        }
        return n;
    }

    /** 够不着的：掌心赤焰；剑在手上、目标在地上远处时也可以横扫。都不行就回到你身边守着、对着它。 */
    private void ranged(ServerLevel level, LivingEntity target, long now, double d, boolean hold) {
        c.getLookControl().setLookAt(target, 40, 40);
        if (c.hasLineOfSight(target) && d < 24 && l.throwFlame(target)) {
            c.getNavigation().stop();
            return;
        }
        if (l.blade.drawn() && l.blade.canFollowUp() && target.onGround() && d >= SWEEP_MIN && d <= SWEEP_MAX
                && !l.blade.pilot.cooling("flame_sweep") && attack("flame_sweep", target, now)) {
            return;
        }
        if (hold || owner == null) return;
        // 优先远程：拉开到 6–9 格；够不着的：回到你身边守着
        if (tactic == CompanionTactic.RANGED && d < 6) {
            Vec3 away = c.position().subtract(target.position()).multiply(1, 0, 1).normalize().scale(3);
            Vec3 to = c.position().add(away);
            if (now % 10 == 0) c.getNavigation().moveTo(to.x, to.y, to.z, 1.3);
        } else if (dist > FAR + 2) {
            if (now % THINK == 0 || c.getNavigation().isDone()) c.getNavigation().moveTo(owner, 1.2);
        } else {
            c.getNavigation().stop();
        }
    }

    /** 血少了：站住托脊横防，挡下就弹开，接破军龙牙；守了 {@link #GUARD_HOLD} 刻还被贴着就闪到你身后。 */
    private void guardAndCounter(ServerLevel level, LivingEntity threat, long now, double d) {
        LacertaBlade blade = l.blade;
        LivingEntity punish = blade.punishing(now);
        if (punish != null && blade.punishUntil() != countered && blade.canFollowUp()) {
            countered = blade.punishUntil();
            blade.unguard();
            if (attack("dragon_fang", punish, now)) return;
        }
        c.getNavigation().stop();
        c.getLookControl().setLookAt(threat, 40, 40);
        if (d > 4) {
            blade.unguard();
            if (c.hasLineOfSight(threat)) l.throwFlame(threat);
            guardSince = -1;
            return;
        }
        if (blade.ready() || blade.guarding()) {
            face(threat);
            blade.guard();
        }
        if (guardSince < 0) guardSince = now;
        if (now - guardSince >= GUARD_HOLD && now >= nextEscape && owner != null) {
            blade.unguard();
            escape(level, owner, threat);
            nextEscape = now + ESCAPE_GAP;
            guardSince = -1;
            escapes++;
        }
    }

    /**
     * 后撤（血少，或不参与攻击时敌人近了）：她不跑——敌人贴着就横防等弹反，远的放掌心赤焰；不参与攻击时只有冲着她来、贴到跟前的才横防，
     * 不然躲到你背后。
     */
    @Override
    protected void retreat(ServerPlayer p, LivingEntity threat, long now) {
        if (!(c.level() instanceof ServerLevel level)) return;
        double d = c.distanceTo(threat);
        if (holdsBack()) {
            boolean onMe = threat instanceof Mob m && m.getTarget() == c && d <= 4 && l.seal.has(LacertaSeals.ARTS);
            if (onMe && (l.blade.drawn() || l.blade.draw())) {
                guardAndCounter(level, threat, now, d);
                return;
            }
            l.blade.sheathe();
            Vec3 back = p.position().subtract(threat.position()).multiply(1, 0, 1);
            Vec3 spot = p.position().add(back.lengthSqr() < 1e-4 ? Vec3.ZERO : back.normalize().scale(2.5));
            if (now % THINK == 0) c.getNavigation().moveTo(spot.x, spot.y, spot.z, 1.4);
            c.getLookControl().setLookAt(threat, 30, 30);
            return;
        }
        if (!l.blade.drawn() && !l.blade.draw()) return;
        // 不会武艺（第二印以前）：不守，照砍
        if (!l.seal.has(LacertaSeals.ARTS)) {
            strike(level, threat, now, d, false);
            return;
        }
        guardAndCounter(level, threat, now, d);
    }

    // ---------------------------------------------------------------- 赤龙威压

    /**
     * 赤龙威压：巨翼一甩、竖瞳一闪（{@code taunt}），12 格内她当作敌人的、能伤的生物都转过来打她，6 秒里转回去也会被拉回来。
     * 开打那一下马上放，之后每 8 秒一次；自己血量三成以下不放；不参与攻击不放；Boss 不强拉。
     */
    void taunt(ServerLevel level, long now, boolean open) {
        if (holdsBack() || now < nextTaunt || l.getHealth() <= l.getMaxHealth() * TAUNT_HEALTH) return;
        ServerPlayer p = owner;
        double radius = l.isDragonForm() ? LacertaDragon.TAUNT_RADIUS : TAUNT_RADIUS;
        List<Mob> mobs = level.getEntitiesOfClass(Mob.class, l.getBoundingBox().inflate(radius),
                m -> m.isAlive() && l.canHarm(m) && l.hostileTo(m) && m.distanceTo(l) <= radius && !m.is(Tags.EntityTypes.BOSSES));
        if (mobs.isEmpty()) return;
        boolean onAllies = mobs.stream().anyMatch(m -> m.getTarget() != null && m.getTarget() != l);
        if (!open && !onAllies && mobs.size() < 2) return;
        nextTaunt = now + TAUNT_GAP;
        taunts++;
        // 巨龙：全幅扇翼威慑（人形是巨翼一甩的 taunt）
        if (l.isDragonForm()) {
            if (!l.dragon.appearing()) l.mood.play("wing_flap", 32);
        } else {
            l.mood.act("taunt");
        }
        for (Mob m : mobs) {
            m.setTarget(l);
            taunted.put(m, now + TAUNT_HOLD);
        }
        Vec3 at = l.position().add(0, 1, 0);
        FoxfireCue.burst(level, at, FoxfireFlame.DRAGON, 1.1f);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.NEUTRAL, .45f, 1.7f);
        if (p != null) CompanionLines.speakEvery(l, CompanionLines.name(LacertaCompanion.ID), CompanionLines.STYLE_COLOR,
                "line.logos_dimension.lacerta.taunt", 50, 600);
    }

    /** 被拉住的这几秒里转回去打别人的，再拉回来。 */
    private void holdTaunt(long now) {
        for (Iterator<Map.Entry<Mob, Long>> it = taunted.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            Mob m = e.getKey();
            if (!m.isAlive() || now > e.getValue()) {
                it.remove();
                continue;
            }
            if (m.getTarget() != l) m.setTarget(l);
        }
    }

    // ---------------------------------------------------------------- 别的

    @Override
    protected void describeOwn(StringBuilder sb) {
        sb.append(" flight=").append(l.flight.describe()).append(" move=").append(l.moveForTest()).append(" recent=").append(l.blade.pilot.recent()).append(" mana=").append(String.format("%.0f/%.0f", l.mana(), l.maxMana()))
                .append(" pose=").append(l.rest.describePose());
    }
}
